package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.social.war.api.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Visible terrain only. No global trap lookup and no custom damage, slow or ally immunity. */
final class WarHazards12111 implements WarHazardAdapter{
    private record CheckedPath(Path path,UUID army,long revision,long at,int nodes,double width,boolean allowed){}
    private final Map<NpcBase,CheckedPath> checkedPaths=new WeakHashMap<>();
    public Optional<Sight> see(NpcBase npc,WarIntent.Position target){
        var l=(ServerLevel)npc.level();var at=new BlockPos(target.x(),target.y(),target.z());
        if(!l.dimension().identifier().toString().equals(target.dimension().toString())||!loaded(l,at)||npc.distanceToSqr(Vec3.atCenterOf(at))>64)return Optional.empty();
        if(!WarBlockWork12111.visibleFrom(npc,npc.getEyePosition(),at))return Optional.empty();
        var state=l.getBlockState(at);return Optional.of(new Sight(target,kind(state),net.minecraft.nbt.NbtUtils.writeBlockState(state).toString()));
    }
    public Optional<WarIntent.Position> safeStand(NpcBase npc,WarIntent.Position target){
        var l=(ServerLevel)npc.level();var at=new BlockPos(target.x(),target.y(),target.z());var candidates=new ArrayList<BlockPos>();
        for(int dy=0;dy<=2;dy++)for(var d:Direction.Plane.HORIZONTAL){var q=at.relative(d).above(dy);
            if(loaded(l,q)&&FieldworkRoutes.walkable(npc,q))candidates.add(q);}
        candidates.sort(Comparator.comparingDouble(q->npc.distanceToSqr(Vec3.atBottomCenterOf(q))));
        return candidates.stream().map(q->new WarIntent.Position(target.dimension(),q.getX(),q.getY(),q.getZ())).filter(p->mayStand(npc,p)).findFirst();
    }
    public Optional<WarKey> kind(String state,NpcBase npc){
        return kind(WarNativeBindings.currentBuild().engineering().state((ServerLevel)npc.level(),state));
    }
    private static Optional<WarKey> kind(net.minecraft.world.level.block.state.BlockState state){
        if(state.is(Blocks.COBWEB))return Optional.of(WarKey.parse("sagadyssey:war/hazard/web"));
        if(state.equals(Blocks.POINTED_DRIPSTONE.defaultBlockState()))return Optional.of(WarKey.parse("sagadyssey:war/hazard/spike"));
        return Optional.empty();
    }
    public boolean mayStand(NpcBase npc,WarIntent.Position feet){
        var memory=WarHazardRuntime.existing(npc.getServer());if(memory.isEmpty())return true;
        var army=memory.get().armyOf(npc);if(army.isEmpty())return true;
        return mayStand(npc,feet,memory.get(),army.get());
    }
    private boolean mayStand(NpcBase npc,WarIntent.Position feet,WarHazardRuntime memory,UUID army){
        double radius=(npc.isPassenger()?npc.getRootVehicle().getBbWidth():npc.getBbWidth())/2.0;
        for(int x=(int)Math.floor(feet.x()+.5-radius+.01);x<=(int)Math.floor(feet.x()+.5+radius-.01);x++)
            for(int z=(int)Math.floor(feet.z()+.5-radius+.01);z<=(int)Math.floor(feet.z()+.5+radius-.01);z++)
                if(memory.blocked(army,new WarIntent.Position(feet.dimension(),x,feet.y(),z)))return false;
        return true;
    }
    public boolean mayFollow(NpcBase npc,Path path){
        var memory=WarHazardRuntime.existing(npc.getServer());if(memory.isEmpty())return true;var army=memory.get().armyOf(npc);if(army.isEmpty())return true;
        var knowledge=memory.get().memory(army.get());if(knowledge.isEmpty()||!knowledge.get().avoid()||knowledge.get().observations().isEmpty())return true;
        long now=npc.level().getGameTime(),revision=knowledge.get().revision();double width=npc.isPassenger()?npc.getRootVehicle().getBbWidth():npc.getBbWidth();var prior=checkedPaths.get(npc);
        if(prior!=null&&prior.path()==path&&prior.army().equals(army.get())&&prior.revision()==revision&&prior.nodes()==path.getNodeCount()&&prior.width()==width&&now>=prior.at()&&now-prior.at()<20)return prior.allowed();
        boolean allowed=true;
        for(int i=Math.max(1,path.getNextNodeIndex());i<path.getNodeCount();i++){var n=path.getNode(i);
            if(!mayStand(npc,new WarIntent.Position(WarKey.parse(npc.level().dimension().identifier().toString()),n.x,n.y,n.z),memory.get(),army.get())){allowed=false;break;}}
        checkedPaths.put(npc,new CheckedPath(path,army.get(),revision,now,path.getNodeCount(),width,allowed));return allowed;
    }
    private static boolean loaded(ServerLevel l,BlockPos p){return l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null;}
}
