package com.jgeted.sagadyssey.social.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 静态依赖门禁，不代替命令、恢复或世界运行测试。 */
class WarArchitectureTest {
    private static Path warSourceRoot() {
        // NeoForge runs JUnit in a child run directory; Console/IDE may use the project root.
        for (Path directory = Path.of("").toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path source = directory.resolve("src/main/java/com/jgeted/sagadyssey/social/war");
            if (Files.isDirectory(source)) return source;
        }
        throw new IllegalStateException("war architecture checks require the project checkout or a child working directory");
    }

    @Test void theCoreAndSchedulerDoNotImportMinecraftOrCivilianImplementations() throws Exception {
        Path root = warSourceRoot();
        var imports = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([^;]+);");
        for (String layer : List.of("api", "domain", "application", "runtime", "spi", "feature")) {
            try (var paths = Files.walk(root.resolve(layer))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    var matcher = imports.matcher(source);
                    while (matcher.find()) {
                        String dependency = matcher.group(1);
                        assertTrue(dependency.startsWith("java.")
                                || dependency.startsWith("com.jgeted.sagadyssey.social.war."),
                                () -> path + " depends on " + dependency);
                    }
                    assertFalse(source.contains("net.minecraft."), path.toString());
                    assertFalse(source.contains("net.neoforged."), path.toString());
                    assertFalse(source.contains("com.jgeted.sagadyssey.social.war.adapter."), path.toString());
                }
            }
        }
    }

    @Test void domainTypesCannotDependOnApplicationRuntimeOrAdapters() throws Exception {
        Path root = warSourceRoot();
        for (String layer : List.of("api", "domain")) {
            try (var paths = Files.walk(root.resolve(layer))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    for (String forbidden : List.of(".application.", ".adapter.", ".runtime.", ".feature.")) {
                        assertFalse(source.contains("com.jgeted.sagadyssey.social.war" + forbidden), path.toString());
                    }
                    if (layer.equals("api")) {
                        assertFalse(source.contains("com.jgeted.sagadyssey.social.war.domain."), path.toString());
                    }
                }
            }
        }
    }

    @Test void useCasesCannotDependOnAssemblyFeaturesOrAdapters() throws Exception {
        Path root = warSourceRoot().resolve("application");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbidden : List.of(".adapter.", ".runtime.", ".feature.")) {
                    assertFalse(source.contains("com.jgeted.sagadyssey.social.war" + forbidden), path.toString());
                }
            }
        }
    }

    @Test void serviceProviderContractsDoNotDependOnUseCases() throws Exception {
        try (var paths = Files.walk(warSourceRoot().resolve("spi"))) {
            for (Path path : paths.filter(WarArchitectureTest::productionJava).toList()) {
                assertFalse(Files.readString(path).contains("com.jgeted.sagadyssey.social.war.application."), path.toString());
            }
        }
    }
    @Test void fieldOrchestrationDoesNotOwnNativeWeaponClassesNbtOrAttackCalls() throws Exception {
        var root = warSourceRoot(); var field = Files.readString(root.resolve("adapter/minecraft/WarFieldRuntime.java"));
        for (String forbidden : List.of("net.minecraft.nbt", "SwordItem", "ArmorItem", "AbstractArrow", "ProjectileUtil", "NpcAmmunitionService", "getPersistentData(", ".doHurtTarget(", ".moveTo(", "new WarDispatchJournal("))
            assertFalse(field.contains(forbidden), forbidden);
        var equipment = Files.readString(root.resolve("adapter/minecraft/WarEquipmentInspector.java"));
        for (String forbidden : List.of("SwordItem", "ArmorItem", "BowItem", "CrossbowItem", "AbstractHorse")) assertFalse(equipment.contains(forbidden), forbidden);
    }

    @Test void sharedNpcAndWorldSystemsDoNotDependOnMilitaryImplementations() throws Exception {
        Path war = warSourceRoot();
        Path root = war.getParent().getParent();
        for (String layer : List.of("npc", "social", "worldsim")) {
            try (var paths = Files.walk(root.resolve(layer))) {
                for (Path path : paths.filter(WarArchitectureTest::productionJava).filter(p -> !p.startsWith(war)).toList()) {
                    assertFalse(Files.readString(path).contains("com.jgeted.sagadyssey.social.war."), path.toString());
                }
            }
        }
    }

    @Test void gameVersionImplementationsAreSelectedByNativeAssembly() throws Exception {
        Path adapter = warSourceRoot().resolve("adapter");
        var versionNamespace = Pattern.compile("com\\.jgeted\\.sagadyssey\\.social\\.war\\.adapter\\.minecraft\\.v\\d+_");
        try (var paths = Files.walk(adapter)) {
            for (Path path : paths.filter(WarArchitectureTest::productionJava).toList()) {
                if (path.getFileName().toString().equals("WarNativeBindings.java")
                        || path.startsWith(adapter.resolve("minecraft/v1_21_11"))) continue;
                assertFalse(versionNamespace.matcher(Files.readString(path)).find(), path.toString());
            }
        }
    }

    private static boolean productionJava(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") && !name.endsWith("GameTests.java");
    }
}
