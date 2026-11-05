package com.jgeted.sagadyssey.social.household;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HouseholdLifecycleTest {
    @Test
    void oneCharacterCannotBelongToTwoHouseholds() {
        HouseholdData data = new HouseholdData();
        UUID character = UUID.randomUUID();

        assertNotNull(data.createWithId(UUID.randomUUID(), "河畔家", "minecraft:overworld",
                null, character, BlockPos.ZERO, 10L));
        assertNull(data.createWithId(UUID.randomUUID(), "重复户", "minecraft:overworld",
                null, character, BlockPos.ZERO, 11L));
        assertEquals(1, data.households().size());
    }

    @Test
    void successionLeavesExactlyOneHead() {
        HouseholdData data = new HouseholdData();
        UUID oldHead = UUID.randomUUID();
        UUID spouse = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        assertNotNull(data.createWithId(householdId, "林家", "minecraft:overworld",
                null, oldHead, BlockPos.ZERO, 1L));
        assertTrue(data.addMember(householdId, spouse, HouseholdRole.SPOUSE, 2L));

        assertTrue(data.setHead(householdId, spouse, 3L));

        HouseholdRecord household = data.byId(householdId);
        assertNotNull(household);
        assertEquals(spouse, household.headCharacterId());
        assertEquals(HouseholdRole.HEAD, household.roleOf(spouse));
        assertEquals(HouseholdRole.KIN, household.roleOf(oldHead));
        assertEquals(1, household.members().stream()
                .filter(member -> member.role() == HouseholdRole.HEAD).count());
    }

    @Test
    void splitMovesMembershipButDoesNotDuplicateHouseOrStorage() {
        HouseholdData data = new HouseholdData();
        UUID oldHead = UUID.randomUUID();
        UUID newHead = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        assertNotNull(data.createWithId(sourceId, "旧户", "minecraft:overworld",
                UUID.randomUUID(), oldHead, new BlockPos(10, 64, 10), 1L));
        assertTrue(data.addMember(sourceId, newHead, HouseholdRole.KIN, 2L));
        assertTrue(data.addMember(sourceId, child, HouseholdRole.CHILD, 3L));
        assertTrue(data.assignSharedStorage(sourceId, "minecraft:overworld",
                new BlockPos(10, 64, 10), new BlockPos(11, 64, 10)));
        assertEquals(18, data.provisionPlan(sourceId).foodItemsTarget());

        HouseholdRecord created = data.split(sourceId, newId, "新户", newHead,
                List.of(newHead, child), 100L);

        assertNotNull(created);
        assertEquals(newId, data.forMember(newHead).id());
        assertEquals(newId, data.forMember(child).id());
        assertEquals(sourceId, data.forMember(oldHead).id());
        assertEquals(HouseholdRole.HEAD, created.roleOf(newHead));
        assertNull(created.homeAnchor());
        assertNull(created.sharedStorage());
        assertNotNull(data.provisionPlan(newId));
        assertEquals(12, data.provisionPlan(newId).foodItemsTarget());
        assertEquals(6, data.provisionPlan(sourceId).foodItemsTarget());
    }

    @Test
    void invalidSplitIsAtomic() {
        HouseholdData data = new HouseholdData();
        UUID head = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        assertNotNull(data.createWithId(sourceId, "原户", "minecraft:overworld",
                null, head, null, 1L));
        assertTrue(data.addMember(sourceId, member, HouseholdRole.KIN, 2L));

        assertNull(data.split(sourceId, UUID.randomUUID(), "坏分户", member,
                List.of(member, UUID.randomUUID()), 3L));
        assertEquals(sourceId, data.forMember(member).id());
        assertEquals(1, data.households().size());
    }
}
