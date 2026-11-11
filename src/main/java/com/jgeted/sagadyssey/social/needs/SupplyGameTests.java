package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class SupplyGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void checkpoint(net.neoforged.neoforge.event.server.ServerStartedEvent event){
        previous=null;
        if(!(event.getServer() instanceof GameTestServer))return;
        var testServer=event.getServer();
        // GameTest starts its clock at zero even with saved world ledgers. Preserve their
        // monotonic timestamps on both ordinary re-runs and mandatory restart runs.
        long savedTime=NeedRequestData.get(testServer).requests().stream().mapToLong(NeedRequest::updatedAt).max().orElse(0);
        testServer.getWorldData().overworldData().setGameTime(Math.max(testServer.overworld().getGameTime(),savedTime+1));
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart"))return;
        try{var server=event.getServer();previous=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("phase6-restart-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            server.getWorldData().overworldData().setGameTime(Math.max(server.overworld().getGameTime(),previous.getLongOr("Time", 0L)+1));
        }catch(Exception e){throw new IllegalStateException("phase6 previous-process checkpoint required",e);}
    }
    @BeforeBatch(batch="phase6") public static void day(ServerLevel level){level.setDayTime(1000);
        var flows=SupplyFlowData.get(level.getServer());var needs=NeedRequestData.get(level.getServer());long now=level.getGameTime();
        // Retire only previous phase-six fixtures in the isolated test world, never player data.
        for(var p:flows.policies())if(p.id().startsWith("phase6-fixture:"))flows.removePolicy(p.id());
        for(var route:flows.routes())if(!route.closed()&&needs.request(route.need()).key().purpose().contains(":phase6-fixture:")&&route.stage()==SupplyFlowData.Stage.HELD&&route.pending()==null)SupplyFlowService.release(level.getServer(),route,now,"previous test fixture retired");
        for(var r:needs.requests())if(r.key().purpose().contains(":phase6-fixture:")&&!r.status().terminal()&&r.status()!=NeedRequestStatus.DELIVERED&&r.reserved()==0&&r.inTransit()==0)needs.transition(r.id(),NeedRequestStatus.CANCELLED,now,"previous fixture retired");
    }
    @BeforeBatch(batch="phase6_reload") public static void reloadDay(ServerLevel level){day(level);}
    @BeforeBatch(batch="phase6_restart") public static void restartDay(ServerLevel level){level.setDayTime(1000);}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1000)
    public static void courierTakesCarriesAndDeliversRealStock(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,npc,3,14,3);
        h.succeedWhen(()->{var r=NeedRequestData.get(MinecraftApi.server(npc)).request(f.need());h.assertTrue(r.status()==NeedRequestStatus.DELIVERED,"need delivered: "+diagnostic(npc));h.assertTrue(SupplyFlowService.count(f.source(),new ItemFingerprint("minecraft:coal",0))==0&&SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==3,"real source-to-target conservation");h.assertTrue(npc.getX()>h.absolutePos(new BlockPos(8,1,5)).getX(),"actual travel, no remote fulfillment");h.assertTrue(RightsStateData.get(MinecraftApi.server(npc)).reservations().stream().noneMatch(x->x.token().equals(SupplyFlowData.get(MinecraftApi.server(npc)).routes().stream().filter(x2->x2.need().equals(f.need())).findFirst().orElseThrow().token())),"terminal route releases leases");});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1600)
    public static void fullOutputKeepsCargoAndRecoversAfterSpaceFreed(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,npc,3,14,3);fill(f.target());
        h.runAtTickTime(250,()->{h.assertTrue(SupplyFlowService.count(npc.getTransportInventory(),new ItemFingerprint("minecraft:coal",0))==3,"full output retains physical cargo");f.target().setItem(0,ItemStack.EMPTY);});
        h.succeedWhen(()->h.assertTrue(NeedRequestData.get(MinecraftApi.server(npc)).request(f.need()).status()==NeedRequestStatus.DELIVERED&&SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==3,"space recovery completes same shipment"));}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1400)
    public static void externalShortageWaitsOnceThenRecoversFromActualSupply(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,npc,3,14,0);
        h.runAtTickTime(250,()->{h.assertTrue(SupplyFlowData.get(MinecraftApi.server(npc)).routes().stream().noneMatch(r->r.need().equals(f.need())),"shortage creates no fake shipment");h.assertTrue(NeedRequestData.get(MinecraftApi.server(npc)).request(f.need()).status()==NeedRequestStatus.WAITING_EXTERNAL,"explicit external wait");f.source().setItem(0,new ItemStack(Items.COAL,3));});
        h.succeedWhen(()->h.assertTrue(SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==3,"real resupply unblocks request"));}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=100)
    public static void twoRequestsCannotPromiseSameBatch(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var f=fixture(h,npc,3,14,3);var principal=SubjectRef.character(npc.getCharacterId());var point=point(h,new BlockPos(17,1,5),principal);
        var second=request(h,npc,point,3,"competition");SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());
        h.runAtTickTime(20,()->{int total=NeedRequestData.get(MinecraftApi.server(npc)).request(f.need()).reserved()+NeedRequestData.get(MinecraftApi.server(npc)).request(second.id()).reserved();h.assertTrue(total==3,"exact batch promised once across both demands");h.assertTrue(SupplyFlowService.count(f.source(),new ItemFingerprint("minecraft:coal",0))==3,"reservation does not move goods");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=100)
    public static void knownForeignStorageDoesNotGrantRights(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var owner=SubjectRef.character(UUID.randomUUID());var foreign=point(h,new BlockPos(4,1,5),owner);((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5))).setItem(0,new ItemStack(Items.COAL,3));var target=point(h,new BlockPos(14,1,5),SubjectRef.character(npc.getCharacterId()));var flows=SupplyFlowData.get(MinecraftApi.server(npc));flows.reveal(SubjectRef.character(npc.getCharacterId()),foreign.containerId());var r=request(h,npc,target,3,"foreign");SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());h.runAtTickTime(20,()->{h.assertTrue(NeedRequestData.get(MinecraftApi.server(npc)).request(r.id()).reserved()==0,"knowing location is not permission");h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(MinecraftApi.server(npc),foreign),r.key().item())==3,"foreign stock untouched");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=120)
    public static void cancellationReleasesHeldStockAndManagedOrder(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var f=fixture(h,npc,3,14,3);var route=SupplyFlowData.get(MinecraftApi.server(npc)).routes().stream().filter(r->r.need().equals(f.need())).findFirst().orElseThrow();SupplyFlowService.release(MinecraftApi.server(npc),route,h.getLevel().getGameTime(),"test cancellation");NeedRequestData.get(MinecraftApi.server(npc)).transition(f.need(),NeedRequestStatus.CANCELLED,h.getLevel().getGameTime(),"cancel");h.runAtTickTime(20,()->{h.assertTrue(RightsStateData.get(MinecraftApi.server(npc)).reservationsForToken(route.token(),h.getLevel().getGameTime()).isEmpty(),"held reservation released");h.assertTrue(com.jgeted.sagadyssey.social.logistics.LogisticsData.get(MinecraftApi.server(npc)).order(route.id()).status()==com.jgeted.sagadyssey.social.logistics.LogisticsOrderStatus.CANCELLED,"transport order cancelled");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=100)
    public static void missingTargetIsUnreachableNotDelivered(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var f=fixture(h,npc,3,14,3);h.setBlock(14,1,5,Blocks.AIR);SupplyFlowService.release(MinecraftApi.server(npc),SupplyFlowData.get(MinecraftApi.server(npc)).routes().stream().filter(r->r.need().equals(f.need())).findFirst().orElseThrow(),h.getLevel().getGameTime(),"target removed");SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());h.runAtTickTime(20,()->{h.assertTrue(NeedRequestData.get(MinecraftApi.server(npc)).request(f.need()).status()==NeedRequestStatus.UNREACHABLE,"invalid target is explained");h.assertTrue(SupplyFlowService.count(f.source(),new ItemFingerprint("minecraft:coal",0))==3,"no fake take or delivery");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1800)
    public static void shortageToTransportToForgingCompletesActualProduction(GameTestHelper h){floor(h);var smith=npc(h,new BlockPos(12,1,5),NpcProfession.BLACKSMITH);var courier=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(courier)).onRecruited(courier,smith.getCharacterId(),h.getLevel().getGameTime());
        var principal=SubjectRef.character(smith.getCharacterId());var source=point(h,new BlockPos(4,1,5),principal);var target=point(h,new BlockPos(14,1,5),principal);h.setBlock(12,1,6,Blocks.ANVIL);
        ((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5))).setItem(0,new ItemStack(Items.IRON_INGOT,3));((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5))).setItem(1,new ItemStack(Items.COAL,1));SupplyFlowData.get(MinecraftApi.server(smith)).reveal(principal,source.containerId());
        var r=SupplyFlowService.request(MinecraftApi.server(smith),principal,"phase6-fixture:production",target,new ItemFingerprint("minecraft:iron_pickaxe",0),1,h.getLevel().getGameTime(),h.getLevel().getGameTime()+72000);SupplyFlowService.match(MinecraftApi.server(smith),h.getLevel().getGameTime());
        h.succeedWhen(()->{h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(MinecraftApi.server(smith),target),new ItemFingerprint("minecraft:iron_pickaxe",0))==1,"actual iron pickaxe in target: "+diagnostic(smith)+" "+diagnostic(courier));h.assertTrue(NeedRequestData.get(MinecraftApi.server(smith)).request(r.id()).status()==NeedRequestStatus.DELIVERED,"production receipt fulfills original demand");h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(MinecraftApi.server(smith),source),new ItemFingerprint("minecraft:iron_ingot",0))==0&&SupplyFlowService.count(SupplyFlowService.container(MinecraftApi.server(smith),source),new ItemFingerprint("minecraft:coal",0))==0,"real iron and coal consumed, no cloned goods");});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1200)
    public static void merchantBuysCarriesSellsAndEarnsActualEmeralds(GameTestHelper h){floor(h);var trader=npc(h,new BlockPos(3,1,5),NpcProfession.TRADER);var seller=point(h,new BlockPos(4,1,5),SubjectRef.character(UUID.randomUUID()));var buyer=point(h,new BlockPos(14,1,5),SubjectRef.character(UUID.randomUUID()));var sellBox=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5));var buyBox=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(14,1,5));sellBox.setItem(0,new ItemStack(Items.COAL,3));buyBox.setItem(0,new ItemStack(Items.EMERALD,9));trader.getPersonalInventory().setItem(0,new ItemStack(Items.EMERALD,3));
        var data=MerchantSupplyData.get(MinecraftApi.server(trader));long now=h.getLevel().getGameTime();var low=new MerchantSupplyData.Offer(UUID.randomUUID(),seller.containerId(),UUID.randomUUID(),new ItemFingerprint("minecraft:coal",0),true,1,3,now+24000);var high=new MerchantSupplyData.Offer(UUID.randomUUID(),buyer.containerId(),UUID.randomUUID(),low.item(),false,3,3,now+24000);data.offer(MinecraftApi.server(trader),seller.owner(),low);data.offer(MinecraftApi.server(trader),buyer.owner(),high);data.observe(trader,low.id(),now);data.reveal(trader.getCharacterId(),new MerchantSupplyData.Observation(high.id(),3,now));
        h.succeedWhen(()->{h.assertTrue(data.realizedProfit(trader.getCharacterId())==6,"profit only after physical resale: "+diagnostic(trader));h.assertTrue(SupplyFlowService.count(trader.getPersonalInventory(),new ItemFingerprint("minecraft:emerald",0))==9,"merchant owns actual six-emerald gain");h.assertTrue(SupplyFlowService.count(sellBox,new ItemFingerprint("minecraft:emerald",0))==3&&SupplyFlowService.count(buyBox,new ItemFingerprint("minecraft:emerald",0))==0&&SupplyFlowService.count(buyBox,low.item())==3,"seller paid, buyer paid and received actual coal");});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=150)
    public static void merchantCannotSelectUnknownRemoteOffer(GameTestHelper h){floor(h);var trader=npc(h,new BlockPos(3,1,5),NpcProfession.TRADER);trader.getPersonalInventory().setItem(0,new ItemStack(Items.EMERALD,3));h.runAtTickTime(100,()->{h.assertTrue(MerchantSupplyData.get(MinecraftApi.server(trader)).journeys().stream().noneMatch(j->j.merchantEntity().equals(trader.getUUID())),"no omniscient merchant route");h.assertTrue(SupplyFlowService.count(trader.getPersonalInventory(),new ItemFingerprint("minecraft:emerald",0))==3,"idle animation never adds money");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=150)
    public static void ordinaryNpcProjectionDoesNotRevealNeedLedger(GameTestHelper h){floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var target=point(h,new BlockPos(14,1,5),SubjectRef.character(npc.getCharacterId()));var r=SupplyFlowService.request(MinecraftApi.server(npc),SubjectRef.character(npc.getCharacterId()),"PHASE6_TESTER_SECRET",target,new ItemFingerprint("minecraft:coal",0),3,h.getLevel().getGameTime(),h.getLevel().getGameTime()+1000);
        var viewer=new net.minecraft.server.level.ServerPlayer(MinecraftApi.server(npc),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"phase6viewer"),net.minecraft.server.level.ClientInformation.createDefault());viewer.setPos(npc.getX()+1,npc.getY(),npc.getZ());String projection=com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService.forViewer(viewer,npc).toString();h.assertTrue(!projection.contains("PHASE6_TESTER_SECRET")&&!projection.contains(r.id().toString()),"ordinary projection does not leak exact requests or backstage truth");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase6_reload",timeoutTicks=1700)
    public static void carryingShipmentSurvivesActualDiskReload(GameTestHelper h){floor(h);var original=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,original,3,14,3);fill(f.target());NpcBase[] current={original};
        h.runAtTickTime(200,()->{h.assertTrue(SupplyFlowService.count(current[0].getTransportInventory(),new ItemFingerprint("minecraft:coal",0))==3,"actual carried cargo before reload");current[0]=reload(h,current[0]);f.target().setItem(0,ItemStack.EMPTY);});
        h.succeedWhen(()->h.assertTrue(NeedRequestData.get(MinecraftApi.server(current[0])).request(f.need()).status()==NeedRequestStatus.DELIVERED&&SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==3,"disk reload resumes same shipment exactly once"));}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=600)
    public static void savesRealCarryingBodyForNextProcess(GameTestHelper h){
        if(Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.assertTrue(previous!=null,"restart retains original carrying checkpoint instead of overwriting it with a new actor");h.succeed();return;}
        var level=h.getLevel();var start=h.absolutePos(new BlockPos(8192,1,8192));for(int cx=(start.getX()>>4)-1;cx<=(start.getX()>>4)+2;cx++)for(int cz=(start.getZ()>>4)-1;cz<=(start.getZ()>>4)+1;cz++){level.setChunkForced(cx,cz,true);level.getChunk(cx,cz);}
        for(int x=0;x<24;x++)for(int z=0;z<12;z++)level.setBlockAndUpdate(start.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        NpcBase[] actor={null};Fixture[] savedFixture={null};
        h.succeedWhen(()->{
            if(actor[0]==null){h.assertTrue(level.isPositionEntityTicking(start.offset(2,0,5))&&level.isPositionEntityTicking(start.offset(15,0,5)),"original carrying fixture chunks actually tick before actor spawn");actor[0]=npc(h,new BlockPos(8194,1,8197),NpcProfession.COURIER);savedFixture[0]=fixture(h,actor[0],8194,8207,3,8197);fill(savedFixture[0].target());}
            var npc=actor[0];var f=savedFixture[0];h.assertTrue(level.getEntity(npc.getUUID())==npc&&npc.tickCount>0,"checkpoint body is actually registered and ticked, not only an untracked object");
            h.assertTrue(SupplyFlowService.count(npc.getTransportInventory(),new ItemFingerprint("minecraft:coal",0))==3,"same real actor took cargo before checkpoint: "+diagnostic(npc));npc.setCommand(NpcCommand.STAY);NpcBrainRuntime.cancel(npc,PlanCancellationReason.MANUAL,"save carrying checkpoint");
            var tag=new CompoundTag();tag.store("Entity", UUIDUtil.CODEC,npc.getUUID());tag.store("Need", UUIDUtil.CODEC,f.need());tag.putLong("Position",npc.blockPosition().asLong());tag.putLong("Target",f.targetPoint().position().asLong());tag.putLong("Time",level.getGameTime());
            try{NbtIo.writeCompressed(tag,level.getServer().getWorldPath(LevelResource.ROOT).resolve("phase6-restart-checkpoint.nbt"));level.getServer().saveEverything(true,true,true);h.succeed();}catch(Exception e){throw new IllegalStateException("checkpoint save",e);}});
    }
    @GameTest(template="fieldwork_empty",batch="phase6_restart",timeoutTicks=20000)
    public static void mandatoryNewProcessResumesSameCarryingActor(GameTestHelper h){if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}if(previous==null)throw new IllegalStateException("missing mandatory checkpoint");
        var pos=BlockPos.of(previous.getLongOr("Position", 0L));var target=BlockPos.of(previous.getLongOr("Target", 0L));var level=h.getLevel();for(int cx=(pos.getX()>>4)-1;cx<=(target.getX()>>4)+1;cx++)for(int cz=(pos.getZ()>>4)-1;cz<=(pos.getZ()>>4)+1;cz++){level.setChunkForced(cx,cz,true);level.getChunk(cx,cz);}
        boolean[] resumed={false};
        h.succeedWhen(()->{var npc=(NpcBase)level.getEntity(previous.read("Entity", UUIDUtil.CODEC).orElseThrow());
            // The unpaced GameTest clock can exhaust thousands of ticks before the
            // entity-region worker finishes. Require the original body AND ticking
            // chunks; yield only while waiting for asynchronous disk readiness.
            boolean ready=npc!=null&&level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(pos))
                    &&level.isPositionEntityTicking(pos)&&level.isPositionEntityTicking(target);
            if(!ready)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            h.assertTrue(ready,"same persisted body and both ticking chunks in new JVM");
            if(!resumed[0]){h.assertTrue(SupplyFlowService.count(npc.getTransportInventory(),new ItemFingerprint("minecraft:coal",0))==3,"actual persisted cargo");((Container)level.getBlockEntity(target)).setItem(0,ItemStack.EMPTY);npc.setCommand(NpcCommand.WORK);resumed[0]=true;}
            h.assertTrue(NeedRequestData.get(level.getServer()).request(previous.read("Need", UUIDUtil.CODEC).orElseThrow()).status()==NeedRequestStatus.DELIVERED,"mandatory previous-process shipment delivered once: "+diagnostic(npc));h.assertTrue(SupplyFlowService.count((Container)level.getBlockEntity(target),new ItemFingerprint("minecraft:coal",0))==3,"new process did not clone or lose shipment");});}

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1400)
    public static void householdAggregatesAndRestocksActualSharedPantry(GameTestHelper h){
        floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var server=MinecraftApi.server(npc);long now=h.getLevel().getGameTime();
        String dimension=h.getLevel().dimension().identifier().toString();var households=com.jgeted.sagadyssey.social.household.HouseholdData.get(server);
        UUID family=ContainerIds.named("phase6-fixture-family:"+npc.getCharacterId());
        var home=households.createWithId(family,"Phase6 pantry",dimension,null,npc.getCharacterId(),h.absolutePos(new BlockPos(14,1,5)),now);
        h.assertTrue(home!=null&&!npc.getUUID().equals(npc.getCharacterId()),"character identity, not entity id, activates family aggregation");
        households.assignSharedStorage(family,dimension,home.homeAnchor(),h.absolutePos(new BlockPos(14,1,5)));
        households.setProvisionPlan(new com.jgeted.sagadyssey.social.household.HouseholdProvisionPlan(family,3,0,0));h.setBlock(14,1,5,Blocks.BARREL);
        var principal=SubjectRef.household(family);var source=point(h,new BlockPos(4,1,5),principal);
        var sourceBox=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5));sourceBox.setItem(0,new ItemStack(Items.BREAD,3));SupplyFlowData.get(server).reveal(principal,source.containerId());
        SupplyFlowService.aggregate(server,now);SupplyFlowService.match(server,now);
        h.succeedWhen(()->{h.assertTrue(SupplyFlowService.count((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(14,1,5)),new ItemFingerprint("minecraft:bread",0))==3,"family actually restocked by member courier: "+diagnostic(npc));
            h.assertTrue(SupplyFlowService.count(sourceBox,new ItemFingerprint("minecraft:bread",0))==0,"pantry goods conserved");
            h.assertTrue(NeedRequestData.get(server).requests().stream().filter(r->r.key().requester().equals(family)&&r.key().purpose().equals("HOUSEHOLD:household:food")&&!r.status().terminal()).count()==1,"one aggregated family request");});
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1400)
    public static void institutionBufferUsesItsAuthorizedCourierAndRealStock(GameTestHelper h){
        floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);UUID institution=ContainerIds.named("phase6-fixture-institution:"+npc.getCharacterId());npc.setHomeSettlementId(institution);
        var principal=SubjectRef.institution(institution);var source=point(h,new BlockPos(4,1,5),principal);var target=point(h,new BlockPos(14,1,5),principal);
        ((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5))).setItem(0,new ItemStack(Items.COAL,3));var flows=SupplyFlowData.get(MinecraftApi.server(npc));flows.reveal(principal,source.containerId());
        flows.policy(new SupplyFlowData.Policy("phase6-fixture:"+npc.getCharacterId(),principal,target.containerId(),new ItemFingerprint("minecraft:coal",0),3,"institution"));
        SupplyFlowService.aggregate(MinecraftApi.server(npc),h.getLevel().getGameTime());SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());
        h.succeedWhen(()->h.assertTrue(SupplyFlowService.count(SupplyFlowService.container(MinecraftApi.server(npc),target),new ItemFingerprint("minecraft:coal",0))==3,"institutional buffer actually restocked: "+diagnostic(npc)));
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=1400)
    public static void twoCouriersCannotTakeSameShipmentTwice(GameTestHelper h){
        floor(h);var first=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var second=npc(h,new BlockPos(3,1,7),NpcProfession.COURIER);
        com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(second)).onRecruited(second,first.getCharacterId(),h.getLevel().getGameTime());var f=fixture(h,first,4,14,3);
        h.succeedWhen(()->{h.assertTrue(NeedRequestData.get(MinecraftApi.server(first)).request(f.need()).status()==NeedRequestStatus.DELIVERED,"one shipment delivered by competing couriers");
            h.assertTrue(SupplyFlowData.get(MinecraftApi.server(first)).routes().stream().filter(r->r.need().equals(f.need())).count()==1,"one immutable carrier claim");
            h.assertTrue(SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==3&&first.getTransportInventory().isEmpty()&&second.getTransportInventory().isEmpty(),"both couriers conserve single batch");});
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=120)
    public static void componentMismatchIsNotInterchangeableSupply(GameTestHelper h){
        floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);npc.setCommand(NpcCommand.STAY);var principal=SubjectRef.character(npc.getCharacterId());var source=point(h,new BlockPos(4,1,5),principal);var target=point(h,new BlockPos(14,1,5),principal);
        var named=new ItemStack(Items.COAL,3);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("reserved exact lot"));((Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5))).setItem(0,named);SupplyFlowData.get(MinecraftApi.server(npc)).reveal(principal,source.containerId());
        var r=request(h,npc,target,3,"components");SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());h.assertTrue(NeedRequestData.get(MinecraftApi.server(npc)).request(r.id()).reserved()==0,"same item type with different components is not promised");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="phase6_reload",timeoutTicks=1600)
    public static void merchantFullBuyerAndDiskReloadKeepPurchaseAndProfitExact(GameTestHelper h){
        floor(h);var original=npc(h,new BlockPos(3,1,5),NpcProfession.TRADER);var seller=point(h,new BlockPos(4,1,5),SubjectRef.character(UUID.randomUUID()));var buyer=point(h,new BlockPos(14,1,5),SubjectRef.character(UUID.randomUUID()));
        var source=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(4,1,5));var target=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(14,1,5));source.setItem(0,new ItemStack(Items.COAL,3));fill(target);target.setItem(0,new ItemStack(Items.EMERALD,9));original.getPersonalInventory().setItem(0,new ItemStack(Items.EMERALD,3));
        var data=MerchantSupplyData.get(MinecraftApi.server(original));long now=h.getLevel().getGameTime();var low=new MerchantSupplyData.Offer(UUID.randomUUID(),seller.containerId(),UUID.randomUUID(),new ItemFingerprint("minecraft:coal",0),true,1,3,now+24000);var high=new MerchantSupplyData.Offer(UUID.randomUUID(),buyer.containerId(),UUID.randomUUID(),low.item(),false,3,3,now+24000);
        data.offer(MinecraftApi.server(original),seller.owner(),low);data.offer(MinecraftApi.server(original),buyer.owner(),high);data.observe(original,low.id(),now);data.reveal(original.getCharacterId(),new MerchantSupplyData.Observation(high.id(),3,now));NpcBase[] current={original};
        h.runAtTickTime(250,()->{h.assertTrue(SupplyFlowService.count(current[0].getTransportInventory(),low.item())==3&&data.realizedProfit(current[0].getCharacterId())==0,"full buyer preserves purchased goods and has no unrealized profit");
            current[0]=reload(h,current[0]);var server=MinecraftApi.server(current[0]);var storage=server.overworld().getDataStorage();try{int version=SharedConstants.getCurrentVersion().dataVersion().version();storage.set(MerchantSupplyData.FACTORY.type(MerchantSupplyData.NAME),MerchantSupplyData.load(storage.readTagFromDisk(MerchantSupplyData.NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));}catch(Exception e){throw new IllegalStateException("merchant disk reload",e);}target.setItem(1,ItemStack.EMPTY);});
        h.succeedWhen(()->{var restored=MerchantSupplyData.get(MinecraftApi.server(current[0]));h.assertTrue(restored.realizedProfit(current[0].getCharacterId())==6,"physical resale after persisted purchase: "+diagnostic(current[0]));
            h.assertTrue(SupplyFlowService.count(source,new ItemFingerprint("minecraft:emerald",0))==3&&SupplyFlowService.count(current[0].getPersonalInventory(),new ItemFingerprint("minecraft:emerald",0))==9&&SupplyFlowService.count(target,low.item())==3,"single purchase, single sale, actual profit");});
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=600)
    public static void carrierDeathDropsGoodsWithoutFalseDelivery(GameTestHelper h){
        floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,npc,4,14,3);fill(f.target());
        h.runAtTickTime(250,()->{h.assertTrue(SupplyFlowService.count(npc.getTransportInventory(),new ItemFingerprint("minecraft:coal",0))==3,"real carried goods before death");MinecraftApi.kill(npc);});
        h.runAtTickTime(280,()->{var request=NeedRequestData.get(h.getLevel().getServer()).request(f.need());h.assertTrue(request.inTransit()==3&&request.delivered()==0&&request.status()==NeedRequestStatus.UNREACHABLE,"death retains obligation without fictitious delivery");
            int dropped=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,h.getBounds()).stream().filter(e->e.getItem().is(Items.COAL)).mapToInt(e->e.getItem().getCount()).sum();h.assertTrue(dropped==3,"all physical cargo dropped exactly once");h.succeed();});
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=400)
    public static void blockedPathWaitsBoundedlyWithoutMovingOrInventingGoods(GameTestHelper h){
        floor(h);var npc=npc(h,new BlockPos(3,1,5),NpcProfession.COURIER);var f=fixture(h,npc,6,14,3);
        for(int y=1;y<=3;y++)for(int x=2;x<=4;x++)for(int z=4;z<=6;z++)if(x!=3||z!=5)h.setBlock(x,y,z,Blocks.STONE);
        h.runAtTickTime(250,()->{h.assertTrue(SupplyFlowService.count(f.source(),new ItemFingerprint("minecraft:coal",0))==3&&SupplyFlowService.count(f.target(),new ItemFingerprint("minecraft:coal",0))==0,"blocked route never fulfills remotely");
            h.assertTrue(NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().anyMatch(p->p.status()==NpcPlanStatus.FAILED),"unreachable path ends a finite plan and explains failure");h.succeed();});
    }

    @GameTest(template="fieldwork_empty",batch="phase6",timeoutTicks=100)
    public static void ordinaryCommandSourceCannotReadBackstageLedger(GameTestHelper h){
        var server=h.getLevel().getServer();var dispatcher=server.getCommands().getDispatcher();
        h.assertTrue(dispatcher.parse("saga supply audit",server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL)).getReader().canRead(),"ordinary source cannot access tester truth");
        h.assertTrue(!dispatcher.parse("saga supply audit",server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER)).getReader().canRead(),"tester source can access truth separately");h.succeed();
    }

    private record Fixture(UUID need,Container source,Container target,FieldworkStorage targetPoint){}
    private static Fixture fixture(GameTestHelper h,NpcBase npc,int sourceX,int targetX,int count){return fixture(h,npc,sourceX,targetX,count,5);}
    private static Fixture fixture(GameTestHelper h,NpcBase npc,int sourceX,int targetX,int count,int z){if(npc.blockPosition().equals(h.absolutePos(new BlockPos(sourceX,1,z))))sourceX++;var principal=SubjectRef.character(npc.getCharacterId());var source=point(h,new BlockPos(sourceX,1,z),principal);var target=point(h,new BlockPos(targetX,1,z),principal);var box=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(sourceX,1,z));if(count>0)box.setItem(0,new ItemStack(Items.COAL,count));SupplyFlowData.get(MinecraftApi.server(npc)).reveal(principal,source.containerId());var r=request(h,npc,target,3,sourceX>1000?"checkpoint":"courier");SupplyFlowService.match(MinecraftApi.server(npc),h.getLevel().getGameTime());return new Fixture(r.id(),box,(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(targetX,1,z)),target);}
    private static NeedRequest request(GameTestHelper h,NpcBase npc,FieldworkStorage target,int quantity,String purpose){return SupplyFlowService.request(MinecraftApi.server(npc),SubjectRef.character(npc.getCharacterId()),"phase6-fixture:"+purpose+":"+UUID.randomUUID(),target,new ItemFingerprint("minecraft:coal",0),quantity,h.getLevel().getGameTime(),h.getLevel().getGameTime()+24000);}
    private static FieldworkStorage point(GameTestHelper h,BlockPos relative,SubjectRef requestedOwner){h.setBlock(relative,Blocks.BARREL);var pos=h.absolutePos(relative);String dim=h.getLevel().dimension().identifier().toString();UUID id=ProductionContainerIds.resolve(h.getLevel().getServer(),dim,pos);var rights=RightsStateData.get(h.getLevel().getServer());var existing=rights.context(id);
        SubjectRef owner=existing==null?requestedOwner:existing.defaultBeneficialOwner();rights.ensureContext(id,dim+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);var point=new FieldworkStorage(dim,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"phase6_fixture");SupplyFlowData.get(h.getLevel().getServer()).register(point);return point;}
    private static NpcBase npc(GameTestHelper h,BlockPos start,NpcProfession profession){var npc=h.spawn(NpcEntityTypes.NPC_BASE.get(),start);npc.setCharacterId(ContainerIds.named("phase6-fixture-actor:"+h.absolutePos(start).asLong()));npc.setProfession(profession);npc.getPersonalInventory().clearContent();npc.getTransportInventory().clearContent();npc.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);npc.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);npc.setPersistenceRequired();npc.setCommand(NpcCommand.WORK);NpcBrainRuntime.setMode(npc,NpcBehaviorMode.PLANNED);return npc;}
    private static void floor(GameTestHelper h){for(int x=0;x<24;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);}
    private static void fill(Container box){for(int i=0;i<box.getContainerSize();i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));}
    private static String diagnostic(NpcBase npc){return "pos="+npc.blockPosition()+" active="+NpcBrainRuntime.data(MinecraftApi.server(npc)).active(npc.getUUID()).map(p->p.currentStep().detail()).orElse("none")+" history="+NpcBrainRuntime.data(MinecraftApi.server(npc)).history(npc.getUUID()).stream().map(p->p.explanation()).toList();}
    private static NpcBase reload(GameTestHelper h,NpcBase npc){var server=MinecraftApi.server(npc);var storage=server.overworld().getDataStorage();try{var file=server.getWorldPath(LevelResource.ROOT).resolve("phase6-body-"+npc.getUUID()+".nbt");NbtIo.writeCompressed(MinecraftApi.saveEntity(npc),file);storage.saveAndJoin();int version=SharedConstants.getCurrentVersion().dataVersion().version();
            storage.set(NpcBrainData.FACTORY.type(NpcBrainData.DATA_NAME),NpcBrainData.FACTORY.deserializer().apply(storage.readTagFromDisk(NpcBrainData.DATA_NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));
            storage.set(NeedRequestData.FACTORY.type(NeedRequestData.NAME),NeedRequestData.load(storage.readTagFromDisk(NeedRequestData.NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));storage.set(SupplyFlowData.FACTORY.type(SupplyFlowData.NAME),SupplyFlowData.load(storage.readTagFromDisk(SupplyFlowData.NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));
            npc.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);var replacement=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());MinecraftApi.loadEntity(replacement, NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()));h.getLevel().addFreshEntity(replacement);NpcBrainRuntime.rebuildAfterDataReload(server);return replacement;
        }catch(Exception e){throw new IllegalStateException("phase6 real disk reload",e);}}
}
