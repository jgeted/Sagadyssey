package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.reservation.NpcReservationKey;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.life.NpcSchedulePlanner;
import java.util.*;

/** 发现 → 预留 → 行走 → 作业 → 携带交付 → 新作业/有限休整。未知 NPC 不自动灰度。 */
public final class FieldworkPlanProvider {
    private FieldworkPlanProvider() { }

    public static boolean compatible(NpcBase npc, NpcPlan plan) {
        String operation = plan.steps().getFirst().params().get("operation");
        if (operation == null) return true; // 休整、携带交付由两类生产者共用。
        try {
            var kind = FieldworkAction.Operation.valueOf(operation);
            if (kind == FieldworkAction.Operation.RECOVER_SCAFFOLD) return true;
            boolean workerAction = kind == FieldworkAction.Operation.MINE || kind == FieldworkAction.Operation.CHOP
                    || kind == FieldworkAction.Operation.PLACE_SCAFFOLD
                    || kind == FieldworkAction.Operation.PLANT
                    && com.jgeted.sagadyssey.social.industry.forestry.TreePlantTask.compatible(npc,plan.steps().getFirst().params());
            return workerAction == (npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.WORKER)
                    && FieldworkDiscovery.allowed(npc, FieldworkTarget.position(plan.steps().getFirst().params()));
        } catch (RuntimeException exception) { return false; }
    }

    public static Optional<NpcPlan> choose(NpcBase npc, long now) {
        if(com.jgeted.sagadyssey.social.politics.NaturalPolityData.paused(npc.getServer(),npc.getCharacterId()))return Optional.empty();
        if (!FieldworkDiscovery.producer(npc) || npc.getCommand() != NpcCommand.WORK || npc.getTarget() != null) return Optional.empty();
        // 导航在落地前不能给出可靠路径；这不是工作区不可达，不能写入长时失败记忆。
        if (!npc.onGround()) {
            npc.reportWorkActivity("调整站位", null, "等待安全落地后检查工作区域");
            return Optional.empty();
        }
        var data = FieldworkData.get(npc.getServer());
        String dimension = npc.level().dimension().location().toString();
        for (var scaffold : data.outstandingScaffolds(npc.getUUID())) {
            if (!scaffold.action().dimension().equals(dimension)) continue;
            if (data.unreachable(npc.getUUID(), dimension, scaffold.action().target(), now).isPresent()) continue;
            return Optional.of(FieldworkPlanFactory.cleanup(npc.getUUID(), scaffold, now));
        }
        if (FieldworkDepositTaskExecutor.hasDeliverableCargo(npc, data)) {
            long retryAt = data.deliveryRetryAt(npc.getUUID());
            return Optional.of(retryAt <= now ? FieldworkPlanFactory.deposit(npc.getUUID(), now)
                    : FieldworkPlanFactory.rest(npc.getUUID(), now, (int) Math.min(200, retryAt - now), "保管待交付产出，稍后复查储物处", true));
        }
        if (!com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.ordinaryFieldwork(npc)) {
            return Optional.of(FieldworkPlanFactory.rest(npc.getUUID(), now, 200,
                    "储备或工程材料已足，短暂休整后重查协作需求", true));
        }
        if (!NpcSchedulePlanner.allowsWork(npc)) return Optional.of(FieldworkPlanFactory.rest(npc.getUUID(), now, 200,
                "不在工作排班内，整理工具并休息", true));
        String profile = npc.getProfession().name() + ':' + npc.getFarmMode().name() + ':' + dimension + ':'
                + (npc.hasWorkZone() ? npc.getWorkZoneMin() + ":" + npc.getWorkZoneMax() : "anchored-discovery");
        // Complete the existing work search before considering external seed replenishment.
        boolean seedPass=data.hasSearchProfile(npc.getUUID(),profile+":seed");
        var computedArea = FieldworkDiscovery.area(npc);
        if (!npc.hasWorkZone() && (seedPass || data.hasSearchProfile(npc.getUUID(), profile))) computedArea = data.scan(npc.getUUID()).map(FieldworkScan.Cursor::area)
                .filter(area -> area.dimension().equals(dimension)).orElse(computedArea);
        var cursor = data.search(npc.getUUID(), seedPass?profile+":seed":profile, computedArea);
        FieldworkTarget[] found = new FieldworkTarget[1];
        boolean[] seedShortage=new boolean[1];
        FieldworkRoutes.Route[] route = new FieldworkRoutes.Route[1];
        var result = FieldworkScan.advanceTimed(cursor, 512, now, now + 600, 250_000L, pos -> {
            if (!npc.level().hasChunkAt(pos)) return FieldworkScan.ProbeResult.UNLOADED;
            if (data.unreachable(npc.getUUID(), dimension, pos, now).isPresent()) return FieldworkScan.ProbeResult.DEFERRED;
            var target = FieldworkDiscovery.inspect(npc, pos);
            boolean missingSeed=false;
            if(target==null&&seedPass){target=FieldworkSeedTaskExecutor.shortageTarget(npc,pos);missingSeed=target!=null;}
            if (target == null) return FieldworkScan.ProbeResult.NONE;
            if (NpcBrainRuntime.reservations(npc.getServer()).find(new NpcReservationKey("fieldwork_block", dimension + ':' + pos.asLong()), now).isPresent()) {
                return FieldworkScan.ProbeResult.DEFERRED;
            }
            var candidateRoute = FieldworkRoutes.plan(npc, pos, npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.WORKER);
            if (candidateRoute == null) {
                data.rememberUnreachable(npc.getUUID(), dimension, pos, now, now + 600, "未找到安全站位或足够脚手架材料");
                return FieldworkScan.ProbeResult.DEFERRED;
            }
            found[0] = target; route[0] = candidateRoute; seedShortage[0]=missingSeed;
            return FieldworkScan.ProbeResult.AVAILABLE;
        });
        data.saveScan(npc.getUUID(), result.cursor());
        if (result.status() == FieldworkScan.Status.FOUND) {
            if(seedPass)data.search(npc.getUUID(),profile,computedArea);
            if(seedShortage[0]){
                var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(npc.getServer()).forMember(npc.getCharacterId());
                data.saveScan(npc.getUUID(),FieldworkScan.Cursor.begin(result.cursor().area()));
                if(com.jgeted.sagadyssey.core.structure.NaturalFarmRules.isFarmer(npc)){
                    var source=home==null?null:FieldworkSeedTaskExecutor.carrotSource(npc,home.id(),now);
                    return Optional.of(source==null
                            ?FieldworkPlanFactory.rest(npc.getUUID(),now,200,"缺少已知且有领取权限的种用胡萝卜，等待补给",true)
                            :FieldworkPlanFactory.seed(npc.getUUID(),found[0],dimension,home.id(),source,now));
                }
                return Optional.of(home==null||home.sharedStorage()==null
                        ? FieldworkPlanFactory.rest(npc.getUUID(),now,200,"缺少小麦种子，尚无家庭种子仓",true)
                        : FieldworkPlanFactory.seed(npc.getUUID(),found[0],dimension,home.id(),now));
            }
            if (found[0].operation()==FieldworkAction.Operation.MINE && FieldworkDiscovery.mineTarget(found[0].before())
                    && !FieldworkToolTaskExecutor.hasUsablePickaxe(npc,found[0].before(),now)) {
                var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(npc.getServer()).forMember(npc.getCharacterId());
                // Retry this real target after replenishment; discovery must not silently skip it.
                data.saveScan(npc.getUUID(),FieldworkScan.Cursor.begin(result.cursor().area()));
                return Optional.of(home==null||home.sharedStorage()==null
                        ? FieldworkPlanFactory.rest(npc.getUUID(),now,200,"缺工具，尚无家庭工具仓",true)
                        : FieldworkPlanFactory.tool(npc.getUUID(),found[0],dimension,home.id(),now));
            }
            return Optional.of(FieldworkPlanFactory.work(npc.getUUID(), found[0], route[0], dimension, now));
        }
        if (result.status() == FieldworkScan.Status.SEARCHING) {
            npc.reportWorkActivity("检查工作区域", null, "分批检查已加载区域中的有效目标");
            return Optional.empty();
        }
        if(!seedPass && result.status()==FieldworkScan.Status.EMPTY
                && npc.getProfession()==com.jgeted.sagadyssey.npc.profession.NpcProfession.FARMER
                && npc.getFarmMode()==com.jgeted.sagadyssey.npc.entity.FarmMode.CROP){
            data.search(npc.getUUID(),profile+":seed",computedArea);
            return Optional.empty();
        }
        if(seedPass)data.search(npc.getUUID(),profile,computedArea);
        String reason = switch (result.status()) {
            case UNLOADED -> "部分工作区域未加载，先整理工具，稍后复查";
            case DEFERRED -> "剩余目标暂不可达或被占用，先整理工具，稍后复查";
            default -> npc.getProfession() == com.jgeted.sagadyssey.npc.profession.NpcProfession.FARMER
                    ? "暂无成熟作物或可执行种植，整理物资并等待生长或补给" : "当前工作区已无可执行目标，结束本轮并休整";
        };
        return Optional.of(FieldworkPlanFactory.rest(npc.getUUID(), now, 200, reason, false));
    }

    /** One registered mature cell (including its real replant/deposit), never a general night shift. */
    public static Optional<NpcPlan> chooseEmergency(NpcBase npc,long now){
        if(!com.jgeted.sagadyssey.core.structure.NaturalFarmRules.isFarmer(npc)
                ||!com.jgeted.sagadyssey.core.structure.NaturalFarmRules.foodEmergency(npc)
                ||npc.getCommand()!=NpcCommand.WORK||npc.getTarget()!=null||!npc.isAlive()
                ||!npc.onGround()&&!npc.isSleeping()
                ||com.jgeted.sagadyssey.social.politics.NaturalPolityData.paused(npc.getServer(),npc.getCharacterId()))return Optional.empty();
        var data=FieldworkData.get(npc.getServer());
        if(!data.outstandingScaffolds(npc.getUUID()).isEmpty())return Optional.empty();
        if(FieldworkDepositTaskExecutor.hasDeliverableCargo(npc,data)&&data.deliveryRetryAt(npc.getUUID())<=now)
            return Optional.of(FieldworkPlanFactory.deposit(npc.getUUID(),now));
        var b=com.jgeted.sagadyssey.core.structure.NaturalFarmRules.forResident(npc);
        String dimension=npc.level().dimension().location().toString();
        var cells=com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.residentVillage(npc.getServer(),npc.getCharacterId())!=null
                ?com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.farmCells(npc)
                :com.jgeted.sagadyssey.core.structure.NaturalVillageLayout.crops().stream().map(local->com.jgeted.sagadyssey.core.structure.NaturalVillageLayout.at(b,local)).toList();
        for(var pos:cells){
            if(!npc.level().hasChunkAt(pos)||data.unreachable(npc.getUUID(),dimension,pos,now).isPresent())continue;
            var target=FieldworkDiscovery.inspect(npc,pos);
            if(target==null||target.operation()!=FieldworkAction.Operation.HARVEST||!target.before().is(net.minecraft.world.level.block.Blocks.CARROTS))continue;
            if(NpcBrainRuntime.reservations(npc.getServer()).find(new NpcReservationKey("fieldwork_block",dimension+':'+pos.asLong()),now).isPresent())continue;
            var route=FieldworkRoutes.plan(npc,pos,false);if(route==null)continue;
            return Optional.of(FieldworkPlanFactory.work(npc.getUUID(),target,route,dimension,now));
        }
        return Optional.empty();
    }
}
