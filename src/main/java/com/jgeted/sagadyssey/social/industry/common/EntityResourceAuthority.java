package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.TransferIntent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.fish.Cod;
import net.minecraft.world.item.DyeColor;
import java.util.UUID;

/** Explicit trusted initialization of already-owned finite stock. Never called by discovery or a client query. */
public final class EntityResourceAuthority {
    private EntityResourceAuthority() {}
    public static UUID registerInitialOwned(NpcBase owner,LivingEntity target,String kind,FieldworkStorage output,UUID need,
                                            BlockPos min,BlockPos max,String initialOwnershipEvidence){
        if(owner.getCharacterId()==null || owner.level()!=target.level() || initialOwnershipEvidence==null || initialOwnershipEvidence.isBlank())
            throw new IllegalArgumentException("explicit initial title evidence required");
        if(!((kind.equals("SHEAR") && target instanceof Sheep sh && !sh.isBaby() && sh.getColor()==DyeColor.WHITE)
                || (kind.equals("FISH_COD") && target instanceof Cod)))throw new IllegalArgumentException("unsupported initial native source");
        if(max.getX()-min.getX()>32 || max.getY()-min.getY()>32 || max.getZ()-min.getZ()>32
                || max.getX()<min.getX() || max.getY()<min.getY() || max.getZ()<min.getZ() || !contains(min,max,target.blockPosition()))
            throw new IllegalArgumentException("bounded pasture/water required");
        var s=MinecraftApi.server(owner);var principal=SubjectRef.character(owner.getCharacterId());
        var rights=RightsStateData.get(s);var context=rights.context(output.containerId());
        if(context==null || !context.defaultBeneficialOwner().equals(principal) || !output.owner().equals(principal)
                || SupplyFlowService.container(s,output)==null)throw new IllegalArgumentException("owned physical output source required");
        var request=NeedRequestData.get(s).request(need);var demand=SupplyFlowData.get(s).demand(need);
        var item=new ItemFingerprint(kind.equals("SHEAR")?"minecraft:white_wool":"minecraft:cod",0);
        if(request==null || demand==null || !request.key().item().equals(item))throw new IllegalArgumentException("real matching demand required");
        if(!demand.principal().equals(principal)
                && (output.intent()!=TransferIntent.GIFT || new RightsService(rights).can(demand.principal(),AssetRight.TRANSFER,
                    output.containerId(),0,item,s.overworld().getGameTime()).status()!=RightsVerdict.Status.ALLOW))
            throw new IllegalArgumentException("external demand lacks explicit source owner's gift permission");
        UUID id=IndustryTransfers.id("native-owned-entity:"+target.getUUID());var t=new CompoundTag();
        t.store("Id", UUIDUtil.CODEC,id);t.store("Target", UUIDUtil.CODEC,target.getUUID());t.store("Owner", UUIDUtil.CODEC,owner.getCharacterId());t.store("Actor", UUIDUtil.CODEC,owner.getCharacterId());
        t.store("Need", UUIDUtil.CODEC,need);t.putString("Kind",kind);t.putString("Dimension",owner.level().dimension().identifier().toString());
        t.putLong("Min",min.asLong());t.putLong("Max",max.asLong());t.put("Output",output.save());t.putString("TitleEvidence",initialOwnershipEvidence);
        EntityResourceActionData.get(s).putSource(t);if(target instanceof net.minecraft.world.entity.Mob mob)mob.setPersistenceRequired();return id;
    }
    /** Formal scheduler binding to a NEW need, derived only from an already registered title.
     * Old source/action facts remain immutable. No new permission or world entity is created. */
    public static UUID bindKnownOwnedNeed(NpcBase owner,UUID priorSource,UUID nextNeed){
        var data=EntityResourceActionData.existing(MinecraftApi.server(owner));var old=data==null?null:data.source(priorSource);
        if(old==null||!old.read("Owner", UUIDUtil.CODEC).orElseThrow().equals(owner.getCharacterId())||!old.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(owner.getCharacterId()))
            throw new IllegalArgumentException("existing owned title required");
        if(NaturalIndustrySources.natural(old.getStringOr("Kind", "")))throw new IllegalArgumentException("wild opportunity is not an owned entity title");
        if(old.read("Need", UUIDUtil.CODEC).orElseThrow().equals(nextNeed))return priorSource;
        var needs=NeedRequestData.get(MinecraftApi.server(owner));var previous=needs.request(old.read("Need", UUIDUtil.CODEC).orElseThrow());
        if((!previous.status().terminal()&&previous.status()!=NeedRequestStatus.DELIVERED)||previous.reserved()!=0||previous.inTransit()!=0)
            throw new IllegalStateException("previous native need still has an obligation");
        for(var a:data.actions()){
            var src=data.source(a.read("Source", UUIDUtil.CODEC).orElseThrow());if(src!=null&&src.read("Target", UUIDUtil.CODEC).orElseThrow().equals(old.read("Target", UUIDUtil.CODEC).orElseThrow())&&!EntityResourceActionData.settled(a.getStringOr("State", "")))
                throw new IllegalStateException("old native effect/cargo not reconciled");
        }
        var target=((net.minecraft.server.level.ServerLevel)owner.level()).getEntity(old.read("Target", UUIDUtil.CODEC).orElseThrow());
        if(!(target instanceof LivingEntity living)||!living.isAlive()||!allows(owner,old,living))throw new IllegalArgumentException("same actual live owned target required");
        var output=FieldworkStorage.load(old.getCompoundOrEmpty("Output"));var principal=SubjectRef.character(owner.getCharacterId());
        var req=needs.request(nextNeed);var scope=SupplyFlowData.get(MinecraftApi.server(owner)).demand(nextNeed);
        var item=new ItemFingerprint(old.getStringOr("Kind", "").equals("SHEAR")?"minecraft:white_wool":"minecraft:cod",0);
        var rights=RightsStateData.get(MinecraftApi.server(owner));
        if(req.status().terminal()||req.status()==NeedRequestStatus.DELIVERED||req.remaining()<=0||!req.key().item().equals(item)||scope==null
                ||!SupplyFlowData.get(MinecraftApi.server(owner)).known(scope.principal()).contains(output.containerId())
                ||rights.context(output.containerId())==null||!rights.context(output.containerId()).defaultBeneficialOwner().equals(principal)
                ||(!scope.principal().equals(principal)&&(output.intent()!=TransferIntent.GIFT
                ||new RightsService(rights).can(scope.principal(),AssetRight.TRANSFER,output.containerId(),0,item,owner.level().getGameTime()).status()!=RightsVerdict.Status.ALLOW)))
            throw new IllegalArgumentException("new need lacks original title,knowledge or gift permission");
        var id=IndustryTransfers.id("known-native-source:"+priorSource+":"+nextNeed);var next=old.copy();next.store("Id", UUIDUtil.CODEC,id);next.store("Need", UUIDUtil.CODEC,nextNeed);
        next.putString("TitleEvidence",old.getStringOr("TitleEvidence", "")+"; next need from existing title "+priorSource);
        data.putSource(next);return id;
    }
    public static boolean contains(BlockPos min,BlockPos max,BlockPos p){return p.getX()>=min.getX()&&p.getX()<=max.getX()&&p.getY()>=min.getY()&&p.getY()<=max.getY()&&p.getZ()>=min.getZ()&&p.getZ()<=max.getZ();}
    public static boolean allows(NpcBase n,CompoundTag source,LivingEntity target){
        return source.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId()) && source.read("Owner", UUIDUtil.CODEC).orElseThrow().equals(n.getCharacterId())
                && source.read("Target", UUIDUtil.CODEC).orElseThrow().equals(target.getUUID()) && source.getStringOr("Dimension", "").equals(n.level().dimension().identifier().toString())
                && contains(BlockPos.of(source.getLongOr("Min", 0L)),BlockPos.of(source.getLongOr("Max", 0L)),target.blockPosition());
    }
}
