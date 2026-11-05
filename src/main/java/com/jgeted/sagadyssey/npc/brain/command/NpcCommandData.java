package com.jgeted.sagadyssey.npc.brain.command;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Schema 1: absent old-world file means no imported authority; corrupt file fails closed. */
public final class NpcCommandData extends SavedData {
    public static final String NAME="sagadyssey_npc_commands_v3";
    public static final Factory<NpcCommandData> FACTORY=new Factory<>(NpcCommandData::new,NpcCommandData::load);
    private final Map<UUID,NpcCommandIntent> intents=new LinkedHashMap<>();
    private final Map<UUID,UUID> active=new HashMap<>(), queued=new HashMap<>();
    public static NpcCommandData get(MinecraftServer server) {
        var storage=server.overworld().getDataStorage();var d=storage.get(FACTORY,NAME);if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))
            throw new IllegalStateException("existing NPC command ledger unreadable; restore consistent backup");
        d=new NpcCommandData();storage.set(NAME,d);return d;
    }
    public List<NpcCommandIntent> all(){return List.copyOf(intents.values());}
    public List<NpcCommandIntent> known(UUID issuer,UUID character){return intents.values().stream()
            .filter(i->i.issuer().equals(issuer)&&i.character().equals(character)).toList();}
    public NpcCommandIntent find(UUID id){return intents.get(id);}
    public Optional<NpcCommandIntent> active(UUID character){return by(character,NpcCommandIntent.Status.ACTIVE);}
    public Optional<NpcCommandIntent> queued(UUID character){return by(character,NpcCommandIntent.Status.QUEUED);}
    private Optional<NpcCommandIntent> by(UUID c,NpcCommandIntent.Status status){return Optional.ofNullable(intents.get((status==NpcCommandIntent.Status.ACTIVE?active:queued).get(c)));}
    public void put(NpcCommandIntent intent) {
        var old=intents.get(intent.id());
        if(old!=null&&(!old.sameIntent(intent)||old.closed()&&!old.equals(intent)
                ||intent.updated()<old.updated()||intent.leg()<old.leg()
                ||old.status()==NpcCommandIntent.Status.ACTIVE&&intent.status()==NpcCommandIntent.Status.QUEUED))
            throw new IllegalStateException("command identity/terminal result is immutable");
        var slots=intent.status()==NpcCommandIntent.Status.ACTIVE?active:queued;
        if(!intent.closed()&&slots.containsKey(intent.character())&&!slots.get(intent.character()).equals(intent.id()))
            throw new IllegalStateException("only one active and one queued command per character");
        if(old==null&&intents.size()>=4096)throw new IllegalStateException("command ledger full; explicit archival required");
        if(old!=null){active.remove(old.character(),old.id());queued.remove(old.character(),old.id());}
        if(!intent.closed())slots.put(intent.character(),intent.id());
        intents.put(intent.id(),intent);setDirty();
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();intents.values().forEach(i->list.add(i.save()));t.put("Intents",list);return t;}
    public static NpcCommandData load(CompoundTag t,HolderLookup.Provider lookup){
        if(!t.contains("Schema",Tag.TAG_INT)||t.getInt("Schema")!=1||!(t.get("Intents") instanceof ListTag list)
                ||list.size()>4096||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("unsupported/corrupt command ledger");
        var d=new NpcCommandData();for(var tag:list){var i=NpcCommandIntent.load((CompoundTag)tag);if(d.find(i.id())!=null)throw new IllegalArgumentException("duplicate command id");d.put(i);}return d;
    }
}
