package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.industry.workshop.WoodMaterialRecipes;
import com.jgeted.sagadyssey.social.industry.workshop.WorkshopTask;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
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

/** Native recipe/rights component checks; no NPC dispatch, production completion, or restart claim. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class HouseholdBedPlanBoundaryGameTests {
    private static boolean selected(){return "household-bed-plan-boundaries".equals(System.getProperty("sagadyssey.playability.phase",""));}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return selected()?List.of(new TestFunction("household_bed_plan_boundaries","household_bed_plan_boundaries","sagadyssey_playability_fix:empty",200,0,true,HouseholdBedPlanBoundaryGameTests::run)):List.of();
    }
    @SubscribeEvent public static void boot(ServerStartedEvent e){
        if(!selected()||!(e.getServer() instanceof GameTestServer))return;
        try{var l=e.getServer().overworld();l.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_playability_fix:empty"))
                .load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception ex){throw new IllegalStateException("component test template unavailable",ex);}
    }
    private record Case(String name,Item plank,int plankCount,Item wool,int woolCount,ItemFingerprint output,boolean otherOwner,int reservation,boolean allowed){}
    private static final ItemFingerprint WHITE=new ItemFingerprint("minecraft:white_bed",0);
    private static final ItemFingerprint RED=new ItemFingerprint("minecraft:red_bed",0);
    private static void run(GameTestHelper h){
        var level=h.getLevel();var server=level.getServer();long now=level.getGameTime();
        // Only a component actor object: never add an entity, register a character, or execute work.
        var actor=new NpcBase(NpcEntityTypes.NPC_BASE.get(),level);
        var principal=SubjectRef.character(actor.getCharacterId());var rights=RightsStateData.get(server);
        var cases=List.of(
                new Case("white_wool_zero",Items.OAK_PLANKS,3,Items.WHITE_WOOL,0,WHITE,false,0,true),
                new Case("white_wool_one",Items.OAK_PLANKS,3,Items.WHITE_WOOL,1,WHITE,false,0,true),
                new Case("white_wool_two",Items.OAK_PLANKS,3,Items.WHITE_WOOL,2,WHITE,false,0,true),
                new Case("white_full_original_recipe",Items.OAK_PLANKS,3,Items.WHITE_WOOL,3,WHITE,false,0,true),
                new Case("planks_insufficient",Items.OAK_PLANKS,2,Items.WHITE_WOOL,0,WHITE,false,0,false),
                new Case("planks_legally_reserved",Items.OAK_PLANKS,3,Items.WHITE_WOOL,0,WHITE,false,1,false),
                new Case("planks_other_private_owner",Items.OAK_PLANKS,3,Items.WHITE_WOOL,0,WHITE,true,0,false),
                new Case("red_missing_not_extended",Items.OAK_PLANKS,3,Items.RED_WOOL,0,RED,false,0,false),
                new Case("red_full_original_recipe",Items.OAK_PLANKS,3,Items.RED_WOOL,3,RED,false,0,true),
                new Case("output_components_not_extended",Items.OAK_PLANKS,3,Items.WHITE_WOOL,0,new ItemFingerprint("minecraft:white_bed",9),false,0,false),
                new Case("birch_actual_native_planks",Items.BIRCH_PLANKS,3,Items.WHITE_WOOL,0,WHITE,false,0,true));
        int index=0;
        for(var test:cases){
            var pos=h.absolutePos(new BlockPos(2+(index%6)*2,1,2+(index/6)*2));index++;
            level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            var box=(Container)level.getBlockEntity(pos);
            box.setItem(0,new ItemStack(test.plank(),test.plankCount()));
            if(test.woolCount()>0)box.setItem(1,new ItemStack(test.wool(),test.woolCount()));
            var owner=test.otherOwner()?SubjectRef.character(UUID.randomUUID()):principal;
            var id=UUID.randomUUID();String dimension=level.dimension().identifier().toString();
            rights.ensureContext(id,dimension+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
            var stock=new FieldworkStorage(dimension,pos,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"component-native-bed-plan");
            if(test.reservation()>0){
                var fp=ItemFingerprint.of(new ItemStack(test.plank()));
                h.assertTrue(rights.reserve(new AssetReservation(UUID.randomUUID(),id,fp,test.reservation(),owner,"component-existing-obligation:"+index,"existing competing material obligation",now,now+1000),test.plankCount(),now),"fixture existing reservation must succeed");
            }
            var beforeItems=items(box,server.registryAccess());var beforeRights=rights.save(new CompoundTag(),server.registryAccess());
            WoodMaterialRecipes.Spec first=null;
            for(int replay=0;replay<2;replay++){
                WoodMaterialRecipes.Spec spec=null;boolean denied=false;
                try{spec=WoodMaterialRecipes.select(actor,stock,test.output(),now);}
                catch(IllegalArgumentException expectedMissing){denied=true;}
                h.assertTrue(denied!=test.allowed(),test.name()+": concrete native plan acceptance");
                if(test.allowed()){
                    var expectedWool=test.output().equals(RED)?new ItemFingerprint("minecraft:red_wool",0):new ItemFingerprint("minecraft:white_wool",0);
                    h.assertTrue(spec!=null&&spec.kind().equals(WorkshopTask.BED)&&spec.work()==400
                            &&spec.inputs().equals(List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(test.plank())),3),new ProductionMaterial(expectedWool,3)))
                            &&spec.output().equals(new ProductionMaterial(test.output(),1)),test.name()+": actual named native recipe, no material/worker/result creation");
                    if(first==null)first=spec;else h.assertTrue(first.equals(spec),test.name()+": identical read-only repeated planning");
                }
                h.assertTrue(beforeItems.equals(items(box,server.registryAccess())),test.name()+": selection must not modify physical inputs");
                h.assertTrue(beforeRights.equals(rights.save(new CompoundTag(),server.registryAccess())),test.name()+": selection must not grant permission, alter title or release reservations");
            }
            Sagadyssey.LOGGER.info("HBED_COMPONENT case={} result=PASS plank={} plankCount={} wool={} woolCount={} output={} otherOwner={} reserved={} expectedPlan={} repeat=2 componentOnly=true unchangedInventory=true unchangedRights=true",test.name(),test.plank(),test.plankCount(),test.wool(),test.woolCount(),test.output(),test.otherOwner(),test.reservation(),test.allowed());
        }
        Sagadyssey.LOGGER.info("HBED_COMPONENT_ACCEPT cases={} nativeRecipes=true realContainers=true noSpawnedActor=true normalDispatch=false noGameRestartClaim=true",cases.size());h.succeed();
    }
    private static ListTag items(Container box,net.minecraft.core.HolderLookup.Provider lookup){
        var result=new ListTag();for(int slot=0;slot<box.getContainerSize();slot++){var row=new CompoundTag();row.putInt("Slot",slot);if(!box.getItem(slot).isEmpty())row.put("Stack",com.jgeted.sagadyssey.core.MinecraftApi.saveItem(box.getItem(slot), lookup));result.add(row);}return result;
    }
}
