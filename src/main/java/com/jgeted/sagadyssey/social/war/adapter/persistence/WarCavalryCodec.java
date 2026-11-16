package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

/** Independent typed cycle state; existing command, body and tactical formats remain unchanged. */
public final class WarCavalryCodec extends NbtSnapshotCodec<WarCavalry>{
    public WarCavalryCodec(){super("sagadyssey:war_cavalry/v1",16*1024*1024,1024);}
    protected UUID identity(WarCavalry v){return v.unit();}
    protected CompoundTag encode(WarCavalry v){
        var t=new CompoundTag();t.store("Unit",UUIDUtil.CODEC,v.unit());t.store("Army",UUIDUtil.CODEC,v.army());t.store("Order",UUIDUtil.CODEC,v.order());t.putLong("Sequence",v.sequence());t.putLong("Revision",v.revision());t.putLong("ChangedAt",v.changedAt());t.putLong("Wave",v.wave());t.putString("Phase",v.phase().name());t.putLong("StartedAt",v.startedAt());t.putLong("NextLaunchAt",v.nextLaunchAt());
        t.put("Assembly",position(v.assembly()));t.put("Objective",position(v.objective()));var list=new ListTag();
        for(var m:v.members()){var r=new CompoundTag();r.store("Contract",UUIDUtil.CODEC,m.contract());r.store("Character",UUIDUtil.CODEC,m.character());r.store("Body",UUIDUtil.CODEC,m.body());r.putBoolean("Spent",v.spent().contains(m.contract()));r.putBoolean("Returning",v.returning().contains(m.contract()));list.add(r);}t.put("Members",list);return t;
    }
    protected WarCavalry decode(CompoundTag t){
        fields(t,Set.of("Unit","Army","Order","Sequence","Revision","ChangedAt","Wave","Phase","StartedAt","NextLaunchAt","Assembly","Objective","Members"));
        if(!(t.get("Members") instanceof ListTag rows)||rows.isEmpty()||rows.size()>128||MinecraftApi.elementType(rows)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid cavalry roster");
        var members=new ArrayList<WarTransmission.Member>();var spent=new HashSet<UUID>();var returning=new HashSet<UUID>();
        for(var raw:rows){var row=(CompoundTag)raw;fields(row,Set.of("Contract","Character","Body","Spent","Returning"));var id=uuid(row,"Contract");members.add(new WarTransmission.Member(id,uuid(row,"Character"),uuid(row,"Body")));if(flag(row,"Spent"))spent.add(id);if(flag(row,"Returning"))returning.add(id);}
        return new WarCavalry(uuid(t,"Unit"),uuid(t,"Army"),uuid(t,"Order"),number(t,"Sequence"),number(t,"Revision"),number(t,"ChangedAt"),number(t,"Wave"),WarCavalry.Phase.valueOf(string(t,"Phase")),number(t,"StartedAt"),number(t,"NextLaunchAt"),position(t.get("Assembly")),position(t.get("Objective")),members,spent,returning);
    }
    private static CompoundTag position(WarIntent.Position p){var t=new CompoundTag();t.putString("Dimension",p.dimension().toString());t.putInt("X",p.x());t.putInt("Y",p.y());t.putInt("Z",p.z());return t;}
    private static WarIntent.Position position(Tag raw){if(!(raw instanceof CompoundTag t))throw new IllegalArgumentException("missing cavalry position");fields(t,Set.of("Dimension","X","Y","Z"));for(var k:List.of("X","Y","Z"))if(!MinecraftApi.containsTag(t,k,Tag.TAG_INT))throw new IllegalArgumentException("invalid cavalry coordinate");return new WarIntent.Position(WarKey.parse(string(t,"Dimension")),t.getIntOr("X",0),t.getIntOr("Y",0),t.getIntOr("Z",0));}
    private static boolean flag(CompoundTag t,String key){if(!MinecraftApi.containsTag(t,key,Tag.TAG_BYTE)||t.getByteOr(key,(byte)-1)<0||t.getByteOr(key,(byte)-1)>1)throw new IllegalArgumentException("invalid cavalry flag");return t.getBooleanOr(key,false);}
}
