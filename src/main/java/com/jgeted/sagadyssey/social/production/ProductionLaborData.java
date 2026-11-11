package com.jgeted.sagadyssey.social.production;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Actual entity execution receipts, never elapsed offline or combat time. Old orders start at zero. */
public final class ProductionLaborData extends NbtSavedData {
    public static final String NAME="sagadyssey_production_labor_v3";
    public static final Factory<ProductionLaborData> FACTORY=new Factory<>(ProductionLaborData::new,ProductionLaborData::load);
    public record Progress(UUID order, int worked, long lastTick) {
        public Progress { if(order==null||worked<0||worked>72000||lastTick<0)throw new IllegalArgumentException("invalid labor receipt"); }
    }
    private final Map<UUID,Progress> progress=new LinkedHashMap<>();
    public static ProductionLaborData get(MinecraftServer server) {
        var storage=server.overworld().getDataStorage();var d=storage.get(FACTORY.type(NAME));if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("unreadable labor ledger");
        d=new ProductionLaborData();storage.set(FACTORY.type(NAME),d);return d;
    }
    public int worked(UUID order){var p=progress.get(order);return p==null?0:p.worked();}
    public int tick(UUID order,int required,long now) {
        if(required<1||required>72000||now<0)throw new IllegalArgumentException("invalid labor limit/time");
        var p=progress.get(order);
        if(p!=null && now<=p.lastTick())return p.worked();
        if(p==null && progress.size()>=4096)throw new IllegalStateException("labor ledger full; explicit archival required");
        int worked=Math.min(required,(p==null?0:p.worked())+1);
        progress.put(order,new Progress(order,worked,now));setDirty();return worked;
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){
        t.putInt("Schema",1);var list=new ListTag();for(var p:progress.values()){
            var row=new CompoundTag();row.store("Order", UUIDUtil.CODEC,p.order());row.putInt("Worked",p.worked());row.putLong("LastTick",p.lastTick());list.add(row);
        }t.put("Progress",list);return t;
    }
    public static ProductionLaborData load(CompoundTag t,HolderLookup.Provider lookup){
        if(!MinecraftApi.containsTag(t, "Schema",Tag.TAG_INT)||t.getIntOr("Schema", 0)!=1||!(t.get("Progress") instanceof ListTag list)
                ||list.size()>4096||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid labor schema");
        var d=new ProductionLaborData();for(var raw:list){var r=(CompoundTag)raw;
            if(!r.read("Order", UUIDUtil.CODEC).isPresent()||!MinecraftApi.containsTag(r, "Worked",Tag.TAG_INT)||!MinecraftApi.containsTag(r, "LastTick",Tag.TAG_LONG))throw new IllegalArgumentException("missing labor receipt fields");
            var p=new Progress(r.read("Order", UUIDUtil.CODEC).orElseThrow(),r.getIntOr("Worked", 0),r.getLongOr("LastTick", 0L));
            if(d.progress.putIfAbsent(p.order(),p)!=null)throw new IllegalArgumentException("duplicate labor order");
        }return d;
    }
}
