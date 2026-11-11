package com.jgeted.sagadyssey.social.industry.food;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.NaturalFarmRules;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.industry.common.EntityResourceActionData;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import com.jgeted.sagadyssey.social.life.NpcFoodItems;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/** A bounded meal request, not an inventory or a second food-reserve policy. */
public final class NaturalFoodDemand {
    public static final String PURPOSE = "natural-fishing-meal";
    public static final ItemFingerprint COD = new ItemFingerprint("minecraft:cod",0);
    private NaturalFoodDemand() { }

    public record Observation(int hungryMembers, int legalFood, int missing, String reason) { }

    public static int mealQuantity(int hungryMembers, int legalFood, boolean unsettledSupply) {
        if (hungryMembers < 0 || legalFood < 0) throw new IllegalArgumentException("negative meal observation");
        return unsettledSupply ? 0 : Math.min(2, Math.max(0, hungryMembers - legalFood));
    }

    /** Counts only loaded original members that can eat this raw food under the existing diet. */
    public static Observation observe(NpcBase worker, long now) {
        var server = MinecraftApi.server(worker);
        var home = HouseholdData.get(server).forMember(worker.getCharacterId());
        if (home == null || home.sharedStorage() == null) return new Observation(0,0,0,"no original household pantry");
        var flows = SupplyFlowData.get(server);
        var principal = SubjectRef.household(home.id());
        var pantryId = ContainerIds.block("household_storage",home.dimension(),home.sharedStorage());
        var pantry = flows.pointOrNull(pantryId);
        if (pantry == null || !pantry.owner().equals(principal) || !SupplyFlowService.validOwner(server,pantry)
                || SupplyFlowService.container(server,pantry) == null) return new Observation(0,0,0,"pantry unavailable or title mismatch");
        int hungry = 0;
        var life = NpcLifeData.get(server);
        for (var member : home.members()) {
            var needs = life.needs(member.characterId());
            var body = CharacterRegistryData.get(server).bodyOf(member.characterId());
            if (needs == null || body == null || !body.dimensionId().equals(home.dimension())) continue;
            var level = SupplyFlowService.level(server,home.dimension());
            if (level == null || !(level.getEntity(body.entityId()) instanceof NpcBase peer)
                    || !peer.isAlive() || !member.characterId().equals(peer.getCharacterId())
                    || needs.hunger() < (NaturalVillageLifeData.ordinaryRawDiet(peer) ? 350 : 800)) continue;
            boolean raw = needs.hunger() >= 800 || NaturalVillageLifeData.ordinaryRawDiet(peer);
            if (NaturalFarmRules.foodSlot(peer,peer.getPersonalInventory(),"BAG",peer.blockPosition(),raw,now) >= 0) continue;
            var record = life.record(peer.getCharacterId());
            if (record != null && record.privateStoragePosition() != null && level.hasChunkAt(record.privateStoragePosition())
                    && NaturalFarmRules.foodSlot(peer,com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(
                        level.getBlockEntity(record.privateStoragePosition())),"PRIVATE",record.privateStoragePosition(),raw,now) >= 0) continue;
            hungry++;
        }
        int available = 0;
        for (UUID id : flows.known(principal)) {
            var point = flows.pointOrNull(id);
            if (point == null || !point.dimension().equals(home.dimension())
                    || !SettlementIndustryAccess.local(worker,point.position())) continue;
            var stock = SupplyFlowService.container(server,point);
            if (stock == null || !SupplyFlowService.validOwner(server,point)) continue;
            var unique = new HashSet<ItemFingerprint>();
            for (int slot=0;slot<stock.getContainerSize();slot++) {
                var stack = stock.getItem(slot);
                if (!NpcFoodItems.canConsumePlanned(stack,true)) continue;
                var item = ItemFingerprint.of(stack);
                if (unique.add(item)) available += SupplyFlowService.available(server,point,principal,item,now);
            }
        }
        boolean incoming = NeedRequestData.get(server).requests().stream()
                .filter(r -> r.key().requester().equals(home.id()) && r.key().destination().equals(pantryId))
                .filter(r -> NpcFoodItems.rationValues().keySet().stream().anyMatch(i -> r.key().item().equals(ItemFingerprint.of(new ItemStack(i)))))
                .anyMatch(r -> unsettled(server,r,now));
        int missing = mealQuantity(hungry,available,incoming);
        return new Observation(hungry,available,missing,incoming?"existing food custody/production must finish first":
                hungry==0?"no currently unmet meal":available>=hungry?"known lawful food already available":"meal deficit with no lawful available food");
    }

    /** Caller has just observed a reachable river/ocean. No food, grant, receipt or actor is manufactured here. */
    public static void request(NpcBase worker, FieldworkStorage output, long now) {
        if (!SettlementIndustryAccess.resident(worker)) return;
        var server = MinecraftApi.server(worker);
        var home = HouseholdData.get(server).forMember(worker.getCharacterId());
        if (home == null || home.sharedStorage() == null) return;
        var principal = SubjectRef.household(home.id());
        var flows = SupplyFlowData.get(server);
        if (!flows.known(principal).contains(output.containerId()) || !SupplyFlowService.validOwner(server,output)
                || new RightsService(RightsStateData.get(server)).can(principal,AssetRight.TRANSFER,output.containerId(),0,COD,now).status()!=RightsVerdict.Status.ALLOW) return;
        var pantry = flows.pointOrNull(ContainerIds.block("household_storage",home.dimension(),home.sharedStorage()));
        if (pantry == null || !pantry.owner().equals(principal)) return;
        var needs = NeedRequestData.get(server);
        var history = needs.requests().stream().filter(r -> r.key().requester().equals(home.id())
                && r.key().purpose().equals("HOUSEHOLD:"+PURPOSE)).toList();
        if (history.stream().anyMatch(r -> unsettled(server,r,now))) return;
        var active = history.stream().filter(r -> !r.status().terminal() && r.status()!=NeedRequestStatus.DELIVERED).toList();
        if (active.size()>1) return; // Ambiguous old obligations are retained, not duplicated.
        if (!active.isEmpty()) {
            var current = active.getFirst();
            if (now < current.key().deadline()) return;
            needs.transition(current.id(),NeedRequestStatus.CANCELLED,now,"Expired meal intent has no custody or uncertain native result; retain history");
        }
        var observation = observe(worker,now);
        if (observation.missing()==0 || history.stream().anyMatch(r -> r.key().windowStart()==now)) return;
        var created = SupplyFlowService.request(server,principal,PURPOSE,pantry,COD,observation.missing(),now,now+24000);
        Sagadyssey.LOGGER.info("NATURAL_FOOD_REQUEST household={} request={} worker={} quantity={} hungry={} legalFood={} reason={}",
                home.id(),created.id(),worker.getCharacterId(),observation.missing(),observation.hungryMembers(),observation.legalFood(),observation.reason());
    }

    private static boolean unsettled(MinecraftServer server, NeedRequest request, long now) {
        if (request.reserved()>0 || request.inTransit()>0) return true;
        var flows = SupplyFlowData.get(server);
        if (flows.production(request.id())!=null && request.status()!=NeedRequestStatus.DELIVERED
                && request.status()!=NeedRequestStatus.CLOSED && request.status()!=NeedRequestStatus.CONSUMED) return true;
        for (var route:flows.routes()) if (route.need().equals(request.id()) && (route.pending()!=null
                || route.stage()==SupplyFlowData.Stage.REVIEW || !route.closed()
                || !RightsStateData.get(server).reservationsForToken(route.token(),now).isEmpty())) return true;
        var nativeData = EntityResourceActionData.existing(server);
        return nativeData!=null && nativeData.actions().stream().anyMatch(a -> a.read("Need", UUIDUtil.CODEC).orElseThrow().equals(request.id())
                && !EntityResourceActionData.settled(a.getStringOr("State", "")));
    }
}
