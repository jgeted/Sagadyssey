package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;

/** 配装目标，不是当前手持状态或一套独立AI；保护值和物品仍以实际装备为准。 */
public record WarLoadout(Mount mount, Weapon primaryWeapon, boolean shield, boolean riderArmor) {
    public enum Mount {
        FOOT("foot", "步兵"), LIGHT("light_cavalry", "轻骑兵"), HEAVY("heavy_cavalry", "重骑兵");
        private final String key, label;
        Mount(String key, String label) { this.key = key; this.label = label; }
        public String key() { return key; }
        public String label() { return label; }
    }
    public enum Weapon {
        SWORD("sword", "剑士"), SPEAR("spear", "长枪"), BOW("bow", "弓箭"), CROSSBOW("crossbow", "弩");
        private final String key, label;
        Weapon(String key, String label) { this.key = key; this.label = label; }
        public String key() { return key; }
        public String label() { return label; }
        public boolean needsMeleeBackup() { return this == BOW || this == CROSSBOW; }
    }
    public WarLoadout { Objects.requireNonNull(mount); Objects.requireNonNull(primaryWeapon); }
    public WarKey id() {
        return new WarKey("sagadyssey", "war/loadout/" + mount.key() + "/" + primaryWeapon.key()
                + "/" + (shield ? "shield" : "no_shield") + "/" + (riderArmor ? "armor" : "no_armor"));
    }
    public String displayName() {
        return (shield ? "有盾" : "无盾") + (riderArmor ? "有甲" : "无甲") + primaryWeapon.label() + mount.label();
    }
}
