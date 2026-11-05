package com.jgeted.sagadyssey.npc.brain.fieldwork;

import com.jgeted.sagadyssey.npc.brain.execution.WaitUntilTaskExecutor;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

public final class FieldworkPlanFactory {
    public static final ResourceLocation PROVIDER = id("fieldwork");
    public static final ResourceLocation RESERVE = id("fieldwork_reserve"), MOVE = id("fieldwork_move"),
            ACT = id("fieldwork_act"), DEPOSIT = id("fieldwork_deposit"), REST = id("fieldwork_rest"), TOOL = id("fieldwork_tool"), SEED = id("fieldwork_seed");
    private FieldworkPlanFactory() { }

    public static NpcPlan work(UUID npcId, FieldworkTarget target, FieldworkRoutes.Route route,
                                String dimension, long now) {
        List<NpcPlanStep> steps = new ArrayList<>();
        Map<String, String> identity = target.params(dimension);
        add(steps, NpcPlanPhase.RESERVING, RESERVE, identity, now);
        add(steps, NpcPlanPhase.TRAVELLING, MOVE, located(route.approach(), dimension), now);
        boolean alongX = !route.scaffold().isEmpty()
                && route.scaffold().getFirst().getX() != route.scaffold().getLast().getX();
        for (BlockPos pos : route.scaffold()) {
            // 从阶梯侧面的地面逐列搭建，不能隔着已搭的承重柱放置下一柱的底格。
            BlockPos buildStand = new BlockPos(pos.getX(), route.approach().getY(), pos.getZ())
                    .offset(alongX ? 0 : 1, 0, alongX ? 1 : 0);
            var block = ((BlockItem) route.material().getItem()).getBlock();
            var placement = new FieldworkTarget(pos, FieldworkAction.Operation.PLACE_SCAFFOLD,
                    Blocks.AIR.defaultBlockState(), block.defaultBlockState(), route.material().getItem(), null);
            Map<String, String> placementParams = new HashMap<>(placement.params(dimension));
            placementParams.putAll(stand(buildStand));
            add(steps, NpcPlanPhase.ACTING, ACT, placementParams, now);
        }
        Map<String, String> acting = new HashMap<>(identity);
        acting.putAll(stand(route.stand()));
        add(steps, NpcPlanPhase.ACTING, ACT, acting, now);
        if (target.operation() == FieldworkAction.Operation.HARVEST && target.before().getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
            net.minecraft.world.item.Item seed = target.before().is(Blocks.WHEAT) ? net.minecraft.world.item.Items.WHEAT_SEEDS
                    : target.before().is(Blocks.CARROTS) ? net.minecraft.world.item.Items.CARROT
                    : target.before().is(Blocks.POTATOES) ? net.minecraft.world.item.Items.POTATO
                    : target.before().is(Blocks.BEETROOTS) ? net.minecraft.world.item.Items.BEETROOT_SEEDS : null;
            if (seed != null) add(steps, NpcPlanPhase.ACTING, ACT, new FieldworkTarget(target.position(), FieldworkAction.Operation.PLANT,
                    Blocks.AIR.defaultBlockState(), target.before().getBlock().defaultBlockState(), seed, null).params(dimension), now);
        } else if (target.operation() == FieldworkAction.Operation.HARVEST && target.before().is(Blocks.COCOA)) {
            add(steps, NpcPlanPhase.ACTING, ACT, new FieldworkTarget(target.position(), FieldworkAction.Operation.PLANT,
                    Blocks.AIR.defaultBlockState(), target.before().setValue(net.minecraft.world.level.block.CocoaBlock.AGE, 0),
                    net.minecraft.world.item.Items.COCOA_BEANS, null).params(dimension), now);
        }
        // 先离开阶梯，随后每格单独回收，不能从 NPC 脚下拆掉承重方块。
        if (!route.scaffold().isEmpty()) add(steps, NpcPlanPhase.SETTLING, MOVE, located(route.approach(), dimension), now);
        for (int index = route.scaffold().size() - 1; index >= 0; index--) {
            var block = ((BlockItem) route.material().getItem()).getBlock();
            var recovery = new FieldworkTarget(route.scaffold().get(index), FieldworkAction.Operation.RECOVER_SCAFFOLD,
                    block.defaultBlockState(), Blocks.AIR.defaultBlockState(), null, null);
            add(steps, NpcPlanPhase.SETTLING, ACT, recovery.params(dimension), now);
        }
        add(steps, NpcPlanPhase.SETTLING, DEPOSIT, Map.of(), now);
        return create(npcId, steps, now, "fieldwork:" + dimension + ':' + target.position().asLong(), "完成单方块作业、回收材料并交付产出");
    }

    public static NpcPlan cleanup(UUID npcId, FieldworkActionReceipt placed, long now) {
        Map<String, String> params = new HashMap<>();
        params.put("dimension", placed.action().dimension());
        params.put("target_x", Integer.toString(placed.action().target().getX()));
        params.put("target_y", Integer.toString(placed.action().target().getY()));
        params.put("target_z", Integer.toString(placed.action().target().getZ()));
        params.put("operation", FieldworkAction.Operation.RECOVER_SCAFFOLD.name());
        params.put("expected", placed.resultingState());
        params.put("result", NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()).toString());
        List<NpcPlanStep> steps = new ArrayList<>();
        add(steps, NpcPlanPhase.RESERVING, RESERVE, params, now);
        add(steps, NpcPlanPhase.ACTING, ACT, params, now);
        add(steps, NpcPlanPhase.SETTLING, DEPOSIT, Map.of(), now);
        return create(npcId, steps, now, "fieldwork:cleanup:" + placed.action().key(), "回收中断任务遗留的真实材料");
    }

    public static NpcPlan deposit(UUID npcId, long now) {
        List<NpcPlanStep> steps = new ArrayList<>();
        add(steps, NpcPlanPhase.SETTLING, DEPOSIT, Map.of(), now);
        return create(npcId, steps, now, "fieldwork:delivery:" + npcId, "搬运此前尚未交付的真实产出");
    }

    public static NpcPlan tool(UUID npcId,FieldworkTarget target,String dimension,UUID household,long now) {
        var params=new HashMap<>(target.params(dimension));params.put("household",household.toString());
        List<NpcPlanStep> steps=new ArrayList<>();add(steps,NpcPlanPhase.ACTING,TOOL,params,now);
        return create(npcId,steps,now,"fieldwork:tool:"+npcId,"从授权家庭仓领取一把备用铁镐");
    }

    public static NpcPlan seed(UUID npcId,FieldworkTarget target,String dimension,UUID household,long now) {
        var params=new HashMap<>(target.params(dimension));params.put("household",household.toString());
        List<NpcPlanStep> steps=new ArrayList<>();add(steps,NpcPlanPhase.ACTING,SEED,params,now);
        return create(npcId,steps,now,"fieldwork:seed:"+npcId,"从授权家庭仓领取一粒小麦种子");
    }

    /** New v3 plans freeze the selected source; old plans without these keys keep household wheat semantics. */
    public static NpcPlan seed(UUID npcId,FieldworkTarget target,String dimension,UUID household,FieldworkStorage source,long now) {
        if(target.input()!=net.minecraft.world.item.Items.CARROT)throw new IllegalArgumentException("Versioned carrot seed target required");
        Objects.requireNonNull(source);
        var params=new HashMap<>(target.params(dimension));params.put("household",household.toString());
        params.put("seed_source",source.containerId().toString());params.put("seed_dimension",source.dimension());
        params.put("seed_x",Integer.toString(source.position().getX()));params.put("seed_y",Integer.toString(source.position().getY()));params.put("seed_z",Integer.toString(source.position().getZ()));
        List<NpcPlanStep> steps=new ArrayList<>();add(steps,NpcPlanPhase.ACTING,SEED,params,now);
        return create(npcId,steps,now,"fieldwork:seed:"+npcId,"从已固定的授权来源领取一份种用胡萝卜");
    }

    public static NpcPlan rest(UUID npcId, long now, int duration, String reason, boolean continueScan) {
        List<NpcPlanStep> steps = new ArrayList<>();
        add(steps, NpcPlanPhase.ACTING, REST, Map.of(WaitUntilTaskExecutor.UNTIL_GAME_TIME,
                Long.toString(Math.addExact(now, duration)), "reason", reason,
                "continue_scan", Boolean.toString(continueScan)), now);
        return create(npcId, steps, now, "fieldwork:rest:" + npcId, reason);
    }

    static Map<String, String> located(BlockPos position, String dimension) {
        return Map.of("dimension", dimension, "x", Integer.toString(position.getX()),
                "y", Integer.toString(position.getY()), "z", Integer.toString(position.getZ()));
    }
    private static Map<String, String> stand(BlockPos pos) {
        return Map.of("stand_x", Integer.toString(pos.getX()), "stand_y", Integer.toString(pos.getY()), "stand_z", Integer.toString(pos.getZ()));
    }
    private static void add(List<NpcPlanStep> steps, NpcPlanPhase phase, ResourceLocation executor,
                             Map<String, String> params, long now) {
        steps.add(new NpcPlanStep(steps.size(), phase, executor, params, NpcPlanStepStatus.PENDING, now, "等待执行"));
    }
    private static NpcPlan create(UUID npcId, List<NpcPlanStep> steps, long now, String key, String reason) {
        return new NpcPlan(UUID.randomUUID(), npcId, key, PROVIDER, NpcPlanKind.WORK, NpcPlanPriority.NORMAL,
                NpcPlanStatus.SELECTED, steps, 0, now, now, now + 200L, now + 12_000L, null, reason);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("sagadyssey", path); }
}
