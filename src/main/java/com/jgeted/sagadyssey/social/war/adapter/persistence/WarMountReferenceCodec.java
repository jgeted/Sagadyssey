package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.domain.WarMountReference;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

/** Strict original entity references. Vanilla entity saves are not a world-atomic journal. */
public final class WarMountReferenceCodec {
    public static final String RIDER_KEY="SagadysseyWarMountV1", MOUNT_KEY="SagadysseyWarMountUseV1";
    private static final Set<String> FIELDS=Set.of("Contract","Character","Body","Mount","Owner","Principal","Dimension");
    public Optional<WarMountReference> read(CompoundTag owner,String key) {
        if(!owner.contains(key))return Optional.empty();
        if(!(owner.get(key) instanceof CompoundTag t) || !t.keySet().equals(FIELDS))throw new IllegalArgumentException("invalid mount reference");
        return Optional.of(new WarMountReference(uuid(t,"Contract"),uuid(t,"Character"),uuid(t,"Body"),uuid(t,"Mount"),uuid(t,"Owner"),uuid(t,"Principal"),WarKey.parse(t.getString("Dimension").orElseThrow())));
    }
    public void store(CompoundTag owner,String key,WarMountReference value) {
        var t=new CompoundTag();t.store("Contract",UUIDUtil.CODEC,value.contract());t.store("Character",UUIDUtil.CODEC,value.character());t.store("Body",UUIDUtil.CODEC,value.body());
        t.store("Mount",UUIDUtil.CODEC,value.mount());t.store("Owner",UUIDUtil.CODEC,value.owner());t.store("Principal",UUIDUtil.CODEC,value.principal());t.putString("Dimension",value.dimension().toString());owner.put(key,t);
    }
    private static UUID uuid(CompoundTag t,String key){return t.read(key,UUIDUtil.CODEC).orElseThrow(()->new IllegalArgumentException("invalid mount UUID"));}
}
