package com.jgeted.sagadyssey.social.industry.animal;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData;
import com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.industry.common.SettlementIndustryAccess;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.NeedRequestStatus;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/** Current approved housing creates a bounded supply target, never a stock or authority grant. */
public final class WoolSupplyNeeds {
    private static final ItemFingerprint WOOL = new ItemFingerprint("minecraft:white_wool", 0);
    private WoolSupplyNeeds() { }

    /** Remaining unmade beds in an existing project; planned future projects do not count. */
    public static int unmadeBeds(int stonePieces, int placed, int storedBeds, boolean carriedBed) {
        int placedBeds = Math.min(3, Math.max(0, placed - stonePieces - 1));
        return Math.max(0, 3 - placedBeds - Math.max(0, storedBeds) - (carriedBed ? 1 : 0));
    }

    public static int requiredWool(MinecraftServer server, NaturalVillageBuilding village) {
        var coordinator = SettlementCoordinatorData.existing(server);
        var original = coordinator == null ? null : coordinator.site(village.buildingKey());
        if (original == null || original.getBooleanOr("Review", false)) return 0;
        var originalJobs = original.getCompoundOrEmpty("Jobs");
        // Older coordinator worlds are retained without silently adding a new carrying obligation.
        int beds = originalJobs.contains("WOOL") && !originalJobs.getCompoundOrEmpty("BED").getStringOr("State", "").equals("DONE") ? 1 : 0;
        var development = SettlementDevelopmentData.existing(server);
        var site = development == null ? null : development.site(village.buildingKey());
        if (site != null && !site.getBooleanOr("Review", false)) {
            for (var value : MinecraftApi.listOfType(site, "Projects", Tag.TAG_COMPOUND)) {
                var project = (CompoundTag) value;
                if (!project.getStringOr("State", "").equals("BUILDING") || project.getBooleanOr("Review", false)) continue;
                var build = project.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("BUILD");
                int index = build.getIntOr("Placed", 0), stone = SettlementDevelopmentLayout.stoneCost(village);
                boolean carriedBed = build.getBooleanOr("MaterialCarried", false) && index >= stone + 1 && index <= stone + 3;
                int storedBeds = publicOwnedStock(server, village, new ItemFingerprint("minecraft:white_bed", 0));
                beds += unmadeBeds(stone, index, storedBeds, carriedBed);
            }
        }
        return beds * 3;
    }

    private static int carriedWool(CompoundTag job) {
        return job.getBooleanOr("Cargo", false) && !job.getBooleanOr("Review", false) ? Math.max(0, job.getIntOr("Quantity", 0)) : 0;
    }

    private static int publicOwnedStock(MinecraftServer server, NaturalVillageBuilding village, ItemFingerprint item) {
        var inventory = NaturalPolityData.inventory(server, village, 3);
        if (inventory == null) return 0;
        var id = NaturalVillageEconomyData.storeId(village, 3);
        var principal = SubjectRef.institution(village.settlementId());
        var rights = RightsStateData.get(server);
        var context = rights.context(id);
        if (context == null || !context.defaultBeneficialOwner().equals(principal)) return 0;
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!item.matches(stack)) continue;
            var claims = rights.claimsAt(id, slot, item);
            if (claims.size() <= 1 && claims.stream().allMatch(claim -> claim.beneficialOwner().equals(principal)
                    && claim.custodian().equals(principal) && claim.quantity() == stack.getCount()
                    && claim.encumbrances().isEmpty())) count += stack.getCount();
        }
        return count;
    }

    /** Public material still missing, excluding wool already entrusted to a growth carrier. */
    public static int publicWoolDeficit(MinecraftServer server, NaturalVillageBuilding village) {
        int available = publicOwnedStock(server, village, WOOL);
        // Actual public stock remains counted while the BED batch reserves it: reservations are not new inventory.
        var coordinator = SettlementCoordinatorData.existing(server);
        var original = coordinator == null ? null : coordinator.site(village.buildingKey());
        if (original != null) available += carriedWool(original.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("WOOL"));
        var development = SettlementDevelopmentData.existing(server);
        var site = development == null ? null : development.site(village.buildingKey());
        if (site != null) for (var value : MinecraftApi.listOfType(site, "Projects", Tag.TAG_COMPOUND)) {
            var project = (CompoundTag) value;
            if (!Set.of("CANCELLED", "STABLE").contains(project.getStringOr("State", "")))
                available += carriedWool(project.getCompoundOrEmpty("Jobs").getCompoundOrEmpty("WOOL"));
        }
        return Math.max(0, requiredWool(server, village) - available);
    }

    /** Deliberate scheduling side effect. Status/inspection code must use the pure reads above instead. */
    public static void maintain(NpcBase worker, long now) {
        if (!SettlementIndustryAccess.resident(worker)) return;
        var server = MinecraftApi.server(worker);
        var village = SettlementDevelopmentData.residentVillage(server, worker.getCharacterId());
        if (village == null) village = com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(worker);
        if (village == null) return;
        var home = HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));
        if (home == null || home.sharedStorage() == null) return;
        var flows = SupplyFlowData.get(server);
        var target = flows.pointOrNull(NaturalVillageEconomyData.storeId(village, 1));
        var principal = SubjectRef.household(home.id());
        if (target == null || !target.owner().equals(principal) || !SupplyFlowService.validOwner(server, target)
                || !target.position().equals(home.sharedStorage()) || SupplyFlowService.container(server, target) == null) return;
        String id = "growth-wool:" + village.buildingKey();
        int desired = publicWoolDeficit(server, village);
        var previous = flows.policies().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (previous != null && (!previous.principal().equals(principal)
                || !previous.destination().equals(target.containerId()) || !previous.item().equals(WOOL)
                || !previous.category().equals("household"))) return; // Preserve conflicting authority for review.
        if (desired == 0) {
            for (var need : NeedRequestData.get(server).requests()) {
                if (need.key().purpose().equals("HOUSEHOLD:household:" + id)
                        && !need.status().terminal() && need.status() != NeedRequestStatus.DELIVERED)
                    NeedRequestData.get(server).reconcileStockDeficit(need.id(), 0, now);
            }
            flows.removePolicy(id);
        } else {
            var next = new SupplyFlowData.Policy(id, principal, target.containerId(), WOOL, desired, "household");
            if (!next.equals(previous)) {
                if (previous != null) flows.removePolicy(id);
                flows.policy(next);
            }
        }
    }
}
