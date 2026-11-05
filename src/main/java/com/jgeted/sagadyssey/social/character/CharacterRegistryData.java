package com.jgeted.sagadyssey.social.character;

import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;

/** 全世界唯一的角色注册表；存放当前账号绑定和所有已故人物档案。 */
public final class CharacterRegistryData extends SavedData {
    public static final String DATA_NAME = "sagadyssey_characters";
    public static final int DATA_VERSION = 2;
    public static final Factory<CharacterRegistryData> FACTORY =
            new Factory<>(CharacterRegistryData::new, CharacterRegistryData::load);

    private final Map<UUID, CharacterRecord> characters = new HashMap<>();
    private final Map<UUID, UUID> currentByAccount = new HashMap<>();
    private final Map<UUID, Integer> lifeCountByAccount = new HashMap<>();
    /** Stable character id -> last observed physical entity body. */
    private final Map<UUID, CharacterBodyRef> npcBodies = new HashMap<>();
    /** 仅供旧存档 NPC 补写 Character ID；永久死亡后仍指向首次迁移的旧角色。 */
    private final Map<UUID, UUID> legacyOwnerByAccount = new HashMap<>();

    public static CharacterRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public CharacterRecord current(UUID accountId) {
        UUID characterId = currentByAccount.get(accountId);
        CharacterRecord record = characterId == null ? null : characters.get(characterId);
        return record != null && record.alive() ? record : null;
    }

    @Nullable
    public CharacterRecord byId(UUID characterId) {
        return characters.get(characterId);
    }

    /** Compatibility bridge for projecting a character-scoped service back to an online account. */
    @Nullable
    public UUID accountForCharacter(UUID characterId) {
        return currentByAccount.entrySet().stream()
                .filter(entry -> entry.getValue().equals(characterId))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Resolves old account-only owner data without rebinding it to a later life. */
    @Nullable
    public UUID compatibilityOwnerCharacter(UUID accountId) {
        UUID legacy = legacyOwnerByAccount.get(accountId);
        if (legacy != null && characters.containsKey(legacy)) return legacy;
        CharacterRecord current = current(accountId);
        return current == null ? null : current.id();
    }

    /**
     * 为世界实体建立不依附玩家账号的永久人物档案。
     * 已存在的档案（包括已故档案）绝不会被重建或复活。
     */
    public CharacterRecord ensureNpcCharacter(UUID characterId, String name,
                                              NpcSocialClass socialClass, NpcOffice office,
                                              String factionId, long gameTime) {
        CharacterRecord existing = characters.get(characterId);
        if (existing != null) return existing;
        CharacterRecord record = new CharacterRecord(characterId, name, socialClass, office,
                factionId, gameTime, 1);
        characters.put(characterId, record);
        setDirty();
        return record;
    }

    /**
     * Records the current body without changing the character identity. A body can belong to only
     * one character at a time; rebinding removes stale reverse claims deterministically.
     */
    public void bindNpcBody(UUID characterId, String dimensionId, UUID entityId, long gameTime) {
        if (!characters.containsKey(characterId)) {
            throw new IllegalStateException("Unknown character: " + characterId);
        }
        npcBodies.entrySet().removeIf(entry -> !entry.getKey().equals(characterId)
                && entry.getValue().entityId().equals(entityId));
        CharacterBodyRef next = new CharacterBodyRef(dimensionId, entityId, gameTime);
        if (next.equals(npcBodies.get(characterId))) return;
        npcBodies.put(characterId, next);
        setDirty();
    }

    @Nullable
    public CharacterBodyRef bodyOf(UUID characterId) {
        return npcBodies.get(characterId);
    }

    /** Resolves a stable character id to its currently loaded body, repairing stale body hints. */
    @Nullable
    public NpcBase findLoadedNpc(MinecraftServer server, UUID characterId) {
        CharacterBodyRef hint = npcBodies.get(characterId);
        if (hint != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(hint.dimensionId())) continue;
                if (level.getEntity(hint.entityId()) instanceof NpcBase npc
                        && characterId.equals(npc.getCharacterId())) return npc;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof NpcBase npc)
                        || !characterId.equals(npc.getCharacterId())) continue;
                bindNpcBody(characterId, level.dimension().location().toString(),
                        npc.getUUID(), level.getGameTime());
                return npc;
            }
        }
        return null;
    }

    /** 只读快照，供一致性审计和历史系统使用。 */
    public List<CharacterRecord> allCharacters() {
        return List.copyOf(characters.values());
    }

    /** 供编年史、对话与管理指令读取；只返回已故人物，不暴露账号关联。 */
    public List<CharacterRecord> notableDead(int limit) {
        return characters.values().stream()
                .filter(record -> !record.alive())
                .sorted(Comparator.comparingInt(CharacterRecord::historicalImportance).reversed()
                        .thenComparing(Comparator.comparingLong(CharacterRecord::deathGameTime).reversed()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public CharacterRecord ensureCurrent(ServerPlayer player, PlayerIdentityData legacyIdentity) {
        CharacterRecord current = current(player.getUUID());
        if (current != null) return current;
        int previousLives = lifeCountByAccount.getOrDefault(player.getUUID(), 0);
        String name = previousLives == 0
                ? player.getGameProfile().getName()
                : generatedName(UUID.randomUUID());
        return createCharacter(player.getUUID(), name, legacyIdentity.socialClass(), legacyIdentity.office(),
                legacyIdentity.titleFactionId(), player.serverLevel().getGameTime());
    }

    /** 返回旧版仅存账号 UUID 的 NPC 应绑定到的人物。 */
    public UUID legacyOwnerCharacter(UUID accountId) {
        UUID legacy = legacyOwnerByAccount.get(accountId);
        if (legacy != null && characters.containsKey(legacy)) return legacy;
        CharacterRecord current = current(accountId);
        if (current == null) throw new IllegalStateException("Account has no current character: " + accountId);
        legacyOwnerByAccount.put(accountId, current.id());
        setDirty();
        return current.id();
    }

    public CharacterRecord createFreshLife(ServerPlayer player) {
        UUID idSeed = UUID.randomUUID();
        return createCharacter(player.getUUID(), generatedName(idSeed),
                NpcSocialClass.COMMONER, NpcOffice.NONE, "sagadyssey:wilderness",
                player.serverLevel().getGameTime(), idSeed);
    }

    private CharacterRecord createCharacter(UUID accountId, String name, NpcSocialClass socialClass,
                                            NpcOffice office, String factionId, long gameTime) {
        return createCharacter(accountId, name, socialClass, office, factionId, gameTime, UUID.randomUUID());
    }

    private CharacterRecord createCharacter(UUID accountId, String name, NpcSocialClass socialClass,
                                            NpcOffice office, String factionId, long gameTime, UUID characterId) {
        int lifeNumber = lifeCountByAccount.merge(accountId, 1, Integer::sum);
        CharacterRecord record = new CharacterRecord(characterId, name, socialClass, office,
                factionId, gameTime, lifeNumber);
        characters.put(characterId, record);
        currentByAccount.put(accountId, characterId);
        setDirty();
        return record;
    }

    @Nullable
    public CharacterRecord markCurrentDead(UUID accountId, long gameTime, String cause) {
        UUID characterId = currentByAccount.remove(accountId);
        if (characterId == null) return null;
        CharacterRecord record = characters.get(characterId);
        if (record != null && record.alive()) record.markDead(gameTime, cause);
        legacyOwnerByAccount.putIfAbsent(accountId, characterId);
        setDirty();
        return record;
    }

    /** 标记一个不依附玩家账号的世界人物死亡；重复死亡事件不会再次生效。 */
    @Nullable
    public CharacterRecord markCharacterDead(UUID characterId, long gameTime, String cause) {
        CharacterRecord record = characters.get(characterId);
        if (record == null || !record.alive()) return null;
        record.markDead(gameTime, cause);
        setDirty();
        return record;
    }

    public void markRescued(UUID accountId) {
        CharacterRecord record = current(accountId);
        if (record == null) return;
        record.markRescued();
        setDirty();
    }

    public void updateIdentity(UUID accountId, NpcSocialClass socialClass, NpcOffice office, String factionId) {
        CharacterRecord record = current(accountId);
        if (record == null) return;
        record.setIdentity(socialClass, office, factionId);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag characterList = new ListTag();
        characters.values().forEach(record -> characterList.add(record.save()));
        tag.put("Characters", characterList);

        ListTag bindingList = new ListTag();
        currentByAccount.forEach((accountId, characterId) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Account", accountId);
            entry.putUUID("Character", characterId);
            bindingList.add(entry);
        });
        tag.put("Bindings", bindingList);

        ListTag lifeList = new ListTag();
        lifeCountByAccount.forEach((accountId, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Account", accountId);
            entry.putInt("Count", count);
            lifeList.add(entry);
        });
        tag.put("LifeCounts", lifeList);

        ListTag legacyOwners = new ListTag();
        legacyOwnerByAccount.forEach((accountId, characterId) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Account", accountId);
            entry.putUUID("Character", characterId);
            legacyOwners.add(entry);
        });
        tag.put("LegacyOwners", legacyOwners);

        ListTag bodyList = new ListTag();
        npcBodies.forEach((characterId, body) -> {
            CompoundTag entry = body.save();
            entry.putUUID("Character", characterId);
            bodyList.add(entry);
        });
        tag.put("NpcBodies", bodyList);
        return tag;
    }

    private static CharacterRegistryData load(CompoundTag tag, HolderLookup.Provider registries) {
        CharacterRegistryData data = new CharacterRegistryData();
        ListTag characters = tag.getList("Characters", Tag.TAG_COMPOUND);
        for (int i = 0; i < characters.size(); i++) {
            CharacterRecord record = CharacterRecord.load(characters.getCompound(i));
            if (record != null) data.characters.put(record.id(), record);
        }
        ListTag bindings = tag.getList("Bindings", Tag.TAG_COMPOUND);
        for (int i = 0; i < bindings.size(); i++) {
            CompoundTag entry = bindings.getCompound(i);
            if (!entry.hasUUID("Account") || !entry.hasUUID("Character")) continue;
            UUID characterId = entry.getUUID("Character");
            CharacterRecord record = data.characters.get(characterId);
            if (record != null && record.alive()) {
                data.currentByAccount.put(entry.getUUID("Account"), characterId);
            }
        }
        ListTag lives = tag.getList("LifeCounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < lives.size(); i++) {
            CompoundTag entry = lives.getCompound(i);
            if (entry.hasUUID("Account")) {
                data.lifeCountByAccount.put(entry.getUUID("Account"), Math.max(0, entry.getInt("Count")));
            }
        }
        ListTag legacyOwners = tag.getList("LegacyOwners", Tag.TAG_COMPOUND);
        for (int i = 0; i < legacyOwners.size(); i++) {
            CompoundTag entry = legacyOwners.getCompound(i);
            if (entry.hasUUID("Account") && entry.hasUUID("Character")
                    && data.characters.containsKey(entry.getUUID("Character"))) {
                data.legacyOwnerByAccount.put(entry.getUUID("Account"), entry.getUUID("Character"));
            }
        }
        ListTag bodies = tag.getList("NpcBodies", Tag.TAG_COMPOUND);
        for (int i = 0; i < bodies.size(); i++) {
            CompoundTag entry = bodies.getCompound(i);
            if (!entry.hasUUID("Character")) continue;
            UUID characterId = entry.getUUID("Character");
            CharacterBodyRef body = CharacterBodyRef.load(entry);
            if (body != null && data.characters.containsKey(characterId)) {
                data.npcBodies.put(characterId, body);
            }
        }
        return data;
    }

    private static String generatedName(UUID seed) {
        String[] first = {"艾尔温", "罗兰", "梅芙", "托马斯", "伊莎贝尔", "加文", "艾达", "奥斯温",
                "塞德里克", "玛蒂尔达", "埃德温", "阿黛拉", "雷纳德", "莉奥娜", "休伯特", "贝伦"};
        String[] bynames = {"河畔", "灰鹿", "赤杨", "石桥", "远行者", "白鸦", "山风", "烛火",
                "橡木", "晨雾", "铁手", "麦穗", "无旗者", "冬溪", "旧", "旧路"};
        int a = Math.floorMod(seed.hashCode(), first.length);
        int b = Math.floorMod(Long.hashCode(seed.getLeastSignificantBits()), bynames.length);
        return first[a] + "·" + bynames[b];
    }
}
