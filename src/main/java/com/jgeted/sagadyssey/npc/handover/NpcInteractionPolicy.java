package com.jgeted.sagadyssey.npc.handover;

/** 区分查看人物与明确递交，避免普通右键误送物品。 */
public final class NpcInteractionPolicy {
    private NpcInteractionPolicy() {}

    public static boolean opensHandoverShortcut(boolean hasHeldItem, boolean crouching) {
        return hasHeldItem && crouching;
    }
}
