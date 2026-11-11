package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CombatEncounter(UUID id, @Nullable UUID settlementId,
                              String dimension, BlockPos center, int radius,
                              long startedAt, long endedAt, EncounterStatus status,
                              List<CombatSide> sides, List<EncounterEvent> events,
                              int revision) {
    public CombatEncounter {
        if (id == null || dimension == null || dimension.isBlank() || center == null
                || radius <= 0 || status == null || sides == null || sides.size() < 2
                || sides.size()>16 || radius>256 || startedAt<0 || endedAt<0
                || revision < 0) throw new IllegalArgumentException("invalid combat encounter");
        sides = List.copyOf(sides);
        events = List.copyOf(events == null ? List.of() : events);
        if(events.size()>256) throw new IllegalArgumentException("combat event budget exceeded");
    }

    @Nullable
    public CombatSide sideOf(UUID characterId) {
        return sides.stream().filter(side -> side.contains(characterId)).findFirst().orElse(null);
    }

    public boolean activeAt(long now) {
        return (status == EncounterStatus.FORMING || status == EncounterStatus.ACTIVE
                || status == EncounterStatus.ROUT) && now >= startedAt;
    }

    public CombatEncounter addEvent(EncounterEvent event) {
        if (!event.encounterId().equals(id)) throw new IllegalArgumentException("foreign event");
        if (events.stream().anyMatch(value -> value.id().equals(event.id()))) return this;
        if(status==EncounterStatus.ENDED) throw new IllegalStateException("ended encounter cannot accept new events");
        if(events.size()>=256) throw new IllegalStateException("combat event budget exceeded");
        List<EncounterEvent> updated = new ArrayList<>(events);
        updated.add(event);
        EncounterStatus next = event.type() == EncounterEventType.ROUTED
                ? EncounterStatus.ROUT : status == EncounterStatus.FORMING
                ? EncounterStatus.ACTIVE : status;
        return new CombatEncounter(id, settlementId, dimension, center, radius, startedAt,
                endedAt, next, sides, updated, revision + 1);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.store("Id", UUIDUtil.CODEC, id);
        if (settlementId != null) tag.store("Settlement", UUIDUtil.CODEC, settlementId);
        tag.putString("Dimension", dimension);
        tag.putLong("Center", center.asLong());
        tag.putInt("Radius", radius);
        tag.putLong("StartedAt", startedAt);
        tag.putLong("EndedAt", endedAt);
        tag.putString("Status", status.name());
        ListTag sideTags = new ListTag();
        sides.forEach(value -> sideTags.add(value.save()));
        tag.put("Sides", sideTags);
        ListTag eventTags = new ListTag();
        events.forEach(value -> eventTags.add(value.save()));
        tag.put("Events", eventTags);
        tag.putInt("Revision", revision);
        return tag;
    }

    static CombatEncounter load(CompoundTag tag) {
        List<CombatSide> sides = new ArrayList<>();
        ListTag sideTags = MinecraftApi.listOfType(tag, "Sides", Tag.TAG_COMPOUND);
        for (int index = 0; index < sideTags.size(); index++) sides.add(CombatSide.load(sideTags.getCompoundOrEmpty(index)));
        List<EncounterEvent> events = new ArrayList<>();
        ListTag eventTags = MinecraftApi.listOfType(tag, "Events", Tag.TAG_COMPOUND);
        for (int index = 0; index < eventTags.size(); index++) events.add(EncounterEvent.load(eventTags.getCompoundOrEmpty(index)));
        return new CombatEncounter(tag.read("Id", UUIDUtil.CODEC).orElseThrow(),
                tag.read("Settlement", UUIDUtil.CODEC).isPresent() ? tag.read("Settlement", UUIDUtil.CODEC).orElseThrow() : null,
                tag.getStringOr("Dimension", ""), BlockPos.of(tag.getLongOr("Center", 0L)),
                tag.getIntOr("Radius", 0), tag.getLongOr("StartedAt", 0L), tag.getLongOr("EndedAt", 0L),
                EncounterStatus.valueOf(tag.getStringOr("Status", "")), sides, events,
                tag.getIntOr("Revision", 0));
    }
}
