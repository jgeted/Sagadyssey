package com.jgeted.sagadyssey.npc.brain.command;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Original patient consent or shipment identity. No replacement proof and no grant of property rights. */
public final class NpcDutyEvidenceData extends SavedData {
    public static final String NAME="sagadyssey_npc_duty_evidence_v3";
    public static final Factory<NpcDutyEvidenceData> FACTORY=new Factory<>(NpcDutyEvidenceData::new,NpcDutyEvidenceData::load);
    public record Evidence(UUID duty,NpcDutyData.Kind kind,UUID record,UUID principal,UUID agent,UUID target,long expires,boolean revoked) {
        public Evidence {if(duty==null||kind==null||record==null||principal==null||agent==null||target==null||expires<0
                ||kind!=NpcDutyData.Kind.MEDICAL&&kind!=NpcDutyData.Kind.ESCORT)throw new IllegalArgumentException("invalid duty proof");}
    }
    private final Map<UUID,Evidence> records=new LinkedHashMap<>();
    public static NpcDutyEvidenceData get(MinecraftServer server){var s=server.overworld().getDataStorage();var d=s.get(FACTORY,NAME);if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable duty evidence");
        d=new NpcDutyEvidenceData();s.set(NAME,d);return d;}
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
        var r=new CompoundTag();r.putUUID("Duty",e.duty());r.putString("Kind",e.kind().name());r.putUUID("Record",e.record());r.putUUID("Principal",e.principal());
        r.putUUID("Agent",e.agent());r.putUUID("Target",e.target());r.putLong("Expires",e.expires());r.putBoolean("Revoked",e.revoked());l.add(r);
    }t.put("Evidence",l);return t;}
    public static NpcDutyEvidenceData load(CompoundTag t,HolderLookup.Provider lookup){if(!t.contains("Schema",Tag.TAG_INT)||t.getInt("Schema")!=1
            ||!(t.get("Evidence") instanceof ListTag l)||l.size()>4096||!l.isEmpty()&&l.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid duty evidence ledger");
        var d=new NpcDutyEvidenceData();for(var raw:l){var r=(CompoundTag)raw;for(var k:List.of("Duty","Record","Principal","Agent","Target"))if(!r.hasUUID(k))throw new IllegalArgumentException("missing duty proof identity");
            if(!r.contains("Kind",Tag.TAG_STRING)||!r.contains("Expires",Tag.TAG_LONG)||!r.contains("Revoked",Tag.TAG_BYTE))throw new IllegalArgumentException("missing duty proof detail");
            var e=new Evidence(r.getUUID("Duty"),NpcDutyData.Kind.valueOf(r.getString("Kind")),r.getUUID("Record"),r.getUUID("Principal"),r.getUUID("Agent"),r.getUUID("Target"),r.getLong("Expires"),r.getBoolean("Revoked"));
            if(d.records.containsKey(e.duty()))throw new IllegalArgumentException("duplicate duty evidence");d.put(e);
        }return d;}
}
