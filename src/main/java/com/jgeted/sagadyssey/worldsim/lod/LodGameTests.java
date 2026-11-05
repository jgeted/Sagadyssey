package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class LodGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void read(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if(!(e.getServer() instanceof GameTestServer))return;previous=null;
        if(Boolean.getBoolean("sagadyssey.phase4.requireRestart"))try {previous=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("phase10-originals.nbt"),NbtAccounter.unlimitedHeap());}catch(Exception ex){throw new IllegalStateException("mandatory phase10 original migration checkpoint",ex);}
    }
    static NpcBase actor(ServerLevel level,BlockPos pos,NpcProfession role) {
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),level);n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);n.setProfession(role);n.getPersonalInventory().clearContent();n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);level.addFreshEntity(n);NpcLifeData.get(n.getServer()).ensureRegistered(n,level.getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    static void force(ServerLevel level,BlockPos pos,boolean forced) {for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){level.setChunkForced((pos.getX()>>4)+x,(pos.getZ()>>4)+z,forced);if(forced)level.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);}}
    static void floor(ServerLevel level,BlockPos pos,int radius){for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)level.setBlock(pos.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);}
    static ProductionOrder order(NpcBase n,BlockPos pos,int work) {
        var level=(ServerLevel)n.level();var boxPos=pos.offset(-1,0,0);var station=pos.offset(1,0,0);level.setBlockAndUpdate(boxPos,Blocks.BARREL.defaultBlockState());level.setBlockAndUpdate(station,Blocks.ANVIL.defaultBlockState());var box=(Container)level.getBlockEntity(boxPos);box.setItem(0,new ItemStack(Items.IRON_INGOT,3));box.setItem(1,new ItemStack(Items.COAL));var owner=SubjectRef.character(n.getCharacterId());var id=ProductionContainerIds.resolve(n.getServer(),"minecraft:overworld",boxPos);RightsStateData.get(n.getServer()).ensureContext(id,"phase10:real-material",owner,owner,AccessPolicy.PRIVATE);
        var d=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:test_iron_pickaxe",List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)),3),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)),1)),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)),1),work,UUID.randomUUID(),"minecraft:overworld",station,id,"minecraft:overworld",boxPos,level.getGameTime(),"stage10 real physical order");ProductionOrderData.get(n.getServer()).put(d);NpcBrainRuntime.createBlacksmithOrderPlan(n,d.id());return d;
    }
    @GameTest(template="fieldwork_empty",batch="phase10_unload",timeoutTicks=20000)
    public static void realChunkUnloadKeepsOriginalBodyAndCannotEarnOfflineForgeWork(GameTestHelper h) {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(30000,1,30000));force(level,pos,true);floor(level,pos,4);
        NpcBase[] body={null};ProductionOrder[] obligation={null};UUID[] planId={null};int[] phase={0},worked={0},hunger={0};long[] absent={0};
        h.succeedWhen(()->{
            var server=level.getServer();
            if(phase[0]==0) {h.assertTrue(level.isPositionEntityTicking(pos),"wait for genuine ticking original chunk");body[0]=actor(level,pos,NpcProfession.BLACKSMITH);obligation[0]=order(body[0],pos,400);phase[0]=1;}
            var n=body[0];var d=obligation[0];
            if(phase[0]==1) {
                h.assertTrue(ProductionLaborData.get(server).worked(d.id())>=30,"original body actually performs partial labor");
                n.setNoAi(true);worked[0]=ProductionLaborData.get(server).worked(d.id());hunger[0]=NpcLifeData.get(server).needs(n.getCharacterId()).hunger();planId[0]=NpcBrainRuntime.data(server).active(n.getUUID()).orElseThrow().id();force(level,pos,false);phase[0]=2;
            }
            if(phase[0]==2) {
                h.assertTrue(level.getEntity(n.getUUID())==null&&n.isRemoved(),"actual chunk unload, not discard or replacement fixture");
                var checkpoint=NpcLodData.get(server).find(n.getUUID());h.assertTrue(checkpoint!=null&&checkpoint.unloaded(),"L1 checkpoint retained original identity");absent[0]=level.getGameTime();phase[0]=3;
            }
            if(phase[0]==3) {
                h.assertTrue(level.getGameTime()-absent[0]>=410,"observe two genuine offline summary intervals");
                h.assertTrue(ProductionLaborData.get(server).worked(d.id())==worked[0],"offline time never counts as forge work");
                h.assertTrue(NpcBrainRuntime.data(server).active(n.getUUID()).orElseThrow().id().equals(planId[0]),"same pending plan, not replayed or fabricated");
                h.assertTrue(NpcLifeData.get(server).needs(n.getCharacterId()).hunger()>=hunger[0]+4,"L1 needs advance without creating food");force(level,pos,true);phase[0]=4;
            }
            if(phase[0]==4) {
                // GameTestServer runs unpaced. Yield only while the real asynchronous entity
                // region read is pending, rather than exhausting 1800 ticks in milliseconds.
                // Keep the original-body assertion: loaded empty/wrong chunks must not pass.
                boolean entitiesReady=level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(pos));
                if(!entitiesReady)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                h.assertTrue(entitiesReady,"wait for actual entity-region read, not only block-chunk availability");
                h.assertTrue(level.getEntity(n.getUUID()) instanceof NpcBase,"original body reloaded from chunk NBT");var restored=(NpcBase)level.getEntity(n.getUUID());
                h.assertTrue(restored!=n&&restored.getCharacterId().equals(n.getCharacterId()),"same original UUID/person; distinct deserialized object, not cloned character");
                h.assertTrue(NpcLodRuntime.loaded(restored),"checkpoint reconciles actual original body");h.assertTrue(NpcBrainRuntime.data(server).active(n.getUUID()).orElseThrow().id().equals(planId[0]),"resume same plan id");restored.setNoAi(false);body[0]=restored;phase[0]=5;
            }
            h.assertTrue(phase[0]==5&&ProductionOrderData.get(server).byId(d.id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"original obligation finishes after actual remaining work");
            var box=(Container)level.getBlockEntity(d.targetContainerPosition());int outputs=0;for(int i=0;i<box.getContainerSize();i++){var s=box.getItem(i);if(s.is(Items.IRON_PICKAXE))outputs+=s.getCount();h.assertTrue(!s.is(Items.IRON_INGOT)&&!s.is(Items.COAL),"real inputs consumed exactly once");}h.assertTrue(outputs==1,"one physical output across unload/reload");body[0].setNoAi(true);
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase10_conflict",timeoutTicks=100)
    public static void emptyEquipmentDoesNotRegenerateConstructorGearOnLoad(GameTestHelper h) {
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(2,1,2));n.setNoAi(true);n.getPersonalInventory().clearContent();for(var slot:net.minecraft.world.entity.EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
        var before=LodFingerprints.inventory(n);var saved=n.saveWithoutId(new CompoundTag());var decoded=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());decoded.load(saved);
        h.assertTrue(before.equals(LodFingerprints.inventory(decoded)),"empty omitted vanilla equipment lists must load as empty, not default spawn swords");n.discard();h.succeed();
    }
    @GameTest(template="fieldwork_empty",batch="phase10_conflict",timeoutTicks=100)
    public static void inconsistentUnloadedInventoryIsQuarantinedWithoutRestoringAStack(GameTestHelper h) {
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(2,1,2));n.setNoAi(true);NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);n.getPersonalInventory().clearContent();n.getPersonalInventory().setItem(0,new ItemStack(Items.COAL,2));h.assertTrue(NpcLodRuntime.loaded(n),"initial original checkpoint");NpcLodRuntime.unload(n,false);var digest=NpcLodData.get(n.getServer()).find(n.getUUID()).inventoryDigest();n.getPersonalInventory().setItem(0,new ItemStack(Items.COAL));
        h.assertTrue(!NpcLodRuntime.loaded(n),"conflict pauses execution");h.assertTrue(NpcLodRuntime.review(NpcLodData.get(n.getServer()).find(n.getUUID())),"backstage explicit review");h.assertTrue(n.getPersonalInventory().getItem(0).getCount()==1&&NpcLodData.get(n.getServer()).find(n.getUUID()).inventoryDigest().equals(digest),"no phantom stock restoration; old evidence retained");n.discard();h.succeed();
    }
    @GameTest(template="fieldwork_empty",batch="phase10_migration_save",timeoutTicks=2000)
    public static void dryRunActualLegacyMigrationAndSafeRollbackPreserveOriginals(GameTestHelper h) {
        if(Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.assertTrue(previous!=null,"must not overwrite original checkpoint on restart");h.succeed();return;}
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(34000,1,34000));force(level,pos,true);floor(level,pos,2);boolean[] done={false};
        h.succeedWhen(()->{h.assertTrue(level.isPositionEntityTicking(pos),"real original migration chunk ticking");if(done[0])return;
            var n=actor(level,pos,NpcProfession.BLACKSMITH);n.setNoAi(true);NpcBrainRuntime.setMode(n,NpcBehaviorMode.LEGACY);n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,3));var stack=n.getPersonalInventory().getItem(0);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Original legacy bread"));
            var targets=List.of(n);var inventory=LodFingerprints.inventory(n);var revision=LodMigration.dryRun(level.getServer(),targets);h.assertTrue(revision.equals(LodMigration.dryRun(level.getServer(),targets))&&NpcBrainRuntime.mode(n)==NpcBehaviorMode.LEGACY,"stable read-only dryrun, no mode change");
            var receipt=LodMigration.apply(level.getServer(),targets,revision);h.assertTrue(NpcBrainRuntime.isPlanned(n)&&LodFingerprints.inventory(n).equals(inventory)&&NpcBrainRuntime.data(n.getServer()).active(n.getUUID()).isEmpty(),"actual migration changes only compatibility mode/checkpoint, not items or plans");
            var report=LodMigrationData.get(n.getServer()).find(receipt);h.assertTrue(report.getCompound("BeforeCounts").equals(report.getCompound("AfterCounts"))&&report.getCompound("AfterCounts").getInt("ItemUnits")>=3,"durable before/after original people, items, orders and plans count report");
            LodMigration.rollback(level.getServer(),receipt);h.assertTrue(NpcBrainRuntime.mode(n)==NpcBehaviorMode.LEGACY&&LodFingerprints.inventory(n).equals(inventory),"safe inverse migration, no restoring copied assets");
            var applied=LodMigration.apply(level.getServer(),targets,LodMigration.dryRun(level.getServer(),targets));var tag=new CompoundTag();tag.putUUID("Body",n.getUUID());tag.putUUID("Person",n.getCharacterId());tag.putUUID("Migration",applied);tag.putString("Inventory",inventory);tag.putLong("Position",pos.asLong());
            try{NbtIo.writeCompressed(tag,level.getServer().getWorldPath(LevelResource.ROOT).resolve("phase10-originals.nbt"));level.getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}catch(Exception ex){throw new IllegalStateException(ex);}done[0]=true;
        });
    }
    @GameTest(template="fieldwork_empty",batch="phase10_migration_restart",timeoutTicks=2000)
    public static void independentJvmLoadsOriginalMigratedBodyAndJournalWithoutDuplicatingAssets(GameTestHelper h) {
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}var pos=BlockPos.of(previous.getLong("Position"));force(h.getLevel(),pos,true);
        h.succeedWhen(()->{var n=(NpcBase)h.getLevel().getEntity(previous.getUUID("Body"));h.assertTrue(n!=null&&n.getCharacterId().equals(previous.getUUID("Person")),"original migrated body/person from disk");h.assertTrue(NpcBrainRuntime.isPlanned(n)&&NpcLodRuntime.loaded(n),"persisted mode and original checkpoint reconcile in independent JVM");h.assertTrue(LodFingerprints.inventory(n).equals(previous.getString("Inventory")),"same slots, counts, equipment and item components");h.assertTrue(LodMigrationData.get(n.getServer()).find(previous.getUUID("Migration")).getString("State").equals("APPLIED"),"same durable migration receipt");h.assertTrue(NpcBrainRuntime.data(n.getServer()).active(n.getUUID()).isEmpty(),"NoAI original not silently executed or replaced");});
    }
    @GameTest(template="fieldwork_empty",batch="phase10_conflict",timeoutTicks=100)
    public static void diskReloadDoesNotRerollSpawnMountOrCreateLead(GameTestHelper h) {
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(2,1,2));n.setNoAi(true);
        n.setProfession(NpcProfession.WARRIOR);n.setCommand(NpcCommand.WORK);n.unbindMount();n.getPersonalInventory().clearContent();
        var before=LodFingerprints.inventory(n);double old=com.jgeted.sagadyssey.core.config.SagadysseyConfig.MOUNT_SPAWN_CHANCE.get();
        try {
            com.jgeted.sagadyssey.core.config.SagadysseyConfig.MOUNT_SPAWN_CHANCE.set(1.0);
            new com.jgeted.sagadyssey.vehicle.VehicleEventHandler().onNpcSpawn(
                    new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(n,h.getLevel(),true));
            h.assertTrue(!n.hasMount()&&before.equals(LodFingerprints.inventory(n)),"disk join cannot generate a horse, saddle or lead even at 100% spawn chance");
        } finally {com.jgeted.sagadyssey.core.config.SagadysseyConfig.MOUNT_SPAWN_CHANCE.set(old);}
        n.discard();h.succeed();
    }
    @GameTest(template="fieldwork_empty",batch="phase10_conflict",timeoutTicks=100)
    public static void ordinarySourceCannotAccessLodTruthOrMigration(GameTestHelper h) {
        var server=h.getLevel().getServer();var dispatcher=server.getCommands().getDispatcher();
        for(var command:List.of("saga lod status","saga lod migration dryrun @e")) {
            h.assertTrue(dispatcher.parse(command,server.createCommandSourceStack().withPermission(0)).getReader().canRead(),"ordinary player cannot access LOD checkpoints or migration");
            h.assertTrue(!dispatcher.parse(command,server.createCommandSourceStack().withPermission(2)).getReader().canRead(),"explicit tester access remains separate");
        }h.succeed();
    }
}
