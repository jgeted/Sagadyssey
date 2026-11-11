package com.jgeted.sagadyssey.gametest;

import com.jgeted.sagadyssey.gametest.PortedGameTests.TestFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.annotation.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Maps the existing Java test definitions to the 1.21.11 registries. */
@EventBusSubscriber(modid = "sagadyssey")
public final class PortedGameTests {
    private static final Identifier TYPE = Identifier.fromNamespaceAndPath("sagadyssey", "java_test");
    private static final List<String> SUITES = List.of(
            "com.jgeted.sagadyssey.core.structure.BoundedExchangeP5GameTests",
            "com.jgeted.sagadyssey.core.structure.CharterDiplomacyP6GameTests",
            "com.jgeted.sagadyssey.core.structure.LimitedConflictP7GameTests",
            "com.jgeted.sagadyssey.core.structure.LodContinuityP8GameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalFarmConsumptionRestartGameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalFarmSeedLodGameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalLifeGameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalMemberP2GameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalVillageR1GameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalVillageR2GameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalVillageR3GameTests",
            "com.jgeted.sagadyssey.core.structure.NaturalVillageRegistrationGameTests",
            "com.jgeted.sagadyssey.core.structure.ResidentContinuityP3GameTests",
            "com.jgeted.sagadyssey.core.structure.SettlementCycleP4GameTests",
            "com.jgeted.sagadyssey.npc.brain.command.BlacksmithRestGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.BlacksmithRestRestartGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.IdentityLifecycleGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcCombatClientFixtureGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcCombatIntegrationGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcCommandGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcDutyGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcDutyRestartGameTests",
            "com.jgeted.sagadyssey.npc.brain.command.NpcDutySoakGameTests",
            "com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkGameTests",
            "com.jgeted.sagadyssey.npc.brain.life.LifeGameTests",
            "com.jgeted.sagadyssey.social.growth.ResourceReserveGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementCoordinatorGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementDevelopmentContinuityGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementDevelopmentGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementDevelopmentRecoveryGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementFoodCapacityRecoveryGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementGrowthCacheBindingGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementGrowthRetryContractGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementHousingCirculationGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementPlayabilityFixGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementRecoveryDeadlineGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementResilienceGameTests",
            "com.jgeted.sagadyssey.social.growth.SettlementSafetyGameTests",
            "com.jgeted.sagadyssey.social.household.ResidentLedgerReadGameTests",
            "com.jgeted.sagadyssey.social.industry.AgricultureColdGameTests",
            "com.jgeted.sagadyssey.social.industry.EntityFurnaceGameTests",
            "com.jgeted.sagadyssey.social.industry.ForestryRenewalGameTests",
            "com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests",
            "com.jgeted.sagadyssey.social.industry.IndustryForestryGameTests",
            "com.jgeted.sagadyssey.social.industry.IndustryIntegrationGameTests",
            "com.jgeted.sagadyssey.social.industry.IndustryInternalGameTests",
            "com.jgeted.sagadyssey.social.industry.NativeCancellationGameTests",
            "com.jgeted.sagadyssey.social.industry.NaturalSupplierGameTests",
            "com.jgeted.sagadyssey.social.industry.SupplyRecoveryGameTests",
            "com.jgeted.sagadyssey.social.industry.WoodBatchGameTests",
            "com.jgeted.sagadyssey.social.industry.WoodMaterialBootstrapGameTests",
            "com.jgeted.sagadyssey.social.industry.WoolDeliveryAccountingGameTests",
            "com.jgeted.sagadyssey.social.industry.animal.HouseholdBedPlanBoundaryGameTests",
            "com.jgeted.sagadyssey.social.industry.animal.HouseholdBedWoolGameTests",
            "com.jgeted.sagadyssey.social.industry.animal.HouseholdMultiMaterialBoundaryGameTests",
            "com.jgeted.sagadyssey.social.industry.animal.HouseholdMultiMaterialGameTests",
            "com.jgeted.sagadyssey.social.industry.animal.NaturalWoolGrowthGameTests",
            "com.jgeted.sagadyssey.social.industry.common.KnownNaturalSheepGameTests",
            "com.jgeted.sagadyssey.social.industry.common.NaturalResourceGameTests",
            "com.jgeted.sagadyssey.social.industry.food.NaturalFoodGameTests",
            "com.jgeted.sagadyssey.social.needs.CancelledSupplyGameTests",
            "com.jgeted.sagadyssey.social.needs.HouseholdFoodSupplyGameTests",
            "com.jgeted.sagadyssey.social.needs.HouseholdStockReconciliationGameTests",
            "com.jgeted.sagadyssey.social.needs.HouseholdSupplyDestinationGameTests",
            "com.jgeted.sagadyssey.social.needs.NearFoodCycleGameTests",
            "com.jgeted.sagadyssey.social.needs.NearSelfSupplyGameTests",
            "com.jgeted.sagadyssey.social.needs.PickaxeBatchGameTests",
            "com.jgeted.sagadyssey.social.needs.PopulationHandoffGameTests",
            "com.jgeted.sagadyssey.social.needs.SeedResupplyGameTests",
            "com.jgeted.sagadyssey.social.needs.StockReconciliationGameTests",
            "com.jgeted.sagadyssey.social.needs.SupplyGameTests",
            "com.jgeted.sagadyssey.social.needs.ToolResupplyGameTests",
            "com.jgeted.sagadyssey.social.religion.ReligionGameTests",
            "com.jgeted.sagadyssey.social.religion.life.ChurchLifeGameTests",
            "com.jgeted.sagadyssey.worldsim.PersistentDataRecoveryGameTests",
            "com.jgeted.sagadyssey.worldsim.SettlementReliabilityProfileGameTests",
            "com.jgeted.sagadyssey.worldsim.lod.LodGameTests",
            "com.jgeted.sagadyssey.worldsim.lod.LodPerformanceGameTests"
    );
    private static Map<Identifier, Consumer<GameTestHelper>> cases = Map.of();
    private static Map<String, Consumer<ServerLevel>> setups = Map.of();
    private static Map<String, Consumer<ServerLevel>> teardowns = Map.of();

    @SuppressWarnings("unchecked")
    public static <T extends net.minecraft.world.level.block.entity.BlockEntity> T blockEntity(
            GameTestHelper helper, net.minecraft.core.BlockPos relative) {
        return (T) helper.getLevel().getBlockEntity(helper.absolutePos(relative));
    }
    private PortedGameTests() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void restoreTestWorld(ServerAboutToStartEvent event) throws IOException {
        String previousRun = System.getProperty("sagadyssey.gametest.restartFrom");
        if (previousRun == null || !(event.getServer() instanceof GameTestServer server)) return;
        Path source = Path.of(previousRun).toRealPath();
        Path destination = server.getWorldPath(LevelResource.ROOT).toRealPath();
        if (destination.startsWith(source) || source.startsWith(destination)
                || !Files.isRegularFile(source.resolve("level.dat"))) {
            throw new IOException("A separate, stopped GameTest world is required");
        }
        // The vanilla launcher resets its universe before this event. Restore the
        // snapshot before the server opens any region or mod saved-data files.
        try (var paths = Files.walk(source)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                Path relative = source.relativize(path);
                if (relative.toString().equals("session.lock")) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Unexpected link in GameTest world: " + path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @SubscribeEvent
    public static void codecs(RegisterEvent event) {
        event.register(Registries.TEST_INSTANCE_TYPE, TYPE, () -> JavaTest.CODEC);
        event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, TYPE, () -> JavaBatch.CODEC);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        String namespaces = System.getProperty("neoforge.enabledGameTestNamespaces", "");
        Set<String> allowed = new HashSet<>(Arrays.asList(namespaces.split(",")));
        allowed.remove("");
        var tests = new ArrayList<TestFunction>();
        var before = new HashMap<String, Consumer<ServerLevel>>();
        var after = new HashMap<String, Consumer<ServerLevel>>();
        for (String name : SUITES) {
            try {
                Class<?> suite = Class.forName(name, false, PortedGameTests.class.getClassLoader());
                Method[] methods = suite.getDeclaredMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName));
                for (Method method : methods) {
                    var test = method.getAnnotation(GameTest.class);
                    if (test != null) tests.add(fromMethod(method, test));
                    if (method.isAnnotationPresent(GameTestGenerator.class)) {
                        for (Object candidate : (Collection<?>) invoke(method)) tests.add((TestFunction) candidate);
                    }
                    var setup = method.getAnnotation(BeforeBatch.class);
                    if (setup != null) before.put(setup.batch(), level -> invoke(method, level));
                    var teardown = method.getAnnotation(AfterBatch.class);
                    if (teardown != null) after.put(teardown.batch(), level -> invoke(method, level));
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Missing development test suite " + name, e);
            }
        }
        setups = Map.copyOf(before);
        teardowns = Map.copyOf(after);
        var callbacks = new HashMap<Identifier, Consumer<GameTestHelper>>();
        var environments = new HashMap<String, Holder<TestEnvironmentDefinition>>();
        for (TestFunction test : tests) {
            Identifier structure = Identifier.parse(test.structureName());
            if (!allowed.isEmpty() && !allowed.contains(structure.getNamespace())) continue;
            Identifier id = Identifier.fromNamespaceAndPath(structure.getNamespace(), test.testName().toLowerCase(Locale.ROOT));
            if (callbacks.putIfAbsent(id, test.function()) != null) {
                throw new IllegalStateException("Duplicate game test " + id);
            }
            Holder<TestEnvironmentDefinition> env = environments.computeIfAbsent(test.batchName(), batch ->
                    event.registerEnvironment(Identifier.fromNamespaceAndPath("sagadyssey",
                            "batch/" + batch.toLowerCase(Locale.ROOT)), new JavaBatch(batch)));
            var data = new TestData<>(env, structure, test.maxTicks(), Math.toIntExact(test.setupTicks()),
                    test.required(), test.rotation(), test.manualOnly(), test.maxAttempts(),
                    test.requiredSuccesses(), test.skyAccess());
            event.registerTest(id, new JavaTest(id, data));
        }
        cases = Map.copyOf(callbacks);
    }

    private static TestFunction fromMethod(Method method, GameTest test) {
        Class<?> suite = method.getDeclaringClass();
        var prefix = method.getAnnotation(PrefixGameTestTemplate.class);
        if (prefix == null) prefix = suite.getAnnotation(PrefixGameTestTemplate.class);
        String start = prefix == null || prefix.value() ? suite.getSimpleName().toLowerCase(Locale.ROOT) + "." : "";
        String methodName = method.getName().toLowerCase(Locale.ROOT);
        var holder = suite.getAnnotation(GameTestHolder.class);
        String namespace = test.templateNamespace().isEmpty()
                ? (holder == null ? "minecraft" : holder.value()) : test.templateNamespace();
        String path = start + (test.template().isEmpty() ? methodName : test.template());
        return new TestFunction(test.batch(), start + methodName, namespace + ":" + path,
                Rotation.values()[Math.floorMod(test.rotationSteps(), 4)], test.timeoutTicks(), test.setupTicks(),
                test.required(), test.manualOnly(), test.attempts(), test.requiredSuccesses(), test.skyAccess(),
                helper -> invoke(method, helper));
    }

    private static Object invoke(Method method, Object... args) {
        try {
            method.setAccessible(true);
            Object instance = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                var constructor = method.getDeclaringClass().getDeclaredConstructor();
                constructor.setAccessible(true);
                instance = constructor.newInstance();
            }
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException(method.toString(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method.toString(), e);
        }
    }

    private record JavaBatch(String batch) implements TestEnvironmentDefinition {
        static final MapCodec<JavaBatch> CODEC = Codec.STRING.fieldOf("batch").xmap(JavaBatch::new, JavaBatch::batch);

        @Override
        public void setup(ServerLevel level) {
            var action = setups.get(batch);
            if (action != null) action.accept(level);
        }

        @Override
        public void teardown(ServerLevel level) {
            var action = teardowns.get(batch);
            if (action != null) action.accept(level);
        }

        @Override
        public MapCodec<JavaBatch> codec() {
            return CODEC;
        }
    }

    private static final class JavaTest extends GameTestInstance {
        static final MapCodec<JavaTest> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(test -> test.id),
                TestData.CODEC.forGetter(JavaTest::info)
        ).apply(instance, JavaTest::new));

        private final Identifier id;

        JavaTest(Identifier id, TestData<Holder<TestEnvironmentDefinition>> data) {
            super(data);
            this.id = id;
        }

        @Override
        public void run(GameTestHelper helper) {
            var callback = cases.get(id);
            if (callback == null) throw new IllegalStateException("Missing test callback " + id);
            callback.accept(helper);
        }

        @Override
        public MapCodec<JavaTest> codec() {
            return CODEC;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal("Sagadyssey Java test");
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GameTest {
        int timeoutTicks() default 100;
        String batch() default "defaultBatch";
        boolean skyAccess() default false;
        int rotationSteps() default 0;
        boolean required() default true;
        boolean manualOnly() default false;
        String templateNamespace() default "";
        String template() default "";
        long setupTicks() default 0L;
        int attempts() default 1;
        int requiredSuccesses() default 1;
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GameTestHolder { String value() default "minecraft"; }

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface PrefixGameTestTemplate { boolean value() default true; }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface GameTestGenerator {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface BeforeBatch { String batch(); }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AfterBatch { String batch(); }

    public record TestFunction(String batchName, String testName, String structureName, Rotation rotation,
            int maxTicks, long setupTicks, boolean required, boolean manualOnly, int maxAttempts,
            int requiredSuccesses, boolean skyAccess, Consumer<GameTestHelper> function) {
        public TestFunction(String batch, String name, String structure, int timeout, long setup, boolean required,
                Consumer<GameTestHelper> function) {
            this(batch, name, structure, Rotation.NONE, timeout, setup, required, function);
        }

        public TestFunction(String batch, String name, String structure, Rotation rotation, int timeout, long setup,
                boolean required, Consumer<GameTestHelper> function) {
            this(batch, name, structure, rotation, timeout, setup, required, false, 1, 1, false, function);
        }
    }
}
