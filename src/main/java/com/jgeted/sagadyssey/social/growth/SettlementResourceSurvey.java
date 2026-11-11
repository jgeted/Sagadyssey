package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.NaturalVillageBuilding;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLayout;
import com.jgeted.sagadyssey.knowledge.DisclosurePolicy;
import com.jgeted.sagadyssey.knowledge.FactKey;
import com.jgeted.sagadyssey.knowledge.KnowledgeStateData;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkAction;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkDiscovery;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkPlanFactory;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkTarget;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 有明确地块的浅层露头勘察；观察和真正挖开覆土分属两个实际动作。 */
final class SettlementResourceSurvey {
    private SettlementResourceSurvey() { }

    static void declareParcel(CompoundTag row, NaturalVillageBuilding village, UUID official, long now) {
        // 用地定义是自然模板或正式启用决定的一部分，绝不是视野半径自动授权。
        var a = NaturalVillageLayout.at(village, new BlockPos(27, -3, 12));
        var b = NaturalVillageLayout.at(village, new BlockPos(47, 5, 26));
        var parcel = new CompoundTag();
        parcel.putLong("Min", new BlockPos(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ())).asLong());
        parcel.putLong("Max", new BlockPos(Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ())).asLong());
        parcel.store("Institution", UUIDUtil.CODEC, village.settlementId());
        parcel.store("AuthorizedBy", UUIDUtil.CODEC, official);
        parcel.putLong("AuthorizedAt", now);
        parcel.putString("Rule", "template-y-minus3-to-plus5;preserve-return-path;no-buildings;no-player-edits;loaded-only");
        row.put("Parcel", parcel);
        row.put("ObservedResources", new CompoundTag());
        row.put("ProtectedEdits", new CompoundTag());
    }

    static boolean inParcel(CompoundTag row, BlockPos position) {
        var parcel = row.getCompoundOrEmpty("Parcel");
        if (!parcel.read("AuthorizedBy", UUIDUtil.CODEC).isPresent()) return false;
        var min = BlockPos.of(parcel.getLongOr("Min", 0L));
        var max = BlockPos.of(parcel.getLongOr("Max", 0L));
        return position.getX() >= min.getX() && position.getX() <= max.getX()
                && position.getY() >= min.getY() && position.getY() <= max.getY()
                && position.getZ() >= min.getZ() && position.getZ() <= max.getZ();
    }

    private static boolean natural(BlockState state) {
        // 不把人工圆石、木板、箱子、农田和功能方块当成新矿点。
        return SettlementMaterials.quarry(state) || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.GRAVEL)
                || state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    private static boolean safe(NpcBase npc, CompoundTag row, BlockPos position) {
        var level = (ServerLevel) npc.level();
        if (!inParcel(row, position) || level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) return false;
        for (String key : row.getCompoundOrEmpty("ProtectedEdits").keySet()) {
            if (BlockPos.of(Long.parseLong(key)).distSqr(position) <= 9) return false;
        }
        var village = SettlementCoordinatorData.building(row);
        for (var building : SettlementRegistryData.get(MinecraftApi.server(npc)).buildings()) {
            if (!building.dimension().equals(village.dimension()) || building.key().equals(village.buildingKey())) continue;
            if (building.position().distSqr(position) < 32 * 32) return false;
        }
        // 保守检查可见邻域；未加载也不猜测可以施工。
        for (var nearby : BlockPos.betweenClosed(position.offset(-2, 0, -2), position.offset(2, 3, 2))) {
            if (level.getChunkSource().getChunkNow(nearby.getX() >> 4, nearby.getZ() >> 4) == null) return false;
            var state = level.getBlockState(nearby);
            if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
            if (!state.isAir() && !natural(state) && !state.is(Blocks.SHORT_GRASS)
                    && !state.is(Blocks.TALL_GRASS) && !state.is(Blocks.FERN) && !state.is(Blocks.LARGE_FERN)
                    && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.FLOWERS) && !state.is(Blocks.SNOW)) return false;
        }
        return true;
    }

    static boolean allowed(NpcBase npc, CompoundTag row, BlockPos position) {
        if (npc.getProfession() != NpcProfession.WORKER) return false;
        var observation = row.getCompoundOrEmpty("ObservedResources").getCompoundOrEmpty(Long.toString(position.asLong()));
        if (!observation.read("Observer", UUIDUtil.CODEC).isPresent()) return false;
        var state = npc.level().getBlockState(position);
        if (observation.getStringOr("Purpose", "").equals("PUBLIC_EXIT_CLEARANCE")) {
            return safeExit(npc, row, position)
                    && (state.isAir() || state.is(BlockTags.LEAVES)
                    && NbtUtils.writeBlockState(state).toString().equals(observation.getStringOr("State", "")));
        }
        if (!safe(npc, row, position)) return false;
        // 原计划采完后的交付阶段仍有权引用这个位置，但不能再次采出物品。
        return state.isAir() || natural(state) && NbtUtils.writeBlockState(state).toString().equals(observation.getStringOr("State", ""));
    }

    private static boolean safeExit(NpcBase npc, CompoundTag row, BlockPos position) {
        var village = SettlementCoordinatorData.building(row);
        if (!exitClearanceCell(village, position)
                || ((ServerLevel) npc.level()).getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) return false;
        for (String key : row.getCompoundOrEmpty("ProtectedEdits").keySet())
            if (BlockPos.of(Long.parseLong(key)).distSqr(position) <= 9) return false;
        for (var building : SettlementRegistryData.get(MinecraftApi.server(npc)).buildings()) {
            if (building.dimension().equals(village.dimension()) && !building.key().equals(village.buildingKey())
                    && building.position().distSqr(position) < 32 * 32) return false;
        }
        var state = npc.level().getBlockState(position);
        return state.isAir() || state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
                && !state.getValue(LeavesBlock.PERSISTENT) && state.getFluidState().isEmpty();
    }

    static boolean needsSurvey(NpcBase npc, CompoundTag row) {
        var village = SettlementCoordinatorData.building(row);
        // 先用原有限采区，不能为了展示探索而绕开现成的合法石料。
        for (var local : BlockPos.betweenClosed(new BlockPos(21, 1, 15), new BlockPos(24, 3, 19))) {
            var position = NaturalVillageLayout.at(village, local);
            if (((ServerLevel) npc.level()).getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) return false;
            if (SettlementMaterials.quarry(npc.level().getBlockState(position))) return false;
        }
        return clearedCount(npc, row) != row.getIntOr("LastSurveyClearedCount", 0)
                || npc.level().getGameTime() >= row.getLongOr("SurveyRetryAt", 0L);
    }

    private static int clearedCount(NpcBase npc, CompoundTag row) {
        int count = 0;
        for (String key : row.getCompoundOrEmpty("ObservedResources").keySet()) {
            var point = BlockPos.of(Long.parseLong(key));
            if (((ServerLevel) npc.level()).getChunkSource().getChunkNow(point.getX() >> 4, point.getZ() >> 4) != null
                    && npc.level().getBlockState(point).isAir()) count++;
        }
        return count;
    }

    static Optional<NpcPlan> mining(NpcBase npc, CompoundTag row, long now) {
        var resources = row.getCompoundOrEmpty("ObservedResources");
        var positions = resources.keySet().stream().map(key -> BlockPos.of(Long.parseLong(key)))
                .sorted(Comparator.<BlockPos>comparingInt(position -> resources.getCompoundOrEmpty(Long.toString(position.asLong()))
                        .getStringOr("Purpose", "").equals("ACCESS_REPAIR") ? 0 : SettlementMaterials.quarry(npc.level().getBlockState(position)) ? 1 : 2)
                        .thenComparingDouble(position -> position.distSqr(npc.blockPosition()))).toList();
        for (var position : positions) {
            if (!allowed(npc, row, position) || npc.level().getBlockState(position).isAir()) continue;
            if(SettlementResourceStock.applies(npc)&&!resources.getCompoundOrEmpty(Long.toString(position.asLong())).getStringOr("Purpose", "").equals("ACCESS_REPAIR")
                    &&!resources.getCompoundOrEmpty(Long.toString(position.asLong())).getStringOr("Purpose", "").equals("PUBLIC_EXIT_CLEARANCE")
                    &&FieldworkDiscovery.mineTarget(npc.level().getBlockState(position))&&!SettlementResourceStock.wants(npc,npc.level().getBlockState(position)))continue;
            String purpose = resources.getCompoundOrEmpty(Long.toString(position.asLong())).getStringOr("Purpose", "");
            if (row.contains("EscapeStand") && !purpose.equals("ACCESS_REPAIR")) continue;
            // 到达许可底界的泥土后，下面不再有合法可探层；不能为找石料继续剥无收益草皮。
            if (purpose.isEmpty() && position.getY() == BlockPos.of(row.getCompoundOrEmpty("Parcel").getLongOr("Min", 0L)).getY()
                    && !SettlementMaterials.quarry(npc.level().getBlockState(position))
                    &&!(SettlementResourceStock.applies(npc)&&FieldworkDiscovery.mineTarget(npc.level().getBlockState(position))&&SettlementResourceStock.wants(npc,npc.level().getBlockState(position)))) continue;
            if (purpose.isEmpty() && !SettlementMaterials.quarry(npc.level().getBlockState(position))
                    &&!(SettlementResourceStock.applies(npc)&&FieldworkDiscovery.mineTarget(npc.level().getBlockState(position))&&SettlementResourceStock.wants(npc,npc.level().getBlockState(position)))) {
                if (!row.contains("ProbeAnchor")) continue;
                var anchor = BlockPos.of(row.getLongOr("ProbeAnchor", 0L));
                if (Math.abs(position.getX() - anchor.getX()) > 1 || Math.abs(position.getZ() - anchor.getZ()) > 1) continue;
            }
            if (FieldworkData.get(MinecraftApi.server(npc)).unreachable(npc.getUUID(), npc.level().dimension().identifier().toString(), position, now).isPresent()) continue;
            var route = FieldworkRoutes.plan(npc, position, false, true);
            if (route == null) continue;
            if (purpose.isEmpty() && !SettlementSurveyNavigation.connected(npc, route.stand(),
                    returnStand(row), position)) continue;
            var operation = resources.getCompoundOrEmpty(Long.toString(position.asLong())).getStringOr("Purpose", "").equals("PUBLIC_EXIT_CLEARANCE")
                    ? FieldworkAction.Operation.CHOP : FieldworkAction.Operation.MINE;
            var target = new FieldworkTarget(position, operation,
                    npc.level().getBlockState(position), Blocks.AIR.defaultBlockState(), null, null);
            return Optional.of(FieldworkPlanFactory.work(npc.getUUID(), target, route, npc.level().dimension().identifier().toString(), now));
        }
        return Optional.empty();
    }

    private static BlockPos returnStand(CompoundTag row) {
        return NaturalVillageLayout.at(SettlementCoordinatorData.building(row), new BlockPos(12, 1, 20));
    }

    /** 保留原人物和货物，以真实挖掘形成上行台阶；不传送、不填账、不挖出授权区。 */
    private static String recoverAccess(NpcBase npc, CompoundTag row, CompoundTag task, long now) {
        if (row.contains("EscapeStand")) {
            var stand = BlockPos.of(row.getLongOr("EscapeStand", 0L));
            if (npc.blockPosition().equals(stand)) {
                row.remove("EscapeStand");
                row.putInt("EscapeSteps", row.getIntOr("EscapeSteps", 0) + 1);
                row.remove("EscapeCheckedAt");
                task.putLong("LastProgress", now);
            } else if (FieldworkRoutes.walkable(npc, stand)) {
                SettlementSurveyNavigation.move(npc, stand);
                return "沿实际开出的上行台阶返回：" + SettlementSurveyNavigation.status(npc);
            } else {
                for (var point : new BlockPos[]{stand.above(), stand}) {
                    if (natural(npc.level().getBlockState(point)) && safe(npc, row, point)
                            && FieldworkRoutes.closeEnough(npc, point)) observeAccess(npc, row, point, now);
                }
                return "YIELD";
            }
        }
        if (now - row.getLongOr("EscapeCheckedAt", 0L) < 200 && row.getBooleanOr("EscapeConnected", false)) return null;
        row.putLong("EscapeCheckedAt", now);
        boolean connected = SettlementSurveyNavigation.connected(npc, npc.blockPosition(), returnStand(row), null);
        row.putBoolean("EscapeConnected", connected);
        if (connected) return null;
        if (!inParcel(row, npc.blockPosition())) return null;
        if (row.getIntOr("EscapeSteps", 0) >= 8) return "返程修复达到八级上行预算，保留人员与货物等待核对";
        var options = new ArrayList<BlockPos>();
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var stand = npc.blockPosition().relative(direction).above();
            if (!safe(npc, row, stand) || !safe(npc, row, stand.above())
                    || !natural(npc.level().getBlockState(stand.below()))) continue;
            var feet = npc.level().getBlockState(stand); var head = npc.level().getBlockState(stand.above());
            if ((feet.isAir() || natural(feet)) && (head.isAir() || natural(head))) options.add(stand);
        }
        if (options.isEmpty()) return "无合法上行修复位置，保留携货与原工作，不向未知地块挖掘";
        // 优先保留向地块内部继续上行的空间，避免在地块边界掏出另一处死角。
        var parcel = row.getCompoundOrEmpty("Parcel");
        var min = BlockPos.of(parcel.getLongOr("Min", 0L)); var max = BlockPos.of(parcel.getLongOr("Max", 0L));
        options.sort(Comparator.<BlockPos>comparingInt(p -> -Math.min(Math.min(p.getX() - min.getX(), max.getX() - p.getX()),
                Math.min(p.getZ() - min.getZ(), max.getZ() - p.getZ()))).thenComparingDouble(returnStand(row)::distSqr));
        row.putLong("EscapeStand", options.getFirst().asLong());
        row.putLong("EscapeStarted", now);
        return "按原授权地块和原采掘回执修复上行通路";
    }

    private static void observeAccess(NpcBase npc, CompoundTag row, BlockPos point, long now) {
        var observation = new CompoundTag();
        observation.store("Observer", UUIDUtil.CODEC, npc.getCharacterId());
        observation.store("Body", UUIDUtil.CODEC, npc.getUUID());
        observation.putString("Purpose", "ACCESS_REPAIR");
        observation.putLong("ObservedAt", now);
        observation.putString("State", NbtUtils.writeBlockState(npc.level().getBlockState(point)).toString());
        row.getCompoundOrEmpty("ObservedResources").put(Long.toString(point.asLong()), observation);
    }

    private static List<BlockPos> waypoints(NaturalVillageBuilding village) {
        var points = new ArrayList<BlockPos>();
        for (int x = 29; x <= 45; x += 4) for (int z = 14; z <= 24; z += 4) {
            points.add(NaturalVillageLayout.at(village, new BlockPos(x, 1, z)));
        }
        return points;
    }

    static String work(NpcBase npc, CompoundTag row, CompoundTag task, long now) {
        var level = (ServerLevel) npc.level();
        var village = SettlementCoordinatorData.building(row);
        var observations = row.getCompoundOrEmpty("ObservedResources");
        var parcel = row.getCompoundOrEmpty("Parcel");
        if (parcel.getStringOr("Rule", "").equals("surface-test-pits-max-depth-3;no-buildings;no-player-edits;loaded-only")) {
            parcel.putString("OriginalRuleText", parcel.getStringOr("Rule", ""));
            parcel.putString("Rule", "template-y-minus3-to-plus5;preserve-return-path;no-buildings;no-player-edits;loaded-only");
            parcel.putLong("RuleTextClarifiedAt", now); // 坐标范围完全不变，修正旧说明与实际检查不一致。
        }
        int cleared = clearedCount(npc, row);
        if (cleared != row.getIntOr("LastSurveyClearedCount", 0)) {
            row.putInt("LastSurveyClearedCount", cleared);
            row.putInt("SurveyIndex", 0);
            row.remove("SurveyRetryAt");
            task.remove("PointStarted");
            task.putLong("LastProgress", now);
            task.remove("Blocker");
        }
        if (!row.contains("EntranceNavigationVersion")) {
            row.putInt("PreviousSurveyIndex", row.getIntOr("SurveyIndex", 0));
            row.putInt("EntranceNavigationVersion", 1);
            row.putInt("SurveyIndex", 0);
            row.remove("SurveyRetryAt");
        }
        String recovery = recoverAccess(npc, row, task, now);
        if (recovery != null) return recovery;
        String exit = leaveRegisteredEnclosure(npc, row, village, now);
        if (exit != null) return exit;
        // 露头包含坑壁，不只是从上向下看到的草皮；分批读取有限邻域，优先接续已露出的石层。
        long scanOrigin = npc.blockPosition().asLong();
        if (row.getLongOr("ProbeOrigin", 0L) != scanOrigin) { row.putLong("ProbeOrigin", scanOrigin); row.putInt("ProbeCursor", 0); }
        int cursor = row.getIntOr("ProbeCursor", 0);
        for (int probe = 0; probe < 64; probe++) {
            int index = (cursor + probe) % 567;
            var point = npc.blockPosition().offset(index % 9 - 4, index / 81 - 3, index / 9 % 9 - 4);
            if (!inParcel(row, point) || level.getChunkSource().getChunkNow(point.getX() >> 4, point.getZ() >> 4) == null
                    || !natural(level.getBlockState(point)) || !FieldworkRoutes.closeEnough(npc, point) || !safe(npc, row, point)) continue;
            String key = Long.toString(point.asLong());
            if (!observations.contains(key)) {
                var observation = new CompoundTag();
                observation.store("Observer", UUIDUtil.CODEC, npc.getCharacterId()); observation.store("Body", UUIDUtil.CODEC, npc.getUUID());
                observation.putLong("ObservedAt", now); observation.putString("State", NbtUtils.writeBlockState(level.getBlockState(point)).toString());
                observations.put(key, observation); task.putLong("LastProgress", now);
                KnowledgeStateData.get(MinecraftApi.server(npc)).observe(npc.getCharacterId(),
                        new FactKey(village.settlementId(), "authorized_surface_resource", key), observation.getStringOr("State", ""),
                        PrecisionBand.EXACT, 1, "原居民现场观察可见坑壁", now, 24000, DisclosurePolicy.SHAREABLE);
            }
        }
        row.putInt("ProbeCursor", (cursor + 64) % 567);
        // 只看原人物附近的露头，射线确认可见；地下未知物必须真实挖开后再观察。
        for (var column : BlockPos.betweenClosed(npc.blockPosition().offset(-4, 0, -4), npc.blockPosition().offset(4, 0, 4))) {
            if (level.getChunkSource().getChunkNow(column.getX() >> 4, column.getZ() >> 4) == null) continue;
            var standing = groundStand(npc, row, column);
            if (standing == null) continue;
            var position = standing.below();
            if (!safe(npc, row, position) || !natural(level.getBlockState(position))) continue;
            var hit = level.clip(new ClipContext(npc.getEyePosition(), Vec3.atCenterOf(position),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, npc));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(position)) continue;
            String key = Long.toString(position.asLong());
            if (!observations.contains(key)) {
                var observation = new CompoundTag();
                observation.store("Observer", UUIDUtil.CODEC, npc.getCharacterId());
                observation.store("Body", UUIDUtil.CODEC, npc.getUUID());
                observation.putLong("ObservedAt", now);
                observation.putString("State", NbtUtils.writeBlockState(level.getBlockState(position)).toString());
                observations.put(key, observation);
                KnowledgeStateData.get(MinecraftApi.server(npc)).observe(npc.getCharacterId(),
                        new FactKey(village.settlementId(), "authorized_surface_resource", key), observation.getStringOr("State", ""),
                        PrecisionBand.EXACT, 1, "原居民现场视线观察", now, 24000, DisclosurePolicy.SHAREABLE);
                task.putLong("LastProgress", now);
            }
        }
        if (mining(npc, row, now).isPresent()) return "YIELD";
        String probe = probe(npc, row, task, now);
        if (probe != null) return probe;
        var points = waypoints(village);
        int index = row.getIntOr("SurveyIndex", 0);
        if (index >= points.size()) {
            row.putLong("SurveyRetryAt", now + 24000);
            task.putString("Blocker", "AUTHORIZED_SURFACE_RANGE_EXHAUSTED");
            return "YIELD";
        }
        var point = points.get(index);
        if (level.getChunkSource().getChunkNow(point.getX() >> 4, point.getZ() >> 4) == null) {
            task.putString("Blocker", "REGION_UNLOADED");
            row.putLong("SurveyRetryAt", now + 1200);
            return "YIELD";
        }
        var stand = groundStand(npc, row, point);
        if (stand == null) {
            row.putInt("SurveyIndex", index + 1);
            return "授权点地势不适合浅层作业，保留原因并检查下一点";
        }
        if (npc.blockPosition().distSqr(stand) <= 4) {
            row.putInt("SurveyIndex", index + 1);
            return "已到现场观察，继续下一明确观察点";
        }
        if (!task.contains("PointStarted") || task.getIntOr("PointIndex", 0) != index) {
            task.putLong("PointStarted", now);
            task.putInt("PointIndex", index);
            task.putInt("PointWorkTicks", 0);
        }
        task.putInt("PointWorkTicks", task.getIntOr("PointWorkTicks", 0) + 1);
        if (task.getIntOr("PointWorkTicks", 0) > 2400) {
            row.putInt("SurveyIndex", index + 1);
            task.putString("Blocker", "SURVEY_PATH_BLOCKED");
            return "YIELD";
        }
        SettlementSurveyNavigation.move(npc, stand);
        return "原工人步行到已授权地块勘察，不加载远处也不透视地下";
    }

    /** 连续检查一个有限试坑，再转移观察点；不能把整片表土逐格铲完当成找石料。 */
    private static String probe(NpcBase npc, CompoundTag row, CompoundTag task, long now) {
        var tested = row.getCompoundOrEmpty("TestedProbes");
        if (!row.contains("ProbeAnchor")) {
            var candidates = row.getCompoundOrEmpty("ObservedResources").keySet().stream()
                    .map(key -> BlockPos.of(Long.parseLong(key)))
                    .filter(p -> inParcel(row, p) && natural(npc.level().getBlockState(p))
                            && p.getY() > BlockPos.of(row.getCompoundOrEmpty("Parcel").getLongOr("Min", 0L)).getY()
                            && !tested.contains(p.getX() + ":" + p.getZ()) && safe(npc, row, p))
                    .sorted(Comparator.<BlockPos>comparingInt(p -> -p.getY())
                            .thenComparingDouble(npc.blockPosition()::distSqr)).toList();
            if (candidates.isEmpty()) return null;
            row.putLong("ProbeAnchor", candidates.getFirst().asLong());
            row.putInt("ProbeWorkTicks", 0);
            row.putInt("ProbeOpeningCleared", clearedCount(npc, row));
            row.putLong("ProbeStarted", now);
            task.putLong("LastProgress", now);
            return "已选择有合法探测余深的有限试坑，沿同一处寻找石层";
        }
        var anchor = BlockPos.of(row.getLongOr("ProbeAnchor", 0L));
        row.putInt("ProbeWorkTicks", row.getIntOr("ProbeWorkTicks", 0) + 1);
        var stand = groundStand(npc, row, anchor);
        if (stand != null && npc.distanceToSqr(stand.getX() + .5, stand.getY(), stand.getZ() + .5) > .64
                && row.getIntOr("ProbeWorkTicks", 0) <= 2400
                && clearedCount(npc, row) - row.getIntOr("ProbeOpeningCleared", 0) < 48) {
            SettlementSurveyNavigation.move(npc, stand);
            return "实际走近试坑，观察已露出的侧壁：" + SettlementSurveyNavigation.status(npc);
        }
        if (stand != null && row.getIntOr("ProbeWorkTicks", 0) < 24) return "分批观察试坑侧壁，尚不认定资源耗尽";
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            tested.putLong((anchor.getX() + x) + ":" + (anchor.getZ() + z), now);
        row.put("TestedProbes", tested);
        row.remove("ProbeAnchor");
        task.putString("Blocker", "PROBE_NO_SAFE_PRODUCTIVE_ACTION");
        return "试坑未找到可安全接续的材料，记录范围与原因后换下一处";
    }

    private static BlockPos groundStand(NpcBase npc, CompoundTag row, BlockPos column) {
        var parcel = row.getCompoundOrEmpty("Parcel");
        int low = BlockPos.of(parcel.getLongOr("Min", 0L)).getY();
        int high = BlockPos.of(parcel.getLongOr("Max", 0L)).getY();
        for (int y = high; y >= low; y--) {
            var ground = new BlockPos(column.getX(), y, column.getZ());
            if (natural(npc.level().getBlockState(ground)) && FieldworkRoutes.walkable(npc, ground.above())) return ground.above();
        }
        return null;
    }

    /** 只操作这份已登记模板的公共外门，先离开围护再去资源点。 */
    static boolean exitClearanceCell(NaturalVillageBuilding village, BlockPos position) {
        for (int x = 11; x <= 13; x++) for (int z = 22; z <= 28; z++) for (int y = 1; y <= 4; y++) {
            if (NaturalVillageLayout.at(village, new BlockPos(x, y, z)).equals(position)) return true;
        }
        return false;
    }

    private static String leaveRegisteredEnclosure(NpcBase npc, CompoundTag row, NaturalVillageBuilding village, long now) {
        var a = NaturalVillageLayout.at(village, BlockPos.ZERO);
        var b = NaturalVillageLayout.at(village, new BlockPos(25, 0, 21));
        var pos = npc.blockPosition();
        boolean inside = pos.getX() >= Math.min(a.getX(), b.getX()) && pos.getX() <= Math.max(a.getX(), b.getX())
                && pos.getZ() >= Math.min(a.getZ(), b.getZ()) && pos.getZ() <= Math.max(a.getZ(), b.getZ());
        var endColumn = NaturalVillageLayout.at(village, new BlockPos(12, 1, 28));
        boolean onExit = exitClearanceCell(village, pos) || exitClearanceCell(village, pos.above());
        if (!inside && !onExit) return null;
        if (!inside && Math.abs(pos.getX() - endColumn.getX()) <= 1 && Math.abs(pos.getZ() - endColumn.getZ()) <= 1) return null;
        var door = NaturalVillageLayout.at(village, new BlockPos(12, 1, 21));
        var inner = NaturalVillageLayout.at(village, new BlockPos(12, 1, 20));
        var outer = endColumn;
        var state = npc.level().getBlockState(door);
        if (!state.is(Blocks.OAK_DOOR)) return "登记外门失效，需要通路恢复；不拆墙或传送";
        if (onExit || npc.distanceToSqr(door.getCenter()) <= 20.25) {
            if (!state.getValue(DoorBlock.OPEN) && npc.distanceToSqr(door.getCenter()) <= 20.25)
                ((DoorBlock) state.getBlock()).setOpen(npc, npc.level(), state, door, true);
            // 固定公共出口净空带：3×7、最多四格高，仅清可见自然树叶；不砍树干、不拆人工方块。
            if (row.getIntOr("ExitClearanceVersion", 0) < 2) {
                row.putInt("ExitClearanceVersion", 2);
                row.putString("ExitClearanceBounds", "local x11..13,z22..28,y1..4;natural-leaves-only");
                row.putLong("ExitClearanceApprovedAt", now);
            }
            for (int z = 22; z <= 28; z++) for (int x : new int[]{12, 11, 13}) for (int y = 1; y <= 4; y++) {
                var leaf = NaturalVillageLayout.at(village, new BlockPos(x, y, z));
                if (!npc.level().getBlockState(leaf).is(BlockTags.LEAVES) || !safeExit(npc, row, leaf)
                        || !FieldworkRoutes.closeEnough(npc, leaf)) continue;
                var observation = new CompoundTag();
                observation.store("Observer", UUIDUtil.CODEC, npc.getCharacterId());
                observation.store("ApprovedBy", UUIDUtil.CODEC, row.read("ApprovedBy", UUIDUtil.CODEC).orElseThrow());
                observation.putLong("ObservedAt", now);
                observation.putString("Purpose", "PUBLIC_EXIT_CLEARANCE");
                observation.putString("State", NbtUtils.writeBlockState(npc.level().getBlockState(leaf)).toString());
                row.getCompoundOrEmpty("ObservedResources").put(Long.toString(leaf.asLong()), observation);
                return "YIELD";
            }
            // 先到最近尚未走过的真实地面，逐步看见后面的树叶，不隔着树林批量清理。
            for (int z = 22; z <= 28; z++) {
                var column = NaturalVillageLayout.at(village, new BlockPos(12, 1, z));
                for (int y = village.buildingOrigin().getY() + 4; y >= village.buildingOrigin().getY(); y--) {
                    var point = new BlockPos(column.getX(), y, column.getZ());
                    if (!FieldworkRoutes.walkable(npc, point) || npc.distanceToSqr(point.getX() + .5, y, point.getZ() + .5) < 1
                            || point.distSqr(endColumn) >= npc.blockPosition().distSqr(endColumn)) continue;
                    if (npc.blockPosition().distSqr(point) > 16) continue;
                    SettlementSurveyNavigation.move(npc, point);
                    return "沿固定公共出口净空带逐步行走：" + SettlementSurveyNavigation.status(npc);
                }
            }
            SettlementSurveyNavigation.move(npc, outer);
        } else FieldworkRoutes.move(npc, inner);
        return "经原聚落公共外门实际出场后勘察：" + SettlementSurveyNavigation.status(npc);
    }
}
