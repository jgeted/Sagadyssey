package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;

/** 一个计划步骤只能修改一个方块；播种和收获、搭建和回收使用不同步骤键。 */
public record FieldworkAction(UUID planId, UUID npcId, int stepIndex, String dimension,
                              BlockPos target, Operation operation, String expectedState) {
    public FieldworkAction {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(operation, "operation");
        target = Objects.requireNonNull(target, "target").immutable();
        requirePosition(target);
        if (stepIndex < 0 || stepIndex >= 64) throw new IllegalArgumentException("invalid step index");
        requireText(dimension, 256, "dimension");
        requireText(expectedState, 1_024, "expectedState");
    }

    public String key() {
        return planId + ":" + stepIndex;
    }

    public enum Operation {
        HARVEST, PLANT, TILL, MINE, CHOP, PLACE_SCAFFOLD, RECOVER_SCAFFOLD,
        IRRIGATE, REFILL_WATER, FERTILIZE, CRAFT_FERTILIZER
    }

    static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is missing or too long");
        }
        return value;
    }

    /** 与 Minecraft 的 packed BlockPos 一致，拒绝存档时会被截断的坐标。 */
    static void requirePosition(BlockPos position) {
        if (Math.abs((long) position.getX()) > 30_000_000L
                || Math.abs((long) position.getZ()) > 30_000_000L
                || position.getY() < -2_048 || position.getY() > 2_047) {
            throw new IllegalArgumentException("position outside supported world bounds");
        }
    }
}
