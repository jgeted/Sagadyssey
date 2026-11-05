package com.jgeted.sagadyssey.npc.brain.fieldwork;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** 整个指定工作区的稳定索引；不以 NPC 的当前位置裁剪工作区。 */
public record FieldworkSearchArea(String dimension, BlockPos min, BlockPos max) {
    public FieldworkSearchArea {
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("dimension is required");
        }
        min = Objects.requireNonNull(min, "min").immutable();
        max = Objects.requireNonNull(max, "max").immutable();
        FieldworkAction.requirePosition(min);
        FieldworkAction.requirePosition(max);
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("bounds must be normalized");
        }
        // 禁止坐标乘法溢出；不通过截断工作区掩盖错误。
        Math.multiplyExact(Math.multiplyExact(width(min.getX(), max.getX()),
                width(min.getY(), max.getY())), width(min.getZ(), max.getZ()));
    }

    public long size() {
        return width(min.getX(), max.getX()) * width(min.getY(), max.getY())
                * width(min.getZ(), max.getZ());
    }

    /** X 最快，接着 Z，最后 Y；已存游标的含义不会因 NPC 移动而改变。 */
    public BlockPos position(long index) {
        if (index < 0L || index >= size()) throw new IllegalArgumentException("index outside area");
        long width = width(min.getX(), max.getX());
        long depth = width(min.getZ(), max.getZ());
        long column = index / width;
        return new BlockPos((int) (min.getX() + index % width),
                (int) (min.getY() + column / depth), (int) (min.getZ() + column % depth));
    }

    private static long width(int min, int max) {
        return (long) max - min + 1L;
    }
}
