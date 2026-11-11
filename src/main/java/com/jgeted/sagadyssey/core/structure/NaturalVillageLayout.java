package com.jgeted.sagadyssey.core.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.List;

/** Versioned physical layout of the supported cottage and crop yard. All positions are template local. */
public final class NaturalVillageLayout {
    public static final Identifier LIVING = Identifier.parse("sagadyssey:vilg_2_living_v1");
    public static final Identifier FARM_V2=Identifier.parse("sagadyssey:vilg_2_farm_v2"), FARM_V3=Identifier.parse("sagadyssey:vilg_2_farm_v3"), WOOD_V2=Identifier.parse("sagadyssey:vilg_2_wood_v2");
    public static final Identifier HOMESTEAD_V4=Identifier.parse("sagadyssey:vilg_2_homestead_v4");
    public static final BlockPos ARRIVAL_PANTRY=new BlockPos(12,1,10);
    public static boolean chartered(NaturalVillageBuilding b){return FARM_V2.toString().equals(b.templateId())||autonomousFarm(b)||WOOD_V2.toString().equals(b.templateId());}
    public static boolean autonomousFarm(NaturalVillageBuilding b){return FARM_V3.toString().equals(b.templateId())||starterHomestead(b);}
    public static boolean starterHomestead(NaturalVillageBuilding b){return HOMESTEAD_V4.toString().equals(b.templateId())||com.jgeted.sagadyssey.social.growth.GrowthLayout.supports(b);}
    /** The starter has a declared housing deficit; existing layouts still require all three beds. */
    public static List<BlockPos> initialBeds(NaturalVillageBuilding b){return starterHomestead(b)?BEDS.subList(0,2):BEDS;}
    public static BlockPos initialBed(NaturalVillageBuilding b,int resident){
        return resident<initialBeds(b).size()?at(b,initialBeds(b).get(resident)):null;
    }
    public static boolean woodcamp(NaturalVillageBuilding b){return WOOD_V2.toString().equals(b.templateId());}
    public static List<BlockPos> stores(NaturalVillageBuilding b){return chartered(b)?List.of(RAW,PANTRY,WOOD,PUBLIC,ARRIVAL_PANTRY):List.of(RAW,PANTRY,WOOD,PUBLIC);}
    public static String storageRole(int i){return i==1||i==4?"household_storage":i==3?"natural_public":"private_storage";}
    public static List<BlockPos> spareBeds(){return List.of(new BlockPos(2,1,17),new BlockPos(4,1,17),new BlockPos(6,1,17));}

    public static final BlockPos RAW = new BlockPos(10,1,5), PANTRY = new BlockPos(10,1,10),
            WOOD = new BlockPos(10,1,17), PUBLIC = new BlockPos(10,1,19);
    public static final BlockPos INDUSTRY_BENCH = new BlockPos(11,1,16);
    public static final BlockPos INDUSTRY_FURNACE = new BlockPos(9,1,16);
    public static final List<BlockPos> SPAWNS = List.of(new BlockPos(11,1,4),new BlockPos(9,1,10),new BlockPos(12,1,17));
    public static final List<BlockPos> BEDS = List.of(new BlockPos(2,1,13),new BlockPos(4,1,13),new BlockPos(6,1,13));
    private NaturalVillageLayout() {}
    public static BlockPos at(NaturalVillageBuilding b, BlockPos relative) {
        return StructureTemplate.calculateRelativePosition(new StructurePlaceSettings().setRotation(b.rotation()),relative).offset(b.buildingOrigin());
    }
    public static boolean supportedTemplate(String id) {
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.INDUSTRY_TEMPLATE.toString().equals(id))return true;
        return NaturalVillageBuilding.SUPPORTED.toString().equals(id)||LIVING.toString().equals(id)||FARM_V2.toString().equals(id)||FARM_V3.toString().equals(id)||HOMESTEAD_V4.toString().equals(id)||WOOD_V2.toString().equals(id)||com.jgeted.sagadyssey.social.growth.GrowthLayout.TEMPLATE.toString().equals(id)||com.jgeted.sagadyssey.social.growth.GrowthLayout.COORDINATED.toString().equals(id)||com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.TEMPLATE.toString().equals(id)||com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.LEGACY_TEMPLATE.toString().equals(id);
    }
    public static java.util.List<BlockPos> crops() {
        var list=new java.util.ArrayList<BlockPos>();
        for(int x=14;x<=20;x+=2)for(int z=4;z<=8;z+=2)list.add(new BlockPos(x,1,z));
        return List.copyOf(list);
    }
}
