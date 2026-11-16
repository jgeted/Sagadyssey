package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

public final class WarCommandPostCodec extends NbtSnapshotCodec<WarCommandPost>{
    public WarCommandPostCodec(){super("sagadyssey:war_command_post/v1",1024*1024,1024);}
    protected UUID identity(WarCommandPost p){return p.unit();}
    protected CompoundTag encode(WarCommandPost p){
        var t=new CompoundTag();t.store("Unit",UUIDUtil.CODEC,p.unit());t.store("Army",UUIDUtil.CODEC,p.army());t.store("Binder",UUIDUtil.CODEC,p.binder());
        t.putString("Dimension",p.position().dimension().toString());t.putInt("X",p.position().x());t.putInt("Y",p.position().y());t.putInt("Z",p.position().z());t.putString("Block",p.block().toString());t.putLong("Revision",p.revision());return t;
    }
    protected WarCommandPost decode(CompoundTag t){
        fields(t,Set.of("Unit","Army","Binder","Dimension","X","Y","Z","Block","Revision"));
        for(String k:List.of("X","Y","Z"))if(!MinecraftApi.containsTag(t,k,Tag.TAG_INT))throw new IllegalArgumentException("invalid post coordinate");
        return new WarCommandPost(uuid(t,"Unit"),uuid(t,"Army"),uuid(t,"Binder"),new WarIntent.Position(WarKey.parse(string(t,"Dimension")),t.getIntOr("X",0),t.getIntOr("Y",0),t.getIntOr("Z",0)),WarKey.parse(string(t,"Block")),number(t,"Revision"));
    }
}
