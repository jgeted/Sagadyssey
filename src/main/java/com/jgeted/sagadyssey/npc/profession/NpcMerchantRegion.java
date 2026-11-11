package com.jgeted.sagadyssey.npc.profession;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** 商人所在地的粗粒度经济区；用于每日补货时形成可解释的地区价差。 */
public enum NpcMerchantRegion {
    PLAINS("plains"),
    COAST("coast"),
    HIGHLAND("highland"),
    ARID("arid"),
    NORTH("north"),
    NETHER("nether"),
    END("end");

    private final String id;

    NpcMerchantRegion(String id) {
        this.id = id;
    }

    public String id() { return id; }
    public Component displayName() { return Component.translatable("merchant_region.sagadyssey." + id); }

    public static NpcMerchantRegion detect(Level level, BlockPos pos) {
        if (level.dimension() == Level.NETHER) return NETHER;
        if (level.dimension() == Level.END) return END;
        String biome = level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().getPath()).orElse("");
        if (containsAny(biome, "ocean", "beach", "river")) return COAST;
        if (containsAny(biome, "peak", "slope", "mountain", "stony", "windswept")) return HIGHLAND;
        if (containsAny(biome, "desert", "badlands", "savanna")) return ARID;
        if (containsAny(biome, "snow", "frozen", "ice", "taiga", "grove")) return NORTH;
        return PLAINS;
    }

    /** 小于 1 表示当地货源充足，大于 1 表示当地稀缺。 */
    public double resultPriceMultiplier(ItemStack result) {
        return switch (this) {
            case COAST -> isSeafood(result) ? 0.78D : isOre(result) ? 1.15D : 1.00D;
            case HIGHLAND -> isOre(result) || isStone(result) ? 0.82D : isCrop(result) ? 1.16D : 1.00D;
            case ARID -> isDesertGood(result) ? 0.82D : isWood(result) || isCrop(result) ? 1.18D : 1.00D;
            case NORTH -> isWood(result) || result.is(Items.WHITE_WOOL) ? 0.86D
                    : isCrop(result) ? 1.14D : 1.00D;
            case NETHER -> isNetherGood(result) ? 0.78D : isCrop(result) ? 1.25D : 1.08D;
            case END -> isEndGood(result) ? 0.78D : isCrop(result) ? 1.22D : 1.10D;
            case PLAINS -> isCrop(result) ? 0.92D : 1.00D;
        };
    }

    public static NpcMerchantRegion byName(String name) {
        if (name == null || name.isBlank()) return PLAINS;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PLAINS;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean isSeafood(ItemStack s) {
        return s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.PUFFERFISH)
                || s.is(Items.TROPICAL_FISH) || s.is(Items.KELP) || s.is(Items.DRIED_KELP);
    }

    private static boolean isOre(ItemStack s) {
        return s.is(Items.COAL) || s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT)
                || s.is(Items.COPPER_INGOT) || s.is(Items.DIAMOND) || s.is(Items.LAPIS_LAZULI);
    }

    private static boolean isStone(ItemStack s) {
        return s.is(Items.STONE) || s.is(Items.COBBLESTONE) || s.is(Items.STONE_BRICKS)
                || s.is(Items.ANDESITE) || s.is(Items.DIORITE) || s.is(Items.GRANITE);
    }

    private static boolean isCrop(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.BREAD) || s.is(Items.CARROT)
                || s.is(Items.POTATO) || s.is(Items.BEETROOT) || s.is(Items.PUMPKIN)
                || s.is(Items.MELON_SLICE);
    }

    private static boolean isDesertGood(ItemStack s) {
        return s.is(Items.SAND) || s.is(Items.GLASS) || s.is(Items.CACTUS);
    }

    private static boolean isWood(ItemStack s) {
        return s.is(Items.OAK_LOG) || s.is(Items.SPRUCE_LOG) || s.is(Items.BIRCH_LOG)
                || s.is(Items.DARK_OAK_LOG) || s.is(Items.JUNGLE_LOG) || s.is(Items.ACACIA_LOG);
    }

    private static boolean isNetherGood(ItemStack s) {
        return s.is(Items.NETHER_WART) || s.is(Items.BLAZE_ROD) || s.is(Items.GHAST_TEAR)
                || s.is(Items.QUARTZ) || s.is(Items.GLOWSTONE_DUST);
    }

    private static boolean isEndGood(ItemStack s) {
        return s.is(Items.ENDER_PEARL) || s.is(Items.CHORUS_FRUIT) || s.is(Items.END_STONE);
    }
}
