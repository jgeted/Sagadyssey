package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.LightLayer;
import java.util.*;

/** Only enabled local residents. No damage injection, healing or new movement scheduler. */
public final class SettlementSafetyRuntime {
    private SettlementSafetyRuntime() {}
    public static boolean insideDefence(NpcBase n,BlockPos target) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(n));var r=d==null?null:d.resident(n);if(r==null)return false;
        var centre=NaturalVillageLayout.at(SettlementCoordinatorData.building(r),new BlockPos(12,1,12));
        return target.distSqr(centre)<=SettlementSafetyPolicy.DEFENCE_RADIUS*SettlementSafetyPolicy.DEFENCE_RADIUS;
    }
    public static boolean allowedTarget(NpcBase n,LivingEntity target) {
        if(!SettlementSafetyData.enabled(n)||!(target instanceof Zombie))return true;
        if(target.getClass()!=Zombie.class)return false;
        var z=(Zombie)target;
        boolean threatened=z.getTarget() instanceof NpcBase victim&&SettlementSafetyData.enabled(victim)
                &&sameSite(n,victim)||n.getLastHurtByMob()==z;
        var role=n.getProfession();boolean trained=role==NpcProfession.WARRIOR||role==NpcProfession.HEAVY;
        boolean equipment=n.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)||n.getMainHandItem().getItem() instanceof AxeItem;
        var needs=com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(n)).needs(n.getCharacterId());
        var life=com.jgeted.sagadyssey.social.life.NpcLifeData.get(MinecraftApi.server(n)).record(n.getCharacterId());
        boolean exhausted=needs!=null&&needs.hunger()>=800||life!=null&&life.fatigue()>=800;
        return !exhausted&&insideDefence(n,z.blockPosition())&&insideDefence(n,n.blockPosition())
                &&SettlementSafetyPolicy.defend(trained,equipment,n.getHealth(),n.getMaxHealth(),
                InjuryData.get(MinecraftApi.server(n)).triage(n.getCharacterId(),n.level().getGameTime()).incapacitated(),threatened,0);
    }
    private static boolean sameSite(NpcBase a,NpcBase b) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(a));
        return d!=null&&d.resident(a)==d.resident(b)&&d.resident(a)!=null;
    }
    static Zombie threat(NpcBase n) {
        return n.level().getEntitiesOfClass(Zombie.class,n.getBoundingBox().inflate(SettlementSafetyPolicy.THREAT_RADIUS),
                z->z.getClass()==Zombie.class&&z.isAlive()&&n.hasLineOfSight(z))
                .stream().min(Comparator.comparingDouble(n::distanceToSqr)).orElse(null);
    }
    static boolean sheltered(NpcBase n,BlockPos p) {
        if(!(n.level() instanceof ServerLevel l)||!SettlementSafetyData.loaded(l,p)||!FieldworkRoutes.walkable(n,p))return false;
        // An actual roof plus measured illumination; this is a refuge, not proof against all monsters.
        boolean roof=false;for(int y=2;y<=4;y++)if(!l.getBlockState(p.above(y)).getCollisionShape(l,p.above(y)).isEmpty())roof=true;
        return roof&&l.getBrightness(LightLayer.BLOCK,p)>0;
    }
    static BlockPos refuge(NpcBase n) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(n));var r=d==null?null:d.resident(n);if(r==null)return null;
        var b=SettlementCoordinatorData.building(r);
        if(SettlementDevelopmentLayout.supports(b)){
            var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(MinecraftApi.server(n)).forMember(n.getCharacterId());
            if(home!=null&&home.homeAnchor()!=null)for(int dx=0;dx<=2;dx++){
                var p=home.homeAnchor().offset(dx,0,0);
                if(sheltered(n,p)&&FieldworkRoutes.plan(n,p,false)!=null)return p;
            }
        }
        for(var local:List.of(new BlockPos(4,1,14),new BlockPos(6,1,14),new BlockPos(8,1,14),new BlockPos(15,1,14))) {
            var p=NaturalVillageLayout.at(b,local);if(!sheltered(n,p))continue;
            var path=n.getNavigation().createPath(p,0);
            if(n.blockPosition().distSqr(p)<=2||path!=null&&path.canReach())return p;
        }
        return null;
    }
    static boolean withdrawal(NpcBase n,long now) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(n));var r=d==null?null:d.resident(n);if(r==null)return false;
        var z=threat(n);
        if(z!=null) {
            d.observedThreat(r,now);
            return !allowedTarget(n,z);
        }
        return !SettlementSafetyPolicy.quiet(now,r.getLongOr("LastThreat", 0L));
    }
    static boolean nightOutside(NpcBase n) {
        return SettlementSafetyData.enabled(n)&&SettlementSafetyPolicy.night(n.level().getDayTime())&&!sheltered(n,n.blockPosition());
    }
    public static void maintain(NpcBase n) {
        if(!n.isAlive()||n.level().isClientSide()||!NpcBrainRuntime.isPlanned(n)||!SettlementSafetyData.enabled(n))return;
        if(n.getTarget()!=null&&!allowedTarget(n,n.getTarget())){n.clearOwnerCombatTargets();n.setTarget(null);}
        maintainDoors(n);
        if(n.level().getGameTime()%20!=0)return;
        boolean retreat=withdrawal(n,n.level().getGameTime());
        if(!retreat&&!nightOutside(n))return;
        var active=NpcBrainRuntime.data(MinecraftApi.server(n)).active(n.getUUID()).orElse(null);
        if(active!=null&&active.providerId().equals(SettlementSafetyTask.ID))return;
        // Night avoidance must not starve residents or repeatedly cancel genuine sleep/food work.
        if(!retreat&&active!=null&&active.providerId().toString().contains("life"))return;
        if(retreat&&n.isSleeping())n.stopSleeping();
        if(active!=null)NpcBrainRuntime.cancel(n,PlanCancellationReason.THREAT,"真实威胁/夜间暗区，保留原义务后避险");
    }
    private static void maintainDoors(NpcBase n) {
        var d=SettlementSafetyData.existing(MinecraftApi.server(n));var r=d==null?null:d.resident(n);if(r==null)return;
        long now=n.level().getGameTime();if(r.getLongOr("DoorTick", 0L)==now)return;r.putLong("DoorTick",now);
        var b=SettlementCoordinatorData.building(r);var l=(ServerLevel)n.level();
        if(!SettlementGrowthData.get(MinecraftApi.server(n)).authority(MinecraftApi.server(n),b))return;
        for(var local:List.of(new BlockPos(12,1,21),new BlockPos(16,1,12))) {
            var p=NaturalVillageLayout.at(b,local);if(!SettlementSafetyData.loaded(l,p))continue;
            var state=l.getBlockState(p);if(!state.is(net.minecraft.world.level.block.Blocks.OAK_DOOR))continue;
            boolean alarm=now-r.getLongOr("LastThreat", 0L)<SettlementSafetyPolicy.QUIET_TICKS;
            var inside=NaturalVillageLayout.at(b,local.equals(new BlockPos(12,1,21))?new BlockPos(12,1,20):new BlockPos(16,1,13));
            boolean crossing=false;
            for(var resident:l.getEntitiesOfClass(NpcBase.class,new net.minecraft.world.phys.AABB(p).inflate(4))) {
                if(d.resident(resident)!=r||!resident.isAlive()||resident.distanceToSqr(p.getCenter())>16)continue;
                double inward=(resident.getX()-p.getX()-.5)*(inside.getX()-p.getX())
                        +(resident.getZ()-p.getZ()-.5)*(inside.getZ()-p.getZ());
                // During an alarm an already-entered resident must not hold the door open for pursuit.
                if(alarm&&inward>.5)continue;
                var path=resident.getNavigation().getPath();
                if(path==null||path.isDone())continue;
                for(int j=path.getNextNodeIndex();j<Math.min(path.getNodeCount(),path.getNextNodeIndex()+4);j++)
                    if(path.getNodePos(j).distSqr(p)<=1)crossing=true;
            }
            var door=(net.minecraft.world.level.block.DoorBlock)state.getBlock();String timer="DoorOpen:"+p.asLong();
            boolean open=state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
            boolean elapsed=SettlementDoorTiming.mayClose(r,p,open,crossing,now);
            if(crossing) {
                door.setOpen(n,l,state,p,true);r.putLong(timer,now);
            } else if(open&&(alarm||elapsed)
                    &&l.getEntitiesOfClass(LivingEntity.class,new net.minecraft.world.phys.AABB(p).expandTowards(0,1,0).inflate(.25)).isEmpty()) {
                door.setOpen(n,l,state,p,false);r.putLong("LastDoorClosed",now);
                SettlementDoorTiming.mayClose(r,p,false,false,now);
            }
        }d.setDirty();
    }
}
