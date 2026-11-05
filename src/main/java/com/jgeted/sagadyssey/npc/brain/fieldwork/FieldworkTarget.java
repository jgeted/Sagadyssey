package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** 可直接写入计划参数的单方块意图，不持有实体或运行时导航。 */
public record FieldworkTarget(BlockPos position, FieldworkAction.Operation operation,
                              BlockState before, BlockState after, Item input, Item replacement) {
    public FieldworkTarget {
        position = position.immutable();
    }

    public Map<String, String> params(String dimension) {
        Map<String, String> params = new HashMap<>();
        params.put("dimension", dimension);
        params.put("target_x", Integer.toString(position.getX()));
        params.put("target_y", Integer.toString(position.getY()));
        params.put("target_z", Integer.toString(position.getZ()));
        params.put("operation", operation.name());
        params.put("expected", NbtUtils.writeBlockState(before).toString());
        params.put("result", NbtUtils.writeBlockState(after).toString());
        if (input != null) params.put("input", BuiltInRegistries.ITEM.getKey(input).toString());
        if (replacement != null) params.put("replacement", BuiltInRegistries.ITEM.getKey(replacement).toString());
        if (operation == FieldworkAction.Operation.CRAFT_FERTILIZER) {
            params.put("replacement_count", input == net.minecraft.world.item.Items.BONE_BLOCK ? "9" : "3");
        }
        return Map.copyOf(params);
    }

    public static BlockPos position(Map<String, String> params) {
        return new BlockPos(Integer.parseInt(params.get("target_x")),
                Integer.parseInt(params.get("target_y")), Integer.parseInt(params.get("target_z")));
    }
}
