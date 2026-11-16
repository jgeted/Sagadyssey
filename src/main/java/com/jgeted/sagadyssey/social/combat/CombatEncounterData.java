package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CombatEncounterData extends NbtSavedData {
    private static final String DATA_NAME = "sagadyssey_combat_encounters";
    private static final Factory<CombatEncounterData> FACTORY =
            new Factory<>(CombatEncounterData::new, CombatEncounterData::load);
    private final Map<UUID, CombatEncounter> encounters = new HashMap<>();
    private final java.util.Set<UUID> reportedCapacity = new java.util.HashSet<>();
    private final Map<UUID, java.util.Set<UUID>> activeByCharacter = new HashMap<>();
    private final Map<UUID, CombatImpact> settlement = new HashMap<>();
    private final Map<UUID,List<CombatImpact>> frozenByEncounter = new HashMap<>();
    private record Owner(String kind,UUID id,UUID principal){}
    private record HostKey(UUID encounter,String kind,UUID owner){}
    private final Map<HostKey,CombatEncounterBinding> bindings=new HashMap<>();
    private final Map<Owner,java.util.Set<UUID>> byOwner=new HashMap<>();
    private final Map<UUID,java.util.Set<HostKey>> byEncounter=new HashMap<>();

    public static CombatEncounterData get(MinecraftServer server) {
        var storage=server.overworld().getDataStorage();var data=storage.get(FACTORY.type(DATA_NAME));if(data!=null)return data;
        if(java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(DATA_NAME+".dat"))) throw new IllegalStateException("unreadable combat ledger; explicit recovery required");
        data=new CombatEncounterData();storage.set(FACTORY.type(DATA_NAME),data);return data;
    }

    public boolean add(CombatEncounter encounter) {
        if (encounters.containsKey(encounter.id())) return false;
        if (encounters.size() >= 4096) throw new IllegalStateException("encounter ledger full; explicit archival required");
        if (encounters.putIfAbsent(encounter.id(), encounter) != null) return false;
        index(encounter);
        setDirty();
        return true;
    }

    @Nullable public CombatEncounter encounter(UUID id) { return encounters.get(id); }
    public int size(){return encounters.size();}
    public boolean hostCapacityAvailable(){return encounters.size()<4096&&bindings.size()<8192;}
    public List<CombatEncounter> encounters() { return List.copyOf(encounters.values()); }

    void update(CombatEncounter encounter) {
        var previous = encounters.get(encounter.id());
        if (previous == null) throw new IllegalArgumentException("unknown encounter");
        if (previous == encounter) return;
        boolean wasActive = previous.status() != EncounterStatus.ENDED;
        boolean remainsActive = encounter.status() != EncounterStatus.ENDED;
        boolean membershipChanged = wasActive != remainsActive || wasActive && !previous.sides().equals(encounter.sides());
        if (membershipChanged && wasActive) unindex(previous);
        encounters.put(encounter.id(), encounter);
        if (membershipChanged && remainsActive) index(encounter);
        setDirty();
    }

    private void index(CombatEncounter encounter) {
        if(encounter.status()==EncounterStatus.ENDED)return;
        for (var side : encounter.sides()) for (var character : side.members()) {
            var ids=activeByCharacter.computeIfAbsent(character, ignored -> new java.util.HashSet<>());
            ids.add(encounter.id());
        }
    }
    private void unindex(CombatEncounter e){for(var side:e.sides())for(var member:side.members()){var ids=activeByCharacter.get(member);if(ids!=null){ids.remove(e.id());if(ids.isEmpty())activeByCharacter.remove(member);}}}
    public List<CombatEncounter> activeEncounters(UUID character,long now){
        return activeByCharacter.getOrDefault(character,java.util.Set.of()).stream().map(encounters::get).filter(e->e.activeAt(now)&&e.sideOf(character)!=null)
                .sorted(java.util.Comparator.comparingLong(CombatEncounter::startedAt).thenComparing(CombatEncounter::id)).toList();
    }
    public List<CombatEncounter> hosted(String kind,UUID owner,UUID principal,long now){
        return byOwner.getOrDefault(new Owner(kind,owner,principal),java.util.Set.of()).stream().map(encounters::get).filter(e->e.activeAt(now))
                .sorted(java.util.Comparator.comparingLong(CombatEncounter::startedAt).thenComparing(CombatEncounter::id)).toList();
    }
    public List<CombatEncounterBinding> bindings(UUID encounter){return byEncounter.getOrDefault(encounter,java.util.Set.of()).stream().map(bindings::get).sorted(java.util.Comparator.comparing(CombatEncounterBinding::ownerKind).thenComparing(CombatEncounterBinding::owner)).toList();}
    public CombatEncounterBinding binding(UUID encounter,String kind,UUID owner){return bindings.get(new HostKey(encounter,kind,owner));}
    boolean reportCapacityOnce(UUID encounter) { return reportedCapacity.add(encounter); }

    boolean canContinue(CombatEncounter encounter) {
        int members = encounter.sides().stream().mapToInt(side -> side.members().size()).sum();
        int hosts = byEncounter.getOrDefault(encounter.id(), java.util.Set.of()).size();
        return encounters.size() < 4096 && bindings.size() + hosts <= 8192
                && (settling(encounter.id()) || settlement.size() + members <= 4096);
    }

    /** Start a fresh event window after the preceding encounter and its writeback are complete. */
    CombatEncounter continueAfter(CombatEncounter previous, long now) {
        var ended = encounters.get(previous.id());
        if (ended == null || ended.status() != EncounterStatus.ENDED || !settling(previous.id()))
            throw new IllegalStateException("the preceding encounter has not settled");
        var next = new CombatEncounter(UUID.randomUUID(), ended.settlementId(), ended.dimension(), ended.center(),
                ended.radius(), now, 0, EncounterStatus.ACTIVE, ended.sides(), List.of(), 0);
        add(next);
        for (var binding : bindings(previous.id())) {
            putBinding(new CombatEncounterBinding(binding.ownerKind(), binding.owner(), binding.principal(),
                    next.id(), binding.side()));
        }
        return next;
    }
    /** Same SavedData publication for roster and host reference. Callers must establish current authority independently. */
    boolean enlist(CombatEncounter expected,CombatEncounter next,@Nullable CombatEncounterBinding binding){
        if(!expected.equals(encounters.get(expected.id()))||settling(expected.id())||!expected.id().equals(next.id())||expected.status()==EncounterStatus.ENDED)return false;
        if(binding==null){update(next);return true;}
        validateBinding(next,binding);var key=new HostKey(binding.encounter(),binding.ownerKind(),binding.owner());var old=bindings.get(key);
        if(old!=null&&!old.equals(binding))throw new IllegalArgumentException("encounter host identity conflict");
        if(old==null&&bindings.size()>=8192)return false;
        update(next);if(old==null)putBinding(binding);return true;
    }
    boolean add(CombatEncounter encounter,CombatEncounterBinding binding){
        validateBinding(encounter,binding);if(bindings.size()>=8192)throw new IllegalStateException("encounter binding capacity");
        if(!add(encounter))return false;putBinding(binding);return true;
    }
    private static void validateBinding(CombatEncounter e,CombatEncounterBinding b){if(!e.id().equals(b.encounter())||e.sides().stream().noneMatch(s->s.id().equals(b.side())))throw new IllegalArgumentException("orphan encounter binding");}
    private void putBinding(CombatEncounterBinding b){var key=new HostKey(b.encounter(),b.ownerKind(),b.owner());if(bindings.putIfAbsent(key,b)!=null)throw new IllegalArgumentException("duplicate encounter binding");byOwner.computeIfAbsent(new Owner(b.ownerKind(),b.owner(),b.principal()),ignored->new java.util.HashSet<>()).add(b.encounter());byEncounter.computeIfAbsent(b.encounter(),ignored->new java.util.HashSet<>()).add(key);setDirty();}

    public boolean settling(UUID encounter) {
        return frozenByEncounter.containsKey(encounter);
    }

    /** Freeze context, counts and timestamp once. Replaying another NbtSavedData inbox uses exactly this snapshot. */
    public List<CombatImpact> freeze(UUID encounter, java.util.function.Supplier<List<CombatImpact>> snapshot) {
        var existing=frozenByEncounter.get(encounter);
        if (existing!=null) return existing;
        var values=List.copyOf(snapshot.get());
        if (values.isEmpty() || values.stream().anyMatch(i -> !encounter.equals(i.encounter())))
            throw new IllegalArgumentException("invalid encounter settlement snapshot");
        if (settlement.size()+values.size()>4096) throw new IllegalStateException("settlement snapshot ledger full");
        values.forEach(i -> CombatImpact.put(settlement,i));frozenByEncounter.put(encounter,values);setDirty();return values;
    }

    @Nullable
    public CombatEncounter activeFor(UUID characterId, long now) {
        return activeEncounters(characterId,now).stream().findFirst().orElse(null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        encounters.values().forEach(value -> list.add(value.save()));
        tag.put("Encounters", list);
        tag.putInt("Schema",2);
        var hosts=new ListTag();bindings.values().stream().sorted(java.util.Comparator.comparing(CombatEncounterBinding::encounter).thenComparing(CombatEncounterBinding::ownerKind).thenComparing(CombatEncounterBinding::owner)).forEach(b->hosts.add(b.save()));tag.put("Bindings",hosts);
        tag.put("CombatImpacts",CombatImpact.save(settlement));
        return tag;
    }

    public static CombatEncounterData load(CompoundTag tag, HolderLookup.Provider registries) {
        CombatEncounterData data = new CombatEncounterData();
        int schema=tag.getIntOr("Schema",1);
        if (tag.contains("Schema") && (!MinecraftApi.containsTag(tag, "Schema",Tag.TAG_INT)||(schema!=1&&schema!=2))) throw new IllegalArgumentException("unknown combat schema");
        if (!(tag.get("Encounters") instanceof ListTag rows)||rows.size()>4096||!rows.isEmpty()&&MinecraftApi.elementType(rows)!=Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid combat ledger");
        ListTag list = MinecraftApi.listOfType(tag, "Encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CombatEncounter value = CombatEncounter.load(list.getCompoundOrEmpty(index));
            if(data.encounters.putIfAbsent(value.id(), value)!=null) throw new IllegalArgumentException("duplicate encounter");
            data.index(value);
        }
        CombatImpact.load(tag,data.settlement);
        if(data.settlement.values().stream().anyMatch(i -> !data.encounters.containsKey(i.encounter()))) throw new IllegalArgumentException("orphan combat settlement");
        data.settlement.values().forEach(i -> data.frozenByEncounter.computeIfAbsent(i.encounter(),ignored -> new java.util.ArrayList<>()).add(i));
        data.frozenByEncounter.replaceAll((id,values)->List.copyOf(values));
        if(schema==2){
            if(!(tag.get("Bindings") instanceof ListTag hosts)||hosts.size()>8192||!hosts.isEmpty()&&MinecraftApi.elementType(hosts)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid encounter bindings");
            for(var raw:hosts){var b=CombatEncounterBinding.load((CompoundTag)raw);var e=data.encounters.get(b.encounter());if(e==null)throw new IllegalArgumentException("orphan encounter binding");validateBinding(e,b);data.putBinding(b);}
        }else if(tag.contains("Bindings"))throw new IllegalArgumentException("unexpected legacy encounter bindings");
        data.setDirty(false);
        return data;
    }
}
