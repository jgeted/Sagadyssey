package com.jgeted.sagadyssey.core.structure;

import com.google.gson.annotations.SerializedName;

/**
 * 单个结构的 JSON 配置数据模型。
 * 对应 data/sagadyssey/structure/*.json 的字段。
 */
public class StructureConfig {
    private String id;
    private String nbt;
    private String name;
    private String category;
    private String size;

    private String biomes;
    private String step;

    @SerializedName("terrain_adaptation")
    private String terrainAdaptation;

    @SerializedName("start_height")
    private StartHeight startHeight;

    private int spacing;
    private int separation;

    @SerializedName("max_distance_from_center")
    private int maxDistanceFromCenter;

    private int weight;

    @SerializedName("allow_underground")
    private boolean allowUnderground;

    @SerializedName("allow_water")
    private boolean allowWater;

    // === 内嵌类 ===

    public static class StartHeight {
        private String type;

        @SerializedName("min_inclusive")
        private HeightValue minInclusive;

        @SerializedName("max_inclusive")
        private HeightValue maxInclusive;

        public String getType() { return type; }
        public HeightValue getMinInclusive() { return minInclusive; }
        public HeightValue getMaxInclusive() { return maxInclusive; }
    }

    public static class HeightValue {
        private int absolute;

        public int asInt() {
            return absolute;
        }
    }

    // === Getter ===

    public String getId() { return id; }
    public String getNbt() { return nbt; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getSize() { return size; }
    public String getBiomes() { return biomes; }
    public String getStep() { return step; }
    public String getTerrainAdaptation() { return terrainAdaptation; }
    public StartHeight getStartHeight() { return startHeight; }
    public int getSpacing() { return spacing; }
    public int getSeparation() { return separation; }
    public int getMaxDistanceFromCenter() { return maxDistanceFromCenter; }
    public int getWeight() { return weight; }
    public boolean isAllowUnderground() { return allowUnderground; }
    public boolean isAllowWater() { return allowWater; }

    /** 从 nbt 字段中提取 NBT 文件名（去掉 "sagadyssey:" 前缀） */
    public String getNbtFileName() {
        if (nbt.startsWith("sagadyssey:")) {
            return nbt.substring("sagadyssey:".length());
        }
        return nbt;
    }
}
