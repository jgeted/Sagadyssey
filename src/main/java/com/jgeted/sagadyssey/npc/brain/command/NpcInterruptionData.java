package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.execution.PlanCancellationReason;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Linked receipts, not resurrection of terminal plans. Continuations must revalidate their domain obligation. */
public final class NpcInterruptionData extends NbtSavedData {
    public static final String NAME="sagadyssey_npc_interruptions_v3";
    public static final Factory<NpcInterruptionData> FACTORY=new Factory<>(NpcInterruptionData::new,NpcInterruptionData::load);
    public record Receipt(UUID plan,UUID body,UUID character,String provider,String candidate,
                          PlanCancellationReason reason,long interrupted,long expires,UUID continuation) {
        public Receipt { if(plan==null||body==null||character==null||provider==null||provider.isBlank()||candidate==null
                ||candidate.length()>512||reason==null||interrupted<0||expires<interrupted)throw new IllegalArgumentException("invalid interruption"); }
    }
    private final Map<UUID,Receipt> receipts=new LinkedHashMap<>();
    private final Map<UUID,UUID> pending=new HashMap<>();
    public static NpcInterruptionData get(MinecraftServer server){var s=server.overworld().getDataStorage();var d=s.get(FACTORY.type(NAME));if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable interruption ledger");
        d=new NpcInterruptionData();s.set(FACTORY.type(NAME),d);return d;}
    public List<Receipt> all(){return List.copyOf(receipts.values());}
    public Receipt pending(UUID body){return receipts.get(pending.get(body));}
    public void interrupted(NpcPlan p,UUID character,PlanCancellationReason reason,long now){
        if(receipts.containsKey(p.id()))return;
        if(receipts.size()>=4096)throw new IllegalStateException("interruption ledger full; explicit archival required");
        var r=new Receipt(p.id(),p.npcId(),character,p.providerId().toString(),p.candidateKey(),reason,now,
                Math.max(now,p.expiresAt()),null);receipts.put(p.id(),r);pending.put(p.npcId(),p.id());setDirty();
    }
    public void continued(NpcPlan next){var r=pending(next.npcId());
        if(r==null||!r.provider().equals(next.providerId().toString())||!r.candidate().equals(next.candidateKey()))return;
        receipts.put(r.plan(),new Receipt(r.plan(),r.body(),r.character(),r.provider(),r.candidate(),r.reason(),r.interrupted(),r.expires(),next.id()));
        pending.remove(r.body(),r.plan());setDirty();}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();for(var r:receipts.values()){
        var row=new CompoundTag();row.store("Plan", UUIDUtil.CODEC,r.plan());row.store("Body", UUIDUtil.CODEC,r.body());row.store("Character", UUIDUtil.CODEC,r.character());
        row.putString("Provider",r.provider());row.putString("Candidate",r.candidate());row.putString("Reason",r.reason().name());
        row.putLong("Interrupted",r.interrupted());row.putLong("Expires",r.expires());if(r.continuation()!=null)row.store("Continuation", UUIDUtil.CODEC,r.continuation());list.add(row);
    }t.put("Receipts",list);return t;}
    public static NpcInterruptionData load(CompoundTag t,HolderLookup.Provider lookup){
        if(!MinecraftApi.containsTag(t, "Schema",Tag.TAG_INT)||t.getIntOr("Schema", 0)!=1||!(t.get("Receipts") instanceof ListTag list)||list.size()>4096
                ||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid interruption ledger");
        var d=new NpcInterruptionData();for(var raw:list){var r=(CompoundTag)raw;
            for(var field:List.of("Plan","Body","Character"))if(!r.read(field, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing interruption identity");
            for(var field:List.of("Provider","Candidate","Reason"))if(!MinecraftApi.containsTag(r, field,Tag.TAG_STRING))throw new IllegalArgumentException("missing interruption detail");
            for(var field:List.of("Interrupted","Expires"))if(!MinecraftApi.containsTag(r, field,Tag.TAG_LONG))throw new IllegalArgumentException("missing interruption time");
            if(r.contains("Continuation")&&!r.read("Continuation", UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("invalid continuation identity");
            var p=new Receipt(r.read("Plan", UUIDUtil.CODEC).orElseThrow(),r.read("Body", UUIDUtil.CODEC).orElseThrow(),r.read("Character", UUIDUtil.CODEC).orElseThrow(),r.getStringOr("Provider", ""),r.getStringOr("Candidate", ""),
                    PlanCancellationReason.valueOf(r.getStringOr("Reason", "")),r.getLongOr("Interrupted", 0L),r.getLongOr("Expires", 0L),r.read("Continuation", UUIDUtil.CODEC).isPresent()?r.read("Continuation", UUIDUtil.CODEC).orElseThrow():null);
            if(d.receipts.putIfAbsent(p.plan(),p)!=null)throw new IllegalArgumentException("duplicate interruption");
            if(p.continuation()==null){var old=d.pending(p.body());if(old==null||p.interrupted()>=old.interrupted())d.pending.put(p.body(),p.plan());}
        }return d;
    }
}
