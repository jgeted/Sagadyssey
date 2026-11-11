package com.jgeted.sagadyssey.social.character;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.identity.NpcIdentity;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 世界中的一个社会人物。玩家账号只是当前控制者，不是人物本身。
 * 人物永久死亡后本记录仍保留，供继承、编年史和对话系统读取。
 */
public final class CharacterRecord {
    private final UUID id;
    private String name;
    private String socialClass;
    private String office;
    private String factionId;
    private final long bornGameTime;
    private final int lifeNumber;
    private boolean alive;
    private long deathGameTime;
    private String deathCause;
    private int historicalImportance;
    private int rescueCount;

    public CharacterRecord(UUID id, String name, NpcSocialClass socialClass, NpcOffice office,
                           String factionId, long bornGameTime, int lifeNumber) {
        this.id = id;
        this.name = sanitizeName(name);
        this.socialClass = socialClass.name();
        this.office = office.name();
        this.factionId = normalizeFaction(factionId);
        this.bornGameTime = bornGameTime;
        this.lifeNumber = Math.max(1, lifeNumber);
        this.alive = true;
        this.deathGameTime = -1L;
        this.deathCause = "";
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public NpcSocialClass socialClass() { return NpcSocialClass.byName(socialClass); }
    public NpcOffice office() { return NpcOffice.byName(office); }
    public String factionId() { return factionId; }
    public long bornGameTime() { return bornGameTime; }
    public int lifeNumber() { return lifeNumber; }
    public boolean alive() { return alive; }
    public long deathGameTime() { return deathGameTime; }
    public String deathCause() { return deathCause; }
    public int historicalImportance() { return historicalImportance; }
    public int rescueCount() { return rescueCount; }
    public int precedence() { return NpcIdentity.precedence(socialClass(), office()); }

    public void setIdentity(NpcSocialClass socialClass, NpcOffice office, String factionId) {
        this.socialClass = (socialClass == null ? NpcSocialClass.COMMONER : socialClass).name();
        this.office = (office == null ? NpcOffice.NONE : office).name();
        this.factionId = normalizeFaction(factionId);
    }

    public void markRescued() { rescueCount++; }

    public void markDead(long gameTime, String cause) {
        alive = false;
        deathGameTime = gameTime;
        deathCause = cause == null ? "" : cause;
        historicalImportance = Math.max(historicalImportance,
                5 + precedence() * 10 + rescueCount * 2);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        tag.putString("Name", name);
        tag.putString("SocialClass", socialClass);
        tag.putString("Office", office);
        tag.putString("Faction", factionId);
        tag.putLong("Born", bornGameTime);
        tag.putInt("LifeNumber", lifeNumber);
        tag.putBoolean("Alive", alive);
        tag.putLong("Death", deathGameTime);
        tag.putString("DeathCause", deathCause);
        tag.putInt("HistoricalImportance", historicalImportance);
        tag.putInt("RescueCount", rescueCount);
        return tag;
    }

    @Nullable
    public static CharacterRecord load(CompoundTag tag) {
        if (!tag.read("Id", UUIDUtil.CODEC).isPresent()) return null;
        CharacterRecord record = new CharacterRecord(
                tag.read("Id", UUIDUtil.CODEC).orElseThrow(),
                tag.getStringOr("Name", ""),
                NpcSocialClass.byName(tag.getStringOr("SocialClass", "")),
                NpcOffice.byName(tag.getStringOr("Office", "")),
                tag.getStringOr("Faction", ""),
                tag.getLongOr("Born", 0L),
                tag.getIntOr("LifeNumber", 0));
        record.alive = !tag.contains("Alive") || tag.getBooleanOr("Alive", false);
        record.deathGameTime = tag.contains("Death") ? tag.getLongOr("Death", 0L) : -1L;
        record.deathCause = tag.getStringOr("DeathCause", "");
        record.historicalImportance = tag.getIntOr("HistoricalImportance", 0);
        record.rescueCount = tag.getIntOr("RescueCount", 0);
        return record;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "无名旅人";
        String cleaned = name.replaceAll("§[0-9a-fk-or]", "").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private static String normalizeFaction(String factionId) {
        if (factionId == null || factionId.isBlank()) return "sagadyssey:wilderness";
        return factionId.contains(":") ? factionId : "sagadyssey:" + factionId;
    }
}
