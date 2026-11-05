package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.common.IndustryTransfers;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Component matching fixtures are separate from the untouched failed world's normal NPC recovery. */
@GameTestHolder("sagadyssey_cancelled_recovery") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class CancelledSupplyGameTests {
    private static final BlockPos BASE=new BlockPos(3072,80,3072);
    private static final UUID ORIGINAL_NEED=UUID.fromString("bfad1170-4fe4-423b-bdc6-55a2b9fc0804");
    private static final UUID ORIGINAL_CANCELLED=UUID.fromString("8578fcb9-c3f7-3495-b9ac-42bf8309976a");
    private static CompoundTag original,result;private static long resumedAt;
    private static String phase(){return System.getProperty("sagadyssey.cancelled.phase","disabled");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.cancelled.enabled"))return List.of();
        return switch(phase()){
            case "cases" -> List.of(test("cancelled_existing_stock",h->component(h,"stock"),200),test("cancelled_no_stock",h->component(h,"empty"),200),test("effective_order_stays_exclusive",h->component(h,"active"),200),test("two_needs_compete_one_pick",h->component(h,"competition"),200),test("repeat_and_component_nbt",h->component(h,"repeat"),200),test("uncertain_old_batch_preserved",h->component(h,"uncertain"),200));
            case "recover" -> List.of(test("original_failed_world_recovers",CancelledSupplyGameTests::recover,2400));
            case "restart" -> List.of(test("same_recovery_after_process_restart",CancelledSupplyGameTests::restart,800));
            default -> List.of();
        };
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int limit){return new TestFunction("cancel_recovery","cancel_recovery."+name,"sagadyssey_cancelled_recovery:empty",limit,0,true,body);}
    @net.neoforged.bus.api.SubscribeEvent public static void boot(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        original=null;result=null;if(!(e.getServer() instanceof GameTestServer)||!Boolean.getBoolean("sagadyssey.cancelled.enabled"))return;
        try{
            var s=e.getServer();var l=s.overworld();l.getStructureManager().getOrCreate(ResourceLocation.parse("sagadyssey_cancelled_recovery:empty"))
                    .load(l.holderLookup(Registries.BLOCK),TagParser.parseTag("{size:[24,8,24],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
            rules(l);
            if(!phase().equals("cases")){
                var path=s.getWorldPath(LevelResource.ROOT);original=NbtIo.readCompressed(path.resolve("industry-ab-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
                var saved=NbtIo.readCompressed(path.resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
                s.getWorldData().overworldData().setGameTime(saved.getLong("Time")+1);resumedAt=saved.getLong("Time")+1;
                rules(l);var cp=new ChunkPos(BASE);for(int x=cp.x-1;x<=cp.x+2;x++)for(int z=cp.z-1;z<=cp.z+2;z++){l.setChunkForced(x,z,true);l.getChunk(x,z);}
                if(phase().equals("restart"))result=NbtIo.readCompressed(path.resolve("cancel-recovery-result.nbt"),NbtAccounter.unlimitedHeap());
            }
        }catch(Exception x){throw new IllegalStateException("require exact complete failed/recovered world; never reconstruct people",x);}
    }
    private static void rules(ServerLevel l){l.setDayTime(1000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,l.getServer());}
    private static void sprint(GameTestHelper h,int n){try{h.getLevel().getServer().getCommands().getDispatcher().execute("tick sprint "+n,h.getLevel().getServer().createCommandSourceStack().withPermission(4).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}}
    private static ItemFingerprint pick(){return new ItemFingerprint("minecraft:iron_pickaxe",0);}
    private record Fixture(NpcBase owner,FieldworkStorage source,FieldworkStorage target,NeedRequest need,ProductionOrder order){}
    private static FieldworkStorage point(GameTestHelper h,int x,SubjectRef owner){var l=h.getLevel();var p=h.absolutePos(new BlockPos(x,1,5));l.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState());var id=ProductionContainerIds.resolve(l.getServer(),l.dimension().location().toString(),p);RightsStateData.get(l.getServer()).ensureContext(id,"cancel-case:"+p.asLong(),owner,owner,AccessPolicy.PRIVATE);var t=new FieldworkStorage(l.dimension().location().toString(),p,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"finite cancelled-link fixture");SupplyFlowData.get(l.getServer()).register(t);return t;}
    private static Fixture fixture(GameTestHelper h,String purpose,int quantity,boolean stock,boolean cancelled){
        var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();for(int x=0;x<20;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(3,1,3));n.setPersistenceRequired();n.setCommand(NpcCommand.STAY);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();var principal=SubjectRef.character(n.getCharacterId());
        var source=point(h,4,principal);var target=point(h,12,principal);if(stock)SupplyFlowService.container(s,source).setItem(0,new ItemStack(Items.IRON_PICKAXE));SupplyFlowData.get(s).reveal(principal,source.containerId());
        var need=SupplyFlowService.request(s,principal,"cancel-case:"+purpose,target,pick(),quantity,now,now+10000);
        var order=ProductionOrder.open(SupplyFlowService.receipt(need.id(),"production-order"),principal,principal,"sagadyssey:iron_pickaxe",List.of(new ProductionMaterial(new ItemFingerprint("minecraft:iron_ingot",0),3*quantity),new ProductionMaterial(new ItemFingerprint("minecraft:coal",0),quantity)),new ProductionMaterial(pick(),quantity),80,SupplyFlowService.receipt(need.id(),"production-batch"),target.dimension(),target.position().north(),target.containerId(),target.dimension(),target.position(),now,"finite initial pending order");
        ProductionOrderData.get(s).put(order);if(cancelled){order=order.transition(ProductionOrderStatus.CANCELLED,now,"initial cancelled before any worker or material effect");ProductionOrderData.get(s).put(order);}SupplyFlowData.get(s).production(need.id(),order.id());return new Fixture(n,source,target,need,order);
    }
    private static long routes(GameTestHelper h,UUID id){return SupplyFlowData.get(h.getLevel().getServer()).routes().stream().filter(r->r.need().equals(id)).count();}
    private static void component(GameTestHelper h,String kind){
        var f=fixture(h,kind,1,!kind.equals("empty"),!kind.equals("active"));var s=h.getLevel().getServer();var flows=SupplyFlowData.get(s);var needs=NeedRequestData.get(s);long now=h.getLevel().getGameTime();
        NeedRequest second=null;ProductionOrder secondOrder=null;
        if(kind.equals("competition")){
            second=SupplyFlowService.request(s,SubjectRef.character(f.owner().getCharacterId()),"cancel-case:competing",f.target(),pick(),1,now,now+10000);
            secondOrder=ProductionOrder.open(SupplyFlowService.receipt(second.id(),"production-order"),f.order().issuer(),f.order().outputOwner(),f.order().recipeId(),f.order().inputs(),f.order().output(),80,SupplyFlowService.receipt(second.id(),"production-batch"),f.target().dimension(),f.target().position().north(),f.target().containerId(),f.target().dimension(),f.target().position(),now,"second real finite obligation");ProductionOrderData.get(s).put(secondOrder);secondOrder=secondOrder.transition(ProductionOrderStatus.CANCELLED,now,"cancelled before work");ProductionOrderData.get(s).put(secondOrder);flows.production(second.id(),secondOrder.id());
        }
        if(kind.equals("uncertain")){
            var box=SupplyFlowService.container(s,f.target());box.setItem(0,new ItemStack(Items.IRON_INGOT,3));box.setItem(1,new ItemStack(Items.COAL));
            ProductionService.begin(s,f.order().batchId(),f.owner().getCharacterId(),f.order().outputOwner(),f.target().containerId(),box,f.order().recipeId(),f.order().inputs(),f.order().output(),80,now);
        }
        for(int i=0;i<20;i++)SupplyFlowService.match(s,now);
        h.assertTrue(flows.production(f.need().id()).equals(f.order().id()),"old immutable history binding retained");
        h.assertTrue(ProductionOrderData.get(s).byId(f.order().id()).orElseThrow().equals(f.order()),"do not rewrite cancelled or effective original order");
        int sourceQty=SupplyFlowService.count(SupplyFlowService.container(s,f.source()),pick());h.assertTrue(sourceQty==(kind.equals("empty")?0:1),"matching only reserves, never moves or invents physical tools");
        if(kind.equals("active")){h.assertTrue(routes(h,f.need().id())==0 && needs.request(f.need().id()).reserved()==0,"valid production remains exclusive, no parallel promise");}
        else if(kind.equals("uncertain")){h.assertTrue(routes(h,f.need().id())==0 && ProductionBatchData.get(s).batch(f.order().batchId()).status()==ProductionBatchStatus.RESERVED,"old material obligation not released");h.assertTrue(needs.request(f.need().id()).detail().contains("PRODUCTION_REVIEW"),"uncertain outstanding batch explicitly preserved for review");}
        else if(kind.equals("empty")){h.assertTrue(routes(h,f.need().id())==0 && needs.request(f.need().id()).status()==NeedRequestStatus.WAITING_EXTERNAL,"no legal stock waits without recreating manufacture");h.assertTrue(needs.request(f.need().id()).remaining()==1,"unfulfilled obligation kept");}
        else if(kind.equals("competition")){h.assertTrue(routes(h,f.need().id())+routes(h,second.id())==1,"one real pick supports only one route across two legal needs");h.assertTrue(needs.request(f.need().id()).reserved()+needs.request(second.id()).reserved()==1,"no duplicate inventory reservation");}
        else {h.assertTrue(routes(h,f.need().id())==1 && needs.request(f.need().id()).reserved()==1,"cancelled order must not block one real lawful stock route");}
        if(kind.equals("repeat")){
            var restoredFlow=SupplyFlowData.load(flows.save(new CompoundTag(),s.registryAccess()),s.registryAccess());var restoredNeeds=NeedRequestData.load(needs.save(new CompoundTag(),s.registryAccess()),s.registryAccess());
            h.assertTrue(restoredFlow.production(f.need().id()).equals(f.order().id())&&restoredFlow.routes().stream().filter(r->r.need().equals(f.need().id())).count()==1&&restoredNeeds.request(f.need().id()).reserved()==1,"component NBT preserves same history, one held route and real reservation; not a world restart");
        }
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("CANCEL_CASE_RESULT kind={} tick={} need={} oldOrder={} sourcePhysical={} routes={} reserved={} detail={}",kind,now,f.need().id(),f.order().id(),sourceQty,routes(h,f.need().id()),needs.request(f.need().id()).reserved(),needs.request(f.need().id()).detail());h.succeed();
    }
    private static NpcBase person(GameTestHelper h,String key){var n=h.getLevel().getEntity(original.getUUID(key));h.assertTrue(n instanceof NpcBase,"original "+key+" body, no replacement");var p=(NpcBase)n;h.assertTrue(p.getCharacterId().equals(original.getUUID(key+"Person")),"same persistent "+key);return p;}
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static void originalInvariant(GameTestHelper h){var s=h.getLevel().getServer();person(h,"Miner");person(h,"Operator");person(h,"Smith");person(h,"Courier");h.assertTrue(ProductionOrderData.get(s).byId(ORIGINAL_CANCELLED).orElseThrow().status()==ProductionOrderStatus.CANCELLED,"original cancelled order history retained");h.assertTrue(SupplyFlowData.get(s).production(ORIGINAL_NEED).equals(ORIGINAL_CANCELLED),"do not delete or replace original linkage");}
    private static boolean usedOriginalPick(NpcBase n){
        int total=n.getMainHandItem().is(Items.IRON_PICKAXE)?n.getMainHandItem().getCount():0;boolean worn=n.getMainHandItem().is(Items.IRON_PICKAXE)&&n.getMainHandItem().getDamageValue()==1;
        for(int i=0;i<n.getPersonalInventory().getContainerSize();i++){var item=n.getPersonalInventory().getItem(i);if(item.is(Items.IRON_PICKAXE)){total+=item.getCount();worn|=item.getDamageValue()==1;}}
        return total==1&&worn;
    }
    private static long mined(GameTestHelper h,NpcBase n){return FieldworkData.get(h.getLevel().getServer()).receiptsFor(n.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE).count();}
    private static void save(GameTestHelper h,CompoundTag mark){try{NbtIo.writeCompressed(mark,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cancel-recovery-result.nbt"));h.getLevel().getServer().saveEverything(true,false,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception x){throw new IllegalStateException(x);}}
    private static void recover(GameTestHelper h){
        if(original==null)throw new IllegalStateException("original failed world's complete copy required");var l=h.getLevel();var s=l.getServer();sprint(h,3000);var seen=new HashSet<UUID>();long[] lastDelivery={-1};
        h.onEachTick(()->{var r=NeedRequestData.get(s).request(ORIGINAL_NEED);if(r.delivered()>0&&lastDelivery[0]<0)lastDelivery[0]=l.getGameTime();for(var route:SupplyFlowData.get(s).routes())if(route.need().equals(ORIGINAL_NEED)&&seen.add(route.id()))com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("CANCEL_ORIGINAL_ROUTE tick={} need={} route={} stage={} quantity={} principal={}",l.getGameTime(),r.id(),route.id(),route.stage(),route.quantity(),route.principal());});
        h.succeedWhen(()->{
            originalInvariant(h);var n=person(h,"Miner");h.assertTrue(l.getGameTime()-resumedAt<=2400,"new frozen recovery window, original FAIL never changed");
            var need=NeedRequestData.get(s).request(ORIGINAL_NEED);h.assertTrue(need.requested()==2&&need.delivered()==1&&need.reserved()==0&&need.inTransit()==0&&need.remaining()==1,"actual partial delivery1, remaining1, no false full fulfillment");
            h.assertTrue(mined(h,n)==6&&l.getBlockState(BASE.offset(8,0,5)).isAir(),"original miner really mines the original remaining stone");
            var rawStore=(Container)l.getBlockEntity(BASE.offset(3,0,8));h.assertTrue(count(n.getPersonalInventory(),Items.COBBLESTONE)+count(rawStore,Items.COBBLESTONE)==1,"one actual new cobblestone exists, not only a receipt");
            h.assertTrue(usedOriginalPick(n),"same newly forged real pick worn once");
            var tool=FieldworkStorage.load(original.getCompound("ToolStock"));var pantry=FieldworkStorage.load(original.getCompound("Pantry"));h.assertTrue(count(SupplyFlowService.container(s,tool),Items.IRON_PICKAXE)==0&&count(SupplyFlowService.container(s,pantry),Items.IRON_PICKAXE)==0,"original real pick now with miner, no copies in stores");
            var courier=person(h,"Courier");h.assertTrue(count(courier.getTransportInventory(),Items.IRON_PICKAXE)==0,"original cargo cleared only by true delivery");
            var mark=new CompoundTag();mark.putLong("Started",resumedAt);mark.putLong("Finished",l.getGameTime());mark.putLong("DeliveredAt",lastDelivery[0]);mark.putInt("RouteCount",(int)SupplyFlowData.get(s).routes().stream().filter(r->r.need().equals(ORIGINAL_NEED)).count());mark.putInt("OrderCount",ProductionOrderData.get(s).openOrders().size());save(h,mark);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("CANCEL_ORIGINAL_RECOVERED tick={} started={} elapsed={} miner={} need={} cancelled={} delivered=1 remaining=1 mineReceipts=6 newPickDamage=1 deliveryAt={}",l.getGameTime(),resumedAt,l.getGameTime()-resumedAt,n.getCharacterId(),ORIGINAL_NEED,ORIGINAL_CANCELLED,lastDelivery[0]);
        });
    }
    private static void restart(GameTestHelper h){if(original==null||result==null)throw new IllegalStateException("complete recovered world required");var l=h.getLevel();sprint(h,1000);h.runAtTickTime(600,()->{originalInvariant(h);var n=person(h,"Miner");var r=NeedRequestData.get(l.getServer()).request(ORIGINAL_NEED);h.assertTrue(r.delivered()==1&&r.remaining()==1&&r.reserved()==0&&r.inTransit()==0,"partial obligation persists, no repeat reward/tool delivery");h.assertTrue(mined(h,n)==6&&usedOriginalPick(n),"same original work and pick after full process restart");h.assertTrue(SupplyFlowData.get(l.getServer()).routes().stream().filter(x->x.need().equals(ORIGINAL_NEED)).count()==result.getInt("RouteCount"),"no duplicate route or assignment");com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("CANCEL_RESTART_ACCEPT tick={} originalMiner={} need={} delivered=1 remaining=1 mineReceipts=6 routes={}",l.getGameTime(),n.getCharacterId(),ORIGINAL_NEED,result.getInt("RouteCount"));h.succeed();});}
}
