package com.jgeted.sagadyssey.npc.brain.blacksmith;

import net.minecraft.resources.Identifier;

/** 铁匠订单计划的稳定执行器与参数标识。 */
public final class BlacksmithTaskIds {
    public static final Identifier PROVIDER = id("blacksmith_order");
    public static final Identifier RESERVE_ORDER = id("blacksmith_reserve_order");
    public static final Identifier MOVE_TO_BLOCK = id("move_to_block");
    public static final Identifier FORGE = id("blacksmith_forge");
    public static final Identifier SETTLE = id("blacksmith_settle");

    public static final String ORDER_ID = "orderId";
    public static final String BATCH_ID = "batchId";
    public static final String DIMENSION = "dimension";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String REQUIRED_WORK_TICKS = "requiredWorkTicks";

    private BlacksmithTaskIds() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("sagadyssey", path);
    }
}
