package com.jgeted.sagadyssey.social.war.api;

/** 战争动作语义，不使用原版物品类名或版本枚举。 */
public enum WarAttackKind {
    SWORD_STRIKE(WarLoadout.Weapon.SWORD), SPEAR_JAB(WarLoadout.Weapon.SPEAR),
    SPEAR_CHARGE(WarLoadout.Weapon.SPEAR), BOW_SHOT(WarLoadout.Weapon.BOW),
    CROSSBOW_SHOT(WarLoadout.Weapon.CROSSBOW), MOUNTED_SPEAR_CHARGE(WarLoadout.Weapon.SPEAR);

    private final WarLoadout.Weapon weapon;
    private final WarKey key;
    WarAttackKind(WarLoadout.Weapon weapon) {
        this.weapon = weapon;
        this.key = new WarKey("sagadyssey", "war/action/" + name().toLowerCase(java.util.Locale.ROOT));
    }
    public WarLoadout.Weapon weapon() { return weapon; }
    public WarKey key() { return key; }
    public static WarAttackKind primary(WarLoadout.Weapon weapon) {
        return switch (weapon) { case SWORD -> SWORD_STRIKE; case SPEAR -> SPEAR_JAB; case BOW -> BOW_SHOT; case CROSSBOW -> CROSSBOW_SHOT; };
    }
}
