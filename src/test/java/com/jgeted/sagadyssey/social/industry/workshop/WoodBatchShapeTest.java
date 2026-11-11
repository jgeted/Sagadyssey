package com.jgeted.sagadyssey.social.industry.workshop;
import com.jgeted.sagadyssey.social.production.ProductionMaterial;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
/** Shape guards are independent of runtime tag/recipe validation, which has native GameTests. */
class WoodBatchShapeTest {
    private ProductionMaterial m(String id,int n){return new ProductionMaterial(new ItemFingerprint(id,0),n);}
    @Test void materialSpeciesDoesNotChangeOriginalBatchCosts(){
        assertTrue(WoodMaterialRecipes.shape(WorkshopTask.PLANKS,List.of(m("minecraft:spruce_log",3)),m("minecraft:spruce_planks",12),200));
        assertTrue(WoodMaterialRecipes.shape(WorkshopTask.BED,List.of(m("minecraft:birch_planks",3),m("minecraft:red_wool",3)),m("minecraft:red_bed",1),400));
    }
    @Test void changedYieldWorkOrInputsCannotUseTheExistingOperationIdentity(){
        assertFalse(WoodMaterialRecipes.shape(WorkshopTask.PLANKS,List.of(m("minecraft:spruce_log",2)),m("minecraft:spruce_planks",12),200));
        assertFalse(WoodMaterialRecipes.shape(WorkshopTask.STICKS,List.of(m("minecraft:birch_planks",2)),m("minecraft:stick",64),200));
        assertFalse(WoodMaterialRecipes.shape(WorkshopTask.BED,List.of(m("minecraft:birch_planks",3),m("minecraft:red_wool",3)),m("minecraft:red_bed",1),1));
    }
    @Test void componentBearingInputsAreNotSilentlyConvertedToGenericMaterials(){
        assertFalse(WoodMaterialRecipes.shape(WorkshopTask.PLANKS,List.of(new ProductionMaterial(new ItemFingerprint("minecraft:spruce_log",9),3)),m("minecraft:spruce_planks",12),200));
        assertFalse(WoodMaterialRecipes.shape("example:magic",List.of(m("minecraft:dirt",3)),m("minecraft:diamond",12),200));
    }
}
