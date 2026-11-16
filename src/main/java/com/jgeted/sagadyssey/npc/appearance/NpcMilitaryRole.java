package com.jgeted.sagadyssey.npc.appearance;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Cosmetic military posts. These neither change civilian profession nor grant military authority. */
public enum NpcMilitaryRole {
    STANDARD_BEARER("旗手", NpcMilitaryOutfit.FOOT_SPEAR, .83F),
    STAFF_OFFICER("军务官", NpcMilitaryOutfit.FOOT_BOW, 1.12F),
    TROOP_CAPTAIN("兵种队长", NpcMilitaryOutfit.FOOT_SWORD, .83F),
    DEPUTY("随队副手", NpcMilitaryOutfit.FOOT_SPEAR, .73F),
    ENGINEER("工兵", NpcMilitaryOutfit.FOOT_CROSSBOW, .53F),
    ENGINEERING_LEAD("工程负责人", NpcMilitaryOutfit.FOOT_CROSSBOW, .68F),
    QUARTERMASTER("军需负责人", NpcMilitaryOutfit.FOOT_BOW, .97F),
    MILITARY_CARRIER("辎重运输员", NpcMilitaryOutfit.FOOT_CROSSBOW, .63F);

    private final String label;
    private final NpcMilitaryOutfit base;
    private final float hemScale;

    NpcMilitaryRole(String label, NpcMilitaryOutfit base, float hemScale) {
        this.label = label; this.base = base; this.hemScale = hemScale;
    }

    public String id() { return "support_" + name().toLowerCase(Locale.ROOT); }
    public String label() { return label; }
    public NpcMilitaryOutfit baseOutfit() { return base; }
    public float hemScale() { return hemScale; }
    public boolean documents() {
        return this == STAFF_OFFICER || this == DEPUTY || this == ENGINEERING_LEAD || this == QUARTERMASTER;
    }
    public boolean tools() { return this == ENGINEER || this == ENGINEERING_LEAD; }
    public boolean hood() { return this == STAFF_OFFICER || this == QUARTERMASTER || this == MILITARY_CARRIER; }
    public static Optional<NpcMilitaryRole> find(String id) {
        return id == null ? Optional.empty() : Arrays.stream(values()).filter(role -> role.id().equals(id)).findFirst();
    }
}
