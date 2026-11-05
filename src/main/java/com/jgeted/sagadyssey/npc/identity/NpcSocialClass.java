package com.jgeted.sagadyssey.npc.identity;

import net.minecraft.network.chat.Component;

/** 持久的社会阶层；不包含骑士、领主或国王等可授予、可撤销的职务。 */
public enum NpcSocialClass {
    COMMONER("commoner", 0, true, 1.00D),
    FREEMAN("freeman", 1, true, 1.15D),
    BURGHER("burgher", 2, true, 1.50D),
    NOBLE("noble", 4, false, 0.00D),
    ROYAL("royal", 5, false, 0.00D);

    private final String id;
    private final int precedence;
    private final boolean directlyRecruitable;
    private final double recruitmentMultiplier;

    NpcSocialClass(String id, int precedence, boolean directlyRecruitable,
                   double recruitmentMultiplier) {
        this.id = id;
        this.precedence = precedence;
        this.directlyRecruitable = directlyRecruitable;
        this.recruitmentMultiplier = recruitmentMultiplier;
    }

    public String id() { return id; }
    public int precedence() { return precedence; }
    public boolean isDirectlyRecruitable() { return directlyRecruitable; }
    public double recruitmentMultiplier() { return recruitmentMultiplier; }
    public Component displayName() { return Component.translatable("social_class.sagadyssey." + id); }

    public static NpcSocialClass byName(String name) {
        if (name == null || name.isBlank()) return COMMONER;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return COMMONER;
        }
    }
}
