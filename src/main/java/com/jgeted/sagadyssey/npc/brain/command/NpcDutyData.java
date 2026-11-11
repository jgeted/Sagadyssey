package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** One bounded duty per original body. Identity and consent/proof sources cannot be retargeted on reload. */
public final class NpcDutyData extends NbtSavedData {
    public static final String NAME="sagadyssey_npc_duties_v3";
    public static final Factory<NpcDutyData> FACTORY=new Factory<>(NpcDutyData::new,NpcDutyData::load);
    public enum Kind { MEDICAL, ESCORT, PATROL, RETREAT }
    public enum Status { OPEN, SETTLING, COMPLETED, CANCELLED, REVIEW }
    public record Duty(UUID id,UUID body,UUID character,UUID targetBody,UUID targetCharacter,Kind kind,
                       UUID source,List<UUID> proofs,String dimension,BlockPos anchor,int duration,
                       long created,long expires,int worked,long lastTick,Status status,String detail) {
        public Duty {
            if(id==null||body==null||character==null||targetBody==null||targetCharacter==null||kind==null||source==null
                    ||Identifier.tryParse(dimension)==null||anchor==null||duration<1||duration>2400||created<0
                    ||expires<=created||expires-created>24000||worked<0||worked>duration||lastTick<created||status==null)
                throw new IllegalArgumentException("invalid duty identity/progress");
            proofs=List.copyOf(proofs);if(proofs.size()>16||new HashSet<>(proofs).size()!=proofs.size())throw new IllegalArgumentException("invalid duty proof");
            if(kind==Kind.ESCORT&&proofs.isEmpty())throw new IllegalArgumentException("escort requires original permission");
            detail=Objects.requireNonNull(detail);if(detail.length()>512)throw new IllegalArgumentException("duty detail too long");
        }
        public boolean closed(){return status==Status.COMPLETED||status==Status.CANCELLED||status==Status.REVIEW;}
        public String token(){return "treatment:"+id;}
        public Duty tick(long now){if(closed()||status!=Status.OPEN||now<=lastTick)return this;
            return update(status,Math.min(duration,worked+1),now,detail);}
        public Duty update(Status next,int count,long now,String text){if(now<lastTick||count<worked||closed()&&next!=status)throw new IllegalArgumentException("nonmonotonic duty");
            return new Duty(id,body,character,targetBody,targetCharacter,kind,source,proofs,dimension,anchor,duration,created,expires,count,now,next,text);}
        public boolean sameIntent(Duty other){return id.equals(other.id)&&body.equals(other.body)&&character.equals(other.character)
                &&targetBody.equals(other.targetBody)&&targetCharacter.equals(other.targetCharacter)&&kind==other.kind&&source.equals(other.source)
                &&proofs.equals(other.proofs)&&dimension.equals(other.dimension)&&anchor.equals(other.anchor)&&duration==other.duration&&created==other.created&&expires==other.expires;}
    }
    private final Map<UUID,Duty> duties=new LinkedHashMap<>();
    private final Map<UUID,UUID> active=new HashMap<>();
    private final Map<String,UUID> last=new HashMap<>();
    private final Map<UUID,Long> retry=new HashMap<>();
    public static NpcDutyData get(MinecraftServer server){var s=server.overworld().getDataStorage();var d=s.get(FACTORY.type(NAME));if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable duty ledger");
        d=new NpcDutyData();s.set(FACTORY.type(NAME),d);return d;}
    public Duty find(UUID id){return duties.get(id);}
    public Duty active(UUID body){return duties.get(active.get(body));}
    public List<Duty> all(){return List.copyOf(duties.values());}
    public Duty last(UUID body,Kind kind){return duties.get(last.get(body+":"+kind));}
    public long retryAt(UUID body){return retry.getOrDefault(body,0L);}
    public void defer(UUID body,long now){retry.put(body,now+100);setDirty();}
    public void put(Duty next){var old=find(next.id());
        if(old!=null&&(!old.sameIntent(next)||next.worked()<old.worked()||next.lastTick()<old.lastTick()||old.closed()&&!old.equals(next)
                ||old.status()==Status.SETTLING&&next.status()==Status.OPEN))throw new IllegalStateException("duty identity/result immutable");
        if(old==null&&duties.size()>=4096)throw new IllegalStateException("duty ledger full; explicit archival required");
        if(!next.closed()&&active.containsKey(next.body())&&!active.get(next.body()).equals(next.id()))throw new IllegalStateException("two duties for one body");
        if(next.closed())active.remove(next.body(),next.id());else active.put(next.body(),next.id());duties.put(next.id(),next);
        var latest=last(next.body(),next.kind());if(latest==null||next.created()>=latest.created())last.put(next.body()+":"+next.kind(),next.id());setDirty();}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();for(var d:duties.values()){
        var r=new CompoundTag();r.store("Id", UUIDUtil.CODEC,d.id());r.store("Body", UUIDUtil.CODEC,d.body());r.store("Character", UUIDUtil.CODEC,d.character());r.store("TargetBody", UUIDUtil.CODEC,d.targetBody());r.store("TargetCharacter", UUIDUtil.CODEC,d.targetCharacter());
        r.store("Source", UUIDUtil.CODEC,d.source());r.putString("Kind",d.kind().name());var proofs=new ListTag();for(var id:d.proofs()){var p=new CompoundTag();p.store("Id", UUIDUtil.CODEC,id);proofs.add(p);}r.put("Proofs",proofs);
        r.putString("Dimension",d.dimension());r.putLong("Anchor",d.anchor().asLong());r.putInt("Duration",d.duration());r.putInt("Worked",d.worked());
        r.putLong("Created",d.created());r.putLong("Expires",d.expires());r.putLong("LastTick",d.lastTick());r.putString("Status",d.status().name());r.putString("Detail",d.detail());list.add(r);
    }t.put("Duties",list);var backoff=new ListTag();retry.forEach((id,time)->{var r=new CompoundTag();r.store("Body", UUIDUtil.CODEC,id);r.putLong("Until",time);backoff.add(r);});t.put("Retry",backoff);return t;}
    public static NpcDutyData load(CompoundTag t,HolderLookup.Provider lookup){
        if(!MinecraftApi.containsTag(t, "Schema",Tag.TAG_INT)||t.getIntOr("Schema", 0)!=1)throw new IllegalArgumentException("invalid duty schema");var d=new NpcDutyData();
        for(var raw:list(t,"Duties",4096)){var r=(CompoundTag)raw;
            for(var f:List.of("Id","Body","Character","TargetBody","TargetCharacter","Source"))if(!r.read(f, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing duty identity");
            for(var f:List.of("Kind","Dimension","Status","Detail"))if(!MinecraftApi.containsTag(r, f,Tag.TAG_STRING))throw new IllegalArgumentException("missing duty string");
            for(var f:List.of("Anchor","Created","Expires","LastTick"))if(!MinecraftApi.containsTag(r, f,Tag.TAG_LONG))throw new IllegalArgumentException("missing duty time/place");
            for(var f:List.of("Duration","Worked"))if(!MinecraftApi.containsTag(r, f,Tag.TAG_INT))throw new IllegalArgumentException("missing duty progress");
            var proofs=new ArrayList<UUID>();for(var proof:list(r,"Proofs",16)){var p=(CompoundTag)proof;if(!p.read("Id", UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing proof");proofs.add(p.read("Id", UUIDUtil.CODEC).orElseThrow());}
            var duty=new Duty(r.read("Id", UUIDUtil.CODEC).orElseThrow(),r.read("Body", UUIDUtil.CODEC).orElseThrow(),r.read("Character", UUIDUtil.CODEC).orElseThrow(),r.read("TargetBody", UUIDUtil.CODEC).orElseThrow(),r.read("TargetCharacter", UUIDUtil.CODEC).orElseThrow(),Kind.valueOf(r.getStringOr("Kind", "")),
                    r.read("Source", UUIDUtil.CODEC).orElseThrow(),proofs,r.getStringOr("Dimension", ""),BlockPos.of(r.getLongOr("Anchor", 0L)),r.getIntOr("Duration", 0),r.getLongOr("Created", 0L),r.getLongOr("Expires", 0L),r.getIntOr("Worked", 0),r.getLongOr("LastTick", 0L),Status.valueOf(r.getStringOr("Status", "")),r.getStringOr("Detail", ""));
            if(d.find(duty.id())!=null)throw new IllegalArgumentException("duplicate duty");d.put(duty);
        }for(var raw:list(t,"Retry",4096)){var r=(CompoundTag)raw;if(!r.read("Body", UUIDUtil.CODEC).isPresent()||!MinecraftApi.containsTag(r, "Until",Tag.TAG_LONG)||r.getLongOr("Until", 0L)<0
                ||d.retry.putIfAbsent(r.read("Body", UUIDUtil.CODEC).orElseThrow(),r.getLongOr("Until", 0L))!=null)throw new IllegalArgumentException("invalid duty backoff");}return d;
    }
    private static ListTag list(CompoundTag t,String key,int max){if(!(t.get(key) instanceof ListTag list)||list.size()>max||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid duty list "+key);return list;}
}
