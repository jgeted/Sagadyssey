package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings.ServiceCheckpoint;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.domain.WarState;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Native SavedData batch boundary. Future completion alone is insufficient: verify bounded published files. */
public final class WarMusterCheckpoint12111 implements ServiceCheckpoint {
    private record Checkpoint(WarState core, Map<UUID,ServiceRelationship> services) {}
    private final MinecraftServer server;
    private final Path directory;
    private CompletableFuture<Checkpoint> pending;
    private long nextAt;
    private boolean requested;
    private WarState core;
    private Map<UUID,ServiceRelationship> services=Map.of();
    private String problem="";
    public WarMusterCheckpoint12111(MinecraftServer server){this.server=server;directory=server.getWorldPath(LevelResource.ROOT).resolve("data");}
    public void request(){requested=true;}
    public Optional<WarState> core(){return Optional.ofNullable(core);}
    public boolean contains(ServiceRelationship value){return value.equals(services.get(value.id()));}
    public String problem(){return problem;}
    public void tick(long now){
        if(pending!=null&&pending.isDone()){
            try{var saved=pending.join();core=saved.core();services=saved.services();}
            catch(CompletionException failure){problem="MUSTER_CHECKPOINT_REQUIRES_REVIEW:"+failure.getCause().getClass().getSimpleName();core=null;services=Map.of();}
            pending=null;
        }
        if(pending==null&&requested&&now>=nextAt&&problem.isEmpty()){
            requested=false;nextAt=now+20;
            pending=server.overworld().getDataStorage().scheduleSave().thenApplyAsync(ignored->{
                try{return read();}catch(IOException failure){throw new CompletionException(failure);}
            });
        }
    }
    private Checkpoint read()throws IOException{
        var saved=WarSavedData.readDisk(directory,new WarStateCodec(WarStateCodec.Limits.defaults()));
        if(saved.isEmpty()||!saved.get().writable())throw new IOException("main military checkpoint missing or blocked");
        var file=directory.resolve(ServiceRelationshipData.DATA_NAME+".dat");
        try{
            var attr=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attr.isRegularFile()||attr.size()>16*1024*1024)throw new IOException("invalid service checkpoint");
        }catch(NoSuchFileException absent){
            for(String suffix:List.of(".old","_old"))try{Files.readAttributes(file.resolveSibling(file.getFileName()+suffix),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw new IOException("service primary missing with backup");}catch(NoSuchFileException missing){}
            return new Checkpoint(saved.get().checkpoint(),Map.of());
        }
        var root=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));
        if(!(root.get("data") instanceof CompoundTag data)||!(data.get("Relationships") instanceof ListTag rows)||rows.size()>16384)throw new IOException("invalid service snapshot root");
        var result=new HashMap<UUID,ServiceRelationship>();for(Tag raw:rows){if(!(raw instanceof CompoundTag row))throw new IOException("invalid relationship row");var r=ServiceRelationship.load(row);if(result.putIfAbsent(r.id(),r)!=null)throw new IOException("duplicate relationship");}
        return new Checkpoint(saved.get().checkpoint(),Map.copyOf(result));
    }
}
