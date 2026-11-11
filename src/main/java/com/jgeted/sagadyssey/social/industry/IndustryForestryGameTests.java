package com.jgeted.sagadyssey.social.industry;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import java.util.*;
import net.neoforged.neoforge.gametest.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;

/** One original stump, one finite starting seed, then real native growth; never bonemeal or block-result injection. */
@GameTestHolder("sagadyssey_industries") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class IndustryForestryGameTests {
    private static CompoundTag checkpoint;
    private static final String MARKER="industry-forest-checkpoint.nbt";
    private static String phase(){return System.getProperty("sagadyssey.industries.phase","");}
    private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind TICKS=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("sagadyssey-industry-forest");
    @GameTestGenerator public static Collection<TestFunction> tests() {
        if(!Boolean.getBoolean("sagadyssey.industries.enabled") || !phase().startsWith("forest"))return List.of();
        return List.of(new TestFunction("industry_forest",phase().equals("forest-start")?"industry.forest_checkpoint":"industry.forest_two_regrowths",
                "sagadyssey_industries:empty",30000,0,true,phase().equals("forest-start")?IndustryForestryGameTests::run:IndustryForestryGameTests::resume));
    }
    @net.neoforged.bus.api.SubscribeEvent public static void restore(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        checkpoint=null;if(!(e.getServer() instanceof GameTestServer) || !Boolean.getBoolean("sagadyssey.industries.enabled"))return;
        try{e.getServer().overworld().getStructureManager().getOrCreate(net.minecraft.resources.Identifier.parse("sagadyssey_industries:empty"))
                .load(e.getServer().overworld().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        TagParser.parseCompoundFully("{size:[96,10,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));
        }catch(Exception x){throw new IllegalStateException("industry template install failed",x);}
        if(!phase().equals("forest-resume"))return;
        try{
            var s=e.getServer();checkpoint=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
            var world=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            // Existing GameTest restart convention: saved game time +1, never real-world offline catch-up.
            s.getWorldData().overworldData().setGameTime(world.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(world.getLongOr("DayTime", 0L)+1);
            var rules=world.getCompoundOrEmpty("game_rules");
            if(rules.getIntOr("minecraft:random_tick_speed", -1) != 3 || rules.getBooleanOr("minecraft:advance_time", true)
                    || rules.getBooleanOr("minecraft:spawn_mobs", true))throw new IllegalStateException("source checkpoint violates frozen rules");
            // GameTestServer deliberately constructs fresh rules (randomTickSpeed=0), even on a saved world.
            // Restore the original frozen scenario, not a larger growth rate or a later completion deadline.
            s.overworld().getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
            s.overworld().getGameRules().set(GameRules.ADVANCE_TIME, false,s);
            s.overworld().getGameRules().set(GameRules.SPAWN_MOBS, false,s);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("INDUSTRY_FOREST_RULES_RESTORED randomTickSpeed=3 doDaylightCycle=false doMobSpawning=false sourceTime={}",world.getLongOr("Time", 0L));
            force(s.overworld(),BlockPos.of(checkpoint.getLongOr("Root", 0L)));
        }catch(Exception x){throw new IllegalStateException("closed complete forest world checkpoint required",x);}
    }
    private static void force(net.minecraft.server.level.ServerLevel l,BlockPos root){
        var cp=new ChunkPos(root);
        for(int x=cp.x-1;x<=cp.x+1;x++)for(int z=cp.z-1;z<=cp.z+1;z++){
            var chunk=new ChunkPos(x,z);l.setChunkForced(x,z,true);l.getChunk(x,z);com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), TICKS,chunk,2,chunk,true);
        }
    }
    private static void run(GameTestHelper h) {
        var l=h.getLevel();var s=l.getServer();String dim=l.dimension().identifier().toString();long now=l.getGameTime();
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, false,s);
        l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
        force(l,h.absolutePos(new BlockPos(8198,1,8198)));
        for(int x=0;x<20;x++)for(int z=0;z<12;z++)h.setBlock(x+8192,0,z+8192,Blocks.STONE);
        var root=h.absolutePos(new BlockPos(8198,1,8198));h.setBlock(8198,0,8198,Blocks.GRASS_BLOCK);h.setBlock(8198,1,8198,Blocks.OAK_LOG);
        for(int x=4;x<=8;x++)for(int z=4;z<=8;z++)h.setBlock(x+8192,2,z+8192,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE,1));
        h.setBlock(8205,1,8198,Blocks.BARREL);var storage=h.absolutePos(new BlockPos(8205,1,8198));
        var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(8194,1,8198));n.setProfession(NpcProfession.WORKER);n.setCommand(NpcCommand.WORK);
        n.setPersistenceRequired();n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();
        n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_AXE));n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.getPersonalInventory().setItem(0,new ItemStack(Items.OAK_SAPLING,1));
        n.getPersonalInventory().setItem(1,new ItemStack(Items.DIRT,32));n.getPersonalInventory().setItem(2,new ItemStack(Items.BREAD,8));
        n.setWorkZone(h.absolutePos(new BlockPos(8195,1,8195)),h.absolutePos(new BlockPos(8202,10,8202)));
        var life=NpcLifeData.get(s);life.ensureRegistered(n,now);h.assertTrue(life.assignPrivateStorage(s,n.getCharacterId(),dim,storage,now),"owned store configured");
        var site=SettlementRegistryData.get(s).create(s,"finite forest plot",SettlementType.VILLAGE,dim,root,32,"sagadyssey:kingdom",n.getCharacterId(),now);
        var home=HouseholdData.get(s).create("forester",dim,site.id(),n.getCharacterId(),storage,now);
        h.assertTrue(home!=null,"original identity linked to real settlement and household");
        var owner=SubjectRef.character(n.getCharacterId());var rights=RightsStateData.get(s);var bag=ContainerIds.npcPersonal(n.getCharacterId());
        rights.ensureContext(bag,"npc:"+n.getCharacterId()+":personal",owner,owner,AccessPolicy.PRIVATE);
        var id=ProductionContainerIds.resolve(s,dim,storage);rights.ensureContext(id,dim+":"+storage.asLong(),owner,owner,AccessPolicy.PRIVATE);
        var work=FieldworkData.get(s);work.bindTestStorage(n.getUUID(),new FieldworkStorage(dim,storage,id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"industry-forest-fixture"));
        NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);
        try{s.getCommands().getDispatcher().execute("tick sprint 31000",s.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}
        h.succeedWhen(()->{
            h.assertTrue(work.receiptsFor(n.getUUID()).stream().anyMatch(r->r.action().operation()==FieldworkAction.Operation.PLANT && r.action().target().equals(root))
                    && l.getBlockState(root).is(Blocks.OAK_SAPLING),"normal scheduler planted original stump before checkpoint");
            try{
                var mark=new CompoundTag();mark.store("Body", UUIDUtil.CODEC,n.getUUID());mark.store("Person", UUIDUtil.CODEC,n.getCharacterId());
                mark.store("Home", UUIDUtil.CODEC,home.id());mark.store("Site", UUIDUtil.CODEC,site.id());mark.putLong("Root",root.asLong());mark.putLong("Storage",storage.asLong());
                mark.putLong("StartedAt",now);mark.putLong("CheckpointTime",l.getGameTime());mark.put("ObservedBody",com.jgeted.sagadyssey.core.MinecraftApi.saveEntity(n));
                NbtIo.writeCompressed(mark,s.getWorldPath(LevelResource.ROOT).resolve(MARKER));
                s.saveEverything(true,false,true);
                
                com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("INDUSTRY_FOREST_CHECKPOINT time={} person={} body={} initialSeed=1 normalPlant=true",l.getGameTime(),n.getCharacterId(),n.getUUID());
            }catch(Exception x){throw new IllegalStateException(x);}
        });
    }
    private static void resume(GameTestHelper h){
        if(checkpoint==null)throw new IllegalStateException("missing full prior-process world");
        var l=h.getLevel();var s=l.getServer();var root=BlockPos.of(checkpoint.getLongOr("Root", 0L));var storage=BlockPos.of(checkpoint.getLongOr("Storage", 0L));
        try{s.getCommands().getDispatcher().execute("tick sprint 31000",s.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception x){throw new IllegalStateException(x);}
        h.succeedWhen(()->{
            h.assertTrue(l.getGameTime()-checkpoint.getLongOr("StartedAt", 0L)<=30000,"original 30000 tick total bound, including before restart");
            var entity=l.getEntity(checkpoint.read("Body", UUIDUtil.CODEC).orElseThrow());h.assertTrue(entity instanceof NpcBase,"original body loaded, never replaced");var n=(NpcBase)entity;
            h.assertTrue(n.getCharacterId().equals(checkpoint.read("Person", UUIDUtil.CODEC).orElseThrow()),"same persistent person");
            var home=HouseholdData.get(s).forMember(n.getCharacterId());h.assertTrue(home!=null && home.id().equals(checkpoint.read("Home", UUIDUtil.CODEC).orElseThrow()) && home.settlementId().equals(checkpoint.read("Site", UUIDUtil.CODEC).orElseThrow()),"same household and settlement after full restart");
            var work=FieldworkData.get(s);
            var receipts=work.receiptsFor(n.getUUID());
            long planted=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.PLANT && r.action().target().equals(root)).count();
            long roots=receipts.stream().filter(r->r.action().operation()==FieldworkAction.Operation.CHOP && r.action().target().equals(root)
                    && r.action().expectedState().contains("minecraft:oak_log")).count();
            h.assertTrue(planted>=2 && roots>=3,"two native regrowths harvested after finite initial stump; plants="+planted+" roots="+roots);
            int producedSeeds=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().startsWith("minecraft:oak_sapling") && d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            h.assertTrue(producedSeeds>=1,"second planting has a real produced seed, not a second initial seed");
            var box=(Container)l.getBlockEntity(storage);int stored=0;for(int i=0;i<box.getContainerSize();i++)if(box.getItem(i).is(Items.OAK_LOG))stored+=box.getItem(i).getCount();
            h.assertTrue(stored>=3 && !FieldworkDepositTaskExecutor.hasDeliverableCargo(n,work),"original worker actually deposits products; protected seed may remain");
            int producedLogs=receipts.stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().startsWith("minecraft:oak_log") && d.quantity()>0).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();
            h.assertTrue(stored+n.getPersonalInventory().count(i->i.is(Items.OAK_LOG))==producedLogs,"exact produced log location balance");
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("INDUSTRY_FOREST_ACCEPT tick={} person={} body={} home={} site={} root={} plants={} rootCuts={} producedSeeds={} logs={} initialSeed=1 nativeRandomTick=3",
                    l.getGameTime(),n.getCharacterId(),n.getUUID(),home.id(),home.settlementId(),root,planted,roots,producedSeeds,producedLogs);
        });
    }
}
