package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.identity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.war.application.WarCommandService;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import java.nio.file.*;
import java.util.*;

/** Original services and shared InstitutionOffice in an isolated native world; no desktop or client required. */
@GameTestHolder("sagadyssey_war_staff") @PrefixGameTestTemplate(false) @EventBusSubscriber(modid="sagadyssey")
public final class WarStaffGameTests {
    private static boolean enabled(){return Boolean.getBoolean("sagadyssey.war.staff.test");}
    private static boolean relayPhase(){return Set.of("relay","duty").contains(phase());}
    private static String phase(){return System.getProperty("sagadyssey.war.staff.phase","single");}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void started(ServerStartedEvent e)throws Exception{
        if(!enabled()||!(e.getServer() instanceof GameTestServer))return;var s=e.getServer();var l=s.overworld();
        if(Set.of("resume","missing-office","missing-staff").contains(phase())){var p=read(s.getWorldPath(LevelResource.ROOT).resolve("war-staff-save.json"));s.getWorldData().overworldData().setGameTime(p.get("gameTime").getAsLong());s.getWorldData().overworldData().setDayTime(p.get("dayTime").getAsLong());}
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME,false,s);l.getGameRules().set(GameRules.SPAWN_MOBS,false,s);
        l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_staff:empty")).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void missing(ServerAboutToStartEvent e)throws Exception{
        if(enabled()&&phase().equals("missing-office")){var f=e.getServer().getWorldPath(LevelResource.ROOT).resolve("data/sagadyssey_institutions.dat");Files.move(f,f.resolveSibling(f.getFileName()+".test-retained"));}
        if(enabled()&&phase().equals("missing-staff")){var f=e.getServer().getWorldPath(LevelResource.ROOT).resolve("data/sagadyssey_war_staff.sgw");Files.move(f,f.resolveSibling(f.getFileName()+".test-retained"));}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){return enabled()?List.of(new TestFunction("war_staff",phase(),"sagadyssey_war_staff:empty",6000,0,true,phase().equals("resume")?WarStaffGameTests::resume:phase().startsWith("missing-")?WarStaffGameTests::blocked:WarStaffGameTests::cycle)):List.of();}
    private static void cycle(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();var origin=h.absolutePos(BlockPos.ZERO).offset(2048,-3,0);force(h,origin);
        for(int x=0;x<20;x++)for(int z=0;z<14;z++){l.setBlockAndUpdate(origin.offset(x,0,z),Blocks.STONE.defaultBlockState());for(int y=1;y<8;y++)l.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        var p=WarMusterGameTests.battlePlayer(h,"StaffLord",new BlockPos(2,1,8));p.setPos(origin.getX()+4.5,origin.getY()+1,origin.getZ()+8.5);var actor=CharacterRegistry.currentId(p);var life=NpcLifeData.get(s);
        var people=new ArrayList<NpcBase>();var contracts=new ArrayList<UUID>();
        for(int i=0;i<2;i++){
            var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);n.teleportTo(origin.getX()+4.5+i*5,origin.getY()+1,origin.getZ()+5.5);n.setPersistenceRequired();n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.STAY);n.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);
            for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));n.getPersonalInventory().clearContent();n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));n.getPersonalInventory().setItem(1,new ItemStack(Items.IRON_PICKAXE));
            life.onRecruited(n,actor,l.getGameTime());life.setHunger(n.getCharacterId(),300,l.getGameTime());people.add(n);
        }
        p.getInventory().setItem(3,new ItemStack(Items.EMERALD,16));WarMusterGameTests.command(p,"saga war muster create StaffArmy");var muster=WarMusterRuntime.existing(s).orElseThrow();var army=WarMusterEntry.armyId(actor,"StaffArmy");var unit=WarMusterEntry.unitId(army,"Workers");UUID[] first={null},second={null};
        UUID[] relay={null},cancelledRelay={null},revokedRelay={null},ownerRally={null},interrupted={null};var dutyProof=new JsonObject();
        var sequence=h.startSequence().thenWaitUntil(()->h.assertTrue(muster.book().owns(actor,army),"ordinary army owner durable"))
                .thenExecute(()->WarMusterGameTests.command(p,"saga war muster unit StaffArmy Workers sword"))
                .thenWaitUntil(()->h.assertTrue(muster.book().unit(unit).filter(u->u.recorded()&&muster.book().durable(u)).isPresent(),"original unit ready"))
                .thenExecute(()->{for(var n:people)contracts.add(WarMusterGameTests.battleOffer(h,p,n,"StaffArmy","Workers",phase().equals("support")&&n==people.get(1)));})
                .thenWaitUntil(()->h.assertTrue(contracts.size()==2&&contracts.stream().allMatch(id->muster.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ACTIVE&&muster.book().durable(o)).isPresent()),"two original consenting combatants"))
                .thenExecute(()->h.assertTrue(WarMusterGameTests.command(p,"saga war staff review StaffArmy")==1,"explicit formation review command"))
                .thenWaitUntil(()->{var staff=WarStaffRuntime.existing(s).orElseThrow();h.assertTrue(staff.book().establishment(army).filter(e->staff.book().durable(e)&&e.strength()==(phase().equals("support")?1:2)).isPresent(),"real service count includes only original combatants");})
                .thenExecute(()->{
                    h.assertTrue(WarMusterGameTests.command(p,"saga war staff appoint StaffArmy staff_officer "+people.getFirst().getCharacterId()+" 1 regular test")==0,"small squad cannot appoint dedicated staff");
                    h.assertTrue(WarMusterGameTests.command(p,"saga war staff appoint StaffArmy deputy "+people.getFirst().getCharacterId()+" 1 regular test")==0,"formal deputy requires21 combatants");
                    near(p,people.getFirst());h.assertTrue(WarMusterGameTests.command(p,"saga war staff appoint StaffArmy temporary_deputy "+people.getFirst().getCharacterId()+" 1 acting 领主明确交办")==1,"commoner may be appointed a compatible acting deputy");
                    var staff=WarStaffRuntime.existing(s).orElseThrow();var a=staff.book().entries().stream().filter(Appointment.class::isInstance).map(Appointment.class::cast).findFirst().orElseThrow();first[0]=a.id();h.assertTrue(!staff.effective(a),"pending intent is not shared office authority");
                }).thenWaitUntil(()->h.assertTrue(effective(s,first[0]),"shared office and military evidence both durably active"))
                .thenExecute(()->{if(Set.of("supplies","support").contains(phase())){
                    var staff=WarStaffRuntime.existing(s).orElseThrow();var office=staff.book().appointment(first[0]).orElseThrow();
                    h.assertTrue(WarMusterGameTests.command(p,"saga war staff supplies StaffArmy")==1,"owner can query original supply ledger without appointing a quartermaster");
                    for(var n:people)life.setHunger(n.getCharacterId(),550,l.getGameTime());
                    h.assertTrue(staff.effective(office),"hunger fixture keeps same legal office");
                }})
                .thenWaitUntil(()->{if(Set.of("supplies","support").contains(phase())){
                    var provision=ServiceProvisionRuntime.existing(s).orElseThrow();h.assertTrue(contracts.stream().allMatch(id->provision.durable(id)&&provision.find(id).orElseThrow().settlements().stream().filter(v->v.applied()&&v.attempt().purpose()==ServiceProvision.Purpose.MEAL).count()==1),"two real original ration consumptions durably recorded");
                    h.assertTrue(people.stream().allMatch(n->n.getPersonalInventory().count(i->i.is(Items.BREAD))==15&&life.needs(n.getCharacterId()).hunger()<550),"actual original bread decreased and original needs improved");
                }})
                .thenExecute(()->{if(Set.of("supplies","support").contains(phase())){
                    var staff=WarStaffRuntime.existing(s).orElseThrow();var provision=ServiceProvisionRuntime.existing(s).orElseThrow();var before=contracts.stream().map(id->provision.find(id).orElseThrow()).toList();var revisions=people.stream().map(n->n.getPersonalInventory().revision()).toList();
                    for(int i=0;i<10;i++){var report=staff.supplies(p,army);h.assertTrue(report.get(0).contains("已核对保存 2")&&report.get(2).contains("实际餐食 2份"),"report uses confirmed original meal evidence");}
                    h.assertTrue(before.equals(contracts.stream().map(id->provision.find(id).orElseThrow()).toList())&&revisions.equals(people.stream().map(n->n.getPersonalInventory().revision()).toList())&&p.getInventory().countItem(Items.EMERALD)==16,"repeated supply queries do not settle, consume or duplicate salary");
                    var stranger=WarMusterGameTests.battlePlayer(h,"SupplyOther",new BlockPos(2,1,9));boolean denied=false;try{staff.supplies(stranger,army);}catch(IllegalArgumentException expected){denied=true;}h.assertTrue(denied,"foreign OP cannot read owner's supply report");
                }})
                .thenExecute(()->{if(phase().equals("support")){
                    var at=origin.offset(6,1,8);l.setBlockAndUpdate(at,Blocks.WHITE_BANNER.defaultBlockState());p.setItemSlot(EquipmentSlot.MAINHAND,MapItem.create(l,at.getX(),at.getZ(),(byte)0,true,true));var media=WarServerEvents.existing(s).orElseThrow().commandMedia().orElseThrow();media.select(p,unit);media.mode(p,com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.RALLY);
                    var result=media.issue(p,new com.jgeted.sagadyssey.social.war.api.WarIntent.Position(com.jgeted.sagadyssey.social.war.api.WarKey.parse(l.dimension().identifier().toString()),at.getX(),at.getY(),at.getZ()),false);h.assertTrue(result.outcome()==com.jgeted.sagadyssey.social.war.application.WarDispatchService.Outcome.ACCEPTED,"normal combat order accepted in mixed combat/support unit");ownerRally[0]=result.record().orElseThrow().command().operationId();
                    h.assertTrue(result.record().orElseThrow().members().size()==1&&result.record().orElseThrow().members().getFirst().character().equals(people.getFirst().getCharacterId()),"dedicated staff is excluded from actual original transmission roster");
                }})
                .thenWaitUntil(()->{if(phase().equals("support")){
                    var reports=WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army);var staffReport=reports.stream().filter(r->r.character().equals(people.get(1).getCharacterId())).findFirst().orElseThrow();
                    h.assertTrue(staffReport.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.WAITING&&staffReport.reason().equals("DEDICATED_STAFF_REPORT_DUTY:RECENT_FRIENDLY_REPORTS=1"),"original primary executor actually maintains own-unit reports");
                    h.assertTrue(reports.stream().anyMatch(r->r.character().equals(people.getFirst().getCharacterId())&&r.order().filter(ownerRally[0]::equals).isPresent()&&r.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.REGROUPING),"combatant executes normal rally while staff keeps report duty");
                    var helper=people.get(1);h.assertTrue(helper.getTarget()==null&&helper.getNavigation().isDone()&&!helper.isUsingItem()&&WorkCommitmentRuntime.blocksCivil(helper),"dedicated worker stays exclusively in original military support labor");
                    boolean denied=false;try{WarStaffRuntime.existing(s).orElseThrow().appoint(p,UUID.randomUUID(),army,Role.TEMPORARY_DEPUTY,Optional.empty(),helper.getCharacterId(),1,Mode.REGULAR,"role conflict");}catch(IllegalArgumentException expected){denied=true;}h.assertTrue(denied,"dedicated service cannot silently become part-time combat office");
                }})
                .thenExecute(()->{if(relayPhase()){
                    var at=origin.offset(6,1,8);l.setBlockAndUpdate(at,Blocks.WHITE_BANNER.defaultBlockState());var map=MapItem.create(l,at.getX(),at.getZ(),(byte)0,true,true);p.setItemSlot(EquipmentSlot.MAINHAND,map);
                    var media=WarServerEvents.existing(s).orElseThrow().commandMedia().orElseThrow();media.select(p,unit);media.bindPost(p,new com.jgeted.sagadyssey.social.war.api.WarIntent.Position(com.jgeted.sagadyssey.social.war.api.WarKey.parse(l.dimension().identifier().toString()),at.getX(),at.getY(),at.getZ()),com.jgeted.sagadyssey.social.war.api.WarKey.parse("minecraft:white_banner"));
                }})
                .thenWaitUntil(()->{if(relayPhase())h.assertTrue(WarServerEvents.existing(s).orElseThrow().commandMedia().orElseThrow().post(p).isPresent(),"original banner checkpoint");})
                .thenWaitUntil(()->{if(phase().equals("duty")){
                    var media=WarServerEvents.existing(s).orElseThrow().commandMedia().orElseThrow();media.mode(p,com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.RALLY);
                    var result=media.issue(p,media.post(p).orElseThrow().position(),false);h.assertTrue(result.outcome()==com.jgeted.sagadyssey.social.war.application.WarDispatchService.Outcome.ACCEPTED,"ordinary owner rally accepted before delegation");ownerRally[0]=result.record().orElseThrow().command().operationId();
                }})
                .thenWaitUntil(()->{if(phase().equals("duty")){
                    var reports=WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army);h.assertTrue(reports.size()==2&&reports.stream().allMatch(r->r.order().filter(ownerRally[0]::equals).isPresent()&&r.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.REGROUPING),"both original bodies first perform owner's ordinary rally");
                }})
                .thenExecute(()->{if(relayPhase())try{relay[0]=WarStaffRelayRuntime.open(s).prepare(p,first[0]);}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}})
                .thenWaitUntil(()->{if(phase().equals("duty")){
                    var runtime=WarStaffRelayRuntime.existing(s).orElseThrow();var task=runtime.find(relay[0]).orElseThrow();int actual=runtime.dutyProgress(relay[0]);h.assertTrue(actual>=8&&actual<40,"actual body has begun the exclusive duty");
                    var reports=WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army);var assistant=people.getFirst();var helper=reports.stream().filter(r->r.character().equals(assistant.getCharacterId())).findFirst().orElseThrow();var peer=reports.stream().filter(r->!r.character().equals(assistant.getCharacterId())).findFirst().orElseThrow();
                    h.assertTrue(task.phase()==com.jgeted.sagadyssey.social.war.domain.WarStaffRelay.Phase.QUEUED&&WarServerEvents.existing(s).orElseThrow().field().orElseThrow().transmission(actor,army,relay[0]).isEmpty(),"time not yet earned cannot publish an order");
                    h.assertTrue(helper.reason().equals("STAFF_RELAY_PRIMARY_DUTY")&&helper.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.WAITING&&assistant.getTarget()==null&&!assistant.isUsingItem()&&assistant.getNavigation().isDone(),"original primary executor stops fighting, aiming and movement during real staff work");
                    h.assertTrue(peer.order().filter(ownerRally[0]::equals).isPresent()&&peer.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.REGROUPING,"peer combatant continues original order without whole-unit freeze");
                    boolean duplicate=false;try{runtime.prepare(p,first[0]);}catch(IllegalArgumentException expected){duplicate=true;}h.assertTrue(duplicate,"busy assistant cannot double-book duties");
                    h.assertTrue(WorkCommitmentRuntime.blocksCivil(assistant)&&p.getInventory().countItem(Items.EMERALD)==16,"same original military labor and cost owner, no additional full-time wage");
                    dutyProof.addProperty("task",relay[0].toString());dutyProof.addProperty("originalOwnerOrder",ownerRally[0].toString());dutyProof.addProperty("observedDutyTicks",actual);dutyProof.addProperty("observedAt",l.getGameTime());dutyProof.addProperty("originalBody",assistant.getUUID().toString());dutyProof.addProperty("originalService",contracts.getFirst().toString());dutyProof.addProperty("exclusivePrimaryExecution",true);dutyProof.addProperty("peerContinues",true);
                }})
                .thenWaitUntil(()->{if(relayPhase())try{
                    h.assertTrue(WarStaffRelayRuntime.open(s).find(relay[0]).filter(r->r.phase()==com.jgeted.sagadyssey.social.war.domain.WarStaffRelay.Phase.SENT).isPresent(),"one delegated rally accepted: "+WarStaffRelayRuntime.open(s).status(p,relay[0]));
                    var reports=WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army);h.assertTrue(reports.size()==2&&reports.stream().allMatch(r->r.order().filter(relay[0]::equals).isPresent()&&r.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.REGROUPING),"original unit physically rallies after delegated order");
                }catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}})
                .thenExecute(()->{if(phase().equals("duty"))try{
                    var runtime=WarStaffRelayRuntime.open(s);var sent=runtime.find(relay[0]).orElseThrow();h.assertTrue(sent.changedAt()-sent.createdAt()>=40&&sent.reason().contains("40刻"),"accepted delegation required forty actual executions");dutyProof.addProperty("sentAt",sent.changedAt());dutyProof.addProperty("createdAt",sent.createdAt());interrupted[0]=runtime.prepare(p,first[0]);
                }catch(Exception e){throw new IllegalStateException(e);}})
                .thenWaitUntil(()->{if(phase().equals("duty")){var runtime=WarStaffRelayRuntime.existing(s).orElseThrow();h.assertTrue(runtime.dutyProgress(interrupted[0])>=8,"second task actually occupies original executor before interruption");people.getFirst().setNoAi(true);}})
                .thenWaitUntil(()->{if(phase().equals("duty")){var runtime=WarStaffRelayRuntime.existing(s).orElseThrow();h.assertTrue(runtime.find(interrupted[0]).filter(r->r.phase()==com.jgeted.sagadyssey.social.war.domain.WarStaffRelay.Phase.CANCELLED).isPresent(),"disabled original body cancels unfinished duty");h.assertTrue(!runtime.occupies(people.getFirst().getCharacterId())&&WarServerEvents.existing(s).orElseThrow().field().orElseThrow().transmission(actor,army,interrupted[0]).isEmpty(),"interrupted task releases duty without an order");people.getFirst().setNoAi(false);dutyProof.addProperty("interruptedTask",interrupted[0].toString());dutyProof.addProperty("interruptionReleased",true);}})
                .thenWaitUntil(()->{if(phase().equals("duty")){var reports=WarServerEvents.existing(s).orElseThrow().field().orElseThrow().reports(actor,army);h.assertTrue(reports.size()==2&&reports.stream().allMatch(r->r.order().filter(relay[0]::equals).isPresent()&&r.state()==com.jgeted.sagadyssey.social.war.api.WarFieldReport.State.REGROUPING),"same body resumes its existing order after duty interruption");try{write(s.getWorldPath(LevelResource.ROOT).resolve("war-staff-duty-work.json"),dutyProof);}catch(Exception e){throw new IllegalStateException(e);}}})
                .thenExecute(()->{if(relayPhase())try{cancelledRelay[0]=WarStaffRelayRuntime.open(s).prepare(p,first[0]);l.setBlockAndUpdate(origin.offset(6,1,8),Blocks.AIR.defaultBlockState());}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}})
                .thenWaitUntil(()->{if(relayPhase())try{h.assertTrue(WarStaffRelayRuntime.open(s).find(cancelledRelay[0]).filter(r->r.phase()==com.jgeted.sagadyssey.social.war.domain.WarStaffRelay.Phase.CANCELLED).isPresent(),"removed actual flag cancels pending relay");h.assertTrue(WarServerEvents.existing(s).orElseThrow().field().orElseThrow().transmission(actor,army,cancelledRelay[0]).isEmpty(),"cancelled relay issued no order");}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}})
                .thenExecute(()->{
                    var staff=WarStaffRuntime.existing(s).orElseThrow();var a=staff.book().appointment(first[0]).orElseThrow();var powers=InstitutionData.get(s).officesHeld(army,SubjectRef.character(people.getFirst().getCharacterId()),l.getGameTime());
                    var assistant=people.getFirst();float originalHealth=assistant.getHealth();int originalHunger=life.needs(assistant.getCharacterId()).hunger();long inventoryRevision=assistant.getPersonalInventory().revision();
                    // Explicit fixture probes: queries never heal/feed, change office tenure or grant a replacement.
                    assistant.setHealth(assistant.getMaxHealth()*.25F);
                    h.assertTrue(staff.advice(p,army).getFirst().contains("暂无原身体可用")&&staff.effective(a),"unfit body pauses advice, retains legitimate office");
                    var rejected=UUID.randomUUID();boolean unavailable=false;try{staff.appoint(p,rejected,army,Role.TEMPORARY_DEPUTY,Optional.empty(),assistant.getCharacterId(),1,Mode.ACTING,"unfit candidate");}catch(IllegalArgumentException expected){unavailable=true;}
                    h.assertTrue(unavailable&&staff.book().appointment(rejected).isEmpty(),"unfit candidate cannot reserve a new appointment");
                    assistant.setHealth(originalHealth);life.setHunger(assistant.getCharacterId(),900,l.getGameTime());
                    h.assertTrue(staff.advice(p,army).getFirst().contains("暂无原身体可用")&&staff.effective(a),"original starvation pauses advice without dismissal");
                    life.setHunger(assistant.getCharacterId(),originalHunger,l.getGameTime());assistant.setNoAi(true);
                    h.assertTrue(staff.advice(p,army).getFirst().contains("暂无原身体可用"),"disabled original executor cannot advise");assistant.setNoAi(false);
                    h.assertTrue(!staff.advice(p,army).getFirst().contains("暂无原身体可用")&&staff.effective(a)&&assistant.getPersonalInventory().revision()==inventoryRevision,"restored facts resume same office without item effects");
                    h.assertTrue(powers.size()==1&&powers.getFirst().id().equals(a.id())&&powers.getFirst().authorities().equals(Set.of(OfficeAuthority.REVIEW_MILITARY_REPORTS)),"one original shared office with narrow review power only");
                    h.assertTrue(staff.advice(p,army).size()<=3&&WarMusterGameTests.command(p,"saga war staff advice StaffArmy")==1,"real bounded own-report advice");
                    h.assertTrue(people.stream().allMatch(n->n.getProfession()==NpcProfession.WORKER&&n.getCommand()==NpcCommand.STAY&&n.getSocialClass()==NpcSocialClass.COMMONER&&WorkCommitmentRuntime.blocksCivil(n)),"military appointment preserves original role/class/body/labor");
                    h.assertTrue(WarServerEvents.existing(s).orElseThrow().permission(people.getFirst().getCharacterId(),army)==WarCommandService.Permission.DENIED,"review office is not an unrestricted army-command bypass");
                    var stranger=WarMusterGameTests.battlePlayer(h,"StaffOther",new BlockPos(2,1,9));boolean denied=false;try{staff.revoke(stranger,a.id());}catch(IllegalArgumentException expected){denied=true;}h.assertTrue(denied&&staff.effective(a),"another OP cannot revoke this owner's appointment");
                    near(p,people.get(1));h.assertTrue(WarMusterGameTests.command(p,"saga war staff appoint StaffArmy temporary_deputy "+people.get(1).getCharacterId()+" 1 regular test")==0,"same deputy seat cannot be duplicated");
                });
        if(phase().equals("save")){
            sequence.thenExecute(()->capture(h,p,origin,people,contracts,first[0],"ACTIVE")).thenSucceed();return;
        }
        sequence.thenExecute(()->{var staff=WarStaffRuntime.existing(s).orElseThrow();var previous=staff.book().appointment(first[0]).orElseThrow();
                    if(relayPhase())try{l.setBlockAndUpdate(origin.offset(6,1,8),Blocks.WHITE_BANNER.defaultBlockState());revokedRelay[0]=WarStaffRelayRuntime.open(s).prepare(p,first[0]);}catch(Exception e){throw new IllegalStateException(e);}
                    if(phase().equals("external"))h.assertTrue(InstitutionData.get(s).revokeOffice(first[0]),"original institution revokes office independently");
                    else h.assertTrue(WarMusterGameTests.command(p,"saga war staff revoke "+first[0])==1,"ordinary revoke command");
                    h.assertTrue(!staff.effective(previous),"old cached appointment cannot authorize while revoke is pending");})
                .thenWaitUntil(()->{h.assertTrue(revoked(s,first[0]),"shared original revoke durably confirmed");if(relayPhase())try{h.assertTrue(WarStaffRelayRuntime.open(s).find(revokedRelay[0]).filter(r->r.phase()==com.jgeted.sagadyssey.social.war.domain.WarStaffRelay.Phase.CANCELLED).isPresent(),"revocation cancels pending assistant instruction");}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}})
                .thenExecute(()->{near(p,people.get(phase().equals("support")?0:1));h.assertTrue(WarMusterGameTests.command(p,"saga war staff appoint StaffArmy temporary_deputy "+people.get(phase().equals("support")?0:1).getCharacterId()+" 1 exceptional 补位代理")==1,"replacement uses original second NPC");second[0]=WarStaffRuntime.existing(s).orElseThrow().book().entries().stream().filter(Appointment.class::isInstance).map(Appointment.class::cast).filter(a->!a.id().equals(first[0])).findFirst().orElseThrow().id();})
                .thenWaitUntil(()->h.assertTrue(effective(s,second[0]),"one original replacement effective"))
                .thenExecute(()->{for(var id:contracts)WarMusterGameTests.command(p,"saga war muster retire "+id);h.assertTrue(!effective(s,second[0]),"retirement immediately invalidates office services");})
                .thenWaitUntil(()->h.assertTrue(revoked(s,second[0])&&contracts.stream().allMatch(id->muster.book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ENDED&&muster.book().durable(o)).isPresent()),"retirement revokes shared office and releases original labor"))
                .thenWaitUntil(()->h.assertTrue(p.getInventory().countItem(Items.EMERALD)==14&&people.stream().allMatch(n->n.getPersonalInventory().count(i->i.is(Items.EMERALD))==1),"part-time offices did not create a duplicate full-time salary"))
                .thenWaitUntil(()->{if(Set.of("supplies","support").contains(phase())){var provision=ServiceProvisionRuntime.existing(s).orElseThrow();h.assertTrue(contracts.stream().allMatch(id->provision.durable(id)&&provision.find(id).orElseThrow().pending().isEmpty()&&provision.find(id).orElseThrow().arrears(l.getGameTime()).signum()==0),"physical payment must also be durably acknowledged before confirmed totals");}})
                .thenExecute(()->{var staff=WarStaffRuntime.existing(s).orElseThrow();h.assertTrue(staff.book().establishment(army).orElseThrow().strength()==(phase().equals("support")?1:2),"instant retirement/casualty does not silently change approved establishment");h.assertTrue(staff.book().entries().stream().filter(Appointment.class::isInstance).count()==2,"historical appointment chain retained");if(Set.of("supplies","support").contains(phase())){
                        var report=staff.supplies(p,army);h.assertTrue(report.get(1).contains("应付 2 · 已付 2 · 欠饷 0")&&report.get(2).contains("持续供养 0份")&&report.get(2).contains("实际餐食 2份"),"retired services remain in original cost report, without inventing extra staff pay");
                        var proof=new JsonObject();proof.add("report",new Gson().toJsonTree(report));proof.addProperty("actualMeals",2);proof.addProperty("originalBreadRemainingEach",15);proof.addProperty("readOnlyQueries",10);proof.addProperty("foreignOwnerDenied",true);try{write(s.getWorldPath(LevelResource.ROOT).resolve("war-staff-supplies-work.json"),proof);}catch(Exception e){throw new IllegalStateException(e);}
                    }capture(h,p,origin,people,contracts,second[0],"REVOKED");}).thenSucceed();
    }
    private static void resume(GameTestHelper h){try{
        var s=h.getLevel().getServer();var saved=read(s.getWorldPath(LevelResource.ROOT).resolve("war-staff-save.json"));h.assertTrue(saved.get("process").getAsLong()!=ProcessHandle.current().pid(),"distinct native JVM");var pos=saved.getAsJsonArray("origin");var origin=new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt());force(h,origin);
        var p=WarMusterGameTests.restoreBattlePlayer(h,new GameProfile(UUID.fromString(saved.get("account").getAsString()),saved.get("name").getAsString()),origin.offset(4,1,8));
        var army=UUID.fromString(saved.get("army").getAsString());var appointment=UUID.fromString(saved.get("appointment").getAsString());var contracts=saved.getAsJsonArray("contracts").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();var bodies=saved.getAsJsonArray("bodies").asList().stream().map(v->UUID.fromString(v.getAsString())).toList();var people=new ArrayList<NpcBase>();
        h.startSequence().thenWaitUntil(()->{people.clear();for(var id:bodies){var n=h.getLevel().getEntity(id);h.assertTrue(n instanceof NpcBase,"load same original body");people.add((NpcBase)n);}h.assertTrue(effective(s,appointment),"restored original shared appointment stays effective after original military host binding");})
                .thenExecute(()->{var staff=WarStaffRuntime.existing(s).orElseThrow();h.assertTrue(staff.book().entries().size()==2&&staff.book().establishment(army).orElseThrow().strength()==(phase().equals("support")?1:2),"one establishment and one historical appointment, no recovered grants");h.assertTrue(p.getInventory().countItem(Items.EMERALD)==16,"original cash restored without fixture refill");for(var id:contracts)WarMusterGameTests.command(p,"saga war muster retire "+id);})
                .thenWaitUntil(()->h.assertTrue(revoked(s,appointment)&&contracts.stream().allMatch(id->WarMusterRuntime.existing(s).orElseThrow().book().offer(id).filter(o->o.stage()==WarMusterEntry.Stage.ENDED).isPresent()),"same service and office end after new JVM"))
                .thenWaitUntil(()->h.assertTrue(p.getInventory().countItem(Items.EMERALD)==14&&people.stream().allMatch(n->n.getPersonalInventory().count(i->i.is(Items.EMERALD))==1),"new JVM retains one actual wage per original service"))
                .thenExecute(()->capture(h,p,origin,people,contracts,appointment,"REVOKED")).thenSucceed();
    }catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}}
    private static void blocked(GameTestHelper h){try{
        var s=h.getLevel().getServer();var root=s.getWorldPath(LevelResource.ROOT);var file=root.resolve("data/sagadyssey_war_staff.sgw"+(phase().equals("missing-staff")?".test-retained":""));byte[] evidence=Files.readAllBytes(file);
        if(phase().equals("missing-staff")){boolean blocked=false;try{WarStaffRuntime.open(s);}catch(Exception expected){blocked=true;}h.assertTrue(blocked,"surviving shared office prevents replacement of missing military evidence");}
        h.startSequence().thenIdle(80).thenExecute(()->{h.assertTrue(WarStaffRuntime.existing(s).isEmpty()&&WarStaffRuntime.failure(s).contains("REVIEW"),"missing authority cannot be recreated from the other ledger");try{h.assertTrue(Arrays.equals(evidence,Files.readAllBytes(file)),"military evidence retained without automatic promotion replay");if(phase().equals("missing-staff"))h.assertTrue(!Files.exists(root.resolve("data/sagadyssey_war_staff.sgw")),"no empty replacement staff journal");var proof=new JsonObject();proof.addProperty("stage","REVIEW");proof.addProperty("missingAuthorityBlocks",true);proof.addProperty("originalStaffJournalUnchanged",true);write(root.resolve("war-staff-"+phase()+".json"),proof);}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}}).thenSucceed();
    }catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}}
    private static void force(GameTestHelper h,BlockPos origin){for(int x=origin.getX()>>4;x<=(origin.getX()+20)>>4;x++)for(int z=origin.getZ()>>4;z<=(origin.getZ()+14)>>4;z++)h.getLevel().setChunkForced(x,z,true);}
    private static void near(ServerPlayer p,NpcBase n){p.setPos(n.getX(),n.getY(),n.getZ()+3);}
    private static boolean effective(net.minecraft.server.MinecraftServer s,UUID id){return WarStaffRuntime.existing(s).flatMap(r->r.book().appointment(id).filter(r::effective)).isPresent();}
    private static boolean revoked(net.minecraft.server.MinecraftServer s,UUID id){return WarStaffRuntime.existing(s).flatMap(r->r.book().appointment(id).filter(a->a.phase()==Phase.REVOKED&&r.book().durable(a))).isPresent();}
    private static void capture(GameTestHelper h,ServerPlayer p,BlockPos origin,List<NpcBase> people,List<UUID> contracts,UUID appointment,String stage){
        var s=h.getLevel().getServer();var proof=new JsonObject();proof.addProperty("process",ProcessHandle.current().pid());proof.addProperty("account",p.getUUID().toString());proof.addProperty("name",p.getGameProfile().name());proof.addProperty("actor",CharacterRegistry.currentId(p).toString());proof.addProperty("army",WarMusterEntry.armyId(CharacterRegistry.currentId(p),"StaffArmy").toString());proof.addProperty("appointment",appointment.toString());proof.addProperty("stage",stage);proof.addProperty("headlessNoOpWire",true);proof.add("origin",new Gson().toJsonTree(List.of(origin.getX(),origin.getY(),origin.getZ())));proof.add("bodies",new Gson().toJsonTree(people.stream().map(NpcBase::getUUID).toList()));proof.add("contracts",new Gson().toJsonTree(contracts));proof.addProperty("payerEmeralds",p.getInventory().countItem(Items.EMERALD));proof.add("npcEmeralds",new Gson().toJsonTree(people.stream().map(n->n.getPersonalInventory().count(i->i.is(Items.EMERALD))).toList()));proof.add("staff",new Gson().toJsonTree(WarStaffRuntime.existing(s).orElseThrow().book().entries().stream().filter(Appointment.class::isInstance).map(Appointment.class::cast).map(a->Map.of("id",a.id(),"holder",a.terms().holder(),"service",a.terms().service(),"role",a.terms().role(),"phase",a.phase(),"revision",a.revision(),"reason",a.terms().reason(),"class",a.terms().socialClass(),"profession",a.terms().profession(),"scope",a.terms().unit().map(UUID::toString).orElse("ARMY"))).toList()));
        try{write(s.getWorldPath(LevelResource.ROOT).resolve("war-staff-"+phase()+".json"),proof);}catch(Exception e){if(e instanceof net.minecraft.gametest.framework.GameTestAssertException assertion)throw assertion;throw new IllegalStateException(e);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent e)throws Exception{if(enabled()&&phase().equals("save")){var root=e.getServer().getWorldPath(LevelResource.ROOT);var proof=read(root.resolve("war-staff-save.json"));var data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.create(64L*1024*1024)).getCompoundOrEmpty("Data");proof.addProperty("gameTime",data.getLongOr("Time",0));proof.addProperty("dayTime",data.getLongOr("DayTime",0));write(root.resolve("war-staff-save.json"),proof);}}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void write(Path p,JsonObject proof)throws Exception{Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(proof));}
}
