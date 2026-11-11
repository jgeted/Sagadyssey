package com.jgeted.sagadyssey.npc.brain.execution;

import com.jgeted.sagadyssey.npc.ai.InteractionApproach;
import com.jgeted.sagadyssey.npc.ai.MovementProgress;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlan;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanBlocker;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStep;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将 NPC 移动到指定方块附近的通用执行器。
 *
 * <p>该执行器只管理导航，不生产、消费物品，也不播放实体动作。世界重载后的
 * {@link #recover} 会重新建立导航，而不是依赖 JVM 中遗失的临时路径。</p>
 */
public final class MoveToBlockTaskExecutor implements NpcTaskExecutor {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            "sagadyssey", "move_to_block");
    public static final String DIMENSION = "dimension";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String SPEED = "speed";
    public static final String RANGE = "range";

    static final double DEFAULT_SPEED = 1.0D;
    static final double DEFAULT_RANGE = 1.5D;
    private static final double MIN_SPEED = 0.05D;
    private static final double MAX_SPEED = 4.0D;
    private static final double MIN_RANGE = 0.25D;
    private static final double MAX_RANGE = 16.0D;
    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final long REPATH_INTERVAL = 20L;
    private static final long RETRY_DELAY = 40L;
    private static final int MAX_PATH_FAILURES = 3;

    private final DriverFactory drivers;
    private final Map<StepKey, MovementState> movements = new HashMap<>();
    @Override public void forget(java.util.UUID body,java.util.UUID plan) {
        movements.entrySet().removeIf(e->{if(e.getKey().npcId().equals(body)&&e.getKey().planId().equals(plan)){e.getValue().driver.stop();return true;}return false;});
    }

    public MoveToBlockTaskExecutor() {
        this(EntityMovementDriver::from);
    }

    /** 测试缝；正式运行时只使用服务端实体驱动。 */
    MoveToBlockTaskExecutor(DriverFactory drivers) {
        this.drivers = Objects.requireNonNull(drivers, "drivers");
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public TaskTickResult start(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        return begin(context, plan, step, "开始前往目标");
    }

    @Override
    public TaskTickResult tick(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        StepKey key = StepKey.from(context, plan, step);
        Parameters parameters;
        try {
            parameters = parseParameters(step.params());
        } catch (IllegalArgumentException exception) {
            stopAndRemove(key, null);
            return TaskTickResult.failed("移动参数无效：" + exception.getMessage());
        }

        MovementState state = movements.get(key);
        if (state == null) return begin(context, plan, step, "重建丢失的导航");
        MovementDriver driver = state.driver;
        TaskTickResult invalid = validateWorld(context, parameters, driver, key);
        if (invalid != null) return invalid;
        if (driver.reached(parameters.target(), parameters.range())) {
            stopAndRemove(key, driver);
            return TaskTickResult.stepSucceeded("已到达移动目标");
        }

        long now = context.gameTime();
        boolean stalled = driver.stalled(parameters.target());
        boolean shouldRepath = stalled || now >= state.nextRepathAt;
        if (shouldRepath) {
            boolean accepted = driver.moveTo(parameters.target(), parameters.speed());
            state.nextRepathAt = safeAdd(now, REPATH_INTERVAL);
            if (!accepted || stalled) {
                state.pathFailures++;
                if (state.pathFailures >= MAX_PATH_FAILURES) {
                    stopAndRemove(key, driver);
                    return blocked(parameters, "PATH_UNREACHABLE",
                            "连续三次无法建立或推进目标路径", safeAdd(now, RETRY_DELAY));
                }
            }
        }
        return TaskTickResult.running("正在前往 " + targetLabel(parameters));
    }

    @Override
    public TaskTickResult recover(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
        StepKey key = StepKey.from(context, plan, step);
        stopAndRemove(key, null);
        return begin(context, plan, step, "重载后重建导航");
    }

    @Override
    public void cancel(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step,
                       PlanCancellationReason reason) {
        StepKey key = StepKey.from(context, plan, step);
        MovementState state = movements.remove(key);
        MovementDriver driver = state == null ? drivers.create(context) : state.driver;
        if (driver != null) driver.stop();
    }

    private TaskTickResult begin(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step,
                                 String detail) {
        StepKey key = StepKey.from(context, plan, step);
        Parameters parameters;
        try {
            parameters = parseParameters(step.params());
        } catch (IllegalArgumentException exception) {
            stopAndRemove(key, null);
            return TaskTickResult.failed("移动参数无效：" + exception.getMessage());
        }

        MovementDriver driver = drivers.create(context);
        if (driver == null) {
            stopAndRemove(key, null);
            return TaskTickResult.failed("移动步骤仅支持服务端 NPC 实体上下文");
        }
        stopAndRemove(key, null);
        TaskTickResult invalid = validateWorld(context, parameters, driver, key);
        if (invalid != null) return invalid;
        if (driver.reached(parameters.target(), parameters.range())) {
            driver.stop();
            return TaskTickResult.stepSucceeded("已经位于移动目标范围内");
        }

        driver.resetProgress(parameters.target());
        MovementState state = new MovementState(driver,
                safeAdd(context.gameTime(), REPATH_INTERVAL));
        movements.put(key, state);
        if (!driver.moveTo(parameters.target(), parameters.speed())) {
            state.pathFailures = 1;
        }
        return TaskTickResult.running(detail + "：" + targetLabel(parameters));
    }

    @Nullable
    private TaskTickResult validateWorld(NpcExecutionContext context, Parameters parameters,
                                         MovementDriver driver, StepKey key) {
        if (!driver.alive()) {
            stopAndRemove(key, driver);
            return TaskTickResult.failed("NPC 已失效，无法继续移动");
        }
        if (!driver.dimension().equals(parameters.dimension())) {
            stopAndRemove(key, driver);
            return TaskTickResult.failed("移动目标位于其他维度：" + parameters.dimension());
        }
        if (!driver.validHeight(parameters.target().getY())) {
            stopAndRemove(key, driver);
            return TaskTickResult.failed("移动目标高度超出当前维度范围");
        }
        if (!driver.chunkLoaded(parameters.target())) {
            stopAndRemove(key, driver);
            return blocked(parameters, "TARGET_CHUNK_UNLOADED",
                    "移动目标区块尚未加载", safeAdd(context.gameTime(), RETRY_DELAY));
        }
        return null;
    }

    private static TaskTickResult blocked(Parameters parameters, String code,
                                          String detail, long retryAt) {
        return TaskTickResult.blocked(detail, new NpcPlanBlocker(
                code, detail, retryAt, targetLabel(parameters)));
    }

    private void stopAndRemove(StepKey key, @Nullable MovementDriver fallback) {
        MovementState removed = movements.remove(key);
        MovementDriver driver = removed == null ? fallback : removed.driver;
        if (driver != null) driver.stop();
    }

    static Parameters parseParameters(Map<String, String> params) {
        if (params == null) throw new IllegalArgumentException("参数表缺失");
        Identifier dimension = Identifier.tryParse(required(params, DIMENSION));
        if (dimension == null) throw new IllegalArgumentException("dimension 无法解析");
        int x = parseCoordinate(params, X);
        int y = parseCoordinate(params, Y);
        int z = parseCoordinate(params, Z);
        if (Math.abs((long) x) > MAX_HORIZONTAL_COORDINATE
                || Math.abs((long) z) > MAX_HORIZONTAL_COORDINATE) {
            throw new IllegalArgumentException("水平坐标超出世界边界");
        }
        double speed = parseFinite(params, SPEED, DEFAULT_SPEED);
        double range = parseFinite(params, RANGE, DEFAULT_RANGE);
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new IllegalArgumentException("speed 必须在 0.05 到 4.0 之间");
        }
        if (range < MIN_RANGE || range > MAX_RANGE) {
            throw new IllegalArgumentException("range 必须在 0.25 到 16.0 之间");
        }
        return new Parameters(dimension, new BlockPos(x, y, z), speed, range);
    }

    private static String required(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + key);
        return value.trim();
    }

    private static int parseCoordinate(Map<String, String> params, String key) {
        try {
            return Integer.parseInt(required(params, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    private static double parseFinite(Map<String, String> params, String key,
                                      double defaultValue) {
        String encoded = params.get(key);
        if (encoded == null || encoded.isBlank()) return defaultValue;
        try {
            double value = Double.parseDouble(encoded.trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " 必须是有限数字");
        }
    }

    private static String targetLabel(Parameters parameters) {
        return parameters.dimension() + ":" + parameters.target().toShortString();
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    static record Parameters(Identifier dimension, BlockPos target,
                             double speed, double range) {
    }

    @FunctionalInterface
    interface DriverFactory {
        @Nullable MovementDriver create(NpcExecutionContext context);
    }

    interface MovementDriver {
        Identifier dimension();

        boolean alive();

        boolean validHeight(int y);

        boolean chunkLoaded(BlockPos target);

        boolean reached(BlockPos target, double range);

        boolean moveTo(BlockPos target, double speed);

        void resetProgress(BlockPos target);

        boolean stalled(BlockPos target);

        void stop();
    }

    private static final class EntityMovementDriver implements MovementDriver {
        private final NpcBase npc;
        private final NpcEntityExecutionContext context;
        private final MovementProgress progress = new MovementProgress();

        private EntityMovementDriver(NpcEntityExecutionContext context) {
            this.context = context;
            this.npc = context.npc();
        }

        @Nullable
        private static MovementDriver from(NpcExecutionContext context) {
            if (!(context instanceof NpcEntityExecutionContext entityContext)
                    || entityContext.level().isClientSide()) return null;
            return new EntityMovementDriver(entityContext);
        }

        @Override
        public Identifier dimension() {
            return context.level().dimension().identifier();
        }

        @Override
        public boolean alive() {
            return npc.isAlive();
        }

        @Override
        public boolean validHeight(int y) {
            return y >= context.level().getMinY()
                    && y < context.level().getMaxY() + 1;
        }

        @Override
        public boolean chunkLoaded(BlockPos target) {
            return context.level().hasChunkAt(target);
        }

        @Override
        public boolean reached(BlockPos target, double range) {
            return InteractionApproach.reached(npc, target, range * range);
        }

        @Override
        public boolean moveTo(BlockPos target, double speed) {
            return InteractionApproach.move(npc, target, speed);
        }

        @Override
        public void resetProgress(BlockPos target) {
            progress.reset(npc, target);
        }

        @Override
        public boolean stalled(BlockPos target) {
            return progress.tick(npc, target);
        }

        @Override
        public void stop() {
            npc.getNavigation().stop();
            npc.clearLogisticsMoveTarget();
        }
    }

    private record StepKey(java.util.UUID npcId, java.util.UUID planId, int stepIndex) {
        private static StepKey from(NpcExecutionContext context, NpcPlan plan, NpcPlanStep step) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(step, "step");
            return new StepKey(context.npcId(), plan.id(), step.index());
        }
    }

    private static final class MovementState {
        private final MovementDriver driver;
        private long nextRepathAt;
        private int pathFailures;

        private MovementState(MovementDriver driver, long nextRepathAt) {
            this.driver = driver;
            this.nextRepathAt = nextRepathAt;
        }
    }
}
