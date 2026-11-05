package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.world.WorldScenarioData;
import com.jgeted.sagadyssey.social.world.WorldScenarioType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 世界政治状态：阵营关系、合法性和实际人物担任的统治者。 */
public final class PoliticalStateData extends SavedData {
    private static final String DATA_NAME = "sagadyssey_politics";
    private static final Factory<PoliticalStateData> FACTORY =
            new Factory<>(PoliticalStateData::new, PoliticalStateData::load);

    private boolean initialized;
    private final Map<String, String> relations = new HashMap<>();
    private final Map<String, UUID> rulers = new HashMap<>();
    private final Map<String, Integer> legitimacy = new HashMap<>();
    private final Map<String, Boolean> officeVacant = new HashMap<>();
    /** 具体聚落头衔、人物封臣关系与指定继承人。 */
    private final Map<UUID, UUID> titleHolders = new HashMap<>();
    private final Map<UUID, UUID> lieges = new HashMap<>();
    private final Map<UUID, UUID> designatedHeirs = new HashMap<>();

    public static PoliticalStateData get(MinecraftServer server) {
        PoliticalStateData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        data.ensureInitialized(server);
        return data;
    }

    public PoliticalRelation relation(String sourceFaction, String targetFaction) {
        if (normalize(sourceFaction).equals(normalize(targetFaction))) return PoliticalRelation.ALLIED;
        return PoliticalRelation.byName(relations.get(pair(sourceFaction, targetFaction)));
    }

    public boolean hasRelation(String sourceFaction, String targetFaction) {
        return relations.containsKey(pair(sourceFaction, targetFaction));
    }

    public void setRelation(MinecraftServer server, String sourceFaction, String targetFaction,
                            PoliticalRelation relation, long gameTime) {
        String source = normalize(sourceFaction);
        String target = normalize(targetFaction);
        if (source.equals(target)) return;
        legitimacy.putIfAbsent(source, 25);
        legitimacy.putIfAbsent(target, 25);
        officeVacant.putIfAbsent(source, true);
        officeVacant.putIfAbsent(target, true);
        relations.put(pair(source, target), relation.name());
        if (relation.symmetric()) {
            relations.put(pair(target, source), relation.name());
        } else if (relation == PoliticalRelation.VASSAL) {
            relations.put(pair(target, source), PoliticalRelation.OVERLORD.name());
        } else if (relation == PoliticalRelation.OVERLORD) {
            relations.put(pair(target, source), PoliticalRelation.VASSAL.name());
        }
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "political_relation", null,
                source + " / " + target, relation.name(), relation == PoliticalRelation.WAR ? 70 : 35);
    }

    @Nullable
    public UUID ruler(String factionId) { return rulers.get(normalize(factionId)); }
    public boolean isOfficeVacant(String factionId) {
        return officeVacant.getOrDefault(normalize(factionId), true);
    }
    /** 无具体人物持有职位，但由议会、教团或其他制度维持统治。 */
    public boolean isInstitutionalRule(String factionId) {
        String faction = normalize(factionId);
        return !rulers.containsKey(faction) && !isOfficeVacant(faction);
    }
    public int legitimacy(String factionId) {
        return legitimacy.getOrDefault(normalize(factionId), 25);
    }

    public Set<String> knownFactions() { return Set.copyOf(legitimacy.keySet()); }

    @Nullable public UUID titleHolder(UUID settlementId) { return titleHolders.get(settlementId); }
    @Nullable public UUID liegeOf(UUID characterId) { return lieges.get(characterId); }
    @Nullable public UUID designatedHeir(UUID characterId) { return designatedHeirs.get(characterId); }
    public Set<UUID> titlesOf(UUID characterId) {
        return titleHolders.entrySet().stream().filter(entry -> characterId.equals(entry.getValue()))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public long vassalCount(UUID characterId) {
        return lieges.values().stream().filter(characterId::equals).count();
    }
    public boolean isRuler(String factionId, UUID characterId) {
        return characterId != null && characterId.equals(ruler(factionId));
    }

    @Nullable
    public String ruledFaction(UUID characterId) {
        return rulers.entrySet().stream().filter(entry -> characterId.equals(entry.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public boolean canGrantTitle(String factionId, UUID settlementId, UUID characterId) {
        if (isRuler(factionId, characterId)) return true;
        UUID holder = titleHolders.get(settlementId);
        return characterId.equals(holder);
    }

    public boolean grantTitle(MinecraftServer server, UUID settlementId, String settlementName,
                              String factionId, UUID characterId, String characterName, long gameTime) {
        titleHolders.put(settlementId, characterId);
        legitimacy.putIfAbsent(normalize(factionId), 25);
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "title_granted", characterId,
                characterName, settlementName + " / " + normalize(factionId), 55);
        return true;
    }

    public void revokeTitle(MinecraftServer server,UUID settlement,UUID expected,String reason,long now){
        if(expected!=null&&titleHolders.remove(settlement,expected)){setDirty();WorldHistoryData.get(server).append(now,"title_vacant",expected,"聚落职位空缺",reason,55);}
    }

    public boolean swearFealty(MinecraftServer server, UUID vassal, String vassalName,
                               UUID liege, String liegeName, long gameTime) {
        if (vassal.equals(liege) || isInLiegeChain(liege, vassal)) return false;
        lieges.put(vassal, liege);
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "fealty_sworn", vassal,
                vassalName, liegeName, 45);
        return true;
    }

    public boolean designateHeir(MinecraftServer server, UUID holder, String holderName,
                                 UUID heir, String heirName, long gameTime) {
        if (holder.equals(heir) || (titlesOf(holder).isEmpty() && !rulers.containsValue(holder))) return false;
        designatedHeirs.put(holder, heir);
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "heir_designated", holder,
                holderName, heirName, 50);
        return true;
    }

    private boolean isInLiegeChain(UUID start, UUID sought) {
        UUID cursor = start;
        Set<UUID> visited = new java.util.HashSet<>();
        for (int depth = 0; depth < 1_024 && cursor != null; depth++) {
            if (cursor.equals(sought)) return true;
            if (!visited.add(cursor)) return true;
            cursor = lieges.get(cursor);
        }
        return cursor != null;
    }

    public void appointRuler(MinecraftServer server, String factionId, UUID characterId,
                             String characterName, long gameTime) {
        String faction = normalize(factionId);
        var previousRealms = rulers.entrySet().stream()
                .filter(entry -> entry.getValue().equals(characterId) && !entry.getKey().equals(faction))
                .map(Map.Entry::getKey).toList();
        for (String previous : previousRealms) {
            rulers.remove(previous);
            officeVacant.put(previous, true);
            legitimacy.put(previous, Math.max(0, legitimacy(previous) - 15));
        }
        rulers.put(faction, characterId);
        officeVacant.put(faction, false);
        legitimacy.put(faction, Math.max(35, legitimacy(faction)));
        setDirty();
        WorldHistoryData.get(server).append(gameTime, "ruler_appointed", characterId,
                characterName, faction, 85);
    }

    /** 场景初始化或明确的政治事件可据此宣布王位空缺。 */
    public void declareVacancy(MinecraftServer server, String factionId, String reason, long gameTime) {
        String faction = normalize(factionId);
        UUID previous = rulers.remove(faction);
        boolean changed = previous != null || !officeVacant.getOrDefault(faction, true);
        officeVacant.put(faction, true);
        legitimacy.put(faction, Math.min(legitimacy(faction), 10));
        setDirty();
        if (changed) {
            WorldHistoryData.get(server).append(gameTime, "throne_vacant", previous,
                    faction, reason, 90);
        }
    }

    public void vacateOfficesForCharacter(MinecraftServer server, UUID characterId,
                                          String characterName, long gameTime) {
        var held = rulers.entrySet().stream()
                .filter(entry -> entry.getValue().equals(characterId))
                .map(Map.Entry::getKey).toList();
        if (held.isEmpty()) return;
        for (String faction : held) {
            rulers.remove(faction);
            officeVacant.put(faction, true);
            legitimacy.put(faction, Math.max(0, legitimacy(faction) - 30));
            WorldHistoryData.get(server).append(gameTime, "throne_vacant", characterId,
                    characterName, faction, 90);
        }
        setDirty();
    }

    /** 人物永久死亡时在服务器权威数据中执行继承，与新玩家角色无关。 */
    public void handleCharacterDeath(MinecraftServer server, UUID characterId,
                                     String characterName, long gameTime) {
        UUID heir = designatedHeirs.remove(characterId);
        designatedHeirs.entrySet().removeIf(entry -> characterId.equals(entry.getValue()));
        var heirRecord = heir == null ? null
                : com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(server).byId(heir);
        if (heirRecord == null || !heirRecord.alive()) heir = null;

        UUID finalHeir = heir;
        var heldTitles = titleHolders.entrySet().stream()
                .filter(entry -> characterId.equals(entry.getValue())).map(Map.Entry::getKey).toList();
        for (UUID title : heldTitles) {
            if (finalHeir != null) titleHolders.put(title, finalHeir);
            else titleHolders.remove(title);
        }

        var heldRealms = rulers.entrySet().stream().filter(entry -> entry.getValue().equals(characterId))
                .map(Map.Entry::getKey).toList();
        for (String faction : heldRealms) {
            if (finalHeir != null) {
                rulers.put(faction, finalHeir);
                officeVacant.put(faction, false);
                legitimacy.put(faction, Math.max(15, legitimacy(faction) - 12));
            } else {
                rulers.remove(faction);
                officeVacant.put(faction, true);
                legitimacy.put(faction, Math.max(0, legitimacy(faction) - 30));
            }
        }

        UUID inheritedLiege = lieges.remove(characterId);
        if (finalHeir != null && inheritedLiege != null && !finalHeir.equals(inheritedLiege)) {
            lieges.put(finalHeir, inheritedLiege);
        }
        if (finalHeir != null) {
            lieges.replaceAll((vassal, liege) -> characterId.equals(liege) ? finalHeir : liege);
            lieges.remove(finalHeir, finalHeir);
            WorldHistoryData.get(server).append(gameTime, "succession", finalHeir,
                    heirRecord.name(), characterName, 90);
        } else {
            lieges.entrySet().removeIf(entry -> characterId.equals(entry.getValue()));
            for (String faction : heldRealms) {
                WorldHistoryData.get(server).append(gameTime, "throne_vacant", characterId,
                        characterName, faction, 90);
            }
            for (UUID title : heldTitles) {
                WorldHistoryData.get(server).append(gameTime, "title_vacant", characterId,
                        characterName, title.toString(), 65);
            }
        }
        setDirty();
    }

    private void ensureInitialized(MinecraftServer server) {
        if (initialized) return;
        initialized = true;
        WorldScenarioType scenario = WorldScenarioData.get(server).scenario();
        for (String faction : Set.of("sagadyssey:kingdom", "sagadyssey:church",
                "sagadyssey:merchant_guild", "sagadyssey:bandit",
                "sagadyssey:dwarven", "sagadyssey:mystic")) {
            legitimacy.put(faction, faction.equals("sagadyssey:kingdom")
                    ? (scenario.sovereignAlive() ? 70 : 10) : 45);
            officeVacant.put(faction, faction.equals("sagadyssey:kingdom")
                    ? !scenario.sovereignAlive() : false);
        }
        setInitialSymmetric("kingdom", "bandit", PoliticalRelation.WAR);
        setInitialSymmetric("kingdom", "church",
                scenario == WorldScenarioType.INTERREGNUM ? PoliticalRelation.TENSE : PoliticalRelation.ALLIED);
        setInitialSymmetric("kingdom", "merchant_guild", PoliticalRelation.FRIENDLY);
        setInitialSymmetric("dwarven", "mystic", PoliticalRelation.NEUTRAL);
        if (scenario == WorldScenarioType.BORDER_WAR) {
            setInitialSymmetric("kingdom", "dwarven", PoliticalRelation.TENSE);
        }
        setDirty();
    }

    private void setInitialSymmetric(String a, String b, PoliticalRelation relation) {
        relations.put(pair(a, b), relation.name());
        relations.put(pair(b, a), relation.name());
    }

    private static String pair(String source, String target) {
        return normalize(source) + "|" + normalize(target);
    }

    private static String normalize(String factionId) {
        if (factionId == null || factionId.isBlank()) return "sagadyssey:wilderness";
        return factionId.contains(":") ? factionId : "sagadyssey:" + factionId;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("Initialized", initialized);
        ListTag relationList = new ListTag();
        relations.forEach((key, value) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Pair", key);
            entry.putString("Relation", value);
            relationList.add(entry);
        });
        tag.put("Relations", relationList);
        ListTag factionList = new ListTag();
        legitimacy.forEach((faction, value) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Faction", faction);
            entry.putInt("Legitimacy", value);
            entry.putBoolean("Vacant", officeVacant.getOrDefault(faction, true));
            UUID ruler = rulers.get(faction);
            if (ruler != null) entry.putUUID("Ruler", ruler);
            factionList.add(entry);
        });
        tag.put("Factions", factionList);
        ListTag titleList = new ListTag();
        titleHolders.forEach((settlement, holder) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Settlement", settlement);
            entry.putUUID("Holder", holder);
            titleList.add(entry);
        });
        tag.put("Titles", titleList);
        ListTag fealtyList = new ListTag();
        lieges.forEach((vassal, liege) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Vassal", vassal);
            entry.putUUID("Liege", liege);
            fealtyList.add(entry);
        });
        tag.put("Fealty", fealtyList);
        ListTag heirList = new ListTag();
        designatedHeirs.forEach((holder, heir) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Holder", holder);
            entry.putUUID("Heir", heir);
            heirList.add(entry);
        });
        tag.put("Heirs", heirList);
        return tag;
    }

    private static PoliticalStateData load(CompoundTag tag, HolderLookup.Provider registries) {
        PoliticalStateData data = new PoliticalStateData();
        data.initialized = tag.getBoolean("Initialized");
        ListTag relations = tag.getList("Relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < relations.size(); i++) {
            CompoundTag entry = relations.getCompound(i);
            data.relations.put(entry.getString("Pair"), entry.getString("Relation"));
        }
        ListTag factions = tag.getList("Factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < factions.size(); i++) {
            CompoundTag entry = factions.getCompound(i);
            String faction = normalize(entry.getString("Faction"));
            data.legitimacy.put(faction, entry.getInt("Legitimacy"));
            data.officeVacant.put(faction, entry.getBoolean("Vacant"));
            if (entry.hasUUID("Ruler")) data.rulers.put(faction, entry.getUUID("Ruler"));
        }
        ListTag titles = tag.getList("Titles", Tag.TAG_COMPOUND);
        for (int i = 0; i < titles.size(); i++) {
            CompoundTag entry = titles.getCompound(i);
            if (entry.hasUUID("Settlement") && entry.hasUUID("Holder"))
                data.titleHolders.put(entry.getUUID("Settlement"), entry.getUUID("Holder"));
        }
        ListTag fealty = tag.getList("Fealty", Tag.TAG_COMPOUND);
        for (int i = 0; i < fealty.size(); i++) {
            CompoundTag entry = fealty.getCompound(i);
            if (entry.hasUUID("Vassal") && entry.hasUUID("Liege"))
                data.lieges.put(entry.getUUID("Vassal"), entry.getUUID("Liege"));
        }
        ListTag heirs = tag.getList("Heirs", Tag.TAG_COMPOUND);
        for (int i = 0; i < heirs.size(); i++) {
            CompoundTag entry = heirs.getCompound(i);
            if (entry.hasUUID("Holder") && entry.hasUUID("Heir"))
                data.designatedHeirs.put(entry.getUUID("Holder"), entry.getUUID("Heir"));
        }
        return data;
    }
}
