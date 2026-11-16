package com.jgeted.sagadyssey.npc.appearance;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Chosen once on the server; unrelated to faction, profession, abilities or gameplay RNG. */
public record NpcAppearanceProfile(NpcHeadShape head, NpcHairStyle hair, NpcSkinTone skin) {
    public static final NpcAppearanceProfile LEGACY =
            new NpcAppearanceProfile(NpcHeadShape.CLASSIC,NpcHairStyle.CLASSIC,NpcSkinTone.ORIGINAL);

    public static NpcAppearanceProfile forIdentity(UUID identity) {
        long seed=identity.getMostSignificantBits() ^ Long.rotateLeft(identity.getLeastSignificantBits(),23);
        return new NpcAppearanceProfile(
                NpcHeadShape.values()[index(seed ^ 0x45a9f37bL,NpcHeadShape.values().length)],
                NpcHairStyle.values()[index(seed ^ 0x93b724d1L,NpcHairStyle.values().length)],
                NpcSkinTone.values()[1+index(seed ^ 0x682d19efL,NpcSkinTone.values().length-1)]);
    }
    private static int index(long value,int count) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return (int)Long.remainderUnsigned(value^(value>>>31),count);
    }
    public void save(CompoundTag tag) {
        tag.putString("HeadShape",head.name());
        tag.putString("HairStyle",hair.name());
        tag.putString("SkinTone",skin.name());
    }
    public static NpcAppearanceProfile read(CompoundTag tag) {
        return new NpcAppearanceProfile(NpcHeadShape.byName(tag.getStringOr("HeadShape","")),
                NpcHairStyle.byName(tag.getStringOr("HairStyle","")),
                NpcSkinTone.byName(tag.getStringOr("SkinTone","")));
    }
}
