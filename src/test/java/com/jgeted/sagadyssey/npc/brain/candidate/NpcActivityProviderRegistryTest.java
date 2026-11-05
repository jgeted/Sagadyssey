package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import com.jgeted.sagadyssey.npc.brain.context.NpcPerceptionSnapshot;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanKind;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPhase;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanPriority;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcActivityProviderRegistryTest {
    @Test
    void gathersCandidatesInStableOrderAndRejectsDuplicateProvider() {
        NpcActivityProviderRegistry registry = new NpcActivityProviderRegistry();
        registry.register(provider("z_provider", "z_candidate"));
        registry.register(provider("a_provider", "a_candidate"));

        assertEquals(List.of("a_candidate", "z_candidate"), registry.proposeAll(context()).stream()
                .map(NpcActivityCandidate::candidateKey).toList());
        assertThrows(IllegalStateException.class,
                () -> registry.register(provider("a_provider", "another")));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.snapshot().clear());
    }

    @Test
    void rejectsCandidateThatClaimsAnotherProvider() {
        NpcActivityProviderRegistry registry = new NpcActivityProviderRegistry();
        ResourceLocation registered = id("registered");
        registry.register(new NpcActivityProvider() {
            @Override
            public ResourceLocation id() {
                return registered;
            }

            @Override
            public List<NpcActivityCandidate> propose(NpcDecisionContext context) {
                return List.of(candidate("candidate",
                        NpcActivityProviderRegistryTest.id("other")));
            }
        });

        assertThrows(IllegalStateException.class, () -> registry.proposeAll(context()));
    }

    private static NpcActivityProvider provider(String providerKey, String candidateKey) {
        ResourceLocation id = id(providerKey);
        return new NpcActivityProvider() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public List<NpcActivityCandidate> propose(NpcDecisionContext context) {
                return List.of(candidate(candidateKey, id));
            }
        };
    }

    private static NpcActivityCandidate candidate(String key, ResourceLocation providerId) {
        return new NpcActivityCandidate(key, providerId, NpcPlanKind.WORK,
                NpcPlanPriority.NORMAL, null, null,
                List.of(new NpcPlanStepTemplate(NpcPlanPhase.ACTING,
                        id("executor"), Map.of())), 10.0D,
                0.0D, 0.0D, 0.0D, false, 0L, 100L, 20L,
                Set.of(), Set.of(), Map.of());
    }

    private static NpcDecisionContext context() {
        return new NpcDecisionContext(UUID.randomUUID(), 10L, 1L,
                new NpcPerceptionSnapshot(10L, Set.of(), Set.of(), Set.of()),
                Set.of(), Map.of());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("sagadyssey", path);
    }
}
