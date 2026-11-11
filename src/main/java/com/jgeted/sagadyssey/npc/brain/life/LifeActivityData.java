package com.jgeted.sagadyssey.npc.brain.life;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Server-only intent/receipt ledger. Ambiguous interrupted settlements require review, never replay. */
public final class LifeActivityData extends NbtSavedData {
    public static final String NAME = "sagadyssey_life_activities_v3";
    public static final Factory<LifeActivityData> FACTORY = new Factory<>(LifeActivityData::new, LifeActivityData::load);
    private final Map<String, String> settlements = new HashMap<>();
    private final Map<UUID, Long> retry = new HashMap<>();
    private final Map<String, Long> started = new HashMap<>();
    private final Map<UUID, ConversationSession> sessions = new HashMap<>();
    private final Map<UUID, List<Place>> places = new HashMap<>();
    private final Set<UUID> recovered = new HashSet<>();

    public record Place(String dimension, BlockPos position, String activity) {
        public Place {
            if (dimension == null || dimension.isBlank() || activity == null || activity.isBlank())
                throw new IllegalArgumentException("invalid known place");
            position = Objects.requireNonNull(position).immutable();
            if (!BlockPos.of(position.asLong()).equals(position)) throw new IllegalArgumentException("invalid place position");
        }
    }
    public static LifeActivityData get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        var existing = storage.get(FACTORY.type(NAME));
        if (existing != null) return existing;
        if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat")))
            throw new IllegalStateException("unreadable life activity ledger; recovery required");
        var created = new LifeActivityData(); storage.set(FACTORY.type(NAME), created); return created;
    }
    public String settlement(String key) { return settlements.get(key); }
    public void pending(String key) {
        requireKey(key);
        if (settlements.putIfAbsent(key, "PENDING") == null) setDirty();
    }
    public void done(String key) {
        if (!"PENDING".equals(settlements.get(key))) throw new IllegalStateException("no pending settlement");
        settlements.put(key, "DONE"); setDirty();
    }
    public void abort(String key) { if (settlements.remove(key, "PENDING")) setDirty(); }
    private static void requireKey(String key) { if(key==null||key.isBlank()||key.length()>1024)throw new IllegalArgumentException("invalid settlement key"); }
    public long start(String key,long now) { requireKey(key);if(now<0)throw new IllegalArgumentException("negative activity time");if(!started.containsKey(key)) { started.put(key,now);setDirty(); } return started.get(key); }
    public void restart(String key,long now) { requireKey(key);if(now<0)throw new IllegalArgumentException("negative activity time");started.put(key,now);setDirty(); }
    public long retryAt(UUID npc) { return retry.getOrDefault(npc, 0L); }
    private static UUID placeKey(UUID npc,String dimension,BlockPos position) {
        return UUID.nameUUIDFromBytes(("life-place:"+npc+":"+dimension+":"+position.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public long placeRetryAt(UUID npc,String dimension,BlockPos position) { return retryAt(placeKey(npc,dimension,position)); }
    public void deferPlace(UUID npc,String dimension,BlockPos position,long now) { retry.put(placeKey(npc,dimension,position),Math.addExact(now,600));setDirty(); }
    public void defer(UUID npc, long now) { retry.put(npc, Math.addExact(now, 200)); setDirty(); }
    public List<Place> knownPlaces(UUID npc) { return List.copyOf(places.getOrDefault(npc, List.of())); }
    public void reveal(UUID npc, Place place) {
        var known = new ArrayList<>(knownPlaces(npc));
        if (!known.contains(place)) { if (known.size() >= 64) throw new IllegalStateException("known places full"); known.add(place); places.put(npc, known); setDirty(); }
    }
    public Collection<ConversationSession> sessions() { return List.copyOf(sessions.values()); }
    public void session(ConversationSession session) { sessions.put(session.id(), session); setDirty(); }
    public ConversationSession session(UUID id, long now) {
        var session = sessions.get(id);
        if (session != null && recovered.add(id)) { session = session.recover(now); session(session); }
        return session;
    }
    public void invite(ConversationSession session) { session(session); recovered.add(session.id()); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("Schema", 1);
        var starts = new ListTag(); started.forEach((key,time)-> { var t=new CompoundTag();t.putString("Key",key);t.putLong("Time",time);starts.add(t); });tag.put("Started",starts);
        var receipts = new ListTag(); settlements.forEach((key, state) -> { var t = new CompoundTag(); t.putString("Key", key); t.putString("State", state); receipts.add(t); }); tag.put("Settlements", receipts);
        var delays = new ListTag(); retry.forEach((npc, time) -> { var t = new CompoundTag(); t.store("Npc", UUIDUtil.CODEC, npc); t.putLong("Until", time); delays.add(t); }); tag.put("Retry", delays);
        var conversations = new ListTag(); sessions.values().forEach(s -> conversations.add(s.save())); tag.put("Sessions", conversations);
        var locations = new ListTag(); places.forEach((npc, known) -> known.forEach(p -> { var t = new CompoundTag(); t.store("Npc", UUIDUtil.CODEC, npc); t.putString("Dimension", p.dimension()); t.putLong("Position", p.position().asLong()); t.putString("Activity", p.activity()); locations.add(t); })); tag.put("Places", locations);
        return tag;
    }
    public static LifeActivityData load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!MinecraftApi.containsTag(tag, "Schema",Tag.TAG_INT) || tag.getIntOr("Schema", 0) != 1) throw new IllegalArgumentException("unknown life ledger schema");
        for(String field:List.of("Started","Settlements","Retry","Sessions","Places")) {
            if(!(tag.get(field) instanceof ListTag list)||MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND && !list.isEmpty())throw new IllegalArgumentException("invalid life list "+field);
        }
        var data = new LifeActivityData();
        for(Tag value:MinecraftApi.listOfType(tag, "Started",Tag.TAG_COMPOUND)) { var t=(CompoundTag)value;if(t.getStringOr("Key", "").isBlank()||!MinecraftApi.containsTag(t, "Time",Tag.TAG_LONG)||t.getLongOr("Time", 0L)<0||data.started.putIfAbsent(t.getStringOr("Key", ""),t.getLongOr("Time", 0L))!=null)throw new IllegalArgumentException("invalid start checkpoint"); }
        for (String field : List.of("Settlements", "Retry", "Sessions", "Places"))
            if (!MinecraftApi.containsTag(tag, field, Tag.TAG_LIST)) throw new IllegalArgumentException("missing " + field);
        for (Tag value : MinecraftApi.listOfType(tag, "Settlements", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value; String key = t.getStringOr("Key", ""), state = t.getStringOr("State", "");
            if (key.isBlank() || !(state.equals("PENDING") || state.equals("DONE")) || data.settlements.putIfAbsent(key, state) != null)
                throw new IllegalArgumentException("invalid/duplicate settlement");
        }
        for (Tag value : MinecraftApi.listOfType(tag, "Retry", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            if (!t.read("Npc", UUIDUtil.CODEC).isPresent() || !MinecraftApi.containsTag(t, "Until", Tag.TAG_LONG) || t.getLongOr("Until", 0L) < 0
                    || data.retry.putIfAbsent(t.read("Npc", UUIDUtil.CODEC).orElseThrow(), t.getLongOr("Until", 0L)) != null) throw new IllegalArgumentException("invalid retry");
        }
        for (Tag value : MinecraftApi.listOfType(tag, "Sessions", Tag.TAG_COMPOUND)) {
            var session = ConversationSession.load((CompoundTag) value);
            if (data.sessions.putIfAbsent(session.id(), session) != null) throw new IllegalArgumentException("duplicate conversation");
        }
        for (Tag value : MinecraftApi.listOfType(tag, "Places", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            if (!t.read("Npc", UUIDUtil.CODEC).isPresent() || !MinecraftApi.containsTag(t, "Position", Tag.TAG_LONG)) throw new IllegalArgumentException("invalid place");
            data.reveal(t.read("Npc", UUIDUtil.CODEC).orElseThrow(), new Place(t.getStringOr("Dimension", ""), BlockPos.of(t.getLongOr("Position", 0L)), t.getStringOr("Activity", "")));
        }
        data.setDirty(false); return data;
    }
}
