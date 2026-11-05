package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.industry.food.FoodIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.stone.StoneIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.forestry.ForestryIndustryAdapter;
import com.jgeted.sagadyssey.social.industry.logistics.IndustrySupplyAdapter;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Opt-in loaded execution slices. No recipe completion, cargo movement or labour is performed by this fixture. */
@GameTestHolder("sagadyssey_industries") @PrefixGameTestTemplate(false)
public final class IndustryIntegrationGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if(!Boolean.getBoolean("sagadyssey.industries.enabled") || !System.getProperty("sagadyssey.industries.phase","baseline").equals("baseline")) return List.of();
        return List.of(test("mixed_blocks",IndustryIntegrationGameTests::mixed,2400),
                test("two_shipments",IndustryIntegrationGameTests::shipments,2400),
                test("two_workshop_orders",IndustryIntegrationGameTests::workshop,3600),
                test("foreign_stock_closed",IndustryIntegrationGameTests::foreign,150),
                test("regression_original_replant",FieldworkGameTests::farmerHarvestReplantsAndDelivers,800),
                test("regression_shared_block",FieldworkGameTests::twoWorkersCannotDuplicateOneBlock,700));
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks) {
        return new TestFunction("industries","industry."+name,"sagadyssey_industries:empty",ticks,0,true,body);
    }
    @BeforeBatch(batch="industries") public static void conditions(ServerLevel l) {
        l.setDayTime(1000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,l.getServer());
        l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
        l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,l.getServer());
        // Mature initial crop blocks are finite fixture inputs; no claim of a natural crop-growth cycle.
    }
    private static void floor(GameTestHelper h) { for(int x=0;x<24;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE); }
    private static NpcBase npc(GameTestHelper h,NpcProfession role,BlockPos p) {
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),p);n.setProfession(role);n.setCommand(NpcCommand.WORK);
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
        n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setPersistenceRequired();NpcLifeData.get(n.getServer()).ensureRegistered(n,h.getLevel().getGameTime());
        var owner=SubjectRef.character(n.getCharacterId());
        RightsStateData.get(n.getServer()).ensureContext(ContainerIds.npcPersonal(n.getCharacterId()),"npc:"+n.getCharacterId()+":personal",owner,owner,AccessPolicy.PRIVATE);
        NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    private static HouseholdRecord family(GameTestHelper h,List<NpcBase> people) {
        var s=h.getLevel().getServer();long now=h.getLevel().getGameTime();String dim=h.getLevel().dimension().location().toString();
        var site=SettlementRegistryData.get(s).create(s,"industry fixture",SettlementType.VILLAGE,dim,
                h.absolutePos(new BlockPos(12,1,6)),32,"sagadyssey:kingdom",people.getFirst().getCharacterId(),now);
        var homes=HouseholdData.get(s);var home=homes.create("industry household",dim,site.id(),people.getFirst().getCharacterId(),h.absolutePos(new BlockPos(20,1,9)),now);
        if(home==null)throw new IllegalStateException("fixture household unavailable");
        for(int i=1;i<people.size();i++)if(!homes.addMember(home.id(),people.get(i).getCharacterId(),HouseholdRole.KIN,now))throw new IllegalStateException("fixture member rejected");
        return homes.byId(home.id());
    }
    private static Container privateStore(GameTestHelper h,NpcBase n,BlockPos p) {
        h.setBlock(p,Blocks.BARREL);var abs=h.absolutePos(p);String dim=h.getLevel().dimension().location().toString();
        h.assertTrue(NpcLifeData.get(n.getServer()).assignPrivateStorage(n.getServer(),n.getCharacterId(),dim,abs,h.getLevel().getGameTime()),"private storage configured by existing API");
        var id=ProductionContainerIds.resolve(n.getServer(),dim,abs);var owner=SubjectRef.character(n.getCharacterId());
        RightsStateData.get(n.getServer()).ensureContext(id,dim+":"+abs.asLong(),owner,owner,AccessPolicy.PRIVATE);
        FieldworkData.get(n.getServer()).bindTestStorage(n.getUUID(),new FieldworkStorage(dim,abs,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"industry-fixture"));
        return (Container)h.getLevel().getBlockEntity(abs);
    }
    private static FieldworkStorage point(GameTestHelper h,BlockPos p,SubjectRef owner) {
        h.setBlock(p,Blocks.BARREL);var abs=h.absolutePos(p);String dim=h.getLevel().dimension().location().toString();
        var id=ProductionContainerIds.resolve(h.getLevel().getServer(),dim,abs);
        RightsStateData.get(h.getLevel().getServer()).ensureContext(id,dim+":"+abs.asLong(),owner,owner,AccessPolicy.PRIVATE);
        var f=new FieldworkStorage(dim,abs,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"industry-fixture");
        SupplyFlowData.get(h.getLevel().getServer()).register(f);return f;
    }
    private static int count(Container c,Item item) { int q=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))q+=c.getItem(i).getCount();return q; }
    private static Container box(GameTestHelper h,FieldworkStorage p) { return SupplyFlowService.container(h.getLevel().getServer(),p); }
    private static void trace(GameTestHelper h,String name,List<NpcBase> people,String detail) {
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("INDUSTRY_RESULT name={} tick={} actors={} {}",name,h.getLevel().getGameTime(),people.stream().map(n->n.getCharacterId()+"/"+n.getUUID()).toList(),detail);
    }
    private static void mixed(GameTestHelper h) {
        floor(h);var farmer=npc(h,NpcProfession.FARMER,new BlockPos(2,1,2));farmer.setFarmMode(FarmMode.CROP);
        var miner=npc(h,NpcProfession.WORKER,new BlockPos(2,1,6));var logger=npc(h,NpcProfession.WORKER,new BlockPos(2,1,10));
        var people=List.of(farmer,miner,logger);var home=family(h,people);
        farmer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_HOE));farmer.getPersonalInventory().setItem(0,new ItemStack(Items.CARROT,16));
        miner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));logger.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_AXE));
        for(int x=5;x<=6;x++) {
            h.setBlock(x,0,2,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
            h.setBlock(x,1,2,Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,7));
            h.setBlock(x,1,6,Blocks.STONE);h.setBlock(x,1,10,Blocks.OAK_LOG);
        }
        var crop=privateStore(h,farmer,new BlockPos(9,1,2));var stone=privateStore(h,miner,new BlockPos(9,1,6));var wood=privateStore(h,logger,new BlockPos(9,1,10));
        farmer.setWorkZone(h.absolutePos(new BlockPos(5,1,2)),h.absolutePos(new BlockPos(6,1,2)));
        miner.setWorkZone(h.absolutePos(new BlockPos(5,1,6)),h.absolutePos(new BlockPos(6,1,6)));
        logger.setWorkZone(h.absolutePos(new BlockPos(5,1,10)),h.absolutePos(new BlockPos(6,1,10)));
        var inputs=IndustryWorldObservation.blocks(miner,new StoneIndustryAdapter(),home.id(),home.settlementId(),"two-stones",2,
                List.of(h.absolutePos(new BlockPos(5,1,6)),h.absolutePos(new BlockPos(6,1,6))),
                Set.of(h.absolutePos(new BlockPos(5,1,6)),h.absolutePos(new BlockPos(6,1,6))),true,true,RightsStateData.get(h.getLevel().getServer()));
        h.assertTrue(new StoneIndustryAdapter().assess(inputs,IndustryContract.Mode.CONTROLLED_NEAR).executableBatches()==2,"read-only view sees two original authorized targets");
        h.succeedWhen(()->{
            var work=FieldworkData.get(h.getLevel().getServer());
            h.assertTrue(count(stone,Items.COBBLESTONE)==2 && count(wood,Items.OAK_LOG)==2,"two real stones and logs delivered");
            h.assertTrue(count(crop,Items.CARROT)>0,"actual carrots deposited");
            for(int x=5;x<=6;x++)h.assertTrue(h.getBlockState(new BlockPos(x,1,2)).is(Blocks.CARROTS)
                    && h.getBlockState(new BlockPos(x,1,2)).getValue(CropBlock.AGE)==0,"original crop cells replanted");
            h.assertTrue(work.receiptsFor(miner.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.MINE).count()==2,"exactly two original mining effects");
            h.assertTrue(work.receiptsFor(logger.getUUID()).stream().filter(r->r.action().operation()==FieldworkAction.Operation.CHOP).count()==2,"exactly two original chopping effects");
            h.assertTrue(miner.getMainHandItem().getDamageValue()==2 && logger.getMainHandItem().getDamageValue()==2,"real tool wear");
            var exhausted=IndustryWorldObservation.blocks(miner,new StoneIndustryAdapter(),home.id(),home.settlementId(),"two-stones",2,
                    List.of(h.absolutePos(new BlockPos(5,1,6)),h.absolutePos(new BlockPos(6,1,6))),
                    Set.of(h.absolutePos(new BlockPos(5,1,6)),h.absolutePos(new BlockPos(6,1,6))),true,true,RightsStateData.get(h.getLevel().getServer()));
            h.assertTrue(new StoneIndustryAdapter().assess(exhausted,IndustryContract.Mode.CONTROLLED_NEAR).state()==IndustryContract.State.BLOCKED,"exhaustion is observed, never regenerated");
            trace(h,"mixed_blocks",people,"stone=2 logs=2 crop="+count(crop,Items.CARROT)+" replanted=2 finite-initial-inputs=true");
        });
    }
    private static void shipments(GameTestHelper h) {
        floor(h);var courier=npc(h,NpcProfession.COURIER,new BlockPos(3,1,5));family(h,List.of(courier));
        var owner=SubjectRef.character(courier.getCharacterId());var source=point(h,new BlockPos(4,1,5),owner);
        var a=point(h,new BlockPos(14,1,4),owner);var b=point(h,new BlockPos(14,1,8),owner);
        box(h,source).setItem(0,new ItemStack(Items.COAL,6));var server=courier.getServer();long now=h.getLevel().getGameTime();
        SupplyFlowData.get(server).reveal(owner,source.containerId());
        var one=SupplyFlowService.request(server,owner,"industry:first",a,new ItemFingerprint("minecraft:coal",0),3,now,now+12000);
        var two=SupplyFlowService.request(server,owner,"industry:second",b,new ItemFingerprint("minecraft:coal",0),3,now,now+12000);
        SupplyFlowService.match(server,now);
        h.succeedWhen(()->{
            h.assertTrue(NeedRequestData.get(server).request(one.id()).status()==NeedRequestStatus.DELIVERED
                    && NeedRequestData.get(server).request(two.id()).status()==NeedRequestStatus.DELIVERED,"both original obligations really delivered");
            h.assertTrue(count(box(h,a),Items.COAL)==3 && count(box(h,b),Items.COAL)==3 && count(box(h,source),Items.COAL)==0,"six physical units conserved");
            h.assertTrue(count(courier.getTransportInventory(),Items.COAL)==0,"no double counted cargo");
            trace(h,"two_shipments",List.of(courier),"needs="+one.id()+","+two.id()+" source=0 targetA=3 targetB=3 cargo=0");
        });
    }
    private static void workshop(GameTestHelper h) {
        floor(h);var smith=npc(h,NpcProfession.BLACKSMITH,new BlockPos(12,1,5));var courier=npc(h,NpcProfession.COURIER,new BlockPos(3,1,5));family(h,List.of(smith,courier));
        var server=smith.getServer();long now=h.getLevel().getGameTime();NpcLifeData.get(server).onRecruited(courier,smith.getCharacterId(),now);
        var owner=SubjectRef.character(smith.getCharacterId());var source=point(h,new BlockPos(4,1,5),owner);
        var a=point(h,new BlockPos(14,1,4),owner);var b=point(h,new BlockPos(17,1,8),owner);h.setBlock(12,1,6,Blocks.ANVIL);
        box(h,source).setItem(0,new ItemStack(Items.IRON_INGOT,6));box(h,source).setItem(1,new ItemStack(Items.COAL,2));
        SupplyFlowData.get(server).reveal(owner,source.containerId());
        var one=SupplyFlowService.request(server,owner,"industry:tool-first",a,new ItemFingerprint("minecraft:iron_pickaxe",0),1,now,now+24000);
        UUID[] second={null};SupplyFlowService.match(server,now);
        h.onEachTick(()->{
            if(second[0]==null && NeedRequestData.get(server).request(one.id()).status()==NeedRequestStatus.DELIVERED) {
                long tick=h.getLevel().getGameTime();second[0]=SupplyFlowService.request(server,owner,"industry:tool-second",b,new ItemFingerprint("minecraft:iron_pickaxe",0),1,tick,tick+24000).id();
            }
        });
        h.succeedWhen(()->{
            h.assertTrue(second[0]!=null && NeedRequestData.get(server).request(second[0]).status()==NeedRequestStatus.DELIVERED,"second normal business request fulfilled");
            h.assertTrue(count(box(h,a),Items.IRON_PICKAXE)==1 && count(box(h,b),Items.IRON_PICKAXE)==1,"two real products delivered");
            h.assertTrue(count(box(h,source),Items.IRON_INGOT)==0 && count(box(h,source),Items.COAL)==0,"finite six iron and two recipe coal consumed");
            long batches=ProductionBatchData.get(server).batches().stream().filter(p->p.workerId().equals(smith.getCharacterId()) && p.status()==ProductionBatchStatus.COMPLETED).count();
            h.assertTrue(batches==2,"two distinct normally worked batches, not replayed success");
            trace(h,"two_workshop_orders",List.of(smith,courier),"needs="+one.id()+","+second[0]+" batches="+batches+" iron=0 recipeCoal=0 products=2");
        });
    }
    private static void foreign(GameTestHelper h) {
        floor(h);var courier=npc(h,NpcProfession.COURIER,new BlockPos(3,1,5));var home=family(h,List.of(courier));
        var owner=SubjectRef.character(courier.getCharacterId());var stranger=SubjectRef.character(UUID.randomUUID());
        var source=point(h,new BlockPos(4,1,5),stranger);var target=point(h,new BlockPos(14,1,5),owner);
        box(h,source).setItem(0,new ItemStack(Items.COAL,3));long now=h.getLevel().getGameTime();var server=courier.getServer();
        courier.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));
        var rights=RightsStateData.get(server);var bag=ContainerIds.npcPersonal(courier.getCharacterId());
        rights.putClaim(new AssetClaim(UUID.randomUUID(),bag,courier.getPersonalInventory().getContainerSize(),
                ItemFingerprint.of(courier.getMainHandItem()),1,stranger,owner,Set.of(),Set.of(),"fixture borrowed tool, no USE",0));
        var toolCheck=IndustryWorldObservation.blocks(courier,new StoneIndustryAdapter(),home.id(),home.settlementId(),"foreign-tool",1,List.of(),Set.of(),true,true,rights);
        h.assertTrue(!toolCheck.toolAvailable(),"custody of a foreign pickaxe never becomes USE authority");
        SupplyFlowData.get(server).reveal(owner,source.containerId());
        var need=SupplyFlowService.request(server,owner,"industry:foreign",target,new ItemFingerprint("minecraft:coal",0),3,now,now+12000);SupplyFlowService.match(server,now);
        var observation=new IndustryContract.Demand(need.id().toString(),courier.getCharacterId(),courier.getUUID(),home.id(),home.settlementId(),now,3,
                Map.of("source:disposable_unit",new IndustryContract.Stock(3,0,0,0,true,false)),true,true,true,true);
        h.assertTrue(new IndustrySupplyAdapter().assess(observation,IndustryContract.Mode.CONTROLLED_NEAR).causes().contains(IndustryContract.Cause.NO_PERMISSION),"knowledge-only offer rejected");
        h.runAtTickTime(100,()->{
            h.assertTrue(count(box(h,source),Items.COAL)==3 && count(box(h,target),Items.COAL)==0 && count(courier.getTransportInventory(),Items.COAL)==0,"foreign title and physical goods unchanged");
            h.assertTrue(NeedRequestData.get(server).request(need.id()).reserved()==0,"no unauthorized promise");
            trace(h,"foreign_stock_closed",List.of(courier),"need="+need.id()+" foreign=3 target=0 cargo=0");h.succeed();
        });
    }
}
