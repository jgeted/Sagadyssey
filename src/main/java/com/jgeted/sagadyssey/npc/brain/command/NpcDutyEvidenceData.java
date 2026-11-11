package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Original patient consent or shipment identity. No replacement proof and no grant of property rights. */
public final class NpcDutyEvidenceData extends NbtSavedData {
    public static final String NAME="sagadyssey_npc_duty_evidence_v3";
    public static final Factory<NpcDutyEvidenceData> FACTORY=new Factory<>(NpcDutyEvidenceData::new,NpcDutyEvidenceData::load);
    public record Evidence(UUID duty,NpcDutyData.Kind kind,UUID record,UUID principal,UUID agent,UUID target,long expires,boolean revoked) {
        public Evidence {if(duty==null||kind==null||record==null||principal==null||agent==null||target==null||expires<0
                ||kind!=NpcDutyData.Kind.MEDICAL&&kind!=NpcDutyData.Kind.ESCORT)throw new IllegalArgumentException("invalid duty proof");}
    }
    private final Map<UUID,Evidence> records=new LinkedHashMap<>();
    public static NpcDutyEvidenceData get(MinecraftServer server){var s=server.overworld().getDataStorage();var d=s.get(FACTORY.type(NAME));if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable duty evidence");
        d=new NpcDutyEvidenceData();s.set(FACTORY.type(NAME),d);return d;}
    public Evidence find(UUID duty){return records.get(duty);}
    public void put(Evidence e){var old=records.get(e.duty());if(old!=null){if(!old.equals(e))throw new IllegalStateException("original duty proof immutable");return;}
        if(records.size()>=4096)throw new IllegalStateException("duty evidence ledger full");records.put(e.duty(),e);setDirty();}
    /** Only the original patient's agency may revoke medical consent. Not the doctor's employer. */
    public boolean revoke(UUID patient,UUID duty){var e=records.get(duty);if(e==null||e.kind()!=NpcDutyData.Kind.MEDICAL||!patient.equals(e.principal()))return false;
        if(!e.revoked()){records.put(duty,new Evidence(e.duty(),e.kind(),e.record(),e.principal(),e.agent(),e.target(),e.expires(),true));setDirty();}return true;}
    public boolean accepts(NpcDutyData.Duty d,long now){var e=find(d.id());return e!=null&&!e.revoked()&&now<e.expires()&&e.expires()==d.expires()
            &&e.kind()==d.kind()&&e.agent().equals(d.character())&&e.target().equals(d.targetCharacter())
            &&(d.kind()!=NpcDutyData.Kind.MEDICAL||e.principal().equals(d.targetCharacter())&&e.record().equals(d.source()));}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var l=new ListTag();for(var e:records.values()){
        var r=new CompoundTag();r.store("Duty", UUIDUtil.CODEC,e.duty());r.putString("Kind",e.kind().name());r.store("Record", UUIDUtil.CODEC,e.record());r.store("Principal", UUIDUtil.CODEC,e.principal());
        r.store("Agent", UUIDUtil.CODEC,e.agent());r.store("Target", UUIDUtil.CODEC,e.target());r.putLong("Expires",e.expires());r.putBoolean("Revoked",e.revoked());l.add(r);
    }t.put("Evidence",l);return t;}
    public static NpcDutyEvidenceData load(CompoundTag t,HolderLookup.Provider lookup){if(!MinecraftApi.containsTag(t, "Schema",Tag.TAG_INT)||t.getIntOr("Schema", 0)!=1
            ||!(t.get("Evidence") instanceof ListTag l)||l.size()>4096||!l.isEmpty()&&MinecraftApi.elementType(l)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid duty evidence ledger");
        var d=new NpcDutyEvidenceData();for(var raw:l){var r=(CompoundTag)raw;for(var k:List.of("Duty","Record","Principal","Agent","Target"))if(!r.read(k, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing duty proof identity");
            if(!MinecraftApi.containsTag(r, "Kind",Tag.TAG_STRING)||!MinecraftApi.containsTag(r, "Expires",Tag.TAG_LONG)||!MinecraftApi.containsTag(r, "Revoked",Tag.TAG_BYTE))throw new IllegalArgumentException("missing duty proof detail");
            var e=new Evidence(r.read("Duty", UUIDUtil.CODEC).orElseThrow(),NpcDutyData.Kind.valueOf(r.getStringOr("Kind", "")),r.read("Record", UUIDUtil.CODEC).orElseThrow(),r.read("Principal", UUIDUtil.CODEC).orElseThrow(),r.read("Agent", UUIDUtil.CODEC).orElseThrow(),r.read("Target", UUIDUtil.CODEC).orElseThrow(),r.getLongOr("Expires", 0L),r.getBooleanOr("Revoked", false));
            if(d.records.containsKey(e.duty()))throw new IllegalArgumentException("duplicate duty evidence");d.put(e);
        }return d;}
}
