package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.*;
import com.jgeted.sagadyssey.social.war.application.WarStaffBook;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

public final class WarStaffCodec extends NbtSnapshotCodec<WarStaffEntry> {
    public WarStaffCodec(){super("sagadyssey:war_staff/v1",16*1024*1024,WarStaffBook.CAPACITY);}
    protected UUID identity(WarStaffEntry v){return v.id();}
    protected CompoundTag encode(WarStaffEntry v){
        var t=new CompoundTag();t.store("Id",UUIDUtil.CODEC,v.id());t.putLong("Revision",v.revision());
        if(v instanceof Establishment e){
            t.putString("Type","establishment");t.store("Owner",UUIDUtil.CODEC,e.owner());t.putLong("ReviewedAt",e.reviewedAt());var rows=new ListTag();
            for(var entry:e.combatants().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){var r=new CompoundTag();r.store("Unit",UUIDUtil.CODEC,entry.getKey());r.putInt("Combatants",entry.getValue());rows.add(r);}t.put("Units",rows);
        }else{
            var a=(Appointment)v;var terms=a.terms();t.putString("Type","appointment");t.putString("Phase",a.phase().name());t.putLong("ChangedAt",a.changedAt());t.putString("Detail",a.detail());
            t.store("Army",UUIDUtil.CODEC,terms.army());t.store("Owner",UUIDUtil.CODEC,terms.owner());t.store("Holder",UUIDUtil.CODEC,terms.holder());t.store("Service",UUIDUtil.CODEC,terms.service());t.putString("Role",terms.role().name());terms.unit().ifPresent(u->t.store("Unit",UUIDUtil.CODEC,u));
            t.putLong("EstablishmentRevision",terms.establishmentRevision());t.putInt("Combatants",terms.combatants());t.putInt("UnitCombatants",terms.unitCombatants());t.putInt("PolicyVersion",terms.policyVersion());t.putLong("StartsAt",terms.startsAt());t.putLong("EndsAt",terms.endsAt());t.putString("Mode",terms.mode().name());t.putString("Reason",terms.reason());t.putString("SocialClass",terms.socialClass());t.putString("Profession",terms.profession());t.putInt("Experience",terms.experience());
        }return t;
    }
    protected WarStaffEntry decode(CompoundTag t){
        String type=string(t,"Type");
        if(type.equals("establishment")){
            fields(t,Set.of("Id","Revision","Type","Owner","ReviewedAt","Units"));
            if(!(t.get("Units") instanceof ListTag rows)||rows.size()>128||!rows.isEmpty()&&MinecraftApi.elementType(rows)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid staffing unit list");
            var counts=new LinkedHashMap<UUID,Integer>();for(var raw:rows){var r=(CompoundTag)raw;fields(r,Set.of("Unit","Combatants"));if(!MinecraftApi.containsTag(r,"Combatants",Tag.TAG_INT)||counts.putIfAbsent(uuid(r,"Unit"),r.getIntOr("Combatants",-1))!=null)throw new IllegalArgumentException("invalid/duplicate unit strength");}
            return new Establishment(uuid(t,"Id"),uuid(t,"Owner"),number(t,"Revision"),number(t,"ReviewedAt"),counts);
        }
        if(!type.equals("appointment"))throw new IllegalArgumentException("unknown staff record");
        var expected=new HashSet<>(Set.of("Id","Revision","Type","Phase","ChangedAt","Detail","Army","Owner","Holder","Service","Role","EstablishmentRevision","Combatants","UnitCombatants","PolicyVersion","StartsAt","EndsAt","Mode","Reason","SocialClass","Profession","Experience"));if(t.contains("Unit"))expected.add("Unit");fields(t,expected);
        for(var key:List.of("Experience","Combatants","UnitCombatants","PolicyVersion"))if(!MinecraftApi.containsTag(t,key,Tag.TAG_INT))throw new IllegalArgumentException("missing staffing numeric evidence");
        var terms=new Terms(uuid(t,"Id"),uuid(t,"Army"),uuid(t,"Owner"),uuid(t,"Holder"),uuid(t,"Service"),Role.valueOf(string(t,"Role")),t.contains("Unit")?Optional.of(uuid(t,"Unit")):Optional.empty(),number(t,"EstablishmentRevision"),t.getIntOr("Combatants",-1),t.getIntOr("UnitCombatants",-1),t.getIntOr("PolicyVersion",-1),number(t,"StartsAt"),number(t,"EndsAt"),Mode.valueOf(string(t,"Mode")),string(t,"Reason"),string(t,"SocialClass"),string(t,"Profession"),t.getIntOr("Experience",-1));
        return new Appointment(terms,Phase.valueOf(string(t,"Phase")),number(t,"Revision"),number(t,"ChangedAt"),string(t,"Detail"));
    }
}
