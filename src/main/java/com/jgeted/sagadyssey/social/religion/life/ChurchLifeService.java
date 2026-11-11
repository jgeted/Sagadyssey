package com.jgeted.sagadyssey.social.religion.life;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.justice.*;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.religion.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.server.network.Filterable;
import java.util.*;

/** Local observation, explicit invitations and real documents; never global faith/technology points. */
public final class ChurchLifeService {
    private ChurchLifeService(){}
    public static ChurchActivity schedule(MinecraftServer s,UUID id,TempleRecord t,ChurchActivity.Kind kind,
            BlockPos venue,UUID subject,UUID source,List<UUID> invited,int minimum,long start,long end,int duration){
        var d=ChurchLifeData.get(s);var a=new ChurchActivity(id,t.id(),kind,venue,subject,source,invited,Set.of(),minimum,start,end,duration,null,ChurchActivity.Stage.PLANNED,0,0,start,s.overworld().getGameTime(),"公开邀请尚未代替到场或同意");
        var old=d.activities().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);if(old!=null){if(!old.sameIntent(a))throw new IllegalStateException("different invitation under same id");return old;}
        if(venue.distSqr(t.counter())>96*96)throw new IllegalArgumentException("outside explicitly known local chapel");
        if(source!=null){var doc=d.record(source);if(!doc.temple().equals(t.id())||!published(s,doc)||kind==ChurchActivity.Kind.LESSON&&doc.access()!=ChurchRecord.Access.PUBLIC_TEXT)throw new IllegalStateException("lesson/copy requires a published, lawfully available local source");}
        if((kind==ChurchActivity.Kind.RITE||kind==ChurchActivity.Kind.LESSON||kind==ChurchActivity.Kind.FUNERAL)&&minimum<1)throw new IllegalArgumentException("assembly requires actual participants");
        d.put(a);return a;
    }
    public static ChurchActivity consent(MinecraftServer s,UUID id,LivingEntity actor){var d=ChurchLifeData.get(s);var a=d.activity(id);var t=ReligionData.get(s).temple(a.temple());
        if(!ReligionService.near(actor,t))throw new IllegalStateException("请先到本地教堂，不能远程替别人同意");
        var next=a.accept(ReligionService.character(actor));d.put(next);return next;}
    public static boolean present(LivingEntity e,TempleRecord t,BlockPos pos){return e!=null&&e.isAlive()&&e.level().dimension().identifier().toString().equals(t.alms().dimension())&&ReligionService.interacting(e,pos);}
    public static boolean usable(MinecraftServer s,TempleRecord t,BlockPos p){var l=SupplyFlowService.level(s,t.alms().dimension());return l!=null&&l.hasChunkAt(p)&&l.getBlockState(p).getCollisionShape(l,p).isEmpty()&&l.getBlockState(p.above()).getCollisionShape(l,p.above()).isEmpty()&&l.getBlockState(p.below()).isCollisionShapeFullBlock(l,p.below());}
    public static List<UUID> attendees(MinecraftServer s,ChurchActivity a,NpcBase host){var t=ReligionData.get(s).temple(a.temple());return a.invited().stream().filter(a.consent()::contains).filter(id->{var e=ReligionService.body(s,id);return present(e,t,a.venue())&&host.hasLineOfSight(e)&&host.distanceToSqr(e)<=36;}).toList();}
    public static boolean published(MinecraftServer s,ChurchRecord r){if(r.activity()==null)return true;var a=ChurchLifeData.get(s).activity(r.activity());return a.stage()==ChurchActivity.Stage.SETTLING||a.stage()==ChurchActivity.Stage.COMPLETED;}
    public static boolean canRead(MinecraftServer s,ChurchRecord r,UUID person){if(!published(s,r))return false;
        return switch(r.access()){case PUBLIC_TEXT->true;case PUBLIC_INDEX,OFFICE_RESTRICTED->InstitutionData.get(s).authority(SubjectRef.character(person),r.temple(),OfficeAuthority.MANAGE_LIBRARY,s.overworld().getGameTime()).allowed();case PARTY_ONLY,CONFIDENTIAL->r.parties().contains(person)||r.author().equals(person);};}
    public static boolean physicalPresent(MinecraftServer s,ChurchRecord r){if(r.physical()==null)return true;var t=ReligionData.get(s).temple(r.temple());var box=SupplyFlowService.container(s,t.treasury());if(box==null)return false;var resolver=new RightsService(RightsStateData.get(s));for(int slot=0;slot<box.getContainerSize();slot++)if(r.physical().matches(box.getItem(slot))&&resolver.can(SubjectRef.institution(t.id()),AssetRight.USE,t.treasury().containerId(),slot,r.physical(),s.overworld().getGameTime()).status()==RightsVerdict.Status.ALLOW)return true;return false;}
    public static String read(MinecraftServer s,UUID id,LivingEntity reader){var d=ChurchLifeData.get(s);var r=d.record(id);var t=ReligionData.get(s).temple(r.temple());UUID person=ReligionService.currentCharacter(reader);if(person==null)throw new IllegalStateException("当前没有可用的在生人物；查询不会创建人物");
        if(!present(reader,t,r.physical()==null?t.counter():t.treasury().position())||!canRead(s,r,person)||!physicalPresent(s,r))throw new IllegalStateException("文书不在场、不可查阅或无本人的合法查阅资格");
        learn(s,person,r);return r.title()+"："+r.text()+"（作者="+r.author()+"；成文="+r.authoredAt()+"；来源="+(r.source()==null?"现场记录/原书":r.source())+"；可信度="+r.confidence()+"）";}
    public static List<String> index(MinecraftServer s,TempleRecord t,LivingEntity reader){if(!ReligionService.near(reader,t))throw new IllegalStateException("请到本地接待处");UUID person=ReligionService.currentCharacter(reader);if(person==null)throw new IllegalStateException("当前没有可用的在生人物；查询不会创建人物");
        return ChurchLifeData.get(s).records().stream().filter(r->r.temple().equals(t.id())&&published(s,r)).filter(r->r.access()==ChurchRecord.Access.PUBLIC_TEXT||r.access()==ChurchRecord.Access.PUBLIC_INDEX||canRead(s,r,person)).map(r->r.id()+" "+r.title()+"（"+r.authoredAt()+"，仅目录不是全文）").toList();}
    public static void learn(MinecraftServer s,UUID person,ChurchRecord r){KnowledgeStateData.get(s).document(person,r.id(),new FactKey(r.temple(),r.predicate(),r.id().toString()),r.text(),PrecisionBand.CATEGORY,r.confidence(),s.overworld().getGameTime(),0,r.access()==ChurchRecord.Access.PUBLIC_TEXT?DisclosurePolicy.PUBLIC:DisclosurePolicy.PRIVATE);}
    /** An OP imports an existing written book, never invents its contents or authenticates its claims. */
    public static ChurchRecord catalogue(MinecraftServer s,TempleRecord t,int slot,ChurchRecord.Access access){var box=SupplyFlowService.container(s,t.treasury());if(box==null||!SupplyFlowService.validOwner(s,t.treasury())||slot<0||slot>=box.getContainerSize())throw new IllegalStateException("valid actual archive slot required");
        var stack=box.getItem(slot);var book=stack.get(DataComponents.WRITTEN_BOOK_CONTENT);if(book==null)throw new IllegalArgumentException("须将真实成书放入教堂财库再登记，不生成教材");
        String text=book.pages().stream().map(p->p.raw().getString()).collect(java.util.stream.Collectors.joining("\n"));
        var id=ContainerIds.named("chapel-original-book:"+t.id()+":"+ItemFingerprint.of(stack));var old=ChurchLifeData.get(s).findRecord(id);if(old!=null)return old;
        var r=new ChurchRecord(id,t.id(),null,null,ContainerIds.named("book-author:"+book.author()),t.treasury().containerId(),s.overworld().getGameTime(),book.title().raw(),text,access,Set.of(),.7,ItemFingerprint.of(stack),"church.source_text");ChurchLifeData.get(s).put(r);return r;}
    public static ItemStack book(ChurchRecord r){var stack=new ItemStack(Items.WRITTEN_BOOK);stack.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough(r.title()),r.author().toString(),0,List.of(Filterable.passThrough(Component.literal(r.text()))),true));var tag=new CompoundTag();tag.store("ChurchDocument", UUIDUtil.CODEC,r.id());tag.putLong("AuthoredAt",r.authoredAt());if(r.source()!=null)tag.store("Source", UUIDUtil.CODEC,r.source());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;}
    public static ChurchRecord prepareRecord(MinecraftServer s,ChurchActivity a,List<UUID> actual){var d=ChurchLifeData.get(s);var existing=d.findRecord(a.id());if(existing!=null)return existing;
        var t=ReligionData.get(s).temple(a.temple());String title=switch(a.kind()){case RITE->"集会记录";case LESSON->"教学记录";case COPY->"抄本";case VISIT->"探访记录";case MARRIAGE->"婚礼仪式记录";case FUNERAL->"丧礼仪式记录";case MAINTENANCE->"场所维护记录";case WATCH->"看守巡查记录";};
        String text=switch(a.kind()){case RITE->"主持者与受邀者实际到场，集会已举行。";case LESSON->"教员与学生当面查阅教材；知识来自该教材，不保证教材内容为真。";case COPY->d.record(a.source()).text();case VISIT->"本人同意的当面探访已完成；此记录不表示疾病已治愈。";case MARRIAGE->"双方明确同意并实际到场，婚礼仪式举行；家庭配偶登记须另行依法确认。";case FUNERAL->"收到死亡报告后举行丧礼；未凭此核实死亡、墓葬或遗产。";case MAINTENANCE->"已检查并维护本地指定步道支撑；未声称整个建筑已修复。";case WATCH->"看守在指定地点实际巡视；没有由后台全知生成犯罪判断。";};
        var access=a.kind()==ChurchActivity.Kind.MARRIAGE||a.kind()==ChurchActivity.Kind.VISIT?ChurchRecord.Access.PARTY_ONLY:a.kind()==ChurchActivity.Kind.COPY?d.record(a.source()).access():ChurchRecord.Access.PUBLIC_TEXT;
        UUID origin=a.source();
        if(a.kind()==ChurchActivity.Kind.FUNERAL){var report=KnowledgeStateData.get(s).beliefsAbout(a.host(),a.subject()).stream().filter(b->b.key().predicate().equals("church.death_report")&&!b.staleAt(s.overworld().getGameTime())).findFirst().orElseThrow(()->new IllegalStateException("no current known death report"));
            origin=report.id();if(d.findRecord(origin)==null){UUID author=report.provenanceChain().isEmpty()?a.host():report.provenanceChain().getFirst();d.put(new ChurchRecord(origin,a.temple(),null,null,author,t.treasury().containerId(),report.learnedAt(),"死亡报告（未核实）",report.displayValue(),ChurchRecord.Access.CONFIDENTIAL,Set.of(a.host()),report.confidence(),null,"church.death_report"));}}
        var parties=new HashSet<>(actual);parties.add(a.host());var draft=new ChurchRecord(a.id(),a.temple(),a.id(),origin,a.host(),t.treasury().containerId(),s.overworld().getGameTime(),title,text,access,parties,a.kind()==ChurchActivity.Kind.COPY?d.record(a.source()).confidence():.9,null,"church."+a.kind().name().toLowerCase(Locale.ROOT));
        var r=a.kind().manuscript()?new ChurchRecord(draft.id(),draft.temple(),draft.activity(),draft.source(),draft.author(),draft.custodian(),draft.authoredAt(),draft.title(),draft.text(),draft.access(),draft.parties(),draft.confidence(),ItemFingerprint.of(book(draft)),draft.predicate()):draft;
        d.put(r);return r;}
    public static void publish(MinecraftServer s,ChurchActivity a,List<UUID> actual){var d=ChurchLifeData.get(s);var r=d.record(a.id());for(UUID person:actual)if(canRead(s,r,person))learn(s,person,r);learn(s,a.host(),r);
        if(a.kind()==ChurchActivity.Kind.LESSON){var source=d.record(a.source());learn(s,a.host(),source);for(var person:actual)if(d.exposure(a.id(),person)>=a.duration())learn(s,person,source);} }
    private static boolean committed(MinecraftServer s,ChurchActivity a){if(!a.kind().manuscript()||a.stage()==ChurchActivity.Stage.SETTLING||a.stage().closed())return false;var batch=ProductionBatchData.get(s).batch(a.id());if(batch==null||batch.status()!=ProductionBatchStatus.COMPLETED)return false;
        var d=ChurchLifeData.get(s);var r=d.record(a.id());var t=ReligionData.get(s).temple(a.temple());if(a.work()!=a.duration()||!batch.workerId().equals(a.host())||!batch.outputOwner().equals(SubjectRef.institution(a.temple()))||!batch.containerId().equals(t.treasury().containerId())||!batch.output().fingerprint().equals(r.physical())||!r.author().equals(a.host()))throw new IllegalStateException("committed manuscript receipt does not match fixed activity");
        var next=a.state(ChurchActivity.Stage.SETTLING,s.overworld().getGameTime(),"原生产提交已证明；只补文书结算，不重复扣物或制书");d.put(next);publish(s,next,List.copyOf(r.parties()));return true;}
    public static void cancel(MinecraftServer s,ChurchActivity a,String why){if(a.stage().closed()||committed(s,a))return;long now=s.overworld().getGameTime();if(a.stage()==ChurchActivity.Stage.SETTLING){ChurchLifeData.get(s).put(a.state(ChurchActivity.Stage.COMPLETED,now,"已结算活动只补收尾，不逆转真实结果"));return;}ProductionService.cancel(s,a.id(),now,why);RightsStateData.get(s).releaseReservations(a.token());ChurchLifeData.get(s).put(a.state(ChurchActivity.Stage.CANCELLED,now,why));}
    public static boolean venueFree(MinecraftServer s,ChurchActivity a){var d=ChurchLifeData.get(s);String dimension=ReligionData.get(s).temple(a.temple()).alms().dimension();return d.activities().stream().noneMatch(b->!b.id().equals(a.id())&&!b.stage().closed()&&b.stage()!=ChurchActivity.Stage.PLANNED&&ReligionData.get(s).temple(b.temple()).alms().dimension().equals(dimension)&&b.venue().distSqr(a.venue())<36);}
    public static ChurchCompact propose(MinecraftServer s,UUID id,TempleRecord t,SubjectRef partner,LivingEntity proposer,long expires,String terms){long now=s.overworld().getGameTime();UUID person=ReligionService.character(proposer);
        if(!ReligionService.near(proposer,t)||!represents(s,person,SubjectRef.institution(t.id()))&&!represents(s,person,partner))throw new IllegalStateException("须由在场且具有当前代表权的人物提出");
        if(partner.type()==SubjectRef.SubjectType.INSTITUTION){var i=InstitutionData.get(s).institution(partner.id());if(i==null||!i.active()||!Objects.equals(i.settlementId(),InstitutionData.get(s).institution(t.id()).settlementId()))throw new IllegalArgumentException("partner must be a real local council/guild/institution");}
        else if(partner.type()==SubjectRef.SubjectType.HOUSEHOLD){var h=com.jgeted.sagadyssey.social.household.HouseholdData.get(s).byId(partner.id());if(h==null||!Objects.equals(h.settlementId(),InstitutionData.get(s).institution(t.id()).settlementId()))throw new IllegalArgumentException("partner must be a real local household");}
        var d=ChurchLifeData.get(s);var old=d.compacts().stream().filter(c->c.id().equals(id)).findFirst().orElse(null);if(old!=null){if(!old.temple().equals(t.id())||!old.partner().equals(partner)||!old.terms().equals(terms)||old.expires()!=expires||!old.proposer().equals(person))throw new IllegalStateException("different compact under same id");return old;}
        var c=new ChurchCompact(id,t.id(),partner,terms,now,expires,person,null,null);d.put(c);return c;}
    private static boolean represents(MinecraftServer s,UUID actor,SubjectRef body){if(body.type()==SubjectRef.SubjectType.INSTITUTION)return InstitutionData.get(s).authority(SubjectRef.character(actor),body.id(),OfficeAuthority.NEGOTIATE_TREATY,s.overworld().getGameTime()).allowed();
        if(body.type()==SubjectRef.SubjectType.HOUSEHOLD){var h=com.jgeted.sagadyssey.social.household.HouseholdData.get(s).byId(body.id());return h!=null&&h.headCharacterId().equals(actor);}return false;}
    public static ChurchCompact sign(MinecraftServer s,UUID id,LivingEntity signer,boolean church){var d=ChurchLifeData.get(s);var c=d.compact(id);var t=ReligionData.get(s).temple(c.temple());UUID person=ReligionService.character(signer);long now=s.overworld().getGameTime();
        if(!ReligionService.near(signer,t)||now>=c.expires()||!represents(s,person,church?SubjectRef.institution(c.temple()):c.partner()))throw new IllegalStateException("签署须在场、未过期且持有效代表权；不是同阵营就可代签");
        var next=c.sign(church,person);d.put(next);
        KnowledgeStateData.get(s).selfReport(person,person,new FactKey(c.temple(),"church.compact_signature",id.toString()),"本人以当前代表权同意的地方协定："+c.terms(),PrecisionBand.CATEGORY,1,now,0,DisclosurePolicy.PRIVATE);
        if(next.active(now)&&d.findRecord(id)==null){var parties=new HashSet<UUID>();parties.add(c.proposer());parties.add(next.templeSigner());parties.add(next.partnerSigner());var r=new ChurchRecord(id,c.temple(),null,null,c.proposer(),t.treasury().containerId(),now,"地方合作协定",c.terms()+"；仅承诺所述本地义务，不自动转移财产、强制参会或保证履约。",ChurchRecord.Access.PARTY_ONLY,parties,.9,null,"church.compact");d.put(r);
            if(c.partner().type()==SubjectRef.SubjectType.INSTITUTION)InstitutionData.get(s).setRelation(new InstitutionRelation(c.temple(),c.partner().id(),InstitutionRelationKind.TRADE_COMPACT,now,c.expires(),"specific chapel compact "+id));
        }return next;}
    public static void tick(MinecraftServer s){long now=s.overworld().getGameTime();var d=ChurchLifeData.get(s);
        for(var a:d.activities())if(!a.stage().closed()){if(a.stage()==ChurchActivity.Stage.SETTLING&&now-a.updated()>=30)d.put(a.state(ChurchActivity.Stage.COMPLETED,now,"作业已提交，有限散会结束，场所释放"));else if(committed(s,a))continue;else if(a.stage()!=ChurchActivity.Stage.SETTLING&&now>a.deadline())cancel(s,a,"超过公开时间窗口，延期预算已结束；释放场所和材料");}
        for(var c:d.calendars())if(c.count()<c.limit()&&now>=c.next()){
            var t=ReligionData.get(s).temple(c.temple());long time=c.next();UUID id=ContainerIds.named("chapel-calendar:"+c.id()+":"+c.count());
            schedule(s,id,t,ChurchActivity.Kind.RITE,c.venue(),null,null,c.invited(),c.minimum(),time,time+Math.min(1200,c.interval()-1),60);
            d.put(new ChurchLifeData.Calendar(c.id(),c.temple(),c.venue(),c.invited(),c.minimum(),c.interval(),c.first(),c.count()+1,c.limit()));
        }
    }
    /** Evidence enters an open local case. It does not convict, change faction standings, or expose secrets. */
    public static DisputeRecord complain(MinecraftServer s,LivingEntity actor,UUID record,UUID forum,String allegation){
        var r=ChurchLifeData.get(s).record(record);read(s,record,actor);UUID person=ReligionService.character(actor);var inst=InstitutionData.get(s);var temple=inst.institution(r.temple());var court=inst.institution(forum);
        if(court==null||!court.active()||!Objects.equals(temple.settlementId(),court.settlementId()))throw new IllegalArgumentException("须向本地实际受理机构提交");
        if(allegation==null||allegation.isBlank()||allegation.length()>256)throw new IllegalArgumentException("specific allegation required");
        UUID id=ContainerIds.named("chapel-complaint:"+person+":"+record+":"+forum+":"+allegation);var justice=JusticeData.get(s);var old=justice.dispute(id);if(old!=null)return old;
        var compact=ChurchLifeData.get(s).compacts().stream().filter(c->c.id().equals(record)).findFirst().orElse(null);
        var claimant=compact!=null&&represents(s,person,compact.partner())?compact.partner():SubjectRef.character(person);
        AdjudicationService.file(justice,inst,id,forum,temple.settlementId(),claimant,SubjectRef.institution(r.temple()),DisputeType.JURISDICTION,allegation,s.overworld().getGameTime());
        if(compact!=null&&compact.partner().type()==SubjectRef.SubjectType.INSTITUTION&&compact.active(s.overworld().getGameTime()))inst.setRelation(new InstitutionRelation(compact.temple(),compact.partner().id(),InstitutionRelationKind.JURISDICTION_DISPUTE,s.overworld().getGameTime(),compact.expires(),"specific compact dispute "+id));
        return AdjudicationService.submitEvidence(justice,id,new EvidenceRef(ContainerIds.named("church-evidence:"+id),EvidenceKind.CHURCH_RECORD,r.id(),SubjectRef.character(person),s.overworld().getGameTime(),"文书来源、作者、时间与可信度可复核；申诉本身不构成有罪结论"));}
}
