package com.jgeted.sagadyssey.social.war.adapter.persistence;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.api.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

public final class WarEngineeringCodec implements com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore.Codec<WarEngineeringJob>{
    private final VersionCodec current=new VersionCodec(false),legacy=new VersionCodec(true);
    public int capacity(){return current.capacity();}
    public int maxBytes(){return current.maxBytes();}
    public byte[] write(Collection<WarEngineeringJob> rows)throws java.io.IOException{return current.write(rows);}
    public Collection<WarEngineeringJob> read(byte[] bytes)throws java.io.IOException{try{return current.read(bytes);}catch(java.io.IOException failure){try{return legacy.read(bytes);}catch(java.io.IOException old){failure.addSuppressed(old);throw failure;}}}
    private static final class VersionCodec extends NbtSnapshotCodec<WarEngineeringJob>{
    private final boolean legacy;
    private VersionCodec(boolean legacy){super(legacy?"sagadyssey:war_engineering/v1":"sagadyssey:war_engineering/v2",16*1024*1024,256);this.legacy=legacy;}
    protected UUID identity(WarEngineeringJob v){return v.id();}
    protected void validate(Collection<WarEngineeringJob> rows){var bodies=new HashSet<UUID>();var people=new HashSet<UUID>();var services=new HashSet<UUID>();for(var v:rows)if(!v.terminal()&&(!bodies.add(v.body())||!people.add(v.person())||!services.add(v.service())))throw new IllegalArgumentException("duplicate original engineering labor");}
    protected CompoundTag encode(WarEngineeringJob v){
        var t=new CompoundTag();id(t,"Id",v.id());id(t,"Army",v.army());id(t,"Owner",v.owner());id(t,"Service",v.service());id(t,"Person",v.person());id(t,"Body",v.body());t.putString("Mode",v.mode().name());t.putString("Material",v.material().map(Object::toString).orElse(""));t.putString("Phase",v.phase().name());t.putInt("Next",v.next());t.putLong("CreatedAt",v.createdAt());t.putLong("Revision",v.revision());t.putLong("ChangedAt",v.changedAt());t.putBoolean("RetireAfter",v.retireAfter());t.putString("Reason",v.reason());t.putIntArray("Adopted",v.adopted().stream().sorted().mapToInt(Integer::intValue).toArray());
        var cells=new ListTag();for(var c:v.cells()){var r=new CompoundTag();r.putString("Dimension",c.position().dimension().toString());r.putInt("X",c.position().x());r.putInt("Y",c.position().y());r.putInt("Z",c.position().z());r.putString("Before",c.before());r.putString("After",c.after());r.putInt("Ticks",c.workTicks());cells.add(r);}t.put("Cells",cells);if(!legacy){var program=new CompoundTag();v.program().ifPresent(p->encodeProgram(program,p));t.put("Program",program);}return t;
    }
    protected WarEngineeringJob decode(CompoundTag t){
        fields(t,"Id,Army,Owner,Service,Person,Body,Mode,Material,Phase,Next,CreatedAt,Revision,ChangedAt,RetireAfter,Reason,Adopted,Cells"+(legacy?"":",Program"));if(!(t.get("Cells") instanceof ListTag rows)||rows.isEmpty()||rows.size()>64)throw new IllegalArgumentException("invalid engineering cells");
        var cells=new ArrayList<WarEngineeringJob.Cell>();for(var raw:rows){var r=(CompoundTag)raw;fields(r,"Dimension,X,Y,Z,Before,After,Ticks");cells.add(new WarEngineeringJob.Cell(new WarIntent.Position(WarKey.parse(string(r,"Dimension")),n(r,"X"),n(r,"Y"),n(r,"Z")),string(r,"Before"),string(r,"After"),n(r,"Ticks")));}
        var adopted=t.getIntArray("Adopted").orElseThrow();var set=new HashSet<Integer>();for(var i:adopted)if(!set.add(i))throw new IllegalArgumentException("duplicate adopted cell");var m=t.getString("Material").orElseThrow();
        return new WarEngineeringJob(uuid(t,"Id"),uuid(t,"Army"),uuid(t,"Owner"),uuid(t,"Service"),uuid(t,"Person"),uuid(t,"Body"),WarEngineeringJob.Mode.valueOf(string(t,"Mode")),m.isEmpty()?Optional.empty():Optional.of(WarKey.parse(m)),cells,WarEngineeringJob.Phase.valueOf(string(t,"Phase")),n(t,"Next"),set,number(t,"CreatedAt"),number(t,"Revision"),number(t,"ChangedAt"),t.getBoolean("RetireAfter").orElseThrow(),string(t,"Reason"),legacy?Optional.empty():decodeProgram(WarHazardCodec.compound(t,"Program")));
    }
    private static void id(CompoundTag t,String k,UUID v){t.store(k,UUIDUtil.CODEC,v);}
    private static int n(CompoundTag t,String k){if(!(t.get(k) instanceof IntTag))throw new IllegalArgumentException("invalid integer");return t.getIntOr(k,0);}
    private static void fields(CompoundTag t,String keys){if(!t.keySet().equals(Set.of(keys.split(","))))throw new IllegalArgumentException("invalid engineering fields");}
    }
    private static void encodeProgram(CompoundTag t,WarEngineeringProgram p){
        t.putString("Template",p.kind().id().toString());t.putInt("Version",p.version());var rows=new ListTag();
        for(var step:p.steps()){var r=new CompoundTag();r.putString("Mode",step.mode().name());r.putString("Material",step.material().map(Object::toString).orElse(""));r.put("Stand",WarHazardCodec.position(step.stand()));rows.add(r);}t.put("Steps",rows);
        var evidence=new CompoundTag();p.evidence().ifPresent(o->evidence.merge(WarHazardCodec.observation(o)));t.put("Evidence",evidence);
    }
    private static Optional<WarEngineeringProgram> decodeProgram(CompoundTag t){
        if(t.isEmpty())return Optional.empty();WarHazardCodec.fields(t,"Template,Version,Steps,Evidence");
        var kind=Arrays.stream(WarEngineeringProgram.Kind.values()).filter(k->k.id().toString().equals(t.getStringOr("Template",""))).findFirst().orElseThrow(()->new IllegalArgumentException("unknown engineering template"));
        var steps=new ArrayList<WarEngineeringProgram.Step>();for(var raw:WarHazardCodec.rows(t,"Steps",64)){var r=(CompoundTag)raw;WarHazardCodec.fields(r,"Mode,Material,Stand");var m=r.getString("Material").orElseThrow();steps.add(new WarEngineeringProgram.Step(WarEngineeringJob.Mode.valueOf(r.getString("Mode").orElseThrow()),m.isEmpty()?Optional.empty():Optional.of(WarKey.parse(m)),WarHazardCodec.position(WarHazardCodec.compound(r,"Stand"))));}
        var e=WarHazardCodec.compound(t,"Evidence");return Optional.of(new WarEngineeringProgram(kind,WarHazardCodec.integer(t,"Version"),steps,e.isEmpty()?Optional.empty():Optional.of(WarHazardCodec.observation(e))));
    }
}
