package com.jgeted.sagadyssey.social.industry.workshop;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkshopExistingRecipeTest {
    private ProductionMaterial material(String item,int count){return new ProductionMaterial(new ItemFingerprint("minecraft:"+item,0),count);}
    private ProductionOrder order(String recipe,List<ProductionMaterial> inputs,ProductionMaterial output,int ticks){
        var owner=SubjectRef.character(UUID.randomUUID());
        return ProductionOrder.open(UUID.randomUUID(),owner,owner,recipe,inputs,output,ticks,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,1,"existing recipe specification");
    }
    @Test void originalBedRecipeAndWorkRemainSupported(){assertTrue(WorkshopTask.recognized(order("sagadyssey:industry_growth_bed",List.of(material("oak_planks",3),material("white_wool",3)),material("white_bed",1),400)));}
    @Test void originalGrowthPlankRecipeIsExecutableThroughWorkshop(){assertTrue(WorkshopTask.recognized(order("sagadyssey:industry_growth_planks",List.of(material("oak_log",3)),material("oak_planks",12),200)));}
    @Test void originalGrowthStickRecipeIsExecutableThroughWorkshop(){assertTrue(WorkshopTask.recognized(order("sagadyssey:industry_growth_sticks",List.of(material("oak_planks",2)),material("stick",4),200)));}
    @Test void arbitraryRecipeIsNeverAdmitted(){assertFalse(WorkshopTask.recognized(order("sagadyssey:unapproved_magic",List.of(material("dirt",1)),material("diamond",64),1)));}
    @Test void BedIdentifierCannotSmuggleUnapprovedInputs(){assertFalse(WorkshopTask.recognized(order("sagadyssey:industry_growth_bed",List.of(material("dirt",1)),material("white_bed",1),400)));}
    @Test void BedIdentifierCannotBypassActualWork(){assertFalse(WorkshopTask.recognized(order("sagadyssey:industry_growth_bed",List.of(material("oak_planks",3),material("white_wool",3)),material("white_bed",1),1)));}
}
