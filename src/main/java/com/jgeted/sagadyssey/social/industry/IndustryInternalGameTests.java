package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.workshop.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;
import net.neoforged.neoforge.gametest.*;

/** New frozen cases. Old FAIL tests and deadlines remain unchanged. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class IndustryInternalGameTests {
    private static String phase(){return System.getProperty("sagadyssey.completion.phase","disabled");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.industry.native"))return List.of();
        return switch(phase()) {
            case "renew-start" -> List.of(test("renewal_checkpoint",IndustryCompletionGameTests::woodStart,120000));
            case "renew-resume" -> List.of(test("renewal_native_balance",IndustryInternalGameTests::renew,120000));
            case "tool-axe" -> List.of(test("original_axe_break_and_resume",h->tool(h,false),16000));
            case "tool-hoe" -> List.of(test("original_hoe_break_and_resume",h->tool(h,true),16000));
            case "wood-materials" -> List.of(test("native_material_woodwork",IndustryInternalGameTests::woodMaterials,16000));
            case "agri-start" -> List.of(test("real_harvest_to_native_food_checkpoint",IndustryInternalGameTests::agriStart,72000));
            case "agri-resume" -> List.of(test("real_harvest_food_recovery",IndustryInternalGameTests::agriResume,72000));
            default -> List.of();
        };
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> run,int ticks){
        return new TestFunction("industry_internal","internal."+name,"sagadyssey_industries_completion:empty",ticks,0,true,h->{
            h.onEachTick(()->{if(h.getLevel().getGameTime()%20==0&&java.nio.file.Files.exists(h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("industry-stop-request.json")))h.fail("frozen wall budget stop; preserve first world");});run.accept(h);
        });
    }
    private static void renew(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("complete original tree world required");
        var l=h.getLevel();var s=l.getServer();var n=original(h,"Worker");var root=BlockPos.of(prior.getLongOr("Root", 0L));sprint(h,120500);
        h.succeedWhen(()->{
            h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=120000,"new frozen total window, old40000 FAIL unchanged");
            var receipts=FieldworkData.get(s).receiptsFor(n.getUUID());
            long planted=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT&&r.action().target().equals(root)).count();
            long cut=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.CHOP&&r.action().target().equals(root)).count();
            h.assertTrue(planted>=2&&cut>=3,"two native regrowths really harvested");
            int seeds=produced(receipts,"oak_sapling"),logs=produced(receipts,"oak_log"),nativeSticks=produced(receipts,"stick");
            h.assertTrue(seeds>=1,"second planting uses actual produced seed");
            for(String key:List.of("One","Two","Three","Four"))h.assertTrue(ProductionOrderData.get(s).byId(prior.read(key, UUIDUtil.CODEC).orElseThrow()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"four original actual wood orders");
            var stock=FieldworkStorage.load(prior.getCompoundOrEmpty("Stock"));var box=box(h,stock);
            h.assertTrue(count(box,Items.OAK_PLANKS)==20,"24made minus4actually consumed");
            h.assertTrue(count(box,Items.STICK)+n.getPersonalInventory().count(i->i.is(Items.STICK))==8+nativeSticks,"native leaf sticks separately included, not ignored");
            h.assertTrue(count(box,Items.OAK_LOG)+n.getPersonalInventory().count(i->i.is(Items.OAK_LOG))+6==logs,"all logs at original locations or consumed");
            h.assertTrue(!FieldworkDepositTaskExecutor.hasDeliverableCargo(n,FieldworkData.get(s)),"outputs delivered; retained planting seed must be separately accounted");
            Sagadyssey.LOGGER.info("INTERNAL_RENEW_ACCEPT tick={} originalPerson={} cut={} planted={} actualSeeds={} logs={} nativeSticks={} manufacturedSticks=8",l.getGameTime(),n.getCharacterId(),cut,planted,seeds,logs,nativeSticks);
        });
    }
    private static int produced(List<FieldworkActionReceipt> receipts,String id){return receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals("minecraft:"+id+"#0")&&d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();}
    private static void tool(GameTestHelper h,boolean hoe){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();
        var worker=npc(h,2,5,hoe?NpcProfession.FARMER:NpcProfession.WORKER);var smith=npc(h,13,5,NpcProfession.BLACKSMITH);
        family(h,"original producer and smith",List.of(worker,smith));hire(smith,worker,now);bed(h,worker,2,18);bed(h,smith,5,18);
        var owner=SubjectRef.character(worker.getCharacterId());var stock=point(h,at(10,0,7),owner,TransferIntent.DELIVERY);
        h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,worker.getCharacterId(),stock.dimension(),stock.position(),now),"lawful original private source");
        FieldworkData.get(s).bindTestStorage(worker.getUUID(),stock);SupplyFlowData.get(s).reveal(owner,stock.containerId());
        box(h,stock).setItem(0,new ItemStack(Items.IRON_INGOT,hoe?2:3));box(h,stock).setItem(1,new ItemStack(Items.STICK,2));l.setBlockAndUpdate(at(13,0,7),Blocks.ANVIL.defaultBlockState());
        var type=hoe?Items.IRON_HOE:Items.IRON_AXE;worker.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(type));worker.getMainHandItem().setDamageValue(worker.getMainHandItem().getMaxDamage()-1);
        worker.setFarmMode(FarmMode.CROP);worker.setWorkZone(at(4,0,5),at(6,0,5));
        for(int x:List.of(4,6))l.setBlockAndUpdate(at(x,hoe?-1:0,5),(hoe?Blocks.DIRT:Blocks.OAK_LOG).defaultBlockState());
        if(hoe){l.setBlockAndUpdate(at(5,-2,5),Blocks.STONE.defaultBlockState());l.setBlockAndUpdate(at(5,-1,5),Blocks.WATER.defaultBlockState());}
        sprint(h,16500);h.succeedWhen(()->{
            var receipts=FieldworkData.get(s).receiptsFor(worker.getUUID()).stream().filter(r->r.action().operation()==(hoe?FieldworkAction.Operation.TILL:FieldworkAction.Operation.CHOP)).toList();h.assertTrue(receipts.size()==2,"old tool broke and original producer truly resumed second work");
            var forged=ProductionOrderData.get(s).ordersForWorker(smith.getCharacterId()).stream().filter(o->o.recipeId().equals(hoe?"minecraft:iron_hoe":"minecraft:iron_axe")&&o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(forged.size()==1,"one replacement genuinely made");
            int worn=worker.getPersonalInventory().count(i->i.is(type)&&i.getDamageValue()>=1)+(worker.getMainHandItem().is(type)&&worker.getMainHandItem().getDamageValue()>=1?1:0);h.assertTrue(worn==1,"one legitimately obtained replacement actually used");
            h.assertTrue(count(box(h,stock),Items.IRON_INGOT)==0&&count(box(h,stock),Items.STICK)==0,"all declared real tool inputs consumed");
            Sagadyssey.LOGGER.info("INTERNAL_TOOL_ACCEPT kind={} tick={} person={} forge={} actions=2 replacementWorn=1",hoe?"hoe":"axe",l.getGameTime(),worker.getCharacterId(),forged.getFirst().id());save(h,new CompoundTag());
        });
    }
    private static void woodMaterials(GameTestHelper h){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();var n=npc(h,3,6,NpcProfession.WORKER);family(h,"native material workshop",List.of(n));bed(h,n,3,18);
        var owner=SubjectRef.character(n.getCharacterId());var stock=point(h,at(8,0,6),owner,TransferIntent.DELIVERY);var table=at(9,0,6);l.setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        box(h,stock).setItem(0,new ItemStack(Items.SPRUCE_LOG,3));box(h,stock).setItem(1,new ItemStack(Items.BIRCH_LOG,3));box(h,stock).setItem(2,new ItemStack(Items.RED_WOOL,3));
        SupplyFlowData.get(s).reveal(owner,stock.containerId());
        var one=WorkshopTask.requestProduct(n,"native-spruce",stock,table,fp("spruce_planks"),now);var two=WorkshopTask.requestProduct(n,"native-birch",stock,table,fp("birch_planks"),now);
        h.assertTrue(WorkshopTask.requestProduct(n,"native-spruce",stock,table,fp("spruce_planks"),now).id().equals(one.id()),"same request replay");
        boolean rejected=false;try{WorkshopTask.requestProduct(n,"native-spruce",stock,table,fp("birch_planks"),now);}catch(IllegalStateException conflict){rejected=true;}h.assertTrue(rejected,"same business changed product conflicts");
        var bad=ProductionOrder.open(java.util.UUID.randomUUID(),owner,owner,WorkshopTask.PLANKS,List.of(new ProductionMaterial(fp("dirt"),3)),new ProductionMaterial(fp("spruce_planks"),12),200,java.util.UUID.randomUUID(),stock.dimension(),table,stock.containerId(),stock.dimension(),stock.position(),now,"invalid tag does not grant a recipe");h.assertTrue(!WorkshopTask.recognized(s,bad),"unrelated resource cannot become planks");
        SupplyFlowData.get(s).policy(new SupplyFlowData.Policy("red-bed",owner,stock.containerId(),fp("red_bed"),1,"workshop"));
        sprint(h,16500);h.succeedWhen(()->{
            h.assertTrue(ProductionOrderData.get(s).byId(one.id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED&&ProductionOrderData.get(s).byId(two.id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"two different native log recipes actually executed");
            h.assertTrue(count(box(h,stock),Items.RED_BED)==1,"formal demand produces real colored bed from available native wood and wool");
            h.assertTrue(count(box(h,stock),Items.SPRUCE_LOG)==0&&count(box(h,stock),Items.BIRCH_LOG)==0&&count(box(h,stock),Items.RED_WOOL)==0,"no fictitious source or refund");
            h.assertTrue(count(box(h,stock),Items.SPRUCE_PLANKS)+count(box(h,stock),Items.BIRCH_PLANKS)==21,"24 real planks minus3 used in bed");
            h.assertTrue(WorkshopTask.requestProduct(n,"native-spruce",stock,table,fp("spruce_planks"),l.getGameTime()).id().equals(one.id()),"old successful request replays after original stock consumed");
            Sagadyssey.LOGGER.info("INTERNAL_WOOD_NATIVE_ACCEPT tick={} person={} logsConsumed=6 planksProduced=24 bed=1 planksLeft=21 duplicateEffect=false",l.getGameTime(),n.getCharacterId());save(h,new CompoundTag());
        });
    }
    private static void agriStart(GameTestHelper h){
        floor(h,3);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();
        var farmer=npc(h,2,3,NpcProfession.FARMER);var cook=npc(h,11,7,NpcProfession.WORKER);var eater=npc(h,23,7,NpcProfession.WORKER);var courier=npc(h,17,7,NpcProfession.COURIER);
        var home=family(h,"internal agricultural food",List.of(farmer,cook,eater,courier));hire(courier,cook,now);farmer.setFarmMode(FarmMode.CROP);farmer.setWorkZone(at(3,0,3),at(9,0,7));farmer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_HOE));
        eater.setCommand(NpcCommand.STAY);eater.getPersonalInventory().clearContent();NpcLifeData.get(s).setHunger(eater.getCharacterId(),120,now);
        for(var pair:List.of(new Object[]{farmer,2},new Object[]{cook,5},new Object[]{eater,8},new Object[]{courier,11}))bed(h,(NpcBase)pair[0],(int)pair[1],18);
        for(int x:new int[]{3,5,7,9})for(int z:new int[]{3,5,7}){l.setBlockAndUpdate(at(x,-1,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));l.setBlockAndUpdate(at(x,0,z),Blocks.POTATOES.defaultBlockState());}
        l.setBlockAndUpdate(at(6,-1,5),Blocks.WATER.defaultBlockState());
        var farmOwner=SubjectRef.character(farmer.getCharacterId());var owner=SubjectRef.character(cook.getCharacterId());var consumer=SubjectRef.household(home);
        var farm=point(h,at(2,0,10),farmOwner,TransferIntent.GIFT);var raw=point(h,at(11,0,10),owner,TransferIntent.DELIVERY);var output=point(h,at(16,0,10),owner,TransferIntent.GIFT);l.setBlockAndUpdate(at(23,0,10),Blocks.BARREL.defaultBlockState());
        com.jgeted.sagadyssey.social.household.HouseholdData.get(s).assignSharedStorage(home,raw.dimension(),at(22,0,10),at(23,0,10));
        var target=new FieldworkStorage(raw.dimension(),at(23,0,10),ContainerIds.block("household_storage",raw.dimension(),at(23,0,10)),consumer,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"household_supply");
        RightsStateData.get(s).ensureContext(target.containerId(),raw.dimension()+":"+at(23,0,10).asLong(),consumer,consumer,AccessPolicy.HOUSEHOLD);SupplyFlowData.get(s).register(target);
        h.assertTrue(com.jgeted.sagadyssey.social.household.HouseholdData.get(s).byId(home).sharedStorage().equals(target.position()),"declared household pantry equals actual target before execution");
        h.assertTrue(NpcLifeData.get(s).assignPrivateStorage(s,farmer.getCharacterId(),farm.dimension(),farm.position(),now),"farm output legal private facility");FieldworkData.get(s).bindTestStorage(farmer.getUUID(),farm);grant(h,farm,owner,"farmer consents to actual crop surplus for internal cooking");grant(h,output,consumer,"cook consents to household meal");
        box(h,raw).setItem(0,new ItemStack(Items.COAL,2));
        var flows=SupplyFlowData.get(s);flows.reveal(owner,farm.containerId());flows.reveal(owner,raw.containerId());flows.reveal(owner,output.containerId());flows.reveal(consumer,output.containerId());
        flows.policy(new SupplyFlowData.Policy("harvest-raw",owner,raw.containerId(),fp("potato"),3,"workshop"));flows.policy(new SupplyFlowData.Policy("harvest-cooked",owner,output.containerId(),fp("baked_potato"),3,"workshop"));
        l.setBlockAndUpdate(at(13,0,10),Blocks.FURNACE.defaultBlockState());var machine=ProductionContainerIds.resolve(s,raw.dimension(),at(13,0,10));RightsStateData.get(s).ensureContext(machine,"real agricultural cooking",owner,owner,AccessPolicy.PRIVATE);
        var need=SupplyFlowService.request(s,consumer,"internal:real-grown-meal",target,fp("baked_potato"),6,now,now+72000);SupplyFlowService.aggregate(s,now);SupplyFlowService.match(s,now);sprint(h,72500);
        h.succeedWhen(()->{
            var first=ProductionOrderData.get(s).assignedTo(cook.getCharacterId()).stream().filter(FurnaceWorkAdapter::recognized).filter(o->o.status()==ProductionOrderStatus.IN_PROGRESS).findFirst().orElse(null);h.assertTrue(first!=null,"actual crop harvest transported and loaded into native furnace");
            h.assertTrue(produced(FieldworkData.get(s).receiptsFor(farmer.getUUID()),"potato")>=3,"raw input is genuinely harvested, no initial raw stock");
            var mark=new CompoundTag();actor(mark,"Farmer",farmer);actor(mark,"Cook",cook);actor(mark,"Eater",eater);actor(mark,"Courier",courier);mark.store("Need", UUIDUtil.CODEC,need.id());mark.putLong("Start",now);mark.put("Farm",farm.save());mark.put("Raw",raw.save());mark.put("Output",output.save());mark.put("Target",target.save());mark.putLong("Furnace",at(13,0,10).asLong());save(h,mark);
            Sagadyssey.LOGGER.info("INTERNAL_AGRI_CHECKPOINT tick={} farmer={} order={} rawInitial=0 youngPlants=12 nativeLoaded=true",l.getGameTime(),farmer.getCharacterId(),first.id());
        });
    }
    private static void agriResume(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("complete agricultural world required");var l=h.getLevel();var s=l.getServer();var farmer=original(h,"Farmer");var cook=original(h,"Cook");var eater=original(h,"Eater");original(h,"Courier");h.onEachTick(()->{if(l.getGameTime()-prior.getLongOr("Start", 0L)>72000)h.fail("original72000 total tick deadline reached; retain original duties");});sprint(h,72500);
        h.succeedWhen(()->{
            h.assertTrue(l.getGameTime()-prior.getLongOr("Start", 0L)<=72000,"frozen crop and cooking deadline includes prior process");
            var orders=ProductionOrderData.get(s).ordersForWorker(cook.getCharacterId()).stream().filter(FurnaceWorkAdapter::recognized).filter(o->o.status()==ProductionOrderStatus.COMPLETED).toList();h.assertTrue(orders.size()>=2&&orders.stream().allMatch(o->FurnaceWorkAdapter.completedDelivery(s,o)),"two actual native cooking cycles with complete physical transfers");
            h.assertTrue(NeedRequestData.get(s).request(prior.read("Need", UUIDUtil.CODEC).orElseThrow()).delivered()==6,"six truly grown and cooked meals physically delivered");
            var repeats=new HashMap<BlockPos,Integer>();for(var receipt:FieldworkData.get(s).receiptsFor(farmer.getUUID()))if(receipt.action().operation()==FieldworkAction.Operation.HARVEST)repeats.merge(receipt.action().target(),1,Integer::sum);
            h.assertTrue(repeats.values().stream().anyMatch(q->q>=2),"same original field naturally regrows and is harvested again");
            var target=FieldworkStorage.load(prior.getCompoundOrEmpty("Target"));int cooked=count(box(h,target),Items.BAKED_POTATO)+eater.getPersonalInventory().count(i->i.is(Items.BAKED_POTATO));h.assertTrue(cooked<6&&NpcLifeData.get(s).needs(eater.getCharacterId()).lastAteAt()>prior.getLongOr("Start", 0L),"recipient actually consumed delivered baked food");
            Sagadyssey.LOGGER.info("INTERNAL_AGRI_ACCEPT tick={} farmer={} cook={} eater={} nativeCycles={} delivered=6 cookedRemaining={} repeatedCells={} rawInitial=0",l.getGameTime(),farmer.getCharacterId(),cook.getCharacterId(),eater.getCharacterId(),orders.size(),cooked,repeats.values().stream().filter(q->q>=2).count());save(h,prior.copy());
        });
    }
}
