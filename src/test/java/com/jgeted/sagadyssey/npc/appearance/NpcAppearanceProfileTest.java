package com.jgeted.sagadyssey.npc.appearance;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class NpcAppearanceProfileTest {
    @Test void missingFieldsKeepOldNpcAppearanceInsteadOfRerolling() {
        var old=new CompoundTag(); old.putString("Gender","FEMALE"); old.putString("FaceVariant","ELDER");
        assertEquals(NpcAppearanceProfile.LEGACY,NpcAppearanceProfile.read(old));
        assertEquals("ELDER",old.getStringOr("FaceVariant",""));
        assertFalse(old.contains("SkinTone"));
    }

    @Test void deterministicNewPeopleCoverTheIndependentAppearancePools() {
        var heads=EnumSet.noneOf(NpcHeadShape.class);
        var hair=EnumSet.noneOf(NpcHairStyle.class);
        var tones=EnumSet.noneOf(NpcSkinTone.class);
        var combinations=new HashSet<NpcAppearanceProfile>();
        for(int i=0;i<4096;i++) {
            var id=new UUID(0x3141592653589793L,i);
            var profile=NpcAppearanceProfile.forIdentity(id);
            assertEquals(profile,NpcAppearanceProfile.forIdentity(id));
            heads.add(profile.head());hair.add(profile.hair());tones.add(profile.skin());combinations.add(profile);
        }
        assertEquals(6,heads.size());assertEquals(4,hair.size());assertEquals(5,tones.size());
        assertFalse(tones.contains(NpcSkinTone.ORIGINAL));
        assertEquals(120,combinations.size());
    }

    @Test void savedProfilesRemainStableAndUnknownFieldsHaveSafeFallbacks() {
        var tag=new CompoundTag();
        var profile=new NpcAppearanceProfile(NpcHeadShape.ANGULAR,NpcHairStyle.TIED,NpcSkinTone.DEEP);
        profile.save(tag);
        assertEquals(profile,NpcAppearanceProfile.read(tag));
        tag.putString("HairStyle","not_a_style");
        assertEquals(NpcHairStyle.CLASSIC,NpcAppearanceProfile.read(tag).hair());
        assertEquals(NpcSkinTone.DEEP,NpcAppearanceProfile.read(tag).skin());
    }

    @Test void palettesMatchFaceAndBothHandsWithoutChangingClothOrVanillaEyes() throws Exception {
        var base="assets/sagadyssey/textures/entity/npc/";
        var loader=getClass().getClassLoader();
        var source=ImageIO.read(Objects.requireNonNull(loader.getResource(base+"revision_v2/profession/none.png")));
        for(var tone:NpcSkinTone.values()) {
            for(var gender:NpcGender.values())for(var face:NpcFaceVariant.values()) {
                var name=gender.id()+"_"+face.id()+".png";
                var image=ImageIO.read(Objects.requireNonNull(loader.getResource(base+"appearance_v3/skin/"
                        +tone.id()+"/revision_v2/face/"+name)));
                assertEquals(256,image.getWidth());assertEquals(128,image.getHeight());
                var original=ImageIO.read(Objects.requireNonNull(loader.getResource(base+"revision_v2/face/"+name)));
                assertEquals(original.getRGB(37,49),image.getRGB(37,49),"Eye white changed");
                assertEquals(original.getRGB(41,49),image.getRGB(41,49),"Iris changed");
                assertArrayEquals(original.getRGB(128,0,128,64,null,0,128),
                        image.getRGB(128,0,128,64,null,0,128),"Hair or headcloth was skin-tinted");
            }
            if(tone==NpcSkinTone.ORIGINAL)continue;
            var body=ImageIO.read(Objects.requireNonNull(loader.getResource(base+"appearance_v3/skin/"
                    +tone.id()+"/revision_v2/profession/none.png")));
            var face=ImageIO.read(Objects.requireNonNull(loader.getResource(base+"appearance_v3/skin/"
                    +tone.id()+"/revision_v2/face/male_young.png")));
            assertEquals(face.getRGB(0,0),body.getRGB(90,62),"Face and right palm differ");
            assertEquals(face.getRGB(0,0),body.getRGB(74,126),"Face and left palm differ");
            assertEquals(source.getRGB(44,48),body.getRGB(44,48),"Clothes were tinted");
        }
    }
}
