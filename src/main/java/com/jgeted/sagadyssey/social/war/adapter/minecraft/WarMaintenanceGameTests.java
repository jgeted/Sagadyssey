package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.*;
import com.jgeted.sagadyssey.gametest.PortedGameTests.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.InjuryData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarDispatchJournal;
import com.jgeted.sagadyssey.social.war.api.WarFieldReport;
import com.jgeted.sagadyssey.social.war.application.WarDispatchService;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;

import java.nio.file.*;
import java.util.*;

@GameTestHolder("sagadyssey_war_maintenance")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class WarMaintenanceGameTests {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ARMY = "Outpost", UNIT = "Watch";
    private static final String SAVED = "war-maintenance-save.json";

    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.maintenance.test"); }
    private static String phase() { return System.getProperty("sagadyssey.war.maintenance.phase","park"); }
    private static boolean archive() { return phase().startsWith("archive-"); }
    private static boolean save() { return Set.of("save","archive-save").contains(phase()); }
    private static boolean restore() { return Set.of("resume","archive-resume","archive-missing").contains(phase()); }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void started(ServerStartedEvent event) throws Exception {
        if (!enabled() || !(event.getServer() instanceof GameTestServer)) return;
        var server = event.getServer();
        if (restore()) {
            var saved = read(root(server).resolve(SAVED));
            server.getWorldData().overworldData().setGameTime(saved.get("gameTime").getAsLong());
            server.getWorldData().overworldData().setDayTime(saved.get("dayTime").getAsLong());
        }
        var level = server.overworld();
        level.setDayTime(1000);
        level.getGameRules().set(GameRules.ADVANCE_TIME,false,server);
        level.getGameRules().set(GameRules.SPAWN_MOBS,false,server);
        level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_war_maintenance:empty"))
                .load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[1,1,1],entities:[],blocks:[],palette:[{Name:'minecraft:air'}]}"));
    }

    @SubscribeEvent
    public static void aboutToStart(ServerAboutToStartEvent event) throws Exception {
        if (!enabled() || !phase().equals("archive-missing")) return;
        var folder = root(event.getServer()).resolve("data/" + WarDispatchJournal.ARCHIVE_DIRECTORY);
        var proof = read(root(event.getServer()).resolve(SAVED));
        var digest = proof.get("archiveHead").getAsString();
        if (!digest.matches("[a-f0-9]{64}")) throw new IllegalStateException("invalid fixture digest");
        Files.move(folder.resolve(digest+".sgw.gz"),folder.resolve(digest+".sgw.gz.test-retained"));
    }

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return enabled() ? List.of(new TestFunction("war_maintenance",phase(),"sagadyssey_war_maintenance:empty",
                12000,0,true,restore()?WarMaintenanceGameTests::restored:WarMaintenanceGameTests::fresh)) : List.of();
    }

    private static final class Scene {
        final GameTestHelper h;
        final MinecraftServer server;
        final BlockPos site;
        final List<NpcBase> people = new ArrayList<>();
        final List<UUID> bodies = new ArrayList<>(), personIds = new ArrayList<>(), services = new ArrayList<>(), orders = new ArrayList<>();
        ServerPlayer lord;
        UUID actor, army, unit;
        GameProfile profile;
        WarTransmission first;
        long firstArchiveCount;
        Scene(GameTestHelper h, BlockPos site) { this.h=h;this.server=h.getLevel().getServer();this.site=site; }
        BlockPos at(int x,int y,int z) { return site.offset(x,y,z); }
        WarServerRuntime core() { return WarServerEvents.existing(server).orElseThrow(); }
        WarFieldRuntime field() { return core().field().orElseThrow(); }
        WarMusterRuntime muster() { return WarMusterRuntime.existing(server).orElseThrow(); }
        long now() { return h.getLevel().getGameTime(); }
        void command(String v) { WarScaleGameTests.command(h,lord,v); }
    }

    private static void fresh(GameTestHelper h) {
        var s = new Scene(h,h.absolutePos(BlockPos.ZERO).offset(3072,12,0));
        force(s,true);
        for (int x=0;x<17;x++) for (int z=0;z<17;z++) h.getLevel().setBlockAndUpdate(s.at(x,0,z),Blocks.STONE.defaultBlockState());
        s.lord = WarMusterGameTests.battlePlayer(h,"OutpostLord",BlockPos.ZERO);
        s.profile = s.lord.getGameProfile();
        s.actor = CharacterRegistry.currentId(s.lord);
        s.army = WarMusterEntry.armyId(s.actor,ARMY);
        s.unit = WarMusterEntry.unitId(s.army,UNIT);
        s.lord.getInventory().setItem(3,new ItemStack(Items.EMERALD,32));
        for (int i=0;i<(archive()?1:2);i++) spawn(s,i);
        s.command("saga war muster create " + ARMY);
        var q = h.startSequence().thenWaitUntil(() -> h.assertTrue(s.muster().book().owns(s.actor,s.army),"original army durable"))
                .thenExecute(() -> s.command("saga war muster unit " + ARMY + " " + UNIT + " sword"))
                .thenWaitUntil(() -> h.assertTrue(s.muster().book().unit(s.unit).filter(u -> u.recorded() && s.muster().book().durable(u)).isPresent(),"original unit durable"));
        for (var soldier : s.people) {
            int i = s.people.indexOf(soldier);
            q.thenExecute(() -> s.services.add(WarMusterGameTests.battleOffer(h,s.lord,soldier,ARMY,UNIT)))
                    .thenWaitUntil(() -> h.assertTrue(s.services.size()>i && s.muster().book().offer(s.services.get(i))
                            .filter(o -> o.stage()==WarMusterEntry.Stage.ACTIVE && s.muster().book().durable(o)).isPresent(),"original service active"));
        }
        q.thenExecute(() -> issue(s)).thenWaitUntil(() -> heldStation(s));
        if (archive()) archive(s,q);
        else far(s,q);
    }

    private static void far(Scene s, GameTestSequence q) {
        var h=s.h;
        q.thenExecute(() -> s.command("saga war far prepare " + ARMY + " " + UNIT + " " + (phase().equals("recall") || save() ? 600 : 120)))
                .thenWaitUntil(() -> checkPhase(s,WarFarReadiness.Phase.ARMED));
        if (phase().equals("cancel")) {
            q.thenExecute(() -> {
                h.assertTrue(s.field().far().orElseThrow().holds(s.unit),"armed original unit fenced");
                s.command("saga war far cancel " + ARMY + " " + UNIT);
            }).thenWaitUntil(() -> checkPhase(s,WarFarReadiness.Phase.CANCELLED))
                    .thenWaitUntil(() -> heldStation(s))
                    .thenExecute(() -> capture(s,"CANCELLED",Map.of())).thenSucceed();
            return;
        }
        q.thenExecute(() -> {
            h.assertTrue(s.field().far().orElseThrow().holds(s.unit),"native actions fenced before departing");
            s.server.getPlayerList().remove(s.lord);
            force(s,false);
        }).thenWaitUntil(() -> {
            assertUnloaded(s);
            checkPhase(s,WarFarReadiness.Phase.OFFLINE);
        });
        if (save()) {
            q.thenExecute(() -> capture(s,"OFFLINE",Map.of())).thenSucceed();
            return;
        }
        if (!phase().equals("recall")) q.thenWaitUntil(() -> {
            assertUnloaded(s);
            checkPhase(s,WarFarReadiness.Phase.PREPARED);
            h.assertTrue(plan(s).fitCount()==s.people.size(),"readiness uses actual original living records");
        });
        returnFromFar(s,q,phase().equals("recall")?0:s.people.size());
    }

    private static void returnFromFar(Scene s,GameTestSequence q,int fit) {
        q.thenExecute(() -> {
            s.h.assertTrue(InjuryData.get(s.server).wounds().isEmpty(),"offline preparation generates no combat injury");
            force(s,true);
            s.lord=WarMusterGameTests.restoreBattlePlayer(s.h,s.profile,s.at(8,1,13));
        }).thenWaitUntil(() -> {
            loadPeople(s);
            checkPhase(s,WarFarReadiness.Phase.RETURNED);
            s.h.assertTrue(!s.field().far().orElseThrow().holds(s.unit) && plan(s).fitCount()==fit,"durable original-body handoff");
        }).thenWaitUntil(() -> heldStation(s))
                .thenExecute(() -> {
                    s.h.assertTrue(s.people.stream().allMatch(n -> n.getPersonalInventory().countItem(Items.BREAD)==16 && WorkCommitmentRuntime.blocksCivil(n)),
                            "same original bread and original labor after return");
                    capture(s,"RETURNED",Map.of());
                }).thenSucceed();
    }

    private static void archive(Scene s,GameTestSequence q) {
        q.thenExecute(() -> s.first=s.field().transmission(s.actor,s.army,s.orders.getFirst()).orElseThrow());
        for (int i=1;i<132;i++) queueOrder(s,q);
        q.thenWaitUntil(() -> s.h.assertTrue(history(s).coldRecords()>=32 && history(s).hotRecords()<132,"first compacted dispatch page"))
                .thenExecute(() -> s.firstArchiveCount = history(s).coldRecords());
        for(int i=0;i<42;i++) queueOrder(s,q);
        q.thenWaitUntil(() -> s.h.assertTrue(history(s).coldRecords()>=s.firstArchiveCount+32 && history(s).problem().isEmpty()
                && history(s).hotRecords()+history(s).coldRecords()==s.orders.size(), "second chained compacted dispatch page confirmed by the execution index"));
        q.thenExecute(() -> {
            var before=history(s);
            var replay=s.field().dispatch(s.first.command());
            s.h.assertTrue(replay.outcome()==WarDispatchService.Outcome.REVIEW && history(s).equals(before),"hot retirement never creates a replacement transmission");
            s.h.assertTrue(s.field().transmission(s.actor,s.army,s.first.command().operationId()).isEmpty(),"archived command absent from execution index");
            capture(s,"COMPACTED",Map.of("hot",before.hotRecords(),"cold",before.coldRecords(),"oldReplay",replay.outcome().name()));
        }).thenSucceed();
    }

    private static void queueOrder(Scene s,GameTestSequence q) {
        q.thenExecute(() -> issue(s)).thenWaitUntil(() -> s.h.assertTrue(s.field().received(s.unit,s.now())
                .filter(t -> t.command().operationId().equals(s.orders.getLast())).isPresent(),"next original delivery confirmed"));
    }

    private static void restored(GameTestHelper h) {
        try {
            var source=read(root(h.getLevel().getServer()).resolve(SAVED));
            var s=new Scene(h,BlockPos.of(source.get("site").getAsLong()));
            h.assertTrue(source.get("process").getAsLong()!=ProcessHandle.current().pid(),"independent JVM");
            s.profile=new GameProfile(uuid(source,"account"),source.get("name").getAsString());
            s.actor=uuid(source,"actor"); s.army=uuid(source,"army"); s.unit=uuid(source,"unit");
            for(var entry:Map.of("bodies",s.bodies,"people",s.personIds,"services",s.services,"orders",s.orders).entrySet())
                for(var v:source.getAsJsonArray(entry.getKey()))entry.getValue().add(UUID.fromString(v.getAsString()));
            var q=h.startSequence();
            if (phase().equals("archive-missing")) {
                q.thenWaitUntil(() -> h.assertTrue(WarMusterRuntime.existing(s.server).filter(m -> m.problem().contains("REVIEW")).isPresent()
                        && WarServerEvents.existing(s.server).flatMap(WarServerRuntime::field).filter(f -> f.blockedReason().contains("REVIEW")).isPresent(),
                        "missing referenced cold page fences native dispatch and preserves hot records"))
                        .thenExecute(() -> capture(s,"ARCHIVE_REVIEW",Map.of("archiveHead",source.get("archiveHead").getAsString()))).thenSucceed();
            } else if (phase().equals("archive-resume")) {
                force(s,true);
                s.lord=WarMusterGameTests.restoreBattlePlayer(h,s.profile,s.at(8,1,13));
                q.thenWaitUntil(() -> {
                    loadPeople(s);
                    h.assertTrue(s.field().received(s.unit,s.now()).filter(t -> t.command().operationId().equals(s.orders.getLast())).isPresent()
                            && history(s).coldRecords()==source.get("cold").getAsLong()
                            && history(s).hotRecords()==source.get("hot").getAsInt(),"same durable archive head and original current order");
                }).thenWaitUntil(() -> heldStation(s)).thenExecute(() -> {
                    capture(s,"ARCHIVE_RESUMED",Map.of("hot",history(s).hotRecords(),"cold",history(s).coldRecords()));
                }).thenSucceed();
            } else {
                q.thenWaitUntil(() -> {
                    assertUnloaded(s);
                    checkPhase(s,WarFarReadiness.Phase.PREPARED);
                    h.assertTrue(plan(s).operation().equals(uuid(source,"operation")) && plan(s).departedAt()==source.get("departedAt").getAsLong()
                            && plan(s).fitCount()==s.bodies.size(),"same original offline window completes in new JVM");
                });
                returnFromFar(s,q,s.bodies.size());
            }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void spawn(Scene s,int i) {
        var n=s.h.spawn(NpcEntityTypes.NPC_BASE.get(),BlockPos.ZERO);
        var at=s.at(6+4*i,1,8);
        n.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);
        n.setPersistenceRequired();
        n.setProfession(NpcProfession.WORKER);
        n.setCommand(NpcCommand.STAY);
        for(var slot:EquipmentSlot.values())n.setItemSlot(slot,ItemStack.EMPTY);
        n.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
        n.getPersonalInventory().clearContent();
        n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,16));
        var life=NpcLifeData.get(s.server);
        life.onRecruited(n,s.actor,s.now());
        life.setHunger(n.getCharacterId(),100,s.now());
        s.people.add(n);
        s.bodies.add(n.getUUID());
        s.personIds.add(n.getCharacterId());
    }

    private static void issue(Scene s) {
        s.orders.add(WarScaleGameTests.order(s.h,s.core(),s.lord,s.army,s.unit,InitialWarDefinitions.HOLD,s.at(8,1,9)));
    }

    private static void heldStation(Scene s) {
        var rows=s.field().reports(s.actor,s.army);
        s.h.assertTrue(rows.size()==s.bodies.size() && rows.stream().allMatch(r -> r.state()==WarFieldReport.State.HOLDING
                && r.order().filter(s.orders.getLast()::equals).isPresent()),"same original bodies at current order stations");
    }

    private static WarFarReadiness plan(Scene s) { return s.field().far().orElseThrow().plan(s.unit).orElseThrow(); }
    private static WarDispatchService.History history(Scene s) { return s.field().history().orElseThrow(); }

    private static void checkPhase(Scene s,WarFarReadiness.Phase phase) {
        var value=WarServerEvents.existing(s.server).flatMap(WarServerRuntime::field).flatMap(WarFieldRuntime::far);
        s.h.assertTrue(value.flatMap(f -> f.plan(s.unit).filter(p -> p.phase()==phase && f.durable(p))).isPresent(),
                "expected durable far phase " + phase + " actual " + value.flatMap(f -> f.plan(s.unit)));
    }

    private static void assertUnloaded(Scene s) {
        s.h.assertTrue(!s.h.getLevel().hasChunkAt(s.site) && s.bodies.stream().allMatch(id -> s.h.getLevel().getEntity(id)==null),
                "original site chunk and exact bodies actually absent");
    }

    private static void loadPeople(Scene s) {
        s.people.clear();
        for(int i=0;i<s.bodies.size();i++) {
            var n=s.h.getLevel().getEntity(s.bodies.get(i));
            s.h.assertTrue(n instanceof NpcBase npc && npc.getCharacterId().equals(s.personIds.get(i)),"exact original saved body and person loaded");
            s.people.add((NpcBase)n);
        }
    }

    private static void force(Scene s,boolean value) {
        for(int x=(s.site.getX()>>4)-1;x<=(s.site.getX()>>4)+1;x++)
            for(int z=(s.site.getZ()>>4)-1;z<=(s.site.getZ()>>4)+1;z++)s.h.getLevel().setChunkForced(x,z,value);
    }

    private static Path root(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
    private static JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static UUID uuid(JsonObject row,String name) { return UUID.fromString(row.get(name).getAsString()); }

    private static void capture(Scene s,String outcome, Map<String,Object> extra) {
        try {
            var row=new JsonObject();
            extra.forEach((key,val)->row.add(key,JSON.toJsonTree(val)));
            row.addProperty("phase",phase());
            row.addProperty("outcome",outcome);
            row.addProperty("process",ProcessHandle.current().pid());
            row.addProperty("site",s.site.asLong());
            row.addProperty("army",s.army.toString());
            row.addProperty("unit",s.unit.toString());
            row.addProperty("actor",s.actor.toString());
            row.addProperty("account",s.profile.id().toString());
            row.addProperty("name",s.profile.name());
            row.add("bodies",JSON.toJsonTree(s.bodies));
            row.add("people",JSON.toJsonTree(s.personIds));
            row.add("services",JSON.toJsonTree(s.services));
            row.add("orders",JSON.toJsonTree(s.orders));
            if (!archive()) {
                var plan=plan(s);
                row.addProperty("operation",plan.operation().toString());
                row.addProperty("departedAt",plan.departedAt());
                row.addProperty("fit",plan.fitCount());
            }
            Files.writeString(root(s.server).resolve("war-maintenance-"+phase()+".json"),JSON.toJson(row));
            if (save()) Files.writeString(root(s.server).resolve(SAVED),JSON.toJson(row));
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) throws Exception {
        if (!enabled() || !save()) return;
        var world=root(event.getServer());
        var path=world.resolve(SAVED);
        if (!Files.isRegularFile(path)) return;
        var saved=read(path);
        var raw=NbtIo.readCompressed(world.resolve("level.dat"),NbtAccounter.create(64*1024*1024)).getCompoundOrEmpty("Data");
        saved.addProperty("gameTime",raw.getLongOr("Time",0));
        saved.addProperty("dayTime",raw.getLongOr("DayTime",0));
        if (archive()) {
            byte[] bytes=Files.readAllBytes(world.resolve("data/"+WarDispatchJournal.FILE_NAME));
            try(var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes,12,bytes.length-44))) {
                var tag=NbtIo.read(in,NbtAccounter.create(32*1024*1024));
                saved.addProperty("archiveHead",tag.getList("Records").orElseThrow().getCompoundOrEmpty(0)
                        .getCompoundOrEmpty("Archive").getStringOr("Digest",""));
            }
        }
        Files.writeString(path,JSON.toJson(saved));
    }
}
