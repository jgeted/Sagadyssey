package com.jgeted.sagadyssey.social.politics;

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
        var p=new CompoundTag();p.putUUID("Id",UUID.randomUUID());p.putUUID("ApprovedBy",UUID.randomUUID());
        p.putUUID("Beneficiary",UUID.randomUUID());p.putUUID("FacilityOwner",NaturalVillageLifeData.decode(village).settlementId());
        p.putString("Stage","BED");p.putInt("Work",70);p.putInt("LaborTicks",270);p.putLong("LastWorkAt",777);
        var row=new CompoundTag();row.put("Village",village);row.put("Project",p);var sites=new ListTag();sites.add(row);
        var root=new CompoundTag();root.putInt("Schema",1);root.put("Sites",sites);root.put("Decisions",new ListTag());return root;
    }
    private static CompoundTag project(CompoundTag t){return t.getList("Sites",Tag.TAG_COMPOUND).getCompound(0).getCompound("Project");}
    @Test void pendingLaborAndOriginalBeneficiarySurviveTogether(){
        var root=input();var p=project(root).copy();
        assertEquals(p,NaturalPolityData.load(root,null).sites().getFirst().getCompound("Project"));
    }
    @Test void interruptedCommitRetainsWorkAndOriginalOwnerForReview(){
        var root=input();var p=project(root);p.putBoolean("Submitting",true);
        var saved=NaturalPolityData.load(root,null).sites().getFirst().getCompound("Project");
        assertEquals("REVIEW",saved.getString("Stage"));assertEquals(p.getUUID("Id"),saved.getUUID("Id"));
        assertEquals(p.getUUID("FacilityOwner"),saved.getUUID("FacilityOwner"));assertEquals(270,saved.getInt("LaborTicks"));
    }
    @Test void foreignOwnerCannotBecomeLocalPublicFacilityByLoading(){
        var root=input();project(root).putUUID("FacilityOwner",UUID.randomUUID());
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
        var p=NaturalPolityData.load(root,null).sites().getFirst().getCompound("Project");
        assertFalse(p.contains("Beneficiary"));assertEquals("BED",p.getString("Stage"));
    }
}
