package com.jgeted.sagadyssey.npc.brain.execution;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 计划步骤执行器注册表。
 */
public final class NpcTaskExecutorRegistry {
    private final Map<ResourceLocation, NpcTaskExecutor> executors = new LinkedHashMap<>();

    public synchronized void register(NpcTaskExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        ResourceLocation id = Objects.requireNonNull(executor.id(), "executor.id()");
        if (executors.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate NPC task executor id: " + id);
        }
        executors.put(id, executor);
    }

    public synchronized Optional<NpcTaskExecutor> find(ResourceLocation id) {
        return Optional.ofNullable(executors.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Map<ResourceLocation, NpcTaskExecutor> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(executors));
    }
}
