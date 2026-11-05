package com.jgeted.sagadyssey.social.industry.forestry;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Reuses the original PLANT transaction and plan shape; no new world/identity/material ledger. */
public final class TreePlantTask {
    private TreePlantTask() {}
    /** Pending full mixed acceptance: test configuration cannot enable this in a normal server. */
    public static boolean enabled(NpcBase n) {
        return n.getServer() instanceof GameTestServer && Boolean.getBoolean("sagadyssey.industries.forestry")
                && n.getProfession()==NpcProfession.WORKER;
    }
    public static Optional<NpcPlan> choose(NpcBase n,long now) {
        if(!enabled(n) || n.getCommand()!=NpcCommand.WORK || n.getTarget()!=null || !n.onGround()
                || !n.hasWorkZone() || !NpcSchedulePlanner.allowsWork(n)
                || !com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.ordinaryFieldwork(n)) return Optional.empty();
        var work=FieldworkData.get(n.getServer());
        if(FieldworkDepositTaskExecutor.hasDeliverableCargo(n,work)) return Optional.empty();
        var rights=RightsStateData.get(n.getServer());var bag=ContainerIds.npcPersonal(n.getCharacterId());
        var owner=SubjectRef.character(n.getCharacterId());
        boolean seed=false;
        for(int slot=0;slot<n.getPersonalInventory().getContainerSize();slot++) {
            var stack=n.getPersonalInventory().getItem(slot);
            if(!stack.is(Items.OAK_SAPLING))continue;
            var fp=ItemFingerprint.of(stack);
            if(rights.reservedQuantity(bag,fp,null,now)==0 && rights.context(bag)!=null
                    && new RightsService(rights).can(owner,AssetRight.CONSUME,bag,slot,fp,now).status()==RightsVerdict.Status.ALLOW) {seed=true;break;}
        }
        if(!seed)return Optional.empty();
        // A prior real log-cut receipt is knowledge of a stump, never permission for a new parcel.
        var stumps=work.receiptsFor(n.getUUID()).stream()
                .filter(r->r.action().operation()==FieldworkAction.Operation.CHOP
                        && r.action().expectedState().contains("minecraft:oak_log"))
                .map(r->r.action().target()).distinct().sorted(Comparator.comparingLong(net.minecraft.core.BlockPos::asLong)).toList();
        for(var p:stumps) {
            if(!n.level().hasChunkAt(p) || !FieldworkDiscovery.allowed(n,p) || !n.level().getBlockState(p).isAir())continue;
            var soil=n.level().getBlockState(p.below());
            if(!soil.is(Blocks.DIRT) && !soil.is(Blocks.GRASS_BLOCK))continue;
            var sapling=Blocks.OAK_SAPLING.defaultBlockState();if(!sapling.canSurvive(n.level(),p))continue;
            var route=FieldworkRoutes.plan(n,p,false);if(route==null)continue;
            var target=new FieldworkTarget(p,FieldworkAction.Operation.PLANT,n.level().getBlockState(p),sapling,Items.OAK_SAPLING,null);
            var original=FieldworkPlanFactory.work(n.getUUID(),target,route,n.level().dimension().location().toString(),now);
            var steps=original.steps().stream().map(step->{
                var params=new HashMap<>(step.params());
                if(params.containsKey("operation"))params.put("forestry","true");
                return new NpcPlanStep(step.index(),step.phase(),step.executorId(),params,step.status(),step.updatedAt(),step.detail());
            }).toList();
            return Optional.of(new NpcPlan(original.id(),original.npcId(),original.candidateKey(),original.providerId(),
                    original.kind(),original.priority(),original.status(),steps,original.currentStepIndex(),original.createdAt(),
                    original.updatedAt(),original.commitmentUntil(),original.expiresAt(),original.blocker(),"Use one owned sapling on a still-authorized original stump"));
        }
        return Optional.empty();
    }
    public static boolean compatible(NpcBase n,Map<String,String> params) {
        return enabled(n) && "true".equals(params.get("forestry"))
                && "minecraft:oak_sapling".equals(params.get("input"))
                && params.getOrDefault("result","").contains("minecraft:oak_sapling")
                && n.hasWorkZone() && FieldworkDiscovery.allowed(n,FieldworkTarget.position(params));
    }
}
