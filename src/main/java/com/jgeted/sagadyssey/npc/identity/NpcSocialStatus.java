package com.jgeted.sagadyssey.npc.identity;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import net.minecraft.network.chat.Component;

/**
 * 旧版线性社会身份，现仅作为存档迁移、开发命令简写和服装/语言表现档位。
 *
 * <p>这是面向玩法的西欧中世纪表现抽象；真实的礼仪优先级由
 * {@link NpcIdentity} 根据社会阶层与职务共同计算。</p>
 */
@Deprecated(forRemoval = false)
public enum NpcSocialStatus {
    COMMONER("commoner", NpcAction.GREET),
    FREEMAN("freeman", NpcAction.HEAD_NOD),
    BURGHER("burgher", NpcAction.COURTESY_BOW),
    KNIGHT("knight", NpcAction.CHEST_SALUTE),
    LORD("lord", NpcAction.NOBLE_ACKNOWLEDGE),
    DUKE("duke", NpcAction.NOBLE_ACKNOWLEDGE),
    KING("king", NpcAction.ROYAL_WAVE);

    private final String id;
    private final NpcAction greetingAction;

    NpcSocialStatus(String id, NpcAction greetingAction) {
        this.id = id;
        this.greetingAction = greetingAction;
    }

    public String id() { return id; }
    public NpcAction greetingAction() { return greetingAction; }
    public Component displayName() { return Component.translatable("identity.sagadyssey." + id); }
    public Component description() { return Component.translatable("identity.sagadyssey." + id + ".description"); }

    public NpcSocialClass defaultSocialClass() {
        return switch (this) {
            case COMMONER -> NpcSocialClass.COMMONER;
            case FREEMAN, KNIGHT -> NpcSocialClass.FREEMAN;
            case BURGHER -> NpcSocialClass.BURGHER;
            case LORD, DUKE -> NpcSocialClass.NOBLE;
            case KING -> NpcSocialClass.ROYAL;
        };
    }

    public NpcOffice defaultOffice() {
        return switch (this) {
            case COMMONER, FREEMAN, BURGHER -> NpcOffice.NONE;
            case KNIGHT -> NpcOffice.SWORN;
            case LORD -> NpcOffice.LOCAL_RULER;
            case DUKE -> NpcOffice.HIGH_RULER;
            case KING -> NpcOffice.SOVEREIGN;
        };
    }

    public static NpcSocialStatus byName(String name) {
        if (name == null || name.isBlank()) return COMMONER;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return COMMONER;
        }
    }
}
