package com.jgeted.sagadyssey.npc.brain.candidate;

import com.jgeted.sagadyssey.npc.brain.context.NpcDecisionContext;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 活动 Provider 注册表。Provider 只生成候选，不能在注册或提案阶段修改世界。
 */
public final class NpcActivityProviderRegistry {
    private final Map<ResourceLocation, NpcActivityProvider> providers = new LinkedHashMap<>();

    public synchronized void register(NpcActivityProvider provider) {
        Objects.requireNonNull(provider, "provider");
        ResourceLocation id = Objects.requireNonNull(provider.id(), "provider id");
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalStateException("duplicate activity provider: " + id);
        }
    }

    public synchronized Optional<NpcActivityProvider> find(ResourceLocation id) {
        return Optional.ofNullable(providers.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Map<ResourceLocation, NpcActivityProvider> snapshot() {
        return Map.copyOf(providers);
    }

    /**
     * 汇总全部候选并验证 Provider 身份和候选键唯一性。
     */
    public synchronized List<NpcActivityCandidate> proposeAll(NpcDecisionContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, NpcActivityCandidate> byKey = new LinkedHashMap<>();
        List<NpcActivityProvider> ordered = providers.values().stream()
                .sorted(Comparator.comparing(provider -> provider.id().toString()))
                .toList();
        for (NpcActivityProvider provider : ordered) {
            List<NpcActivityCandidate> proposed = Objects.requireNonNull(
                    provider.propose(context), "provider candidates");
            for (NpcActivityCandidate candidate : proposed) {
                Objects.requireNonNull(candidate, "candidate");
                if (!provider.id().equals(candidate.providerId())) {
                    throw new IllegalStateException("candidate provider mismatch: "
                            + candidate.candidateKey());
                }
                if (byKey.putIfAbsent(candidate.candidateKey(), candidate) != null) {
                    throw new IllegalStateException("duplicate candidate key: "
                            + candidate.candidateKey());
                }
            }
        }
        List<NpcActivityCandidate> result = new ArrayList<>(byKey.values());
        result.sort(Comparator.comparing(NpcActivityCandidate::candidateKey));
        return List.copyOf(result);
    }
}
