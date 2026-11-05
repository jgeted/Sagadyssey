package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.npc.brain.persistence.NpcBrainData;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

@GameTestHolder("sagadyssey_stock")
@PrefixGameTestTemplate(false)
public final class PersistentDataRecoveryGameTests {
    @GameTest(template = "fieldwork_empty", batch = "ledger_recovery", timeoutTicks = 40)
    public static void unreadableLedgerCannotBeReplacedByEmptyWritableData(GameTestHelper helper) throws Exception {
        verifyRetainedFile(helper, ".dat");
    }

    @GameTest(template = "fieldwork_empty", batch = "ledger_recovery", timeoutTicks = 40)
    public static void retainedBackupPreventsSilentEmptyInitialization(GameTestHelper helper) throws Exception {
        verifyRetainedFile(helper, ".dat_old");
    }

    @GameTest(template = "fieldwork_empty", batch = "ledger_recovery", timeoutTicks = 40)
    public static void genuinelyMissingLedgerCanStillBeCreated(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer, "isolated test server required");
        String name = "review_missing_" + UUID.randomUUID();
        var first = PersistentDataAccess.getOrCreate(server, NpcBrainData.FACTORY, name);
        var second = PersistentDataAccess.getOrCreate(server, NpcBrainData.FACTORY, name);
        helper.assertTrue(first == second, "new ledger is installed exactly once");
        helper.succeed();
    }

    private static void verifyRetainedFile(GameTestHelper helper, String suffix) throws Exception {
        var server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer, "isolated test server required");
        String name = "review_unreadable_" + UUID.randomUUID();
        var directory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Files.createDirectories(directory);
        var path = directory.resolve(name + suffix);
        var invalidData = new CompoundTag();
        invalidData.putInt("Schema", Integer.MAX_VALUE);
        invalidData.putString("OriginalEvidence", "must remain byte-for-byte intact");
        var root = new CompoundTag();
        root.put("data", invalidData);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        NbtIo.writeCompressed(root, path);
        byte[] original = Files.readAllBytes(path);

        for (int attempt = 0; attempt < 2; attempt++) {
            boolean rejected = false;
            try {
                PersistentDataAccess.getOrCreate(server, NpcBrainData.FACTORY, name);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "unreadable existing data must not create an empty ledger");
        }
        server.overworld().getDataStorage().save();
        helper.assertTrue(Arrays.equals(original, Files.readAllBytes(path)),
                "saving other ledgers must preserve the original unreadable file");
        if (suffix.equals(".dat_old"))
            helper.assertTrue(!Files.exists(directory.resolve(name + ".dat")),
                    "a retained backup must not be shadowed by a new empty file");
        helper.succeed();
    }
}
