package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarStaffJournal;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.application.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.spi.WarStaffPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit staffing review and compatible part-time appointments. No NPC scans or parallel primary labor. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarStaffRuntime implements AutoCloseable {
    private static final Map<MinecraftServer,WarStaffRuntime> STATES=new WeakHashMap<>();private static final Map<MinecraftServer,String> FAILURES=new WeakHashMap<>();
    private final MinecraftServer server;private final WarStaffBook book;private final WarNativeBindings.StaffOffices offices;
    private final WarStaffPolicy policy=new WarStaffPolicy(WarStaffPolicy.Rules.prototype());
    private final Set<UUID> recoveredPreparing=new HashSet<>();private final LinkedHashSet<UUID> ready=new LinkedHashSet<>();private boolean closed;
    private final Set<UUID> visible=new HashSet<>();
    private WarStaffRuntime(MinecraftServer server)throws IOException{
        this.server=server;var journal=new WarStaffJournal(path(server));
        try{
            book=new WarStaffBook(journal);offices=WarNativeBindings.currentBuild().staffOffices().open(server,book.entries().stream().filter(Appointment.class::isInstance).map(WarStaffEntry::id).collect(java.util.stream.Collectors.toSet()));
            for(var v:book.entries()){
                if(v instanceof Establishment e&&e.reviewedAt()>now()||v instanceof Appointment a&&a.changedAt()>now())throw new IOException("staff evidence is newer than the original world clock");
                if(v instanceof Appointment a&&a.phase()!=Phase.REVOKED&&a.phase()!=Phase.REVIEW){ready.add(a.id());if(a.phase()==Phase.PREPARING)recoveredPreparing.add(a.id());}
            }
        }catch(IOException|RuntimeException e){journal.close();throw e;}
    }
    public static Optional<WarStaffRuntime> existing(MinecraftServer server){return Optional.ofNullable(STATES.get(server));}
    public static String failure(MinecraftServer server){return FAILURES.getOrDefault(server,"");}
    public static WarStaffRuntime open(MinecraftServer server)throws IOException{
        if(!server.isSameThread())throw new IllegalStateException("staff requires server thread");if(FAILURES.containsKey(server))throw new IOException(FAILURES.get(server));
        var old=STATES.get(server);if(old!=null)return old;
        try{var value=new WarStaffRuntime(server);STATES.put(server,value);return value;}catch(IOException|RuntimeException e){FAILURES.put(server,"STAFF_RECOVERY_REQUIRES_REVIEW:"+e.getClass().getSimpleName());throw e;}
    }
    public WarStaffBook book(){return book;}
    public String problem(){return !book.problem().isEmpty()?book.problem():offices.problem();}
    private WarMusterRuntime muster(){return WarMusterRuntime.existing(server).orElseThrow(()->new IllegalArgumentException("请先通过原入口建立军队"));}
    private WarServerRuntime core(){return WarServerEvents.existing(server).orElseThrow(()->new IllegalArgumentException("原军事系统不可用"));}
    private UUID owner(ServerPlayer player,UUID army){var m=muster();var actor=m.actor(player);if(!m.book().owns(actor,army)||core().permission(actor,army)!=WarCommandService.Permission.ALLOWED)throw new IllegalArgumentException("仅当前领主人物可管理本军队；OP或社会身份不授予此权");return actor;}
    public Reply review(ServerPlayer player,UUID army){
        check();UUID actor=owner(player,army);var counts=new LinkedHashMap<UUID,Integer>();var m=muster();
        for(var v:m.book().entries())if(v instanceof WarMusterEntry.Unit u&&u.army().equals(army)&&u.recorded()&&m.book().durable(u))counts.put(u.id(),0);
        // Review only formally ACTIVE, durable original COMBATANT services. Administrative titles never inflate N.
        for(var v:m.book().entries())if(v instanceof WarMusterEntry.Offer o&&o.request().armyId().equals(army)&&o.stage()==WarMusterEntry.Stage.ACTIVE&&m.book().durable(o)
                &&o.request().role()==WarMilitiaRequest.Role.COMBATANT&&currentService(o.request().id(),o.request().characterId(),army))counts.computeIfPresent(o.request().unitId(),(id,n)->n+1);
        var old=book.establishment(army);return book.put(new Establishment(army,actor,old.map(e->Math.addExact(e.revision(),1)).orElse(1L),now(),counts));
    }
    public Reply appoint(ServerPlayer player,UUID operation,UUID army,Role role,Optional<UUID> unit,UUID person,int days,Mode mode,String reason){
        check();var actor=owner(player,army);if(days<1||days>30)throw new IllegalArgumentException("任期1至30日且不超过原军役");var previous=book.appointment(operation);
        if(previous.isPresent()){
            var t=previous.get().terms();var original=muster().book().offer(t.service());
            if(t.owner().equals(actor)&&t.army().equals(army)&&t.holder().equals(person)&&t.role()==role&&t.unit().equals(unit)&&t.mode()==mode&&t.reason().equals(reason)
                    &&original.filter(o->Math.min(o.endsAt(),Math.addExact(t.startsAt(),days*24000L))==t.endsAt()).isPresent())return new Reply(book.durable(previous.get())?Status.APPLIED:Status.PENDING,book.durable(previous.get())?Optional.of(previous.get()):Optional.empty(),"原任命，不重复授职");
            throw new IllegalArgumentException("任命操作号内容不一致");
        }
        var e=book.establishment(army).filter(book::durable).orElseThrow(()->new IllegalArgumentException("请先审核并保存战斗编制"));
        var eligibility=policy.evaluate(e,role,unit);if(!eligibility.available())throw new IllegalArgumentException(eligibility.reason());
        var offer=muster().book().latest(person).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&muster().book().durable(o)&&o.request().armyId().equals(army)).orElseThrow(()->new IllegalArgumentException("候选人须有本军队当前生效的原军役"));
        if(!WarOfficeQualification.compatible(role,offer.request().role())||!currentService(offer.id(),person,army)||unit.filter(u->!u.equals(offer.request().unitId())).isPresent())throw new IllegalArgumentException("兼任须原战斗军役，专职须原STAFF军役；劳动与兵团范围须相符");
        var npc=ItemLocation.personal(person,offer.body()).body(server,CharacterRegistryData.get(server)).orElseThrow(()->new IllegalArgumentException("原候选身体尚未加载"));
        if(player.level()!=npc.level()||player.distanceToSqr(npc)>36||!player.hasLineOfSight(npc)||npc.isBaby()||npc.isNoAi())throw new IllegalArgumentException("请在六格内面对原服役人物办理");
        if(!available(npc))throw new IllegalArgumentException("候选人需要先休息、进食或处理伤势，暂不办理任职");
        if(!WarOfficeQualification.expertise(role,mode,npc.getExperience(),career(person)))throw new IllegalArgumentException("正式专职岗位需要原经验20或三次已确认军务；也可明确授予acting/exceptional并记录理由");
        if(role==Role.STANDARD_BEARER&&!(npc.getOffhandItem().getItem() instanceof net.minecraft.world.item.BannerItem))throw new IllegalArgumentException("专职旗手须由原递交/装备入口备好真实副手旗帜");
        var social=CharacterRegistryData.get(server).byId(person);long ends=Math.min(offer.endsAt(),Math.addExact(now(),days*24000L));
        var terms=new Terms(operation,army,actor,person,offer.id(),role,unit,e.revision(),e.strength(),unit.map(u->e.combatants().getOrDefault(u,0)).orElse(0),1,now(),ends,mode,reason,social.socialClass().name(),npc.getProfession().name(),Math.max(0,npc.getExperience()));
        var a=new Appointment(terms,Phase.PREPARING,1,now(),role.partTime()?"兼任使用原军役劳动与工资；津贴另行显式约定":"专职使用原STAFF劳动、粮饷和实际职责；不另发重复全职工资");var r=book.put(a);if(r.status()==Status.APPLIED||r.status()==Status.PENDING)ready.add(a.id());return r;
    }
    public Reply revoke(ServerPlayer player,UUID appointment){
        check();var a=book.appointment(appointment).orElseThrow(()->new IllegalArgumentException("任命不存在"));owner(player,a.terms().army());
        if(a.phase()==Phase.REVOKED||a.phase()==Phase.REVOKING)return new Reply(book.durable(a)?Status.APPLIED:Status.PENDING,book.durable(a)?Optional.of(a):Optional.empty(),"原撤职已登记");
        if(a.phase()==Phase.REVIEW)throw new IllegalArgumentException("任职证据需要核对，不能猜测补写");
        var r=book.put(a.advance(Phase.REVOKING,now(),"领主撤销本次职权"));ready.add(a.id());
        if(r.status()==Status.APPLIED||r.status()==Status.PENDING){visible.remove(a.id());WarDutyChangedEvent.publish(server,a.terms().holder());}return r;
    }
    public boolean effective(Appointment a){
        check();return book.appointment(a.id()).filter(a::equals).isPresent()&&a.phase()==Phase.ACTIVE&&book.durable(a)&&currentService(a.terms().service(),a.terms().holder(),a.terms().army())&&muster().book().offer(a.terms().service()).filter(o->WarOfficeQualification.compatible(a.terms().role(),o.request().role())).isPresent()&&offices.active(a.terms(),now());
    }
    Optional<Appointment> fieldCaptain(UUID army,UUID unit){
        if(closed||!problem().isEmpty())return Optional.empty();
        return book.captain(army,unit).filter(this::effective).filter(a->{
            var offer=muster().book().offer(a.terms().service()).orElseThrow();
            return ItemLocation.personal(a.terms().holder(),offer.body()).body(server,CharacterRegistryData.get(server)).filter(this::available).isPresent();
        });
    }
    public Optional<Role> visibleRole(UUID person,UUID service){
        if(closed||!problem().isEmpty())return Optional.empty();
        return book.forPerson(person).filter(a->a.terms().service().equals(service)&&effective(a)).map(a->a.terms().role());
    }
    public List<String> status(ServerPlayer p,UUID army){
        check();owner(p,army);var rows=new ArrayList<String>();var e=book.establishment(army);
        if(e.isEmpty())rows.add("尚无核定编制；用 staff review 显式审核，伤亡不会自动撤职。");
        else{rows.add(policy.scale(e.get())+" · 核定战斗人员 "+e.get().strength()+" · 审核版本 "+e.get().revision()+(book.durable(e.get())?"":" · 保存中"));for(var role:Role.values()){var q=policy.evaluate(e.get(),role,Optional.empty());rows.add(role.title()+"："+(role==Role.CAPTAIN?"本军21人且本兵团12人；兼任":q.available()?(role.partTime()?"可选兼任":"可选专职，须原STAFF军役与履历资格"):q.reason()));}}
        for(var v:book.entries())if(v instanceof Appointment a&&a.terms().army().equals(army))rows.add(a.id()+" · "+a.terms().role().title()+" · 人物 "+a.terms().holder()+" · "+a.phase()+" · 当前有效="+effective(a)+" · "+a.terms().mode()+" · 任期至 "+a.terms().endsAt()+" · 任用时身份/职业/经验 "+a.terms().socialClass()+"/"+a.terms().profession()+"/"+a.terms().experience());
        return List.copyOf(rows);
    }
    public List<String> advice(ServerPlayer p,UUID army){
        return advice(p,army,Optional.empty());
    }
    List<String> advice(ServerPlayer p,UUID army,Optional<UUID> adviser){
        check();UUID actor=owner(p,army);var candidates=book.entries().stream().filter(Appointment.class::isInstance).map(Appointment.class::cast)
                .filter(a->a.terms().army().equals(army)&&adviser.map(a.terms().holder()::equals).orElse(true)&&effective(a))
                .sorted(Comparator.comparing(Appointment::id)).toList();
        var rows=new ArrayList<String>();var field=core().field().orElseThrow();
        for(var a:candidates){var offer=muster().book().offer(a.terms().service()).orElseThrow();var npc=ItemLocation.personal(a.terms().holder(),offer.body()).body(server,CharacterRegistryData.get(server));if(npc.isEmpty()||!available(npc.get()))continue;
            var reports=field.reports(actor,army).stream().filter(r->a.terms().unit().isEmpty()||a.terms().unit().get().equals(r.unit())).toList();
            String prefix=a.terms().role().title()+"：";
            if(reports.isEmpty()||reports.stream().anyMatch(r->now()-r.observedAt()>200))rows.add(prefix+"己方报告缺失或过时，建议先核对位置；不推断未知敌军。");
            if(reports.stream().anyMatch(r->r.state()==WarFieldReport.State.BLOCKED||r.state()==WarFieldReport.State.REVIEW))rows.add(prefix+"有己方执行阻挡，建议检查原报告中的装具、道路或身体原因。");
            if(reports.stream().anyMatch(r->r.state()==WarFieldReport.State.RETREATING||r.state()==WarFieldReport.State.REGROUPING))rows.add(prefix+"己方仍在撤退或重整，可先等待实际到位再行动。");
            if(rows.isEmpty())rows.add(prefix+"己方报告暂无上述异常；保持当前命令或自行调整。本建议不自动下令。");
            if(rows.size()>=3)break;
        }
        return rows.isEmpty()?List.of("暂无原身体可用的有效兼任助手；领主仍可直接指挥。"):List.copyOf(rows.subList(0,Math.min(3,rows.size())));
    }
    Optional<WarStaffPolicy.Eligibility> eligibility(ServerPlayer player, UUID army, Role role, Optional<UUID> unit) {
        owner(player, army);
        return book.establishment(army).filter(book::durable).map(e -> policy.evaluate(e, role, unit));
    }
    /** Original service ledger only, including historical arrears. No payroll/meal advancement on query. */
    public List<String> supplies(ServerPlayer player,UUID army){
        check();UUID actor=owner(player,army);var original=ServiceProvisionRuntime.existing(server);var rows=new ArrayList<String>();
        int recorded=0,verified=0,pending=0,review=0,sustained=0;var due=java.math.BigInteger.ZERO;var paid=java.math.BigInteger.ZERO;var arrears=java.math.BigInteger.ZERO;long meals=0;
        for(var entry:muster().book().entries())if(entry instanceof WarMusterEntry.Offer o&&o.request().armyId().equals(army)&&o.request().actorId().equals(actor)&&o.stage()!=WarMusterEntry.Stage.DECLINED){
            recorded++;var value=original.flatMap(s->s.find(o.id()));
            if(!muster().book().durable(o)||value.isEmpty()){pending++;continue;}
            var v=value.get();var t=v.terms();
            if(!t.ownerKind().equals("sagadyssey:war")||!t.owner().equals(army)||!t.person().equals(o.request().characterId())||!t.body().equals(o.body())
                    ||!t.payer().equals(com.jgeted.sagadyssey.worldsim.rights.SubjectRef.character(actor))
                    ||!t.wageItem().equals(new com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint(o.request().wage().currency().toString(),0))
                    ||t.rate()!=o.request().wage().amountPerPeriod()||t.period()!=o.request().wage().periodTicks()||v.changedAt()>now()) {review++;continue;}
            // Never add an uncommitted or failed settlement to the confirmed total.
            if(!original.get().durable(o.id())){pending++;continue;}
            verified++;due=due.add(v.due(now()));paid=paid.add(v.paid());arrears=arrears.add(v.arrears(now()));
            meals+=v.settlements().stream().filter(s->s.applied()&&s.attempt().purpose()==ServiceProvision.Purpose.MEAL).count();
            if(v.ownsNeeds())sustained++;if(v.pending().isPresent())pending++;if(original.get().problem(o.id()).isPresent())review++;
        }
        rows.add("原军役粮饷 · 观察刻 "+now()+" · 记录 "+recorded+" · 已核对保存 "+verified+" · 未保存/处理中 "+pending+" · 待核对 "+review);
        rows.add("已核对部分：应付 "+due+" · 已付 "+paid+" · 欠饷 "+arrears+" 绿宝石；包含本军历史退役欠款，未核对部分不计入。");
        rows.add("原供养记录：持续供养 "+sustained+"份 · 已确认实际餐食 "+meals+"份；餐食次数不等于库存或可供天数。查询不扣粮、不付款。");
        return List.copyOf(rows);
    }
    public boolean mayRelay(ServerPlayer player,Appointment a,UUID unit){
        try{check();if(!owner(player,a.terms().army()).equals(a.terms().owner())||!effective(a)||a.terms().unit().filter(u->!u.equals(unit)).isPresent())return false;
        var target=core().session().flatMap(s->s.unit(unit));if(target.filter(u->u.armyId().equals(a.terms().army())).isEmpty())return false;
        var offer=muster().book().offer(a.terms().service()).orElseThrow();if(offer.request().role()==WarMilitiaRequest.Role.ENGINEER)return false;if(WarCampaignRuntime.existing(server).filter(c->c.holds(offer.id())).isPresent())return false;return ItemLocation.personal(a.terms().holder(),offer.body()).body(server,CharacterRegistryData.get(server)).filter(n->available(n)&&!n.isInWater()&&!n.isPassenger()).isPresent();}catch(IllegalArgumentException|IllegalStateException unavailable){return false;}
    }
    public int career(UUID person){
        int completed=WarCampaignRuntime.existing(server).map(v->(int)v.entries().stream().filter(WarCampaignEntry.Shipment.class::isInstance).map(WarCampaignEntry.Shipment.class::cast).filter(s->s.run().person().equals(person)&&s.run().phase()==WarSupplyRun.Phase.DONE&&v.durable(s)).count()).orElse(0);
        int engineering=WarEngineeringRuntime.existing(server).map(r->(int)r.entries().stream().filter(j->j.person().equals(person)&&j.phase()==WarEngineeringJob.Phase.DONE&&j.adopted().size()<j.cells().size()&&r.durable(j)).count()).orElse(0);
        return completed+engineering+WarStaffRelayRuntime.existing(server).map(r->r.confirmedDuties(person)).orElse(0);
    }
    public List<String> history(ServerPlayer p,UUID army,UUID person){
        owner(p,army);var rows=new ArrayList<String>();rows.add("人物 "+person+" · 已确认军务/运输履历 "+career(person)+"次；三次履历或原经验20可满足专职正式任用的技能依据，不能替代原军役、规模和职权。");
        for(var e:book.entries())if(e instanceof Appointment a&&a.terms().army().equals(army)&&a.terms().holder().equals(person))rows.add(a.id()+" · "+a.terms().role().title()+" · "+a.terms().mode()+" · "+a.phase()+" · 任用依据 "+a.terms().socialClass()+"/"+a.terms().profession()+"/经验"+a.terms().experience()+" · "+a.terms().reason());
        if(muster().book().latest(person).filter(o->o.request().armyId().equals(army)).isPresent()||rows.size()>1)
            core().field().ifPresent(field->rows.add(field.leadershipSummary(person)));
        return List.copyOf(rows);
    }
    public boolean standardDuty(NpcBase npc,WorkCommitment work,long now){
        var a=book.forPerson(npc.getCharacterId());if(a.filter(v->v.terms().role()==Role.STANDARD_BEARER&&v.terms().service().equals(work.intent().id())&&effective(v)).isEmpty())return false;
        var offer=muster().book().offer(work.intent().id()).orElseThrow();var post=core().commandMedia().flatMap(m->m.post(offer.request().unitId()));
        if(post.isEmpty()||!available(npc)||!(npc.getOffhandItem().getItem() instanceof net.minecraft.world.item.BannerItem)){npc.getNavigation().stop();npc.reportWorkActivity("旗手暂不可履职",null,"需要原身体、真实副手旗帜与本兵团已保存的实际旗点");return true;}
        var point=post.get().position();var l=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(point.dimension().toString())));var at=new net.minecraft.core.BlockPos(point.x(),point.y(),point.z());
        if(l==null||l!=npc.level()||l.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)==null||!(l.getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.AbstractBannerBlock)){npc.getNavigation().stop();npc.reportWorkActivity("旗点不可用",null,"原旗帜缺失或区块未加载，不自动建旗或追踪未知位置");return true;}
        npc.setTarget(null);npc.stopUsingItem();if(npc.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)<=9)npc.getNavigation().stop();else if(now%40==Math.floorMod(npc.getUUID().hashCode(),40))npc.getNavigation().moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,.7);
        npc.reportWorkActivity("守护原集结旗",at,"按原兵团旗点履职；未获得自由发令权限");return true;
    }
    private boolean available(NpcBase npc){
        var health=com.jgeted.sagadyssey.social.combat.InjuryData.get(server).observeTriage(npc.getCharacterId());
        var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(npc.getCharacterId());
        return needs!=null&&WarStaffAvailability.available(new WarStaffAvailability.Facts(npc.isAlive(),!npc.isNoAi(),npc.isSleeping(),health.incapacitated(),health.mortalDanger(),npc.getHealth(),npc.getMaxHealth(),needs.hunger(),needs.lastUpdatedAt()),now());
    }
    private boolean currentService(UUID service,UUID person,UUID army){
        var core=WarServerEvents.existing(server);var m=WarMusterRuntime.existing(server);if(core.isEmpty()||m.isEmpty())return false;
        var offer=m.get().book().offer(service);var character=CharacterRegistryData.get(server).byId(person);
        return character!=null&&character.alive()&&offer.filter(o->m.get().book().durable(o)&&o.stage()==WarMusterEntry.Stage.ACTIVE&&o.request().armyId().equals(army)&&o.request().characterId().equals(person)&&core.get().servicePermits(o.request(),now())
                &&core.get().permission(o.request().actorId(),army)==WarCommandService.Permission.ALLOWED
                &&WorkCommitmentRuntime.existing(server).flatMap(w->w.find(service)).filter(w->w.phase()==WorkCommitment.Phase.ACTIVE&&w.intent().character().equals(person)&&w.intent().body().equals(o.body())&&w.intent().ownerKind().equals("sagadyssey:war")).isPresent()).isPresent();
    }
    private void tick(){
        if(closed)return;book.poll(4);offices.tick(now());if(!problem().isEmpty())return;
        // A restored appointment must wait for the original military host to finish binding.
        // Absence during startup is not evidence that the underlying service ended.
        if(WarMusterRuntime.existing(server).isEmpty()||WarServerEvents.existing(server).flatMap(WarServerRuntime::nativeMobilization).isEmpty())return;
        // Two indexed appointments per tick, including old terms that need revocation. No world scan.
        for(int n=Math.min(2,ready.size());n>0;n--){var it=ready.iterator();UUID id=it.next();it.remove();var a=book.appointment(id).orElseThrow();
            if(a.phase()==Phase.ACTIVE&&book.durable(a)&&visible.add(id))WarDutyChangedEvent.publish(server,a.terms().holder());
            try{step(a);}catch(RuntimeException e){if(a.phase()!=Phase.REVIEW)put(a.advance(Phase.REVIEW,now(),"SHARED_OFFICE_REQUIRES_REVIEW:"+e.getClass().getSimpleName()));}
            var current=book.appointment(id).orElseThrow();if(current.phase()!=Phase.REVOKED&&current.phase()!=Phase.REVIEW)ready.add(id);
            if(current.phase()!=a.phase()&&(current.phase()==Phase.REVOKING||current.phase()==Phase.REVIEW)){
                visible.remove(id);WarDutyChangedEvent.publish(server,a.terms().holder());
            }
        }
    }
    private void step(Appointment a){
        if(!book.durable(a))return;var t=a.terms();if(a.phase()==Phase.REVOKED||a.phase()==Phase.REVIEW)return;
        if(a.phase()!=Phase.REVOKING&&(!t.activeAt(now())||!currentService(t.service(),t.holder(),t.army()))){put(a.advance(Phase.REVOKING,now(),"原军役、生命、授权或任期已结束"));return;}
        boolean exists=offices.exists(a.id());
        if(a.phase()==Phase.PREPARING){
            if(!exists){
                if(recoveredPreparing.contains(a.id())){put(a.advance(Phase.REVIEW,now(),"重启后缺少原授职证据；不自动补授"));return;}
                var account=CharacterRegistryData.get(server).accountForCharacter(t.owner());if(account==null)throw new IllegalStateException("original owner account missing");
                offices.establish(muster().book().army(t.army()).orElseThrow(),account);offices.appoint(t);return;
            }
            if(!offices.matches(t,false)){put(a.advance(Phase.REVIEW,now(),"原机构职务内容已改变"));return;}
            if(!offices.durable(t,false)){offices.request();return;}put(a.advance(Phase.ACTIVE,now(),"原机构任命已保存；仅授权审阅军报与建议"));recoveredPreparing.remove(a.id());
        }else if(a.phase()==Phase.ACTIVE){if(!offices.matches(t,false))put(a.advance(offices.matches(t,true)?Phase.REVOKING:Phase.REVIEW,now(),"原机构职权已撤销或内容改变"));}
        else if(a.phase()==Phase.REVOKING){
            if(!exists){put(a.advance(Phase.REVIEW,now(),"撤职时原机构证据缺失"));return;}
            if(!offices.matches(t,true)){offices.revoke(t);return;}
            if(!offices.durable(t,true)){offices.request();return;}put(a.advance(Phase.REVOKED,now(),"原机构职权撤销已保存；履历与原军役责任保留"));
        }
    }
    private void put(Appointment a){var r=book.put(a);if(r.status()!=Status.APPLIED&&r.status()!=Status.PENDING)throw new IllegalStateException(r.reason());}
    private long now(){return server.overworld().getGameTime();}
    private void check(){if(!server.isSameThread()||closed)throw new IllegalStateException("staff server thread required");if(!problem().isEmpty())throw new IllegalStateException(problem());}
    private static Path path(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("data/"+WarStaffJournal.FILE_NAME);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent e){var file=path(e.getServer());try{for(String suffix:List.of("",".old","_old"))if(Files.exists(file.resolveSibling(file.getFileName()+suffix),LinkOption.NOFOLLOW_LINKS)){open(e.getServer());break;}}catch(IOException|RuntimeException invalid){FAILURES.put(e.getServer(),"STAFF_RECOVERY_REQUIRES_REVIEW:"+invalid.getClass().getSimpleName());}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){var v=STATES.get(e.getServer());if(v!=null)v.tick();}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var v=STATES.remove(e.getServer());FAILURES.remove(e.getServer());if(v!=null)v.close();}
    public void close(){if(closed)return;closed=true;book.close();ready.clear();visible.clear();}
}
