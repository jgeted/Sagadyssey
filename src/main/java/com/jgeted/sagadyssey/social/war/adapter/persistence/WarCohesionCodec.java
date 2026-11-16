package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion;
import com.jgeted.sagadyssey.social.war.domain.WarCohesion.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

/** Independent tactical schema. Frozen core, roster, dispatch and body formats are unchanged. */
public final class WarCohesionCodec extends NbtSnapshotCodec<WarCohesion> {
    public WarCohesionCodec() { super("sagadyssey:war_cohesion/v1",16*1024*1024,1024); }
    protected UUID identity(WarCohesion value) { return value.unit(); }
    protected CompoundTag encode(WarCohesion value) {
        var tag=new CompoundTag(); tag.store("Unit",UUIDUtil.CODEC,value.unit()); tag.store("Army",UUIDUtil.CODEC,value.army());
        tag.putLong("Revision",value.revision()); tag.putLong("ChangedAt",value.changedAt()); tag.putInt("Organization",value.organization());
        tag.putString("Phase",value.phase().name()); tag.store("Order",UUIDUtil.CODEC,value.order()); tag.putLong("Sequence",value.sequence());
        var p=new CompoundTag(); p.putString("Dimension",value.rally().dimension().toString()); p.putInt("X",value.rally().x()); p.putInt("Y",value.rally().y()); p.putInt("Z",value.rally().z()); tag.put("Rally",p);
        var routed=new ListTag(); for(var id:value.routed().stream().sorted().toList()) routed.add(UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,id).getOrThrow()); tag.put("Routed",routed);
        var cursors=new ListTag(); for(var e:value.cursors().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var row=new CompoundTag(); row.store("Encounter",UUIDUtil.CODEC,e.getKey()); row.putInt("Count",e.getValue().count()); row.store("Last",UUIDUtil.CODEC,e.getValue().last()); cursors.add(row);
        } tag.put("Cursors",cursors);
        var impacts=new ListTag(); for(var e:value.impacts().entrySet().stream().sorted(Comparator.comparing((Map.Entry<ImpactKey,Impact> e)->e.getKey().encounter()).thenComparing(e->e.getKey().character())).toList()) {
            var row=new CompoundTag(); row.store("Encounter",UUIDUtil.CODEC,e.getKey().encounter()); row.store("Character",UUIDUtil.CODEC,e.getKey().character());
            row.putInt("Severity",e.getValue().severity()); row.putInt("Strength",e.getValue().originalStrength()); row.putInt("Loss",e.getValue().appliedLoss()); impacts.add(row);
        } tag.put("Impacts",impacts); return tag;
    }
    protected WarCohesion decode(CompoundTag tag) {
        fields(tag,Set.of("Unit","Army","Revision","ChangedAt","Organization","Phase","Order","Sequence","Rally","Routed","Cursors","Impacts"));
        if(!(tag.get("Rally") instanceof CompoundTag p)) throw new IllegalArgumentException("missing rally point"); fields(p,Set.of("Dimension","X","Y","Z"));
        var position=new WarIntent.Position(WarKey.parse(string(p,"Dimension")),integer(p,"X"),integer(p,"Y"),integer(p,"Z"));
        var routed=new HashSet<UUID>();
        for(var raw:rows(tag,"Routed",Tag.TAG_INT_ARRAY,128)) if(!routed.add(UUIDUtil.CODEC.parse(NbtOps.INSTANCE,raw).result().orElseThrow(()->new IllegalArgumentException("invalid routed UUID")))) throw new IllegalArgumentException("duplicate routed member");
        var cursors=new HashMap<UUID,Cursor>(); for(var raw:rows(tag,"Cursors",Tag.TAG_COMPOUND,WarCohesion.MAX_ENCOUNTERS)) {
            var row=(CompoundTag)raw; fields(row,Set.of("Encounter","Count","Last"));
            if(cursors.putIfAbsent(uuid(row,"Encounter"),new Cursor(integer(row,"Count"),uuid(row,"Last")))!=null) throw new IllegalArgumentException("duplicate encounter cursor");
        }
        var impacts=new HashMap<ImpactKey,Impact>(); for(var raw:rows(tag,"Impacts",Tag.TAG_COMPOUND,WarCohesion.MAX_IMPACTS)) {
            var row=(CompoundTag)raw; fields(row,Set.of("Encounter","Character","Severity","Strength","Loss"));
            if(impacts.putIfAbsent(new ImpactKey(uuid(row,"Encounter"),uuid(row,"Character")),new Impact(integer(row,"Severity"),integer(row,"Strength"),integer(row,"Loss")))!=null) throw new IllegalArgumentException("duplicate casualty impact");
        }
        return new WarCohesion(uuid(tag,"Unit"),uuid(tag,"Army"),number(tag,"Revision"),number(tag,"ChangedAt"),integer(tag,"Organization"),
                Phase.valueOf(string(tag,"Phase")),uuid(tag,"Order"),number(tag,"Sequence"),position,routed,cursors,impacts);
    }
    private static ListTag rows(CompoundTag tag,String key,int type,int limit) {
        if(!(tag.get(key) instanceof ListTag rows) || rows.size()>limit || !rows.isEmpty() && MinecraftApi.elementType(rows)!=type)
            throw new IllegalArgumentException("invalid tactical list "+key);
        return rows;
    }
    private static int integer(CompoundTag tag,String key) {
        if(!MinecraftApi.containsTag(tag,key,Tag.TAG_INT)) throw new IllegalArgumentException("missing tactical integer "+key); return tag.getIntOr(key,0);
    }
}
