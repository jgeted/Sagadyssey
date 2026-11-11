package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.NeedRequestStatus;
import com.jgeted.sagadyssey.social.needs.NeedRequestData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.rights.AssetRight;
import com.jgeted.sagadyssey.worldsim.rights.RightsService;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.RightsVerdict;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/** Harvest opportunities. Owner in the source record refers to the output, not the wild animal or water. */
@EventBusSubscriber(modid = "sagadyssey")
public final class NaturalIndustrySources {
    static final String WILD_SHEEP = "SHEAR_NATURAL";
    static final String ANGLING = "ANGLE";
    static final String NATURAL_SPAWN = "SagadysseyNaturalSheep";
    static final int ANGLING_TICKS = 200;
    private static final int SEARCH_RADIUS = 16;
    private static final ItemFingerprint WOOL = ItemFingerprint.of(new ItemStack(Items.WHITE_WOOL));
    private static final ItemFingerprint COD = ItemFingerprint.of(new ItemStack(Items.COD));
    private static final WeakHashMap<NpcBase, Long> NEXT_SEARCH = new WeakHashMap<>();

    private NaturalIndustrySources() {
    }

    @SubscribeEvent
    public static void recordNaturalSpawn(FinalizeSpawnEvent event) {
        if (event.getEntity() instanceof Sheep sheep
                && (event.getSpawnType() == EntitySpawnReason.NATURAL || event.getSpawnType() == EntitySpawnReason.CHUNK_GENERATION)) {
            sheep.getPersistentData().putBoolean(NATURAL_SPAWN, true);
        }
    }

    static boolean natural(String kind) {
        return ANGLING.equals(kind) || WILD_SHEEP.equals(kind);
    }

    static boolean shearing(String kind) {
        return "SHEAR".equals(kind) || WILD_SHEEP.equals(kind);
    }

    /** A remembered opportunity identifies an animal, not ownership or a permanent right to shear it. */
    static boolean knownWildSheep(CompoundTag source, UUID actor, UUID target, String dimension) {
        return WILD_SHEEP.equals(source.getStringOr("Kind", ""))
                && source.read("Actor", UUIDUtil.CODEC).isPresent() && source.read("Actor", UUIDUtil.CODEC).orElseThrow().equals(actor)
                && source.read("Owner", UUIDUtil.CODEC).isPresent() && source.read("Owner", UUIDUtil.CODEC).orElseThrow().equals(actor)
                && source.read("Target", UUIDUtil.CODEC).isPresent() && source.read("Target", UUIDUtil.CODEC).orElseThrow().equals(target)
                && dimension.equals(source.getStringOr("Dimension", ""));
    }

    private static boolean knownWildSheep(NpcBase worker, Sheep sheep, EntityResourceActionData data) {
        return data.sources().stream().anyMatch(source -> knownWildSheep(source, worker.getCharacterId(),
                sheep.getUUID(), worker.level().dimension().identifier().toString()));
    }

    static boolean wildSheepAllowed(NpcBase worker, Sheep sheep, EntityResourceActionData data) {
        if (!sheep.isAlive() || !sheep.getPersistentData().getBooleanOr(NATURAL_SPAWN, false)
                || sheep.isBaby() || sheep.getColor() != DyeColor.WHITE || sheep.isLeashed()
                || sheep.hasCustomName() || sheep.isPersistenceRequired() || sheep.isPassenger()
                || !SettlementIndustryAccess.local(worker, sheep.blockPosition())
                || !worker.hasWorkZone() || !worker.isWorkAllowedAt(sheep.blockPosition())
                    && !knownWildSheep(worker, sheep, data)
                || data.sources().stream().anyMatch(source -> source.read("Target", UUIDUtil.CODEC).orElseThrow().equals(sheep.getUUID())
                        && !WILD_SHEEP.equals(source.getStringOr("Kind", "")))) return false;
        // A nearby fence or gate is evidence that this may be managed stock.
        for (var cell : BlockPos.betweenClosed(sheep.blockPosition().offset(-4, -1, -4),
                sheep.blockPosition().offset(4, 1, 4))) {
            if (!worker.level().hasChunkAt(cell)) return false;
            var state = worker.level().getBlockState(cell);
            if (state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) return false;
        }
        return true;
    }

    static Sheep discoverSheep(NpcBase worker, EntityResourceActionData data) {
        var candidates = new java.util.LinkedHashMap<UUID, Sheep>();
        for (var sheep : worker.level().getEntitiesOfClass(Sheep.class,
                new AABB(worker.blockPosition()).inflate(SEARCH_RADIUS))) {
            candidates.put(sheep.getUUID(), sheep);
        }
        // Revisit only this worker's already recorded animals. getEntity reads loaded entities;
        // it does not create a ticket, discover an unknown animal at a distance, or alter its AI.
        for (var source : data.sources()) {
            if (!source.read("Target", UUIDUtil.CODEC).isPresent() || !knownWildSheep(source, worker.getCharacterId(),
                    source.read("Target", UUIDUtil.CODEC).orElseThrow(), worker.level().dimension().identifier().toString())) continue;
            if (((ServerLevel) worker.level()).getEntity(source.read("Target", UUIDUtil.CODEC).orElseThrow()) instanceof Sheep sheep)
                candidates.put(sheep.getUUID(), sheep);
        }
        return candidates.values().stream().filter(sheep -> sheep.readyForShearing()
                        && wildSheepAllowed(worker, sheep, data))
                .min(Comparator.comparingDouble(worker::distanceToSqr)).orElse(null);
    }

    static boolean fishingWater(ServerLevel level, BlockPos water) {
        if (!level.hasChunkAt(water) || !level.getBlockState(water).is(Blocks.WATER)
                || !level.getFluidState(water).isSource() || !level.getBlockState(water.above()).isAir()
                || !level.getBlockState(water.below()).is(Blocks.WATER) || !level.getFluidState(water.below()).isSource())
            return false;
        var biome = level.getBiome(water);
        return biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_OCEAN);
    }

    private record FishingSpot(BlockPos shore, BlockPos water) { }

    private static FishingSpot spot(NpcBase worker) {
        var level = (ServerLevel) worker.level();
        var origin = worker.blockPosition();
        var candidates = new ArrayList<FishingSpot>();
        for (var shore : BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -3, -SEARCH_RADIUS),
                origin.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))) {
            if (shore.distSqr(origin) > SEARCH_RADIUS * SEARCH_RADIUS
                    || !level.hasChunkAt(shore) || !FieldworkRoutes.walkable(worker, shore)) continue;
            for (var direction : Direction.Plane.HORIZONTAL) {
                var water = shore.relative(direction);
                if (!fishingWater(level, water)) water = water.below();
                if (fishingWater(level, water)) candidates.add(new FishingSpot(shore.immutable(), water.immutable()));
            }
        }
        candidates.sort(Comparator.comparingDouble((FishingSpot spot) -> spot.shore().distSqr(origin))
                .thenComparingLong(spot -> spot.water().asLong()));
        return candidates.stream().limit(12).filter(spot -> {
            if (!SettlementIndustryAccess.local(worker, spot.shore())
                    || !SettlementIndustryAccess.local(worker, spot.water())
                    || !FieldworkRoutes.walkable(worker, spot.shore())) return false;
            var path = worker.getNavigation().createPath(spot.shore(), 0);
            return path != null && path.canReach()
                    || com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.gateWaypoint(worker, spot.shore()) != null;
        }).findFirst().orElse(null);
    }

    /** Discovery consumes neither tools nor world resources. It writes a bounded opportunity only for a live local need. */
    static void discover(NpcBase worker, EntityResourceActionData data, long now) {
        if (!data.hasSourceCapacity() || now < NEXT_SEARCH.getOrDefault(worker, Long.MIN_VALUE)) return;
        NEXT_SEARCH.put(worker, now + 200);
        var server = MinecraftApi.server(worker);
        var owner = SubjectRef.character(worker.getCharacterId());
        var life = NpcLifeData.get(server).record(worker.getCharacterId());
        if (life == null || life.privateStoragePosition() == null
                || !SettlementIndustryAccess.local(worker, life.privateStoragePosition())) return;
        var flows = SupplyFlowData.get(server);
        var storage = ProductionContainerIds.resolve(server, life.dimension(), life.privateStoragePosition());
        var output = flows.pointOrNull(storage);
        if (output == null || !output.owner().equals(owner) || !SupplyFlowService.validOwner(server, output)
                || SupplyFlowService.container(server, output) == null) return;
        // Existing food/transport obligations remain authoritative. Only an actual unmet meal
        // and a currently reachable natural water source may propose a new bounded fish request.
        var food = com.jgeted.sagadyssey.social.industry.food.NaturalFoodDemand.observe(worker, now);
        if (food.missing() > 0 && spot(worker) != null)
            com.jgeted.sagadyssey.social.industry.food.NaturalFoodDemand.request(worker, output, now);
        for (var need : NeedRequestData.get(server).requests()) {
            if (need.status().terminal() || need.status() == NeedRequestStatus.DELIVERED || need.remaining() <= 0) continue;
            var item = need.key().item();
            if (!item.equals(WOOL) && !item.equals(COD)) continue;
            var scope = flows.demand(need.id());
            if (scope == null || !maySupply(worker, output, scope.principal(), item, now)
                    || SupplyFlowService.available(server, output, scope.principal(), item, now) >= need.remaining()) continue;
            String kind = item.equals(WOOL) ? WILD_SHEEP : ANGLING;
            if (item.equals(WOOL)) {
                var sheep = discoverSheep(worker, data);
                if (sheep == null) continue;
                var source = source(owner, output, need.id(), kind, sheep.getUUID());
                data.putSource(source);
                return;
            }
            var spot = spot(worker);
            if (spot == null) continue;
            var water = IndustryTransfers.id("angling-water:" + output.dimension() + ":" + spot.water().asLong());
            var source = source(owner, output, need.id(), kind, water);
            source.putLong("Water", spot.water().asLong());
            source.putLong("Shore", spot.shore().asLong());
            if (data.source(source.read("Id", UUIDUtil.CODEC).orElseThrow()) == null) data.putSource(source);
            return;
        }
    }

    static boolean liveDemand(NpcBase worker, CompoundTag source, long now) {
        var server = MinecraftApi.server(worker);
        var need = NeedRequestData.get(server).request(source.read("Need", UUIDUtil.CODEC).orElseThrow());
        if (need == null || need.status().terminal() || need.status() == NeedRequestStatus.DELIVERED
                || need.remaining() <= 0 || now >= need.key().deadline()) return false;
        var scope = SupplyFlowData.get(server).demand(need.id());
        var stock = FieldworkStorage.load(source.getCompoundOrEmpty("Output"));
        var item = need.key().item();
        return scope != null && SettlementIndustryAccess.local(worker, stock.position())
                && maySupply(worker, stock, scope.principal(), item, now)
                && SupplyFlowService.available(server, stock, scope.principal(), item, now) < need.remaining();
    }

    static boolean maySupply(NpcBase worker, FieldworkStorage output, SubjectRef principal,
                             ItemFingerprint item, long now) {
        var server = MinecraftApi.server(worker);
        return SettlementIndustryAccess.servesLocalPrincipal(worker, principal)
                && SupplyFlowData.get(server).known(principal).contains(output.containerId())
                && SupplyFlowService.validOwner(server, output)
                && (output.owner().equals(principal)
                    || new RightsService(RightsStateData.get(server)).can(principal, AssetRight.TRANSFER,
                            output.containerId(), 0, item, now).status() == RightsVerdict.Status.ALLOW);
    }

    private static CompoundTag source(SubjectRef owner, FieldworkStorage output, UUID need, String kind, UUID target) {
        var source = new CompoundTag();
        source.store("Id", UUIDUtil.CODEC, IndustryTransfers.id("natural-harvest:" + kind + ":" + target + ":" + owner.id() + ":" + need));
        source.store("Target", UUIDUtil.CODEC, target);
        source.store("Owner", UUIDUtil.CODEC, owner.id());
        source.store("Actor", UUIDUtil.CODEC, owner.id());
        source.store("Need", UUIDUtil.CODEC, need);
        source.putString("Kind", kind);
        source.putString("Dimension", output.dimension());
        source.put("Output", output.save());
        return source;
    }

    static List<ItemStack> catchFish(ServerLevel level, NpcBase worker, BlockPos water) {
        var params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
                .withParameter(LootContextParams.TOOL, worker.getMainHandItem())
                .withParameter(LootContextParams.THIS_ENTITY, worker)
                .create(LootContextParamSets.FISHING);
        return level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING_FISH)
                .getRandomItems(params);
    }
}
