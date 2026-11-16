package com.jgeted.sagadyssey.social.war.adapter.persistence;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarStaffRelay;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

public final class WarStaffRelayCodec extends NbtSnapshotCodec<WarStaffRelay>{
    public WarStaffRelayCodec(){super("sagadyssey:war_staff_relay/v1",4*1024*1024,256);}
    protected UUID identity(WarStaffRelay r){return r.id();}
    protected CompoundTag encode(WarStaffRelay r){try{
        var t=new CompoundTag();t.store("Id",UUIDUtil.CODEC,r.id());t.store("Appointment",UUIDUtil.CODEC,r.appointment());t.putByteArray("Post",new WarCommandPostCodec().write(List.of(r.post())));t.putLong("Generation",r.generation());t.putLong("UnitRevision",r.unitRevision());t.putLong("CreatedAt",r.createdAt());t.putLong("Deadline",r.deadline());t.putString("Phase",r.phase().name());t.putLong("Revision",r.revision());t.putLong("ChangedAt",r.changedAt());t.putString("Reason",r.reason());return t;}catch(java.io.IOException e){throw new IllegalArgumentException(e);}
    }
    protected WarStaffRelay decode(CompoundTag t){try{
        fields(t,Set.of("Id","Appointment","Post","Generation","UnitRevision","CreatedAt","Deadline","Phase","Revision","ChangedAt","Reason"));
        var posts=new WarCommandPostCodec().read(t.getByteArray("Post").orElseThrow());if(posts.size()!=1)throw new IllegalArgumentException("one original post required");
        return new WarStaffRelay(uuid(t,"Id"),uuid(t,"Appointment"),posts.iterator().next(),number(t,"Generation"),number(t,"UnitRevision"),number(t,"CreatedAt"),number(t,"Deadline"),WarStaffRelay.Phase.valueOf(string(t,"Phase")),number(t,"Revision"),number(t,"ChangedAt"),string(t,"Reason"));}catch(java.io.IOException e){throw new IllegalArgumentException(e);}
    }
}
