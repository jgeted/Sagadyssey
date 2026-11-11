package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class LifeGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void restoreCheckpoint(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        previous=null;
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart"))return;
        var server=event.getServer();
        try {
            previous=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("phase5-restart-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            server.getWorldData().overworldData().setGameTime(Math.max(server.overworld().getGameTime(),previous.getLongOr("Time", 0L)+1));
            var pos=BlockPos.of(previous.getLongOr("Position", 0L));server.overworld().setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);server.overworld().getChunkAt(pos);
        }catch(Exception e){throw new IllegalStateException("phase5 previous-process checkpoint required",e);}
    }
    @BeforeBatch(batch="phase5") public static void day(ServerLevel level) { level.setDayTime(1000); }
    @BeforeBatch(batch="phase5_sleep") public static void night(ServerLevel level) { level.setDayTime(13000); }

    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=500)
    public static void eatsActualBagFoodOnceAndProducesCheckpoint(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(2,1,5),null,null);npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,2));
        var life=NpcLifeData.get(h.getLevel().getServer());life.setHunger(npc.getCharacterId(),700,h.getLevel().getGameTime());
        h.runAtTickTime(200,()->{
            h.assertTrue(npc.getPersonalInventory().getItem(0).getCount()==1,"one physical food consumed, no repeat");
            h.assertTrue(life.needs(npc.getCharacterId()).hunger()<350,"real food reduces hunger");
            var plan=NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().filter(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.kind()==NpcPlanKind.NEED&&p.status()==NpcPlanStatus.SUCCEEDED).findFirst().orElseThrow();
            h.assertTrue("DONE".equals(LifeActivityData.get(MinecraftApi.server(npc)).settlement(plan.id()+":2")),"one persistent food receipt");
            npc.setCommand(NpcCommand.STAY);NpcBrainRuntime.cancel(npc,PlanCancellationReason.MANUAL,"checkpoint parking after actual meal");
            var parked=h.absolutePos(new BlockPos(4096,1,4096));
            h.getLevel().setChunkForced(parked.getX()>>4,parked.getZ()>>4,true);h.getLevel().getChunkAt(parked);
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)h.getLevel().setBlock(parked.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
            npc.teleportTo(parked.getX()+.5,parked.getY(),parked.getZ()+.5);npc.getNavigation().stop();
            var marker=new CompoundTag();marker.store("Npc", UUIDUtil.CODEC,npc.getUUID());marker.store("Character", UUIDUtil.CODEC,npc.getCharacterId());marker.putString("Receipt",plan.id()+":2");marker.putLong("Position",parked.asLong());marker.putLong("Time",h.getLevel().getGameTime());
            h.runAtTickTime(220,()->{
                marker.putLong("Time",h.getLevel().getGameTime());
                var needs=life.needs(npc.getCharacterId());marker.putInt("Hunger",needs.hunger());marker.putLong("LastAte",needs.lastAteAt());
                try{NbtIo.writeCompressed(marker,MinecraftApi.server(npc).getWorldPath(LevelResource.ROOT).resolve("phase5-restart-checkpoint.nbt"));MinecraftApi.server(npc).saveEverything(true,true,true);}
                catch(Exception e){throw new IllegalStateException(e);}h.succeed();
            });
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase5_restart",timeoutTicks=300)
    public static void wholeProcessRestartRetainsActualMealAndReceipt(GameTestHelper h) {
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}
        var pos=BlockPos.of(previous.getLongOr("Position", 0L));h.getLevel().setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);h.getLevel().getChunkAt(pos);
        h.succeedWhen(()->{
            h.assertTrue(previous!=null,"previous process mandatory");var saved=LifeTaskExecutor.peer(h.getLevel(),previous.read("Npc", UUIDUtil.CODEC).orElseThrow());
            h.assertTrue(saved!=null,"same entity restored from chunk save");
            h.assertTrue(saved.getPersonalInventory().getItem(0).is(Items.BREAD)&&saved.getPersonalInventory().getItem(0).getCount()==1,"no second meal or cloned food across restart");
            var needs=NpcLifeData.get(MinecraftApi.server(saved)).needs(saved.getCharacterId());
            long expectedGain=(h.getLevel().getGameTime()-previous.getLongOr("Time", 0L))/200*2;
            h.assertTrue(previous.contains("Hunger")&&needs.hunger()>=previous.getIntOr("Hunger", 0)
                    && needs.hunger()<=previous.getIntOr("Hunger", 0)+expectedGain+4&&needs.lastAteAt()==previous.getLongOr("LastAte", 0L),
                    "same meal retained; subsequent loaded/L1 resting hunger advances without a phantom second meal: "+needs);
            h.assertTrue("DONE".equals(LifeActivityData.get(MinecraftApi.server(saved)).settlement(previous.getStringOr("Receipt", ""))),"meal receipt retained across process restart");
            var rights=RightsStateData.get(MinecraftApi.server(saved));var bag=ContainerIds.npcPersonal(saved.getCharacterId());
            h.assertTrue(rights.context(bag)!=null&&rights.context(bag).revision()==1
                    && new RightsService(rights).can(SubjectRef.character(saved.getCharacterId()),AssetRight.CONSUME,bag,0,ItemFingerprint.of(saved.getPersonalInventory().getItem(0)),h.getLevel().getGameTime()).status()==RightsVerdict.Status.ALLOW,"meal ownership and single consumption revision survive restart");
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=600)
    public static void walksToPrivateFoodAndConsumesWithRights(GameTestHelper h) {
        floor(h);h.setBlock(15,1,5,Blocks.BARREL);var npc=npc(h,new BlockPos(2,1,5),null,new BlockPos(15,1,5));
        var barrel=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(15,1,5));barrel.setItem(0,new ItemStack(Items.BREAD,2));
        NpcLifeData.get(MinecraftApi.server(npc)).setHunger(npc.getCharacterId(),700,h.getLevel().getGameTime());
        h.succeedWhen(()->{h.assertTrue(barrel.getItem(0).getCount()==1,"one real private ration consumed");h.assertTrue(npc.getX()>h.absolutePos(new BlockPos(10,1,5)).getX(),"walked to storage instead of remote consumption");});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=400)
    public static void foreignOwnedFoodCannotBeEaten(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(2,1,5),null,null);npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,2));
        var owner=SubjectRef.character(npc.getCharacterId());var rights=RightsStateData.get(MinecraftApi.server(npc));var id=ContainerIds.npcPersonal(npc.getCharacterId());
        rights.ensureContext(id,"test:foreignfood",owner,owner,AccessPolicy.PRIVATE);
        rights.putClaim(new AssetClaim(UUID.randomUUID(),id,0,ItemFingerprint.of(npc.getPersonalInventory().getItem(0)),2,SubjectRef.character(UUID.randomUUID()),owner,Set.of(),Set.of(),"borrowed, no consumption grant",0));
        NpcLifeData.get(MinecraftApi.server(npc)).setHunger(npc.getCharacterId(),700,h.getLevel().getGameTime());
        h.runAtTickTime(250,()->{h.assertTrue(npc.getPersonalInventory().getItem(0).getCount()==2,"borrowed food untouched");h.assertTrue(NpcLifeData.get(MinecraftApi.server(npc)).needs(npc.getCharacterId()).hunger()>=700,"no fake feeding");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=500)
    public static void familyCareUsesOneRealRationAtRecipient(GameTestHelper h) {
        floor(h);var carer=npc(h,new BlockPos(2,1,5),null,null);var recipient=npc(h,new BlockPos(5,1,5),null,null);
        var home=HouseholdData.get(MinecraftApi.server(carer)).create("test household",h.getLevel().dimension().identifier().toString(),null,carer.getCharacterId(),carer.blockPosition(),h.getLevel().getGameTime());
        HouseholdData.get(MinecraftApi.server(carer)).addMember(home.id(),recipient.getCharacterId(),HouseholdRole.KIN,h.getLevel().getGameTime());
        carer.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,2));var life=NpcLifeData.get(MinecraftApi.server(carer));life.setHunger(recipient.getCharacterId(),700,h.getLevel().getGameTime());
        h.succeedWhen(()->{h.assertTrue(carer.getPersonalInventory().getItem(0).getCount()==1,"carer provides one owned ration");h.assertTrue(life.needs(recipient.getCharacterId()).hunger()<350,"actual recipient fed");h.assertTrue(life.record(recipient.getCharacterId()).memories().stream().anyMatch(m->m.type().equals("family_care")),"care remembered");});
    }
    @GameTest(template="fieldwork_empty",batch="phase5_sleep",timeoutTicks=700)
    public static void privateBedSleepWakesAndReleasesReservation(GameTestHelper h) {
        floor(h);bed(h,8,5);var npc=npc(h,new BlockPos(2,1,5),new BlockPos(9,1,5),null);
        boolean[] slept={false};h.onEachTick(()->{if(npc.isSleeping())slept[0]=true;});
        h.runAtTickTime(450,()->{h.assertTrue(slept[0],"real bed sleep entered: "+diagnostic(npc));h.getLevel().setDayTime(1000);});
        h.runAtTickTime(550,()->{h.assertTrue(!npc.isSleeping(),"wakes when sleep window ends");h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.status()==NpcPlanStatus.SUCCEEDED),"sleep plan terminates");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=800)
    public static void knownFriendsConverseTogetherAndSettleOnce(GameTestHelper h) {
        floor(h);var a=npc(h,new BlockPos(3,1,5),null,null);var b=npc(h,new BlockPos(6,1,5),null,null);
        var life=NpcLifeData.get(MinecraftApi.server(a));life.linkRelationship(a.getCharacterId(),b.getCharacterId(),NpcRelationKind.FRIEND,10,"known friend");
        h.succeedWhen(()->{
            var sessions=LifeActivityData.get(MinecraftApi.server(a)).sessions().stream().filter(s->s.first().equals(a.getUUID())||s.second().equals(a.getUUID())).toList();
            h.assertTrue(sessions.stream().anyMatch(s->s.status()==ConversationSession.Status.SUCCEEDED),"real synchronized conversation completed: "+sessions+"; first "+diagnostic(a)+"; second "+diagnostic(b));
            h.assertTrue(life.record(a.getCharacterId()).memories().stream().anyMatch(m->m.type().equals("conversation")),"first remembers conversation");
            h.assertTrue(life.record(b.getCharacterId()).memories().stream().anyMatch(m->m.type().equals("conversation")),"second remembers same encounter");
            h.assertTrue(life.record(a.getCharacterId()).relations().getFirst().strength()==12,"one relationship outcome only");
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=500)
    public static void commandCancellationEndsBothConversationAndLeases(GameTestHelper h) {
        floor(h);var a=npc(h,new BlockPos(3,1,5),null,null);var b=npc(h,new BlockPos(6,1,5),null,null);pair(h,a,b,200);
        h.runAtTickTime(70,()->a.setCommand(NpcCommand.STAY));
        h.runAtTickTime(150,()->{h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(a)).active(a.getUUID()).isEmpty(),"explicit stay cancels life plan");
            h.assertTrue(LifeActivityData.get(MinecraftApi.server(a)).sessions().stream().anyMatch(s->s.first().equals(a.getUUID())&&s.status()==ConversationSession.Status.CANCELLED),"shared encounter cancelled");
            h.assertTrue(NpcBrainRuntime.reservations(MinecraftApi.server(a)).snapshot().stream().noneMatch(r->r.npcId().equals(a.getUUID())),"cancelled participant releases places");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=700)
    public static void readRequiresKnownRealLecternAndBook(GameTestHelper h) {
        floor(h);h.setBlock(8,1,5,Blocks.LECTERN);((LecternBlockEntity)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(8,1,5))).setBook(new ItemStack(Items.WRITABLE_BOOK));
        h.setBlock(8,1,5,h.getBlockState(new BlockPos(8,1,5)).setValue(LecternBlock.HAS_BOOK,true));
        var npc=npc(h,new BlockPos(2,1,5),null,null);LifeActivityData.get(MinecraftApi.server(npc)).reveal(npc.getUUID(),new LifeActivityData.Place(h.getLevel().dimension().identifier().toString(),h.absolutePos(new BlockPos(8,1,5)),"sagadyssey:read"));
        h.succeedWhen(()->h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().get("operation").equals("READ")&&p.status()==NpcPlanStatus.SUCCEEDED),"finite reading completed at actual known book"));
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=400)
    public static void unknownFacilitiesDoNotBecomeGlobalKnowledge(GameTestHelper h) {
        floor(h);h.setBlock(8,1,5,Blocks.CAMPFIRE);var a=npc(h,new BlockPos(2,1,5),null,null);var b=npc(h,new BlockPos(4,1,5),null,null);
        LifeActivityData.get(MinecraftApi.server(a)).reveal(a.getUUID(),new LifeActivityData.Place(h.getLevel().dimension().identifier().toString(),h.absolutePos(new BlockPos(8,1,5)),"sagadyssey:tavern"));
        h.runAtTickTime(150,()->{h.assertTrue(LifeActivityData.get(MinecraftApi.server(a)).knownPlaces(b.getUUID()).isEmpty(),"knowledge not shared through global truth");h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(b)).history(b.getUUID()).stream().noneMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("TAVERN")),"unknown facility not chosen");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=600)
    public static void runningWaitSurvivesActualDiskReloadWithFiniteEnd(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(3,1,5),null,null);var plan=LifePlanFactory.create(npc.getUUID(),"WAIT",h.getLevel().dimension().identifier().toString(),npc.blockPosition(),100,h.getLevel().getGameTime(),Map.of());NpcBrainRuntime.installLifePlan(npc,plan);
        NpcBase[] current={npc};h.runAtTickTime(40,()->current[0]=reload(h,current[0]));
        h.succeedWhen(()->h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(current[0])).history(current[0].getUUID()).stream().anyMatch(p->p.id().equals(plan.id())&&p.status()==NpcPlanStatus.SUCCEEDED),"same persisted plan recovers and ends finitely"));
    }
    private static void pair(GameTestHelper h,NpcBase a,NpcBase b,int duration) {
        long now=h.getLevel().getGameTime();UUID id=UUID.randomUUID();String dimension=h.getLevel().dimension().identifier().toString();var place=a.blockPosition();
        LifeActivityData.get(MinecraftApi.server(a)).invite(ConversationSession.invite(id,a.getUUID(),b.getUUID(),dimension,place,duration,now,now+1200));
        for(var npc:List.of(a,b))NpcBrainRuntime.installLifePlan(npc,LifePlanFactory.create(npc.getUUID(),"TALK",dimension,place,duration,now,Map.of("session",id.toString(),"capacity","2","peer",(npc==a?b:a).getUUID().toString())));
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=1100)
    public static void miningSettlesRealProductThenHandsOffToHome(GameTestHelper h) {
        floor(h);for(int x=20;x<40;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);
        bed(h,1,5);h.setBlock(15,1,5,Blocks.BARREL);h.setBlock(30,1,5,Blocks.STONE);
        var npc=npc(h,new BlockPos(25,1,5),new BlockPos(2,1,5),new BlockPos(15,1,5));npc.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));
        var barrel=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(15,1,5));
        h.succeedWhen(()->{
            int product=0;for(int i=0;i<barrel.getContainerSize();i++)if(barrel.getItem(i).is(Items.COBBLESTONE))product+=barrel.getItem(i).getCount();
            h.assertTrue(product==1,"actual mining product settled to legal private container: "+diagnostic(npc)+"; carry="+com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(MinecraftApi.server(npc)).pendingCargo(npc.getUUID())+"; target="+h.getBlockState(new BlockPos(30,1,5))+"; wear="+npc.getMainHandItem().getDamageValue());
            h.assertTrue(npc.getMainHandItem().getDamageValue()==1,"one real work action before life handoff");
            h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("HOME")&&p.status()==NpcPlanStatus.SUCCEEDED),"completed profession task hands off to finite real home plan");
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=500)
    public static void oneSidedAcquaintanceCannotForceSocialConsent(GameTestHelper h) {
        floor(h);var a=npc(h,new BlockPos(3,1,5),null,null);var b=npc(h,new BlockPos(6,1,5),null,null);
        NpcLifeData.get(MinecraftApi.server(a)).adjustRelationship(a.getCharacterId(),b.getCharacterId(),NpcRelationKind.FRIEND,10,"one-sided recognition only");
        h.runAtTickTime(300,()->{h.assertTrue(LifeActivityData.get(MinecraftApi.server(a)).sessions().stream().noneMatch(s->s.first().equals(a.getUUID())||s.second().equals(a.getUUID())),"one-sided knowledge does not imply other person's consent");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5_reload",timeoutTicks=700)
    public static void conversationDiskReloadReconfirmsBothAndRewardsOnce(GameTestHelper h) {
        floor(h);h.getLevel().setDayTime(1000);var first=npc(h,new BlockPos(3,1,5),null,null);var second=npc(h,new BlockPos(5,1,5),null,null);pair(h,first,second,200);
        NpcBase[] current={first};h.runAtTickTime(80,()->current[0]=reload(h,current[0]));
        h.succeedWhen(()->{
            var sessions=LifeActivityData.get(MinecraftApi.server(second)).sessions().stream().filter(s->s.first().equals(current[0].getUUID())).toList();
            h.assertTrue(sessions.stream().anyMatch(s->s.status()==ConversationSession.Status.SUCCEEDED&&"DONE".equals(LifeActivityData.get(MinecraftApi.server(second)).settlement("social:"+s.id()))),"same encounter survives disk reload and settles once");
            var life=NpcLifeData.get(MinecraftApi.server(second));h.assertTrue(life.record(current[0].getCharacterId()).relations().getFirst().strength()==2&&life.record(second.getCharacterId()).relations().getFirst().strength()==2,"one symmetric relationship effect after reload");
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase5_reload",timeoutTicks=500)
    public static void completedMealDiskReloadDoesNotConsumeAgain(GameTestHelper h) {
        floor(h);var first=npc(h,new BlockPos(3,1,5),null,null);first.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,2));NpcLifeData.get(MinecraftApi.server(first)).setHunger(first.getCharacterId(),700,h.getLevel().getGameTime());
        NpcBase[] current={first};h.runAtTickTime(80,()->current[0]=reload(h,current[0]));
        h.runAtTickTime(250,()->{h.assertTrue(current[0].getPersonalInventory().getItem(0).getCount()==1,"completed meal not replayed after actual write/read");h.assertTrue(NpcLifeData.get(MinecraftApi.server(first)).needs(first.getCharacterId()).hunger()<350,"meal needs unchanged by reload");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=500)
    public static void missingReadingFixtureFallsBackToFiniteWait(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(3,1,5),null,null);var data=LifeActivityData.get(MinecraftApi.server(npc));
        data.reveal(npc.getUUID(),new LifeActivityData.Place(h.getLevel().dimension().identifier().toString(),h.absolutePos(new BlockPos(8,1,5)),"sagadyssey:read"));
        h.succeedWhen(()->h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("WAIT")&&p.status()==NpcPlanStatus.SUCCEEDED),"broken known fixture does not cause endless pseudo-reading"));
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=700)
    public static void noFoodThenResupplyRecoversWithoutFakeMeal(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(3,1,5),null,null);var life=NpcLifeData.get(MinecraftApi.server(npc));life.setHunger(npc.getCharacterId(),700,h.getLevel().getGameTime());
        h.runAtTickTime(100,()->{h.assertTrue(life.needs(npc.getCharacterId()).hunger()>=700,"no free hunger reset");npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,2));});
        h.succeedWhen(()->{h.assertTrue(npc.getPersonalInventory().getItem(0).is(Items.BREAD)&&npc.getPersonalInventory().getItem(0).getCount()==1,"one supplied ration consumed after bounded wait");h.assertTrue(life.needs(npc.getCharacterId()).hunger()<350,"resupply unblocks real meal");});
    }
    @GameTest(template="fieldwork_empty",batch="phase5_sleep",timeoutTicks=350)
    public static void twoResidentsCannotSleepInSameBed(GameTestHelper h) {
        floor(h);bed(h,8,5);var a=npc(h,new BlockPos(3,1,5),new BlockPos(9,1,5),null);var b=npc(h,new BlockPos(5,1,5),new BlockPos(9,1,5),null);
        h.runAtTickTime(250,()->{h.assertTrue(a.isSleeping()!=b.isSleeping(),"exactly one resident occupies shared assigned bed");
            var place=h.getLevel().dimension().identifier()+":"+h.absolutePos(new BlockPos(9,1,5)).asLong();
            h.assertTrue(NpcBrainRuntime.reservations(MinecraftApi.server(a)).snapshot().stream().filter(r->r.key().id().startsWith(place+":slot:")).count()==1,"one bed reservation, no overbooking");h.succeed();});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=700)
    public static void visitsKnownHostAtRealResidence(GameTestHelper h) {
        floor(h);bed(h,8,5);var a=npc(h,new BlockPos(3,1,5),null,null);var b=npc(h,new BlockPos(8,1,5),new BlockPos(9,1,5),null);
        NpcLifeData.get(MinecraftApi.server(a)).linkRelationship(a.getCharacterId(),b.getCharacterId(),NpcRelationKind.FRIEND,10,"known host");
        h.succeedWhen(()->{h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(a)).history(a.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("VISIT")&&p.status()==NpcPlanStatus.SUCCEEDED),"mutual visit completed at host home");});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=700)
    public static void returnsHomeWhenWorkAreaIsEmpty(GameTestHelper h) {
        floor(h);bed(h,15,5);var npc=npc(h,new BlockPos(2,1,5),new BlockPos(16,1,5),null);
        h.succeedWhen(()->h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("HOME")&&p.status()==NpcPlanStatus.SUCCEEDED),"empty work area hands off to real return-home plan"));
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=900)
    public static void registeredTavernActivityEndsAtExistingFixture(GameTestHelper h) {
        floor(h);h.getLevel().setDayTime(10000);h.setBlock(8,1,5,Blocks.CAMPFIRE);var npc=npc(h,new BlockPos(2,1,5),null,null);
        LifeActivityData.get(MinecraftApi.server(npc)).reveal(npc.getUUID(),new LifeActivityData.Place(h.getLevel().dimension().identifier().toString(),h.absolutePos(new BlockPos(8,1,5)),"sagadyssey:tavern"));
        h.succeedWhen(()->{h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.providerId().equals(LifePlanFactory.PROVIDER)&&p.currentStep().params().getOrDefault("operation","").equals("TAVERN")&&p.status()==NpcPlanStatus.SUCCEEDED),"finite activity at explicitly known tavern hearth, no invented food");h.assertTrue(npc.getPersonalInventory().isEmpty(),"leisure does not generate goods");});
    }
    @GameTest(template="fieldwork_empty",batch="phase5",timeoutTicks=200)
    public static void ordinaryProjectionDoesNotExposeLifeTruth(GameTestHelper h) {
        floor(h);var npc=npc(h,new BlockPos(3,1,5),null,null);String secret="LIFE_TESTER_SECRET";
        LifeActivityData.get(MinecraftApi.server(npc)).pending(secret);
        var viewer=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"phase5viewer"),net.minecraft.server.level.ClientInformation.createDefault());
        viewer.setPos(npc.getX()+1,npc.getY(),npc.getZ());
        String view=com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService.forViewer(viewer,npc).toString();
        h.assertTrue(!view.contains(secret)&&!view.contains("Settlements")&&!view.contains("visit_place")&&!view.contains("sagadyssey_life_activities_v3"),"ordinary interface excludes life ledger, reservation and checkpoint truth");h.succeed();
    }
    private static NpcBase npc(GameTestHelper h,BlockPos start,BlockPos bed,BlockPos storage) {
        var npc=h.spawn(NpcEntityTypes.NPC_BASE.get(),start);npc.setProfession(NpcProfession.WORKER);npc.getPersonalInventory().clearContent();npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);npc.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        npc.setWorkZone(h.absolutePos(new BlockPos(30,1,5)),h.absolutePos(new BlockPos(30,1,5)));npc.setCommand(NpcCommand.WORK);npc.setPersistenceRequired();
        NpcLifeData.get(MinecraftApi.server(npc)).registerInstitutional(npc,h.getLevel().dimension().identifier().toString(),UUID.randomUUID(),null,"test residence",bed==null?null:h.absolutePos(bed),storage==null?null:h.absolutePos(storage),NpcWorkShift.DAY,h.getLevel().getGameTime(),false);
        NpcBrainRuntime.setMode(npc,NpcBehaviorMode.PLANNED);return npc;
    }
    private static void bed(GameTestHelper h,int x,int z) {
        var base=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST);
        h.setBlock(x,1,z,base.setValue(BedBlock.PART,BedPart.FOOT));h.setBlock(x+1,1,z,base.setValue(BedBlock.PART,BedPart.HEAD));
    }
    private static void floor(GameTestHelper h){for(int x=0;x<20;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);}
    private static String diagnostic(NpcBase npc) {
        var brains=NpcBrainRuntime.data(MinecraftApi.server(npc));
        return "pos="+npc.blockPosition()+" active="+brains.active(npc.getUUID()).map(p->p.kind()+"/"+p.currentStep().executorId()+"/"+p.currentStep().detail()).orElse("none")
                +" history="+brains.history(npc.getUUID()).stream().map(p->p.status()+":"+p.explanation()).toList();
    }
    private static NpcBase reload(GameTestHelper h,NpcBase npc) {
        var server=MinecraftApi.server(npc);var storage=server.overworld().getDataStorage();
        try{
            var file=server.getWorldPath(LevelResource.ROOT).resolve("phase5-entity-"+npc.getUUID()+".nbt");NbtIo.writeCompressed(MinecraftApi.saveEntity(npc),file);
            storage.saveAndJoin();int version=SharedConstants.getCurrentVersion().dataVersion().version();
            storage.set(NpcBrainData.FACTORY.type(NpcBrainData.DATA_NAME),NpcBrainData.FACTORY.deserializer().apply(storage.readTagFromDisk(NpcBrainData.DATA_NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));
            storage.set(LifeActivityData.FACTORY.type(LifeActivityData.NAME),LifeActivityData.load(storage.readTagFromDisk(LifeActivityData.NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));
            npc.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);var replacement=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());MinecraftApi.loadEntity(replacement, NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()));h.getLevel().addFreshEntity(replacement);NpcBrainRuntime.rebuildAfterDataReload(server);return replacement;
        }catch(Exception e){throw new IllegalStateException("real disk reload failed",e);}
    }
}
