package com.jgeted.sagadyssey.social.industry.common;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KnownNaturalSheepTest {
    final UUID actor=UUID.randomUUID(),target=UUID.randomUUID();
    CompoundTag source(){var s=new CompoundTag();for(String k:new String[]{"Id","Need"})s.store(k, UUIDUtil.CODEC,UUID.randomUUID());s.store("Actor", UUIDUtil.CODEC,actor);s.store("Owner", UUIDUtil.CODEC,actor);s.store("Target", UUIDUtil.CODEC,target);s.putString("Kind","SHEAR_NATURAL");s.putString("Dimension","minecraft:overworld");s.put("Output",new CompoundTag());return s;}
    boolean known(CompoundTag s){return NaturalIndustrySources.knownWildSheep(s,actor,target,"minecraft:overworld");}
    @Test void onlySamePersonAnimalDimension(){var s=source();assertTrue(known(s));assertFalse(NaturalIndustrySources.knownWildSheep(s,UUID.randomUUID(),target,"minecraft:overworld"));assertFalse(NaturalIndustrySources.knownWildSheep(s,actor,UUID.randomUUID(),"minecraft:overworld"));assertFalse(NaturalIndustrySources.knownWildSheep(s,actor,target,"minecraft:the_nether"));}
    @Test void ownershipMismatchOrMissingIdentityDoesNotAuthorize(){for(String key:new String[]{"Owner","Actor","Target"}){var s=source();s.store(key, UUIDUtil.CODEC,UUID.randomUUID());assertFalse(known(s));s.remove(key);assertFalse(known(s));}}
    @Test void managedOrFishingSourceCannotBecomeWildMemory(){for(String kind:new String[]{"SHEAR","FISH_COD","ANGLE",""}){var s=source();s.putString("Kind",kind);assertFalse(known(s));}}
    @Test void readOnlyAndNbtRoundTripRetainSameMemory(){var s=source();var d=new EntityResourceActionData();d.putSource(s);d.setDirty(false);var before=d.save(new CompoundTag(),null);assertTrue(known(d.sources().getFirst()));assertFalse(d.isDirty());assertEquals(before,d.save(new CompoundTag(),null));var copy=EntityResourceActionData.load(before,null);assertTrue(known(copy.sources().getFirst()));assertEquals(before,copy.save(new CompoundTag(),null));assertFalse(copy.isDirty());}
}
