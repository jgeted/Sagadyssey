package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.npc.brain.blacksmith.BlacksmithRestPlanFactory;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class BlacksmithRestRestartGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent
    public static void readCheckpoint(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!(e.getServer() instanceof GameTestServer)) return;
        previous = null;
        if (!Boolean.getBoolean("sagadyssey.phase4.requireRestart")) return;
        try {
            previous = NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("phase9-rest-checkpoint.nbt"), NbtAccounter.unlimitedHeap());
        } catch (Exception ex) { throw new IllegalStateException("mandatory original blacksmith rest checkpoint", ex); }
    }

    @GameTest(template="fieldwork_empty", batch="phase9_rest_checkpoint", timeoutTicks=2000)
    public static void saveOriginalRestingSmithWithNoInventedFacilities(GameTestHelper h) {
        if (Boolean.getBoolean("sagadyssey.phase4.requireRestart")) { h.assertTrue(previous != null, "must retain original checkpoint, not substitute smith"); h.succeed(); return; }
        var pos = h.absolutePos(new BlockPos(21000, 1, 21000)); force(h.getLevel(), pos);
        for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) h.getLevel().setBlock(pos.offset(x,-1,z), Blocks.STONE.defaultBlockState(), 3);
        NpcBase[] actor = {null};
        h.succeedWhen(() -> {
            if (actor[0] == null) {
                h.assertTrue(h.getLevel().isPositionEntityTicking(pos), "original chunk must actually tick before spawning");
                var n = new NpcBase(NpcEntityTypes.NPC_BASE.get(), h.getLevel());
                n.setPos(pos.getX()+.5, pos.getY(), pos.getZ()+.5); n.setProfession(NpcProfession.BLACKSMITH);
                n.getPersonalInventory().clearContent(); n.setPersistenceRequired(); n.setCommand(NpcCommand.WORK);
                h.getLevel().addFreshEntity(n); NpcLifeData.get(n.getServer()).ensureRegistered(n,h.getLevel().getGameTime());
                NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED); actor[0] = n;
            }
            var n = actor[0];
            h.assertTrue(NpcBrainRuntime.data(n.getServer()).active(n.getUUID()).isPresent(), "wait for actual entity goal to select rest");
            var rest = NpcBrainRuntime.data(n.getServer()).active(n.getUUID()).orElseThrow();
            h.assertTrue(rest.providerId().equals(BlacksmithRestPlanFactory.PROVIDER), "original smith actually selected rest");
            n.setNoAi(true);
            var tag = new CompoundTag(); tag.putUUID("Body",n.getUUID()); tag.putUUID("Character",n.getCharacterId());
            tag.putUUID("Plan",rest.id()); tag.putLong("Position",pos.asLong()); tag.putLong("Deadline",rest.expiresAt());
            try {
                NbtIo.writeCompressed(tag,n.getServer().getWorldPath(LevelResource.ROOT).resolve("phase9-rest-checkpoint.nbt"));
                n.getServer().saveEverything(true,true,true); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            } catch (Exception ex) { throw new IllegalStateException("save original resting smith",ex); }
        });
    }

    @GameTest(template="fieldwork_empty", batch="phase9_rest_restart", timeoutTicks=400)
    public static void independentProcessRecoversOriginalRestWithoutImmortalWaitOrAssets(GameTestHelper h) {
        if (!Boolean.getBoolean("sagadyssey.phase4.requireRestart")) { h.succeed(); return; }
        h.assertTrue(previous != null,"original checkpoint required"); force(h.getLevel(),BlockPos.of(previous.getLong("Position")));
        boolean[] resumed = {false}; long[] resumedAt = {-1};
        h.succeedWhen(() -> {
            var n = (NpcBase) h.getLevel().getEntity(previous.getUUID("Body"));
            h.assertTrue(n != null && n.getCharacterId().equals(previous.getUUID("Character")),"original saved body and person, no clones");
            var brain = NpcBrainRuntime.data(n.getServer());
            if (!resumed[0]) {
                var saved = brain.active(n.getUUID()).orElseThrow();
                h.assertTrue(saved.id().equals(previous.getUUID("Plan")) && saved.expiresAt()==previous.getLong("Deadline"),"same rest identity and deadline from disk, no extension during offline time");
                n.setNoAi(false); resumed[0] = true; resumedAt[0] = h.getTick();
            }
            h.assertTrue(h.getTick()-resumedAt[0]>=220,"observe more than two bounded rests after new JVM");
            h.assertTrue(brain.active(n.getUUID()).isPresent(), "next tick selects the next bounded rest");
            var active = brain.active(n.getUUID()).orElseThrow();
            h.assertTrue(active.providerId().equals(BlacksmithRestPlanFactory.PROVIDER) && !active.id().equals(previous.getUUID("Plan")) && active.expiresAt()>h.getLevel().getGameTime(),"old rest ended, revalidated finite rest replaced it");
            h.assertTrue(brain.history(n.getUUID()).size()<=32 && n.getPersonalInventory().isEmpty(),"bounded history and no free food, equipment or output across restart");
            n.setNoAi(true);
        });
    }

    private static void force(ServerLevel level, BlockPos pos) {
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) { level.setChunkForced((pos.getX()>>4)+x,(pos.getZ()>>4)+z,true); level.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z); }
    }
}
