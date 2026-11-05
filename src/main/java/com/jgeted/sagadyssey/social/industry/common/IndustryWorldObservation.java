package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.tags.BlockTags;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import java.util.*;
import static com.jgeted.sagadyssey.social.industry.common.IndustryContract.*;

/** Reads only caller-known loaded targets. No discovery, pathfinding, identity creation or ledger access. */
public final class IndustryWorldObservation {
    private IndustryWorldObservation() {}
    public static Demand blocks(NpcBase npc, IndustryAdapter adapter, UUID household, UUID settlement,
                                String demandId, int remainingBatches, Collection<BlockPos> knownTargets,
                                Set<BlockPos> authorizedTargets, boolean currentWorkSlotFree,
                                boolean knownRouteAvailable, RightsStateData existingRights) {
        Objects.requireNonNull(existingRights,"pass the existing authority view; observation does not create it");
        // Caller supplies a current authoritative projection; this observer never invokes legacy get/create resolvers.
        authorizedTargets=Set.copyOf(authorizedTargets);
        if(knownTargets.size()>256) throw new IllegalArgumentException("bounded observation requires <=256 known targets");
        String id=adapter.capabilityId();
        if(!Set.of("food.carrot","forestry.fell","stone.mine").contains(id))
            throw new IllegalArgumentException("no block observer for "+id);
        boolean complete=true, denied=false;int physical=0, usable=0;
        for(var p:new HashSet<>(knownTargets)) {
            if(!npc.level().hasChunkAt(p)) { complete=false;continue; }
            var state=npc.level().getBlockState(p);
            boolean matches=switch(id) {
                case "food.carrot" -> state.is(Blocks.CARROTS) && state.getValue(CropBlock.AGE)==7;
                case "forestry.fell" -> state.is(BlockTags.LOGS);
                default -> state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
            };
            if(!matches) continue;
            physical++;
            if(authorizedTargets.contains(p) && npc.hasWorkZone() && npc.isWorkAllowedAt(p)) usable++; else denied=true;
        }
        boolean farmer=id.equals("food.carrot");
        boolean qualified=farmer ? npc.getProfession()==NpcProfession.FARMER : npc.getProfession()==NpcProfession.WORKER;
        ItemStack tool=npc.getMainHandItem();
        boolean toolPresent=farmer ? tool.getItem() instanceof HoeItem
                : id.equals("forestry.fell") ? tool.getItem() instanceof AxeItem : tool.getItem() instanceof PickaxeItem;
        var bag=ContainerIds.npcPersonal(npc.getCharacterId());
        var toolClaim=toolPresent?existingRights.claimAt(bag,npc.getPersonalInventory().getContainerSize(),ItemFingerprint.of(tool)):null;
        boolean toolAuthorized=toolPresent && existingRights.context(bag)!=null
                && (toolClaim==null || toolClaim.reservations().isEmpty())
                && existingRights.reservedQuantity(bag,ItemFingerprint.of(tool),null,npc.level().getGameTime())==0
                && new RightsService(existingRights).can(SubjectRef.character(npc.getCharacterId()),AssetRight.USE,
                    bag,npc.getPersonalInventory().getContainerSize(),ItemFingerprint.of(tool),npc.level().getGameTime()).status()==RightsVerdict.Status.ALLOW;
        String key=adapter.capability().inputPerBatch().keySet().iterator().next();
        var stock=new Stock(physical,physical-usable,0,0,complete,!denied || usable>0);
        return new Demand(demandId,npc.getCharacterId(),npc.getUUID(),household,settlement,npc.level().getGameTime(),
                remainingBatches,Map.of(key,stock),currentWorkSlotFree && qualified,
                toolAuthorized && (!tool.isDamageableItem() || tool.getDamageValue()<tool.getMaxDamage()),true,knownRouteAvailable);
    }
}
