package com.jgeted.sagadyssey.npc.appearance;

import com.jgeted.sagadyssey.social.war.domain.WarLoadoutCatalog;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NpcOutfitCatalogTest {
    @Test void everyDeclaredOutfitAndIdentityHasBothGenderResourcesForEveryPalette() {
        var catalog=NpcOutfitCatalog.bundled();int count=0;
        for(String palette:catalog.palettes())for(String gender:List.of("male","female")){
            for(var outfit:NpcMilitaryOutfit.values()){
                assertNotNull(getClass().getResource("/assets/sagadyssey/textures/entity/npc/outfits/"+palette+"/military/"+outfit.id()+"_"+gender+".png"));count++;
            }
            for(String status:List.of("commoner","freeman","burgher","knight","lord","duke","king")){
                assertNotNull(getClass().getResource("/assets/sagadyssey/textures/entity/npc/outfits/"+palette+"/civilian/"+status+"_"+gender+".png"));count++;
            }
        }
        assertEquals(342,count);
        assertEquals(8,new HashSet<>(catalog.factions().values()).size());
    }
    @Test void allFortyEightLoadoutsMapToClothingWithoutEncodingShieldOrArmorAsFakeGear() {
        var found=new HashSet<NpcMilitaryOutfit>();
        var loadouts=WarLoadoutCatalog.initial().profiles();assertEquals(48,loadouts.size());
        for(var loadout:loadouts.values())found.add(NpcMilitaryOutfit.find(loadout.mount().key()+"_"+loadout.primaryWeapon().key()).orElseThrow());
        assertEquals(12,found.size());
    }
    @Test void unknownFactionAndInvalidPreviewDoNotPickAnUnrelatedFaction() {
        var c=NpcOutfitCatalog.bundled();
        assertEquals("neutral",c.palette("custom:new_kingdom",""));
        assertEquals("neutral",c.palette(null,"does_not_exist"));
        assertEquals("madder_linen",c.palette("sagadyssey:kingdom","does_not_exist"));
        assertEquals("forest_flax",c.palette("custom:new_kingdom","forest_flax"));
        assertTrue(NpcMilitaryOutfit.find("../../invalid").isEmpty());
        assertTrue(NpcMilitaryOutfit.find("foot_spear").isPresent());
    }
    @Test void customFactionBindingIsDataDrivenAndRejectsMissingPalette() {
        var c=NpcOutfitCatalog.read(new StringReader("{\"palettes\":{\"neutral\":{},\"woad\":{}},\"factions\":{\"custom:duchy\":\"woad\"}}"));
        assertEquals("woad",c.palette("custom:duchy",""));
        assertThrows(IllegalArgumentException.class,()->NpcOutfitCatalog.read(new StringReader("{\"palettes\":{\"neutral\":{}},\"factions\":{\"custom:duchy\":\"missing\"}}")));
    }
}
