package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

/** A bounded design inside the registered v5 parcel, not a general town planner. */
public final class GrowthLayout {
    public static final ResourceLocation COORDINATED=ResourceLocation.parse("sagadyssey:vilg_2_coordinated_v6");
    public static final ResourceLocation TEMPLATE=ResourceLocation.parse("sagadyssey:vilg_2_growth_v5");
    public static final BlockPos PEN_GATE=new BlockPos(7,1,18), BED_HEAD=new BlockPos(16,1,15);
    public static final int HERD_LIMIT=4, FOOD_RESERVE=64, BUILD_LABOR=40;
    private GrowthLayout() {}
    public static boolean supports(NaturalVillageBuilding b){return TEMPLATE.toString().equals(b.templateId())||COORDINATED.toString().equals(b.templateId())||SettlementDevelopmentLayout.supports(b);}
    public static List<BlockPos> extension(){
        var cells=new ArrayList<BlockPos>();
        for(int x=15;x<=21;x+=2)for(int z=4;z<=8;z+=2)cells.add(new BlockPos(x,1,z));
        return List.copyOf(cells);
    }
    public static boolean quarry(NaturalVillageBuilding b,BlockPos pos){
        for(var p:BlockPos.betweenClosed(new BlockPos(21,1,15),new BlockPos(24,3,19)))
            if(NaturalVillageLayout.at(b,p).equals(pos))return true;
        return false;
    }
    public static boolean timber(NaturalVillageBuilding b,BlockPos pos){
        for(int y=1;y<=3;y++)if(NaturalVillageLayout.at(b,new BlockPos(18,y,17)).equals(pos))return true;
        return false;
    }
    public record Placement(Item material,List<BlockPos> positions,List<BlockState> states) {}
    public static List<Placement> house(NaturalVillageBuilding b){return house(b,false);}
    public static List<Placement> house(NaturalVillageBuilding b,boolean wood){
        var result=new ArrayList<Placement>();
        for(int y=1;y<=2;y++)for(int x=14;x<=18;x++)for(int z=12;z<=16;z++){
            if(x!=14&&x!=18&&z!=12&&z!=16||x==16&&z==12)continue;
            result.add(one(b,new BlockPos(x,y,z),Blocks.COBBLESTONE.defaultBlockState(),Items.COBBLESTONE));
        }
        for(int x=14;x<=18;x++)for(int z=12;z<=16;z++)
            result.add(one(b,new BlockPos(x,3,z),Blocks.COBBLESTONE.defaultBlockState(),Items.COBBLESTONE));
        var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.NORTH).rotate(b.rotation());
        result.add(new Placement(Items.OAK_DOOR,List.of(at(b,16,1,12),at(b,16,2,12)),
                List.of(door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER))));
        var bed=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).rotate(b.rotation());
        result.add(new Placement(Items.WHITE_BED,List.of(at(b,16,1,14),at(b,16,1,15)),
                List.of(bed.setValue(BedBlock.PART,BedPart.FOOT),bed.setValue(BedBlock.PART,BedPart.HEAD))));
        if(wood)for(int i=0;i<55;i++){var p=result.get(i);result.set(i,new Placement(Items.OAK_PLANKS,p.positions(),List.of(Blocks.OAK_PLANKS.defaultBlockState())));}
        return List.copyOf(result);
    }
    private static BlockPos at(NaturalVillageBuilding b,int x,int y,int z){return NaturalVillageLayout.at(b,new BlockPos(x,y,z));}
    private static Placement one(NaturalVillageBuilding b,BlockPos p,BlockState state,Item item){
        return new Placement(item,List.of(NaturalVillageLayout.at(b,p)),List.of(state.rotate(b.rotation())));
    }
}
