package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

/** Only army-local dated reports and caution policy; never copied world state. */
public final class WarHazardCodec extends NbtSnapshotCodec<WarHazardMemory>{
    public WarHazardCodec(){super("sagadyssey:war_hazards/v1",8*1024*1024,64);}
    protected UUID identity(WarHazardMemory v){return v.army();}
    protected CompoundTag encode(WarHazardMemory v){
        var t=new CompoundTag();id(t,"Army",v.army());t.putBoolean("Avoid",v.avoid());t.putLong("Revision",v.revision());t.putLong("ChangedAt",v.changedAt());
        var rows=new ListTag();v.observations().forEach(o->rows.add(observation(o)));t.put("Observations",rows);return t;
    }
    protected WarHazardMemory decode(CompoundTag t){
        fields(t,"Army,Avoid,Revision,ChangedAt,Observations");var list=rows(t,"Observations",WarHazardMemory.MAX_REPORTS);var values=new ArrayList<WarHazardObservation>();
        list.forEach(v->values.add(observation((CompoundTag)v)));
        return new WarHazardMemory(uuid(t,"Army"),t.getBoolean("Avoid").orElseThrow(),values,number(t,"Revision"),number(t,"ChangedAt"));
    }
    static CompoundTag observation(WarHazardObservation o){
        var t=new CompoundTag();id(t,"Id",o.id());id(t,"Army",o.army());id(t,"Observer",o.observer());id(t,"Body",o.body());
        t.put("Position",position(o.position()));t.putString("Kind",o.kind().toString());t.putString("State",o.state());t.putLong("ObservedAt",o.observedAt());t.putString("Source",o.source().name());return t;
    }
    static WarHazardObservation observation(CompoundTag t){
        fields(t,"Id,Army,Observer,Body,Position,Kind,State,ObservedAt,Source");
        return new WarHazardObservation(uuid(t,"Id"),uuid(t,"Army"),uuid(t,"Observer"),uuid(t,"Body"),position(compound(t,"Position")),WarKey.parse(string(t,"Kind")),string(t,"State"),number(t,"ObservedAt"),WarHazardObservation.Source.valueOf(string(t,"Source")));
    }
    static CompoundTag position(WarIntent.Position p){var t=new CompoundTag();t.putString("Dimension",p.dimension().toString());t.putInt("X",p.x());t.putInt("Y",p.y());t.putInt("Z",p.z());return t;}
    static WarIntent.Position position(CompoundTag t){fields(t,"Dimension,X,Y,Z");return new WarIntent.Position(WarKey.parse(string(t,"Dimension")),integer(t,"X"),integer(t,"Y"),integer(t,"Z"));}
    static CompoundTag compound(CompoundTag t,String k){if(!(t.get(k) instanceof CompoundTag v))throw new IllegalArgumentException("compound required");return v;}
    static ListTag rows(CompoundTag t,String k,int cap){if(!(t.get(k) instanceof ListTag v)||v.size()>cap||v.stream().anyMatch(x->!(x instanceof CompoundTag)))throw new IllegalArgumentException("bounded records required");return v;}
    static void fields(CompoundTag t,String keys){if(!t.keySet().equals(Set.of(keys.split(","))))throw new IllegalArgumentException("invalid report fields");}
    static int integer(CompoundTag t,String k){if(!(t.get(k) instanceof IntTag))throw new IllegalArgumentException("integer required");return t.getIntOr(k,0);}
    static void id(CompoundTag t,String k,UUID v){t.store(k,UUIDUtil.CODEC,v);}
}
