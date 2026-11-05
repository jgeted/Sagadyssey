package com.jgeted.sagadyssey.npc.identity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;

/** 玩家自身的社会阶层与职务；独立于玩家集团军衔和阵营声望。 */
public final class PlayerIdentityData {
    private String socialClassName = NpcSocialClass.COMMONER.name();
    private String officeName = NpcOffice.NONE.name();
    private String titleFactionId = "sagadyssey:player";

    public PlayerIdentityData() { }

    private PlayerIdentityData(String socialClassName, String officeName, String titleFactionId) {
        this.socialClassName = NpcSocialClass.byName(socialClassName).name();
        this.officeName = NpcOffice.byName(officeName).name();
        this.titleFactionId = normalizeFactionId(titleFactionId);
    }

    public NpcSocialClass socialClass() { return NpcSocialClass.byName(socialClassName); }
    public NpcOffice office() { return NpcOffice.byName(officeName); }
    public String titleFactionId() { return titleFactionId; }
    public int precedence() { return NpcIdentity.precedence(socialClass(), office()); }
    public Component title() { return NpcIdentity.title(socialClass(), office(), titleFactionId); }

    public void set(NpcSocialClass socialClass, NpcOffice office, String factionId) {
        this.socialClassName = socialClass.name();
        this.officeName = office.name();
        this.titleFactionId = normalizeFactionId(factionId);
    }

    /** 永久角色死亡后清除上一世身份；普通的游戏性死亡不会调用。 */
    public void resetForNewCharacter() {
        set(NpcSocialClass.COMMONER, NpcOffice.NONE, "sagadyssey:wilderness");
    }

    private static String normalizeFactionId(String factionId) {
        if (factionId == null || factionId.isBlank()) return "sagadyssey:player";
        return factionId.contains(":") ? factionId : "sagadyssey:" + factionId;
    }

    public static final Codec<PlayerIdentityData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("socialClass", NpcSocialClass.COMMONER.name())
                    .forGetter(data -> data.socialClassName),
            Codec.STRING.optionalFieldOf("office", NpcOffice.NONE.name())
                    .forGetter(data -> data.officeName),
            Codec.STRING.optionalFieldOf("titleFaction", "sagadyssey:player")
                    .forGetter(PlayerIdentityData::titleFactionId)
    ).apply(instance, PlayerIdentityData::new));
}
