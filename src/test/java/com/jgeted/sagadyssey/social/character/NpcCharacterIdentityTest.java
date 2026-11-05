package com.jgeted.sagadyssey.social.character;

import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcCharacterIdentityTest {
    @Test
    void legacyEntityUsesBodyUuidAsInitialStableIdentity() {
        UUID bodyId = UUID.randomUUID();
        assertEquals(bodyId, NpcCharacterIdentityMigration.resolve(new CompoundTag(), bodyId));
    }

    @Test
    void persistedCharacterIdentitySurvivesBodyReplacement() {
        UUID characterId = UUID.randomUUID();
        UUID oldBody = UUID.randomUUID();
        UUID newBody = UUID.randomUUID();
        CompoundTag entityTag = new CompoundTag();
        entityTag.putUUID(NpcCharacterIdentityMigration.CHARACTER_ID_KEY, characterId);

        assertEquals(characterId, NpcCharacterIdentityMigration.resolve(entityTag, oldBody));
        assertEquals(characterId, NpcCharacterIdentityMigration.resolve(entityTag, newBody));
    }

    @Test
    void registryTracksLatestBodyAndPreventsDoubleClaim() {
        CharacterRegistryData registry = new CharacterRegistryData();
        UUID firstCharacter = UUID.randomUUID();
        UUID secondCharacter = UUID.randomUUID();
        UUID body = UUID.randomUUID();
        registry.ensureNpcCharacter(firstCharacter, "甲", NpcSocialClass.COMMONER,
                NpcOffice.NONE, "sagadyssey:wilderness", 1L);
        registry.ensureNpcCharacter(secondCharacter, "乙", NpcSocialClass.COMMONER,
                NpcOffice.NONE, "sagadyssey:wilderness", 1L);

        registry.bindNpcBody(firstCharacter, "minecraft:overworld", body, 10L);
        assertEquals(body, registry.bodyOf(firstCharacter).entityId());

        registry.bindNpcBody(secondCharacter, "minecraft:overworld", body, 20L);
        assertNull(registry.bodyOf(firstCharacter));
        assertEquals(secondCharacter, registry.byId(secondCharacter).id());
        assertEquals(20L, registry.bodyOf(secondCharacter).observedGameTime());
    }

    @Test
    void bodyReferenceRoundTripsThroughNbt() {
        CharacterBodyRef original = new CharacterBodyRef(
                "minecraft:the_nether", UUID.randomUUID(), 42L);
        assertEquals(original, CharacterBodyRef.load(original.save()));
    }
}
