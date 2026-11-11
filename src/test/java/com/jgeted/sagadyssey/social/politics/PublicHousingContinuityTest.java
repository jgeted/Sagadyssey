package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PublicHousingContinuityTest {
    private static CompoundTag input(){
        var village=new CompoundTag();village.putString("Dimension","minecraft:overworld");
        village.putString("Structure","sagadyssey:vilg_2");village.putLong("Start",0);village.putLong("Center",0);village.putLong("Origin",0);
        village.putString("Template","sagadyssey:vilg_2_wood_v2");village.putString("Rotation","NONE");
        var p=new CompoundTag();p.store("Id", UUIDUtil.CODEC,UUID.randomUUID());p.store("ApprovedBy", UUIDUtil.CODEC,UUID.randomUUID());
        p.store("Beneficiary", UUIDUtil.CODEC,UUID.randomUUID());p.store("FacilityOwner", UUIDUtil.CODEC,NaturalVillageLifeData.decode(village).settlementId());
        p.putString("Stage","BED");p.putInt("Work",70);p.putInt("LaborTicks",270);p.putLong("LastWorkAt",777);
        var row=new CompoundTag();row.put("Village",village);row.put("Project",p);var sites=new ListTag();sites.add(row);
        var root=new CompoundTag();root.putInt("Schema",1);root.put("Sites",sites);root.put("Decisions",new ListTag());return root;
    }
    private static CompoundTag project(CompoundTag t){return MinecraftApi.listOfType(t, "Sites",Tag.TAG_COMPOUND).getCompoundOrEmpty(0).getCompoundOrEmpty("Project");}
    @Test void pendingLaborAndOriginalBeneficiarySurviveTogether(){
        var root=input();var p=project(root).copy();
        assertEquals(p,NaturalPolityData.load(root,null).sites().getFirst().getCompoundOrEmpty("Project"));
    }
    @Test void interruptedCommitRetainsWorkAndOriginalOwnerForReview(){
        var root=input();var p=project(root);p.putBoolean("Submitting",true);
        var saved=NaturalPolityData.load(root,null).sites().getFirst().getCompoundOrEmpty("Project");
        assertEquals("REVIEW",saved.getStringOr("Stage", ""));assertEquals(p.read("Id", UUIDUtil.CODEC).orElseThrow(),saved.read("Id", UUIDUtil.CODEC).orElseThrow());
        assertEquals(p.read("FacilityOwner", UUIDUtil.CODEC).orElseThrow(),saved.read("FacilityOwner", UUIDUtil.CODEC).orElseThrow());assertEquals(270,saved.getIntOr("LaborTicks", 0));
    }
    @Test void foreignOwnerCannotBecomeLocalPublicFacilityByLoading(){
        var root=input();project(root).store("FacilityOwner", UUIDUtil.CODEC,UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->NaturalPolityData.load(root,null));
    }
    @Test void malformedBeneficiaryAndImpossibleLaborFailClosed(){
        var root=input();project(root).putString("Beneficiary","untracked person");
        assertThrows(IllegalArgumentException.class,()->NaturalPolityData.load(root,null));
        var other=input();project(other).putInt("LaborTicks",601);
        assertThrows(IllegalArgumentException.class,()->NaturalPolityData.load(other,null));
    }
    @Test void legacyProjectsRemainReadableWithoutInventingAssignment(){
        var root=input();project(root).remove("Beneficiary");project(root).remove("FacilityOwner");
        var p=NaturalPolityData.load(root,null).sites().getFirst().getCompoundOrEmpty("Project");
        assertFalse(p.contains("Beneficiary"));assertEquals("BED",p.getStringOr("Stage", ""));
    }
}
