package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.*;

/** Bounded repair of one supported charter's housing capacity, never a world/land scan. */
public final class PublicHousingConditions {
    private PublicHousingConditions() {}
    public static BlockPos foot(NaturalVillageBuilding b) { return NaturalVillageLayout.at(b,new BlockPos(8,1,18)); }
    public static BlockPos head(NaturalVillageBuilding b) { return NaturalVillageLayout.at(b,new BlockPos(8,1,17)); }
    private static boolean loaded(ServerLevel level,BlockPos p) {
        return p!=null&&level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null;
    }
    public static boolean validBed(ServerLevel level,BlockPos p) {
        if(!loaded(level,p))return false;
        var state=level.getBlockState(p);
        if(!(state.getBlock() instanceof BedBlock))return false;
        var other=p.relative(state.getValue(BedBlock.PART)==BedPart.HEAD
                ?state.getValue(BedBlock.FACING).getOpposite():state.getValue(BedBlock.FACING));
        if(!loaded(level,other))return false;
        var pair=level.getBlockState(other);
        return pair.getBlock()==state.getBlock()&&pair.getValue(BedBlock.FACING)==state.getValue(BedBlock.FACING)
                &&pair.getValue(BedBlock.PART)!=state.getValue(BedBlock.PART);
    }
    public static boolean registered(MinecraftServer server,NaturalVillageBuilding b) {
        var parent=SettlementRegistryData.get(server).building(b.buildingKey());
        return NaturalVillageLayout.chartered(b)&&parent!=null&&b.settlementId().equals(parent.settlementId());
    }
    public static UUID beneficiary(MinecraftServer server,NaturalVillageBuilding b) {
        var level=com.jgeted.sagadyssey.social.needs.SupplyFlowService.level(server,b.dimension());
        if(level==null||!registered(server,b))return null;
        var life=NpcLifeData.get(server);
        var people=NaturalPolityData.population(server,b);
        // A temporarily unavailable bed is not confirmed lost capacity. Do not force-load it.
        for(var id:people){var r=life.record(id);if(r==null||!b.dimension().equals(r.dimension())
                ||r.bedPosition()!=null&&!loaded(level,r.bedPosition()))return null;}
        var known=new ArrayList<>(NaturalVillageLayout.BEDS);
        known.addAll(NaturalVillageLayout.spareBeds());known.add(new BlockPos(8,1,17));
        for(var local:known){var p=NaturalVillageLayout.at(b,local);
            if(!loaded(level,p))return null;
            if(validBed(level,p)&&!life.bedAssigned(NpcResidenceFixtures.normalizeBedHead(level,p),b.dimension())
                    &&!life.bedAssigned(p,b.dimension()))return null; // use existing free capacity first
        }
        return people.stream().sorted().filter(id->!validBed(level,life.record(id).bedPosition())).findFirst().orElse(null);
    }
    public static boolean canPlace(MinecraftServer server,NaturalVillageBuilding b,UUID person) {
        var level=com.jgeted.sagadyssey.social.needs.SupplyFlowService.level(server,b.dimension());
        if(level==null||!registered(server,b)||!NaturalPolityData.population(server,b).contains(person))return false;
        var life=NpcLifeData.get(server);var r=life.record(person);
        if(r==null||validBed(level,r.bedPosition()))return false;
        if(life.records().stream().anyMatch(other->!other.npcId().equals(person)
                &&b.dimension().equals(other.dimension())&&(head(b).equals(other.bedPosition())||foot(b).equals(other.bedPosition()))))return false;
        return loaded(level,foot(b))&&loaded(level,head(b));
    }
}
