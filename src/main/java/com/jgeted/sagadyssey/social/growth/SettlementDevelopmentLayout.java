package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import java.util.ArrayList;
import java.util.List;

/** Versioned founding land declaration. It grants no rights to unrelated parcels or old v6 worlds. */
public final class SettlementDevelopmentLayout {
    public static final ResourceLocation TEMPLATE = ResourceLocation.parse("sagadyssey:vilg_2_development_v8");
    public static final ResourceLocation LEGACY_TEMPLATE = ResourceLocation.parse("sagadyssey:vilg_2_development_v7");
    private SettlementDevelopmentLayout() { }
    public static boolean supports(NaturalVillageBuilding b) { return wide(b)||LEGACY_TEMPLATE.toString().equals(b.templateId()); }
    public static boolean wide(NaturalVillageBuilding b) { return TEMPLATE.toString().equals(b.templateId()); }
    public static int width(NaturalVillageBuilding b) { return wide(b)?6:5; }
    public static int stoneCost(NaturalVillageBuilding b) { return wide(b)?64:55; }
    public static BlockPos localLot(int index) {
        if (index < 0 || index > 1) throw new IllegalArgumentException("Only two founding housing lots declared");
        return new BlockPos(index * 7, 0, 24);
    }
    public static BlockPos at(NaturalVillageBuilding b, BlockPos p) { return NaturalVillageLayout.at(b,p); }
    public static List<BlockPos> beds(NaturalVillageBuilding b,int lot) {
        var base=localLot(lot);var out=new ArrayList<BlockPos>();
        for(int x=1;x<=3;x++)out.add(at(b,base.offset(x,1,3)));return List.copyOf(out);
    }
    public static BlockPos pantry(NaturalVillageBuilding b,int lot) { return at(b,localLot(lot).offset(1,1,1)); }
    public static BlockPos entrance(NaturalVillageBuilding b,int lot) { return at(b,localLot(lot).offset(2,1,-1)); }
    public static List<BlockPos> fields(NaturalVillageBuilding b,int lot) {
        localLot(lot);var result=new ArrayList<BlockPos>();
        for(int x=14;x<=20;x+=2)for(int z=24+lot*8;z<=28+lot*8;z+=2)result.add(at(b,new BlockPos(x,1,z)));
        return List.copyOf(result);
    }
    public static boolean field(NaturalVillageBuilding b,int lot,BlockPos position) {
        return fields(b,lot).stream().anyMatch(p->p.getX()==position.getX()&&p.getZ()==position.getZ()
                &&position.getY()>=p.getY()-1&&position.getY()<=p.getY()+3);
    }
    public static boolean timber(NaturalVillageBuilding b,BlockPos position){
        for(int z:new int[]{23,27,31,35})for(int y=1;y<=3;y++)if(at(b,new BlockPos(23,y,z)).equals(position))return true;
        return false;
    }
    public static List<GrowthLayout.Placement> house(NaturalVillageBuilding b,int lot) {
        var out=new ArrayList<GrowthLayout.Placement>();var base=localLot(lot);
        int lastX=width(b)-1;
        for(int y=1;y<=2;y++)for(int x=0;x<=lastX;x++)for(int z=0;z<=4;z++){
            if(x!=0&&x!=lastX&&z!=0&&z!=4||x==2&&z==0)continue;
            out.add(new GrowthLayout.Placement(Items.COBBLESTONE,List.of(at(b,base.offset(x,y,z))),List.of(Blocks.COBBLESTONE.defaultBlockState())));
        }
        for(int x=0;x<=lastX;x++)for(int z=0;z<=4;z++)out.add(new GrowthLayout.Placement(Items.COBBLESTONE,
                List.of(at(b,base.offset(x,3,z))),List.of(Blocks.COBBLESTONE.defaultBlockState())));
        var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.NORTH).rotate(b.rotation());
        out.add(new GrowthLayout.Placement(Items.OAK_DOOR,List.of(at(b,base.offset(2,1,0)),at(b,base.offset(2,2,0))),
                List.of(door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER))));
        var bed=Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).rotate(b.rotation());
        for(int x=1;x<=3;x++)out.add(new GrowthLayout.Placement(Items.WHITE_BED,
                List.of(at(b,base.offset(x,1,2)),at(b,base.offset(x,1,3))),
                List.of(bed.setValue(BedBlock.PART,BedPart.FOOT),bed.setValue(BedBlock.PART,BedPart.HEAD))));
        out.add(new GrowthLayout.Placement(Items.BARREL,List.of(pantry(b,lot)),List.of(Blocks.BARREL.defaultBlockState())));
        return List.copyOf(out);
    }
}
