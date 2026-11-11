package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.workshop.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Components only: no spawned worker, no normal dispatch, no manufactured result. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class HouseholdMultiMaterialBoundaryGameTests {
    private static final ItemFingerprint WHITE=new ItemFingerprint("minecraft:white_bed",0);
    private static final ItemFingerprint WOOL=new ItemFingerprint("minecraft:white_wool",0);
    private static boolean selected(){return "household-multi-material-boundaries".equals(System.getProperty("sagadyssey.playability.phase",""));}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return selected()?List.of(new TestFunction("household_multi_material_boundaries","household_multi_material_boundaries","sagadyssey_playability_fix:empty",200,0,true,HouseholdMultiMaterialBoundaryGameTests::run)):List.of();
    }
    @SubscribeEvent public static void boot(ServerStartedEvent event){
        if(!selected()||!(event.getServer() instanceof GameTestServer))return;
        try{var level=event.getServer().overworld();level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_playability_fix:empty"))
                .load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception problem){throw new IllegalStateException("component template unavailable",problem);}
    }
    private record Case(String name,int logs,int reserved,boolean otherOwner,int wool,int oakBoards,ItemFingerprint output,boolean allowed,Item expectedBoard){}
    private static void run(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();long now=level.getGameTime();
        var worker=new NpcBase(NpcEntityTypes.NPC_BASE.get(),level);var principal=SubjectRef.character(worker.getCharacterId());var rights=RightsStateData.get(server);
        var cases=List.of(
                new Case("three_birch_logs_one_wool",3,0,false,1,0,WHITE,true,Items.BIRCH_PLANKS),
                new Case("three_birch_logs_zero_wool",3,0,false,0,0,WHITE,true,Items.BIRCH_PLANKS),
                new Case("two_logs_cannot_start_native_batch",2,0,false,1,0,WHITE,false,Items.BIRCH_PLANKS),
                new Case("three_logs_one_already_reserved",3,1,false,1,0,WHITE,false,Items.BIRCH_PLANKS),
                new Case("other_owner_no_grant",3,0,true,1,0,WHITE,false,Items.BIRCH_PLANKS),
                new Case("existing_legal_boards_before_log_plan",3,0,false,1,3,WHITE,true,Items.OAK_PLANKS),
                new Case("red_bed_missing_inputs_not_extended",3,0,false,0,0,new ItemFingerprint("minecraft:red_bed",0),false,Items.BIRCH_PLANKS));
        int index=0;
        for(var test:cases){
            var pos=h.absolutePos(new BlockPos(2+(index%6)*2,1,2+(index/6)*2));index++;
            level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var box=(Container)level.getBlockEntity(pos);
            box.setItem(0,new ItemStack(Items.BIRCH_LOG,test.logs()));
            if(test.wool()>0)box.setItem(1,new ItemStack(Items.WHITE_WOOL,test.wool()));
            if(test.oakBoards()>0)box.setItem(2,new ItemStack(Items.OAK_PLANKS,test.oakBoards()));
            var owner=test.otherOwner()?SubjectRef.character(UUID.randomUUID()):principal;var id=UUID.randomUUID();String dimension=level.dimension().identifier().toString();
            rights.ensureContext(id,dimension+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
            var stock=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"component-multiple-materials");
            if(test.reserved()>0)h.assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(),id,new ItemFingerprint("minecraft:birch_log",0),test.reserved(),owner,"other-original-commitment:"+index,"real competing log commitment",now,now+1000),test.logs(),now),"initial reservation accepted");
            var originalItems=items(box,server.registryAccess());var originalRights=rights.save(new CompoundTag(),server.registryAccess());WoodMaterialRecipes.Spec first=null;
            for(int replay=0;replay<2;replay++){
                WoodMaterialRecipes.Spec spec=null;boolean denied=false;
                try{spec=WoodMaterialRecipes.select(worker,stock,test.output(),now);}catch(IllegalArgumentException unavailable){denied=true;}
                h.assertTrue(denied!=test.allowed(),test.name()+": expected read-only plan decision");
                if(test.allowed()){
                    h.assertTrue(spec!=null&&spec.kind().equals(WorkshopTask.BED)&&spec.work()==400&&spec.output().equals(new ProductionMaterial(WHITE,1))
                            &&spec.inputs().equals(List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(test.expectedBoard())),3),new ProductionMaterial(WOOL,3))),test.name()+": actual native board type, unchanged bed material contract");
                    if(first==null)first=spec;else h.assertTrue(first.equals(spec),test.name()+": same decision on repeat");
                }
                h.assertTrue(originalItems.equals(items(box,server.registryAccess()))&&originalRights.equals(rights.save(new CompoundTag(),server.registryAccess())),test.name()+": planning cannot consume inputs, invent output, release competing claims or grant rights");
            }
            Sagadyssey.LOGGER.info("HMAT_COMPONENT case={} result=PASS logs={} wool={} oakBoards={} reserved={} otherOwner={} allowed={} componentOnly=true",test.name(),test.logs(),test.wool(),test.oakBoards(),test.reserved(),test.otherOwner(),test.allowed());
        }
        // A separate component scene changes available materials after a real OPEN order
        // is declared. Replaying that business must keep the bound birch recipe unchanged.
        var pos=h.absolutePos(new BlockPos(2,1,7));var table=pos.offset(1,0,0);
        level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());level.setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
        var box=(Container)level.getBlockEntity(pos);box.setItem(0,new ItemStack(Items.BIRCH_LOG,3));box.setItem(1,new ItemStack(Items.WHITE_WOOL,1));
        var id=UUID.randomUUID();String dimension=level.dimension().identifier().toString();rights.ensureContext(id,dimension+":"+pos.asLong(),principal,principal,AccessPolicy.PRIVATE);
        var stock=new FieldworkStorage(dimension,pos,id,principal,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"component-fixed-business");
        var original=WorkshopTask.requestProduct(worker,"component-bound-birch-bed",stock,table,WHITE,now);
        h.assertTrue(original.inputs().getFirst().fingerprint().equals(new ItemFingerprint("minecraft:birch_planks",0)),"first business binds concrete birch requirement");
        // Explicit component-test perturbation, never the normal-dispatch positive world.
        box.setItem(0,ItemStack.EMPTY);box.setItem(2,new ItemStack(Items.OAK_PLANKS,3));rights.bumpContext(id);
        var beforeItems=items(box,server.registryAccess());var beforeRights=rights.save(new CompoundTag(),server.registryAccess());
        var beforeOrders=ProductionOrderData.get(server).save(new CompoundTag(),server.registryAccess());var beforeNeeds=ProductionMaterialRequestData.get(server).save(new CompoundTag(),server.registryAccess());
        for(int replay=0;replay<2;replay++){
            var same=WorkshopTask.requestProduct(worker,"component-bound-birch-bed",stock,table,WHITE,now+1+replay);
            h.assertTrue(same.equals(original),"existing order must preserve original identity, batch and concrete materials when stock changes");
            h.assertTrue(beforeItems.equals(items(box,server.registryAccess()))&&beforeRights.equals(rights.save(new CompoundTag(),server.registryAccess()))
                    &&beforeOrders.equals(ProductionOrderData.get(server).save(new CompoundTag(),server.registryAccess()))
                    &&beforeNeeds.equals(ProductionMaterialRequestData.get(server).save(new CompoundTag(),server.registryAccess())),"bound replay adds no order, requirement, resources or rights");
        }
        Sagadyssey.LOGGER.info("HMAT_COMPONENT_ACCEPT cases=8 repeats=2 boundOrder={} boundBatch={} componentOnly=true normalDispatch=false noRestartClaim=true",original.id(),original.batchId());h.succeed();
    }
    private static ListTag items(Container box,net.minecraft.core.HolderLookup.Provider lookup){
        var result=new ListTag();for(int slot=0;slot<box.getContainerSize();slot++){var row=new CompoundTag();row.putInt("Slot",slot);if(!box.getItem(slot).isEmpty())row.put("Stack",com.jgeted.sagadyssey.core.MinecraftApi.saveItem(box.getItem(slot), lookup));result.add(row);}return result;
    }
}
