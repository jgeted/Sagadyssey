package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.MinecraftApi;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarMountReference;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarMountReferenceCodec;
import com.jgeted.sagadyssey.social.war.spi.WarMobilityPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Existing owned horses, native Mob navigation/control, and no spawning, gear grants or owner mutation. */
public final class WarMobility12111 implements Mobility<NpcBase> {
    private static final WarMountReferenceCodec CODEC=new WarMountReferenceCodec();
    private record Finish(WarIntent.Position target,double speed,long until){}
    private static final Map<net.minecraft.server.MinecraftServer,Map<NpcBase,Finish>> FINISHING=Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<NpcBase,Finish> finishing(NpcBase npc,boolean create){var server=((ServerLevel)npc.level()).getServer();return create?FINISHING.computeIfAbsent(server,k->new WeakHashMap<>()):FINISHING.get(server);}
    public void tick(NpcBase npc){
        thread(npc);var path=npc.getNavigation().getPath();if(path!=null&&!path.isDone()&&!com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings.currentBuild().hazards().mayFollow(npc,path)){stop(npc);return;}
        var cache=finishing(npc,false);var goal=cache==null?null:cache.get(npc);if(goal==null)return;
        if(npc.level().getGameTime()>goal.until()||!mayAct(npc)||!npc.isPassenger()||!nextClear(npc,goal.target())){stop(npc);return;}
        npc.getMoveControl().setWantedPosition(goal.target().x()+.5,goal.target().y(),goal.target().z()+.5,goal.speed());
    }
    public Observation prepare(NpcBase npc,WarMilitiaRequest request) {
        thread(npc);var loadout=InitialWarDefinitions.LOADOUTS.find(request.loadout());
        if(loadout.isEmpty())return blocked("UNKNOWN_MOBILITY_LOADOUT");
        if(loadout.get().mount()==WarLoadout.Mount.FOOT)return observe(npc,request);
        if(!(npc.getVehicle() instanceof Horse horse) || !controlled(npc,horse))return blocked("EXISTING_CONTROLLED_SADDLED_HORSE_REQUIRED");
        var owner=MinecraftApi.ownerId(horse);
        if(owner==null || !authorizedOwner(npc,owner,request.actorId()))return blocked("NATIVE_MOUNT_OWNER_PERMISSION_REQUIRED");
        var next=new WarMountReference(request.id(),request.characterId(),npc.getUUID(),horse.getUUID(),owner,request.actorId(),dimension(npc));
        try {
            var rider=CODEC.read(npc.getPersistentData(),WarMountReferenceCodec.RIDER_KEY);var mount=CODEC.read(horse.getPersistentData(),WarMountReferenceCodec.MOUNT_KEY);
            var server=((ServerLevel)npc.level()).getServer();
            if(!WarMountReference.mayPrepare(rider,mount,next,id->WorkCommitmentRuntime.existing(server).flatMap(work->work.find(id)).filter(work->work.phase()==WorkCommitment.Phase.RELEASED).isPresent()))return review("MOUNT_USE_REFERENCE_CONFLICT");
            CODEC.store(npc.getPersistentData(),WarMountReferenceCodec.RIDER_KEY,next);CODEC.store(horse.getPersistentData(),WarMountReferenceCodec.MOUNT_KEY,next);
            return observe(npc,request);
        }catch(IllegalArgumentException e){return review("MALFORMED_MOUNT_USE_REFERENCE");}
    }
    public Observation observe(NpcBase npc,WarMilitiaRequest request) {
        thread(npc);var loadout=InitialWarDefinitions.LOADOUTS.find(request.loadout());
        if(loadout.isEmpty())return blocked("UNKNOWN_MOBILITY_LOADOUT");
        if(!npc.getUUID().equals(Optional.ofNullable(CharacterRegistryData.get(((ServerLevel)npc.level()).getServer()).bodyOf(request.characterId())).map(CharacterBodyRef::entityId).orElse(null))
                || !npc.getCharacterId().equals(request.characterId()))return review("ORIGINAL_RIDER_BODY_REQUIRED");
        if(loadout.get().mount()==WarLoadout.Mount.FOOT)return npc.isPassenger()?blocked("FOOT_SERVICE_HAS_UNPREPARED_VEHICLE"):new Observation(Mode.FOOT,"ACTUAL_FOOT_BODY");
        try {
            var reference=CODEC.read(npc.getPersistentData(),WarMountReferenceCodec.RIDER_KEY);
            if(reference.isEmpty())return review("ORIGINAL_MOUNT_REFERENCE_REQUIRED");var ref=reference.get();
            if(!ref.contract().equals(request.id()) || !ref.character().equals(request.characterId()) || !ref.body().equals(npc.getUUID()) || !ref.principal().equals(request.actorId()) || !ref.dimension().equals(dimension(npc)))return review("ORIGINAL_MOUNT_REFERENCE_MISMATCH");
            if(!npc.isPassenger())return new Observation(Mode.FOOT_FALLBACK,"DISMOUNTED_ORIGINAL_PERSON_ON_FOOT");
            if(!(npc.getVehicle() instanceof Horse horse) || !horse.getUUID().equals(ref.mount()))return review("DIFFERENT_MOUNT_REQUIRES_NEW_PREPARATION");
            if(!CODEC.read(horse.getPersistentData(),WarMountReferenceCodec.MOUNT_KEY).filter(ref::equals).isPresent())return review("MOUNT_REFERENCE_PAIR_REQUIRES_REVIEW");
            if(!Objects.equals(MinecraftApi.ownerId(horse),ref.owner()) || !authorizedOwner(npc,ref.owner(),ref.principal()))return blocked("NATIVE_MOUNT_OWNER_CHANGED");
            if(!controlled(npc,horse))return blocked("ORIGINAL_MOUNT_UNFIT_OR_NOT_CONTROLLED");
            var path=horse.getNavigation().getPath();
            if(path!=null && !path.isDone() && !clear(npc,path.getNextNodePos().getX()+.5,path.getNextNodePos().getY(),path.getNextNodePos().getZ()+.5,false))return blocked("RIDER_CLEARANCE_CHANGED");
            var move=horse.getMoveControl();
            var cache=finishing(npc,false);var finish=cache==null?null:cache.get(npc);
            if(finish!=null&&npc.level().getGameTime()<=finish.until()&&!nextClear(npc,finish.target()))return blocked("RIDER_CLEARANCE_CHANGED");
            if(path==null&&move.hasWanted()){
                double dx=move.getWantedX()-horse.getX(),dy=move.getWantedY()-horse.getY(),dz=move.getWantedZ()-horse.getZ(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz),step=distance>.5?.5/distance:1;
                if(!clear(npc,horse.getX()+dx*step,horse.getY()+dy*step,horse.getZ()+dz*step,false))return blocked("RIDER_CLEARANCE_CHANGED");
            }
            return new Observation(Mode.RIDING,"EXISTING_NATIVE_MOUNT_CONTROL");
        }catch(IllegalArgumentException e){return review("MALFORMED_MOUNT_USE_REFERENCE");}
    }
    public Pose pose(NpcBase npc) {thread(npc);var mover=npc.isPassenger()?npc.getVehicle():npc;return new Pose(dimension(npc),mover.getX(),mover.getY(),mover.getZ());}
    public boolean arrived(NpcBase npc,WarIntent.Position point){
        // Native large-mob path nodes use an integer footprint center and width-dependent waypoint tolerance.
        double radius=npc.isPassenger()?Math.max(.9,npc.getVehicle().getBbWidth()*.5+.5):.9;
        return pose(npc).distanceSquared(point)<=radius*radius;
    }
    /** Called by native attacks as an additional guard; normal field execution also observes the full request. */
    static boolean mayAct(NpcBase npc) {
        if(!npc.isPassenger())return true;
        if(!(npc.getVehicle() instanceof Horse horse) || !controlled(npc,horse))return false;
        try {
            var ref=CODEC.read(npc.getPersistentData(),WarMountReferenceCodec.RIDER_KEY);if(ref.isEmpty())return false;var r=ref.get();
            return r.body().equals(npc.getUUID()) && r.character().equals(npc.getCharacterId()) && r.mount().equals(horse.getUUID()) && r.dimension().equals(dimension(npc))
                    && Objects.equals(MinecraftApi.ownerId(horse),r.owner()) && authorizedOwner(npc,r.owner(),r.principal())
                    && CODEC.read(horse.getPersistentData(),WarMountReferenceCodec.MOUNT_KEY).filter(r::equals).isPresent()
                    && WorkCommitmentRuntime.existing(((ServerLevel)npc.level()).getServer()).flatMap(w->w.find(r.contract())).filter(w->w.phase()==WorkCommitment.Phase.ACTIVE && w.intent().body().equals(npc.getUUID()) && w.intent().character().equals(npc.getCharacterId()) && w.intent().ownerKind().equals("sagadyssey:war")).isPresent();
        }catch(IllegalArgumentException e){return false;}
    }
    static void stop(NpcBase npc) {
        thread(npc);var cache=finishing(npc,false);if(cache!=null)cache.remove(npc);npc.getNavigation().stop();
        if(npc.getVehicle() instanceof Horse horse && horse.getFirstPassenger()==npc) {horse.getNavigation().stop();horse.getMoveControl().setWait();horse.setDeltaMovement(0,horse.getDeltaMovement().y,0);}
    }
    static boolean canStand(NpcBase npc,WarIntent.Position p) {
        thread(npc);return dimension(npc).equals(p.dimension()) && (!npc.isPassenger() || mayAct(npc)) && clear(npc,p.x()+.5,p.y(),p.z()+.5,true);
    }
    static boolean navigate(NpcBase npc,WarIntent.Position p,double speed) {
        if(!Double.isFinite(speed) || speed<=0 || !canStand(npc,p)){stop(npc);return false;}
        var nav=npc.getNavigation();
        if(npc.isPassenger()){
            var mover=npc.getVehicle();double dx=p.x()+.5-mover.getX(),dz=p.z()+.5-mover.getZ();
            // Large-mob path nodes have coarse centers/tolerance. Finish a clear, level last segment
            // with the SAME native MoveControl, preserving native acceleration, collision and inertia.
            if(dx*dx+dz*dz<=9&&Math.abs(mover.getY()-p.y())<.25){
                boolean clear=true;for(int i=1;i<=8&&clear;i++){double t=i/8.0;clear=clear(npc,mover.getX()+dx*t,p.y(),mover.getZ()+dz*t,true);}
                if(clear){nav.stop();finishing(npc,true).put(npc,new Finish(p,speed,Math.addExact(npc.level().getGameTime(),25)));npc.getMoveControl().setWantedPosition(p.x()+.5,p.y(),p.z()+.5,speed);return true;}
            }
        }
        var cache=finishing(npc,false);if(cache!=null)cache.remove(npc);var path=nav.createPath(new BlockPos(p.x(),p.y(),p.z()),0);
        // Native follow range can yield a useful partial path; the shared progress guard detects stalls.
        if(path==null || path.getNodeCount()==0 || path.getNodeCount()>256||!com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings.currentBuild().hazards().mayFollow(npc,path)){stop(npc);return false;}
        if(npc.isPassenger())for(int i=path.getNextNodeIndex();i<path.getNodeCount();i++){
            var node=path.getNode(i);if(!clear(npc,node.x+.5,node.y,node.z+.5,false)){stop(npc);return false;}
        }
        return nav.moveTo(path,speed);
    }
    private static boolean nextClear(NpcBase npc,WarIntent.Position p){
        var mover=npc.getVehicle();if(mover==null||!dimension(npc).equals(p.dimension()))return false;
        double dx=p.x()+.5-mover.getX(),dy=p.y()-mover.getY(),dz=p.z()+.5-mover.getZ(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz),step=distance>.5?.5/distance:1;
        return clear(npc,mover.getX()+dx*step,mover.getY()+dy*step,mover.getZ()+dz*step,false);
    }
    private static boolean clear(NpcBase npc,double x,double y,double z,boolean footing) {
        if(!com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings.currentBuild().hazards().mayStand(npc,new WarIntent.Position(dimension(npc),(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z))))return false;
        var level=(ServerLevel)npc.level();Entity mover=npc.isPassenger()?npc.getVehicle():npc;
        double width=Math.max(npc.getBbWidth(),mover.getBbWidth()),height=npc.isPassenger()?Math.max(3.3,npc.getBoundingBox().maxY-mover.getY()):npc.getBbHeight();
        var box=new AABB(x-width/2+.01,y+.01,z-width/2+.01,x+width/2-.01,y+height,z+width/2-.01);
        if(y<level.getMinY() || box.maxY>level.getMaxY()+1)return false;
        for(int cx=((int)Math.floor(box.minX))>>4;cx<=((int)Math.floor(box.maxX))>>4;cx++)for(int cz=((int)Math.floor(box.minZ))>>4;cz<=((int)Math.floor(box.maxZ))>>4;cz++)if(level.getChunkSource().getChunkNow(cx,cz)==null)return false;
        if(level.getBlockCollisions(mover,box).iterator().hasNext())return false;
        var below=BlockPos.containing(x,y-.01,z);return !footing || level.getBlockState(below).isFaceSturdy(level,below,Direction.UP);
    }
    private static boolean controlled(NpcBase npc,Horse horse){return horse.isAlive() && horse.getHealth()>horse.getMaxHealth()*.25F && !horse.isBaby() && horse.isTamed() && horse.isSaddled() && !horse.isNoAi() && !horse.isLeashed() && horse.getPassengers().size()==1 && horse.getControllingPassenger()==npc;}
    private static boolean authorizedOwner(NpcBase npc,UUID owner,UUID principal){
        var registry=CharacterRegistryData.get(((ServerLevel)npc.level()).getServer());var player=registry.current(owner);
        if(player!=null && player.alive() && (player.id().equals(principal) || player.id().equals(npc.getCharacterId())))return true;
        for(var id:List.of(principal,npc.getCharacterId())){var c=registry.byId(id);var b=registry.bodyOf(id);if(c!=null && c.alive() && b!=null && b.entityId().equals(owner))return true;}return false;
    }
    private static WarKey dimension(NpcBase npc){return WarKey.parse(npc.level().dimension().identifier().toString());}
    private static Observation blocked(String reason){return new Observation(Mode.BLOCKED,reason);}
    private static Observation review(String reason){return new Observation(Mode.REVIEW,reason);}
    private static void thread(NpcBase npc){if(!(npc.level() instanceof ServerLevel level) || !level.getServer().isSameThread())throw new IllegalStateException("native mobility requires server thread");}
}
