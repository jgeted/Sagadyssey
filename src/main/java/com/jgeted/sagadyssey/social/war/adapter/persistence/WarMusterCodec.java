package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.application.WarMusterBook;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.util.*;

/** Versioned military metadata; never embeds items, people or shared authorization records. */
public final class WarMusterCodec extends NbtSnapshotCodec<WarMusterEntry> {
    private static final WarMobilizationCodec REQUEST=new WarMobilizationCodec(8192,1);
    public WarMusterCodec(){super("sagadyssey:war_muster/v1",16*1024*1024,WarMusterBook.MAX_ARMIES+WarMusterBook.MAX_UNITS+WarMusterBook.MAX_OFFERS);}
    protected UUID identity(WarMusterEntry v){return v.id();}
    protected CompoundTag encode(WarMusterEntry v){
        var t=new CompoundTag();t.store("Id",UUIDUtil.CODEC,v.id());
        if(v instanceof Army a){t.putString("Type","army");t.store("Owner",UUIDUtil.CODEC,a.owner());t.putString("Name",a.name());t.putLong("CreatedAt",a.createdAt());}
        else if(v instanceof Unit u){t.putString("Type","unit");t.store("Army",UUIDUtil.CODEC,u.army());t.putString("Name",u.name());t.putString("Profile",u.profile().toString());t.putInt("Version",u.definitionVersion());t.putBoolean("Recorded",u.recorded());t.putLong("Revision",u.revision());}
        else if(v instanceof Offer o){
            t.putString("Type","offer");try{t.putByteArray("Request",REQUEST.write(List.of(WarMobilization.start(o.request()))));}catch(IOException e){throw new IllegalArgumentException(e);}
            t.store("Body",UUIDUtil.CODEC,o.body());t.store("Parent",UUIDUtil.CODEC,o.parent());t.putLong("ParentRevision",o.parentRevision());t.putString("Dimension",o.dimension());
            t.putString("Workplace",o.workplace()==null?"":o.workplace().toString());t.putLong("EndsAt",o.endsAt());t.putDouble("Probability",o.probability());t.putDouble("Draw",o.draw());
            t.putString("Stage",o.stage().name());t.putLong("Revision",o.revision());t.putLong("ChangedAt",o.changedAt());t.putString("Reason",o.reason());
        }return t;
    }
    protected WarMusterEntry decode(CompoundTag t){
        var id=uuid(t,"Id");return switch(string(t,"Type")){
            case "army"->{fields(t,Set.of("Id","Type","Owner","Name","CreatedAt"));yield new Army(id,uuid(t,"Owner"),string(t,"Name"),number(t,"CreatedAt"));}
            case "unit"->{fields(t,Set.of("Id","Type","Army","Name","Profile","Version","Recorded","Revision"));
                if(!(t.get("Version") instanceof IntTag)||!(t.get("Recorded") instanceof ByteTag))throw new IllegalArgumentException("invalid unit fields");
                yield new Unit(id,uuid(t,"Army"),string(t,"Name"),WarKey.parse(string(t,"Profile")),t.getIntOr("Version",0),t.getBooleanOr("Recorded",false),number(t,"Revision"));}
            case "offer"->{
                fields(t,Set.of("Id","Type","Request","Body","Parent","ParentRevision","Dimension","Workplace","EndsAt","Probability","Draw","Stage","Revision","ChangedAt","Reason"));
                if(!(t.get("Request") instanceof ByteArrayTag)||t.getByteArray("Request").orElseThrow().length>8192||!(t.get("Probability") instanceof DoubleTag)||!(t.get("Draw") instanceof DoubleTag))throw new IllegalArgumentException("invalid offer fields");
                try{var requests=REQUEST.read(t.getByteArray("Request").orElseThrow());
                    if(requests.size()!=1||!requests.getFirst().request().id().equals(id)||!requests.getFirst().equals(WarMobilization.start(requests.getFirst().request())))throw new IllegalArgumentException("invalid embedded request");
                    var w=string(t,"Workplace");yield new Offer(requests.getFirst().request(),uuid(t,"Body"),uuid(t,"Parent"),number(t,"ParentRevision"),string(t,"Dimension"),w.isEmpty()?null:UUID.fromString(w),number(t,"EndsAt"),
                            t.getDoubleOr("Probability",Double.NaN),t.getDoubleOr("Draw",Double.NaN),Stage.valueOf(string(t,"Stage")),number(t,"Revision"),number(t,"ChangedAt"),string(t,"Reason"));
                }catch(IOException e){throw new IllegalArgumentException("invalid offer request",e);}
            }
            default->throw new IllegalArgumentException("unsupported muster entry");
        };
    }
}
