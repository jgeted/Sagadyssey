package com.jgeted.sagadyssey.social.service;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.social.life.NpcEmploymentContract;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRelationshipDataTest {
    @Test
    void voluntaryEmploymentGrantsOnlyNamedScopesAndCanBeEnded() {
        ServiceRelationshipData data = new ServiceRelationshipData();
        UUID principal = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        ServiceRelationship relationship = data.beginVoluntaryEmployment(principal, agent, 10L);

        assertEquals(ServiceBasis.VOLUNTARY_EMPLOYMENT, relationship.basis());
        assertTrue(data.authorize(principal, agent, AuthorityScope.FOLLOW, false, 11L).allowed());
        assertFalse(relationship.limits().privateInventoryAccess());
        assertTrue(data.endActive(principal, agent, ServiceStatus.CANCELLED,
                "双方解除", 12L));
        assertEquals(ServiceAuthorityVerdict.Status.DENY,
                data.authorize(principal, agent, AuthorityScope.FOLLOW, false, 13L).status());
    }

    @Test
    void dangerousOrderMayBeRefusedEvenWhenItsScopeExists() {
        ServiceRelationshipData data = new ServiceRelationshipData();
        UUID principal = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        data.beginVoluntaryEmployment(principal, agent, 20L);

        ServiceAuthorityVerdict verdict = data.authorize(principal, agent,
                AuthorityScope.DEFEND_PRINCIPAL, true, 21L);

        assertEquals(ServiceAuthorityVerdict.Status.REFUSED, verdict.status());
        assertTrue(verdict.reason().contains("危险命令"));
    }

    @Test
    void wageContractHasTermAndBreachRevokesAuthority() {
        ServiceRelationshipData data = new ServiceRelationshipData();
        UUID principal = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        NpcEmploymentContract contract = NpcEmploymentContract.begin(agent, principal,
                settlement, 7, 3, 100L);
        ServiceRelationship relationship = data.beginWageContract(contract, "minecraft:overworld");

        assertEquals(contract.endsAt(), relationship.endsAt());
        assertTrue(data.authorize(principal, agent, AuthorityScope.LOGISTICS,
                false, 101L).allowed());
        NpcEmploymentContract breached = contract
                .missedPayment(contract.nextPaymentAt())
                .missedPayment(contract.nextPaymentAt() + contract.payPeriodTicks())
                .missedPayment(contract.nextPaymentAt() + contract.payPeriodTicks() * 2L);
        assertEquals(com.jgeted.sagadyssey.social.life.NpcContractStatus.BREACHED,
                breached.status());

        data.syncContract(breached, breached.nextPaymentAt());

        assertEquals(ServiceAuthorityVerdict.Status.DENY,
                data.authorize(principal, agent, AuthorityScope.LOGISTICS,
                        false, breached.nextPaymentAt()).status());
    }

    @Test
    void relationshipNbtRoundTripPreservesLimitsAndObligations() {
        ServiceRelationshipData data = new ServiceRelationshipData();
        ServiceRelationship original = data.beginVoluntaryEmployment(
                UUID.randomUUID(), UUID.randomUUID(), 50L);

        ServiceRelationship loaded = ServiceRelationship.load(original.save());

        assertEquals(original, loaded);
        assertFalse(loaded.obligations().isEmpty());
        assertFalse(loaded.limits().privateInventoryAccess());
    }

    @Test
    void malformedRelationshipOrAuditDoesNotSilentlyDisappear() {
        var data = new ServiceRelationshipData();
        var relationship = data.beginVoluntaryEmployment(UUID.randomUUID(), UUID.randomUUID(), 10);
        var tag = data.save(new net.minecraft.nbt.CompoundTag(), null);
        MinecraftApi.listOfType(tag, "Relationships", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .add(new net.minecraft.nbt.CompoundTag());
        var original = tag.copy();
        assertThrows(IllegalArgumentException.class, () -> ServiceRelationshipData.load(tag, null));
        assertEquals(original, tag);
        var auditTag = data.save(new net.minecraft.nbt.CompoundTag(), null);
        MinecraftApi.listOfType(auditTag, "Audits", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .add(new net.minecraft.nbt.CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> ServiceRelationshipData.load(auditTag, null));
        assertTrue(data.hasHistory(relationship.agentId()));
    }

    @Test
    void validLedgerRoundTripsAndDuplicateRelationshipIsRejected() {
        var data = new ServiceRelationshipData();
        var relationship = data.beginVoluntaryEmployment(UUID.randomUUID(), UUID.randomUUID(), 10);
        var tag = data.save(new net.minecraft.nbt.CompoundTag(), null);
        var restored = ServiceRelationshipData.load(tag, null);
        assertEquals(data.relationships(), restored.relationships());
        assertTrue(restored.hasScope(relationship.principalId(), relationship.agentId(), AuthorityScope.FOLLOW, 11));
        var list = MinecraftApi.listOfType(tag, "Relationships", net.minecraft.nbt.Tag.TAG_COMPOUND);
        list.add(list.getCompoundOrEmpty(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ServiceRelationshipData.load(tag, null));
    }

    @Test
    void unknownScopeIsNotSilentlyDiscarded() {
        var data = new ServiceRelationshipData();
        var relationship = data.beginVoluntaryEmployment(UUID.randomUUID(), UUID.randomUUID(), 10);
        var tag = relationship.save();
        MinecraftApi.listOfType(tag, "Scopes", net.minecraft.nbt.Tag.TAG_STRING)
                .add(net.minecraft.nbt.StringTag.valueOf("FUTURE_SCOPE"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRelationship.load(tag));
    }

    @Test
    void privateInventoryAccessCannotBeSmuggledIntoServiceLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLimits(null, "", false, true));
    }
}
