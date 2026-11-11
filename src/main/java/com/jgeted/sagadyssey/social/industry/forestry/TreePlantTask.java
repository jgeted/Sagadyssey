package com.jgeted.sagadyssey.social.industry.forestry;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Reuses the original PLANT transaction and plan shape; no new world/identity/material ledger. */
public final class TreePlantTask {
    private TreePlantTask() {}
    public static boolean enabled(NpcBase n) {
        return n.getProfession()==NpcProfession.WORKER
                && (SettlementIndustryAccess.fixture(n,"sagadyssey.industries.forestry")
                    || SettlementIndustryAccess.resident(n));
    }
    public static Optional<NpcPlan> choose(NpcBase n,long now) {
        if(!enabled(n) || n.getCommand()!=NpcCommand.WORK || n.getTarget()!=null || !n.onGround()
                || !n.hasWorkZone() || !NpcSchedulePlanner.allowsWork(n)
                || !com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.ordinaryFieldwork(n)) return Optional.empty();
        var work=FieldworkData.get(MinecraftApi.server(n));
        if(FieldworkDepositTaskExecutor.hasDeliverableCargo(n,work)) return Optional.empty();
        var rights=RightsStateData.get(MinecraftApi.server(n));var bag=ContainerIds.npcPersonal(n.getCharacterId());
        var owner=SubjectRef.character(n.getCharacterId());
        var seeds=new ArrayList<ItemStack>();
        for(int slot=0;slot<n.getPersonalInventory().getContainerSize();slot++) {
            var stack=n.getPersonalInventory().getItem(slot);
            if(!ForestrySeedPolicy.isRenewalSeed(stack))continue;
            var fp=ItemFingerprint.of(stack);
            if(rights.reservedQuantity(bag,fp,null,now)==0 && rights.context(bag)!=null
                    && new RightsService(rights).can(owner,AssetRight.CONSUME,bag,slot,fp,now).status()==RightsVerdict.Status.ALLOW) seeds.add(stack);
        }
        if(seeds.isEmpty())return Optional.empty();
        // A prior real log-cut receipt is knowledge of a stump, never permission for a new parcel.
        var stumps=work.receiptsFor(n.getUUID()).stream()
                .filter(r->r.action().operation()==FieldworkAction.Operation.CHOP
                        && r.action().dimension().equals(n.level().dimension().identifier().toString()))
                .sorted(Comparator.comparingLong(r->r.action().target().asLong())).toList();
        for(var stump:stumps) {
            var p=stump.action().target();
            if(!n.level().hasChunkAt(p) || !FieldworkDiscovery.allowed(n,p) || !n.level().getBlockState(p).isAir())continue;
            var seed=seeds.stream().filter(value->ForestrySeedPolicy.matchesCut(value,stump.action().expectedState())).findFirst().orElse(null);
            if(seed==null)continue;
            var sapling=ForestrySeedPolicy.sapling(seed).orElseThrow();
            // Native placement checks the real soil; no dirt/grass conversion or invented root.
            if(!sapling.canSurvive(n.level(),p))continue;
            var route=FieldworkRoutes.plan(n,p,false);if(route==null)continue;
            var target=new FieldworkTarget(p,FieldworkAction.Operation.PLANT,n.level().getBlockState(p),sapling,seed.getItem(),null);
            var original=FieldworkPlanFactory.work(n.getUUID(),target,route,n.level().dimension().identifier().toString(),now);
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
        if(!enabled(n) || !"true".equals(params.get("forestry")) || !n.hasWorkZone())return false;
        try {
            var input=new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(params.get("input"))));
            var seed=ForestrySeedPolicy.sapling(input);
            if(seed.isEmpty())return false;
            var result=NbtUtils.readBlockState(n.level().holderLookup(Registries.BLOCK),TagParser.parseCompoundFully(params.get("result")));
            return result.equals(seed.get()) && FieldworkDiscovery.allowed(n,FieldworkTarget.position(params));
        }catch(Exception invalidPlan){return false;}
    }
}
