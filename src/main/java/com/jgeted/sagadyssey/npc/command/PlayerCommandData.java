package com.jgeted.sagadyssey.npc.command;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个玩家对应一个集团。集团包含多个小队，NPC 的编组和指挥等级都挂靠在这里。
 * UUID 以字符串保存，避免 Codec 对复杂 Map key 的限制。
 */
public final class PlayerCommandData {
    public static final String MAIN_SQUAD = "main";
    public static final int MAX_MEMBERS = 1_024;
    private static final int[] SQUAD_COLORS = {
            0x42A5F5, 0x66BB6A, 0xFFCA28, 0xAB47BC,
            0xEF5350, 0x26C6DA, 0xFFA726, 0xEC407A
    };

    private final Map<String, String> memberSquads = new HashMap<>();
    private final Map<String, String> memberRanks = new HashMap<>();
    private final Map<String, String> memberNames = new HashMap<>();
    private final Map<String, String> squadNames = new HashMap<>();
    private final Map<String, String> squadStrategies = new HashMap<>();
    private final Map<String, Integer> squadColors = new HashMap<>();
    private int nextSquadNumber = 1;
    private String activeSquadId = MAIN_SQUAD;

    // 选择和框选只在本次在线会话中有效，不写入存档。
    private final Set<UUID> selectedMembers = new HashSet<>();
    @Nullable private BlockPos corner1;
    @Nullable private BlockPos corner2;

    public PlayerCommandData() {
        ensureDefaults();
    }

    private void ensureDefaults() {
        squadNames.putIfAbsent(MAIN_SQUAD, "主力队");
        squadStrategies.putIfAbsent(MAIN_SQUAD, SquadStrategy.BALANCED.name());
        squadColors.putIfAbsent(MAIN_SQUAD, SQUAD_COLORS[0]);
        if (!squadNames.containsKey(activeSquadId)) activeSquadId = MAIN_SQUAD;
    }

    public void registerMember(UUID npcId, String name) {
        String id = npcId.toString();
        if (!memberSquads.containsKey(id) && memberSquads.size() >= MAX_MEMBERS) return;
        memberSquads.putIfAbsent(id, MAIN_SQUAD);
        memberRanks.putIfAbsent(id, CommandRank.MEMBER.name());
        memberNames.put(id, name);
        ensureDefaults();
    }

    public void unregisterMember(UUID npcId) {
        String id = npcId.toString();
        memberSquads.remove(id);
        memberRanks.remove(id);
        memberNames.remove(id);
        selectedMembers.remove(npcId);
    }

    public boolean hasMember(UUID npcId) { return memberSquads.containsKey(npcId.toString()); }
    public String squadOf(UUID npcId) { return memberSquads.getOrDefault(npcId.toString(), MAIN_SQUAD); }
    public CommandRank rankOf(UUID npcId) { return CommandRank.byName(memberRanks.get(npcId.toString())); }
    public SquadStrategy strategyOf(UUID npcId) { return strategy(squadOf(npcId)); }
    public String memberName(UUID npcId) { return memberNames.getOrDefault(npcId.toString(), "NPC"); }

    public Set<UUID> selectedMembers() { return Set.copyOf(selectedMembers); }
    public boolean isSelected(UUID npcId) { return selectedMembers.contains(npcId); }
    public void toggleSelected(UUID npcId) {
        if (!selectedMembers.remove(npcId)) selectedMembers.add(npcId);
    }
    public void select(UUID npcId) { selectedMembers.add(npcId); }
    public void clearSelectedMembers() { selectedMembers.clear(); }
    public void clearSelection() { selectedMembers.clear(); corner1 = null; corner2 = null; }

    /** 永久角色死亡后清除上一世集团；原 NPC 仍保留旧 Character ID，等待继承或重新招募。 */
    public void resetForNewCharacter() {
        memberSquads.clear();
        memberRanks.clear();
        memberNames.clear();
        squadNames.clear();
        squadStrategies.clear();
        squadColors.clear();
        nextSquadNumber = 1;
        activeSquadId = MAIN_SQUAD;
        clearSelection();
        ensureDefaults();
    }

    @Nullable public BlockPos corner1() { return corner1; }
    @Nullable public BlockPos corner2() { return corner2; }
    public void setCorner1(BlockPos pos) { corner1 = pos.immutable(); corner2 = null; }
    public void setCorner2(BlockPos pos) { corner2 = pos.immutable(); }

    public String activeSquadId() { ensureDefaults(); return activeSquadId; }
    public String activeSquadName() { return squadNames.getOrDefault(activeSquadId(), "主力队"); }
    public int activeSquadColor() { return color(activeSquadId()); }
    public SquadStrategy activeStrategy() { return strategy(activeSquadId()); }

    public List<String> squadIds() {
        ensureDefaults();
        List<String> ids = new ArrayList<>(squadNames.keySet());
        ids.sort((a, b) -> {
            if (a.equals(MAIN_SQUAD)) return -1;
            if (b.equals(MAIN_SQUAD)) return 1;
            return a.compareTo(b);
        });
        return ids;
    }

    public String squadName(String squadId) { return squadNames.getOrDefault(squadId, squadId); }
    public SquadStrategy strategy(String squadId) {
        return SquadStrategy.byName(squadStrategies.get(squadId));
    }
    public int color(String squadId) { return squadColors.getOrDefault(squadId, SQUAD_COLORS[0]); }
    public long memberCount(String squadId) {
        return memberSquads.values().stream().filter(squadId::equals).count();
    }

    /** 新建小队并把当前选中成员拆分进去。 */
    public String createSquadFromSelection() {
        String id = "squad_" + nextSquadNumber;
        int color = SQUAD_COLORS[nextSquadNumber % SQUAD_COLORS.length];
        squadNames.put(id, "第 " + nextSquadNumber + " 小队");
        squadStrategies.put(id, SquadStrategy.BALANCED.name());
        squadColors.put(id, color);
        nextSquadNumber++;
        activeSquadId = id;
        assignSelectionToActive();
        return id;
    }

    public void cycleActiveSquad() {
        List<String> ids = squadIds();
        int index = ids.indexOf(activeSquadId());
        activeSquadId = ids.get((index + 1) % ids.size());
    }

    public void assignSelectionToActive() {
        for (UUID id : selectedMembers) {
            if (hasMember(id)) memberSquads.put(id.toString(), activeSquadId());
        }
    }

    /** 删除当前小队并把成员组合回主力队。 */
    public boolean mergeActiveIntoMain() {
        if (MAIN_SQUAD.equals(activeSquadId())) return false;
        String removed = activeSquadId;
        memberSquads.replaceAll((id, squad) -> removed.equals(squad) ? MAIN_SQUAD : squad);
        squadNames.remove(removed);
        squadStrategies.remove(removed);
        squadColors.remove(removed);
        activeSquadId = MAIN_SQUAD;
        return true;
    }

    public void selectActiveSquad() {
        selectedMembers.clear();
        memberSquads.forEach((id, squad) -> {
            if (activeSquadId().equals(squad)) {
                try { selectedMembers.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
            }
        });
    }

    public void changeSelectedRank(boolean promote) {
        for (UUID id : selectedMembers) {
            if (!hasMember(id)) continue;
            CommandRank rank = rankOf(id);
            memberRanks.put(id.toString(), (promote ? rank.promote() : rank.demote()).name());
        }
    }

    public void cycleActiveStrategy() {
        squadStrategies.put(activeSquadId(), activeStrategy().next().name());
    }

    public Map<String, String> memberSquadsView() { return Map.copyOf(memberSquads); }
    public Map<String, String> memberRanksView() { return Map.copyOf(memberRanks); }
    public Map<String, String> memberNamesView() { return Map.copyOf(memberNames); }
    public Map<String, String> squadNamesView() { ensureDefaults(); return Map.copyOf(squadNames); }
    public Map<String, String> squadStrategiesView() { ensureDefaults(); return Map.copyOf(squadStrategies); }
    public Map<String, Integer> squadColorsView() { ensureDefaults(); return Map.copyOf(squadColors); }

    public static final Codec<PlayerCommandData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("memberSquads", Map.of()).forGetter(PlayerCommandData::memberSquadsView),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("memberRanks", Map.of()).forGetter(PlayerCommandData::memberRanksView),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("memberNames", Map.of()).forGetter(PlayerCommandData::memberNamesView),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("squadNames", Map.of()).forGetter(PlayerCommandData::squadNamesView),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("squadStrategies", Map.of()).forGetter(PlayerCommandData::squadStrategiesView),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("squadColors", Map.of()).forGetter(PlayerCommandData::squadColorsView),
            Codec.INT.optionalFieldOf("nextSquadNumber", 1).forGetter(d -> d.nextSquadNumber),
            Codec.STRING.optionalFieldOf("activeSquad", MAIN_SQUAD).forGetter(PlayerCommandData::activeSquadId)
    ).apply(instance, (memberSquads, memberRanks, memberNames, squadNames, strategies, colors, nextNumber, active) -> {
        PlayerCommandData data = new PlayerCommandData();
        data.memberSquads.putAll(memberSquads);
        data.memberRanks.putAll(memberRanks);
        data.memberNames.putAll(memberNames);
        data.squadNames.putAll(squadNames);
        data.squadStrategies.putAll(strategies);
        data.squadColors.putAll(colors);
        data.nextSquadNumber = Math.max(1, nextNumber);
        data.activeSquadId = active;
        data.ensureDefaults();
        return data;
    }));
}
