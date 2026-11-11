package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real Server getters and isolated fault files; not a migration, reboot, or normal NPC-work claim. */
@GameTestHolder("sagadyssey_playability_fix")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid="sagadyssey")
public final class ResidentLedgerReadGameTests {
    private static final UUID PERSON=UUID.fromString("78a69c1a-6156-4d7c-abde-49539c13468d");
    private static final UUID BODY=UUID.fromString("cdd90edf-dbca-4f66-b918-23478f3095c0");
    private static boolean selected(){return "reliability-ledger-read".equals(System.getProperty("sagadyssey.playability.phase",""));}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return selected()?List.of(new TestFunction("reliability_ledger_read","reliability_ledger_read",
                "sagadyssey_playability_fix:empty",100,0,true,ResidentLedgerReadGameTests::run)):List.of();
    }
    @SubscribeEvent public static void boot(ServerStartedEvent e){
        if(!selected()||!(e.getServer() instanceof GameTestServer))return;
        try{var level=e.getServer().overworld();level.getStructureManager().getOrCreate(Identifier.parse("sagadyssey_playability_fix:empty"))
                .load(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception failure){throw new IllegalStateException("isolated ledger test template unavailable",failure);}
    }
    private static CompoundTag fixture(){
        var data=new CompoundTag();data.putInt("Schema",1);data.put("Applications",new ListTag());
        var goods=new ListTag();var row=new CompoundTag();row.store("Person", UUIDUtil.CODEC,PERSON);row.store("Body", UUIDUtil.CODEC,BODY);
        row.putString("State","REVIEW");row.putString("Reason","inert original estate obligation, no identity creation");
        row.putInt("OutstandingQuantity",3);row.putString("InventoryOwner",PERSON.toString());goods.add(row);
        data.put("DeathGoods",goods);return data;
    }
    private static String sha(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private static void rejected(GameTestHelper h,Runnable read,String label){
        boolean rejected=false;
        try{read.run();}catch(IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected,label+": unreadable ledger must be rejected, not created");
    }
    private static void preserve(Path file,Path evidence,String name)throws Exception{
        if(Files.exists(file))Files.move(file,evidence.resolve(name));
    }
    private static void fault(GameTestHelper h,MinecraftServer server,ResidentWorkMigrationData authority,
                              Path file,Path evidence,String name,byte[] bytes)throws Exception{
        var storage=server.overworld().getDataStorage();
        h.assertTrue(ResidentWorkMigrationData.existing(server)==authority,"valid authority before isolated fault");
        Files.write(file,bytes,StandardOpenOption.CREATE_NEW);storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),null);
        try{
            rejected(h,()->ResidentWorkMigrationData.existing(server),name+" existing");
            rejected(h,()->ResidentWorkMigrationData.existing(server),name+" repeated existing");
            rejected(h,()->ResidentWorkMigrationData.get(server),name+" formal get");
            h.assertTrue(storage.get(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME))==null,name+": no replacement ledger");
            h.assertTrue(Arrays.equals(bytes,Files.readAllBytes(file)),name+": original failure bytes preserved");
            Sagadyssey.LOGGER.info("LEDGER_READ case={} result=PASS tick={} existingRejected=true getRejected=true sha256={} faultInjected=true",name,server.getTickCount(),sha(bytes));
        }finally{
            preserve(file,evidence,name);
            storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),authority);
        }
    }
    private static void run(GameTestHelper h){
        var server=h.getLevel().getServer();
        h.assertTrue(server instanceof GameTestServer,"this file fault test requires its isolated GameTestServer world");
        Path root=server.getWorldPath(LevelResource.ROOT),directory=root.resolve("data");
        Path main=directory.resolve(ResidentWorkMigrationData.NAME+".dat"),backup=directory.resolve(ResidentWorkMigrationData.NAME+".dat_old");
        Path evidence=root.resolve("reliability-ledger-read-evidence");
        var storage=server.overworld().getDataStorage();
        try{
            h.assertTrue(!Files.exists(main)&&!Files.exists(backup),"fresh test world required; never replace an existing ledger file");
            h.assertTrue(ResidentWorkMigrationData.existing(server)==null,"fresh test must have no current resident migration ledger");
            Files.createDirectories(directory);Files.createDirectory(evidence);
            for(int repeat=0;repeat<100;repeat++)h.assertTrue(ResidentWorkMigrationData.existing(server)==null,"missing getter stays non-creating");
            h.assertTrue(!Files.exists(main)&&!Files.exists(backup),"repeated reads create no file");
            Sagadyssey.LOGGER.info("LEDGER_READ case=missing result=PASS tick={} reads=101 ledgerCreated=false",server.getTickCount());

            int createTick=server.getTickCount();var created=ResidentWorkMigrationData.get(server);
            h.assertTrue(created!=null&&ResidentWorkMigrationData.existing(server)==created,"formal get is visible in same tick");
            h.assertTrue(server.getTickCount()==createTick,"get visibility covered same server tick");
            h.assertTrue(created.applications().isEmpty(),"formal empty ledger does not create a resident application");
            h.assertTrue(MinecraftApi.listOfType(created.save(new CompoundTag(),server.registryAccess()), "DeathGoods",Tag.TAG_COMPOUND).isEmpty(),"formal empty ledger does not create an identity or estate");
            Sagadyssey.LOGGER.info("LEDGER_READ case=formal_get_after_missing result=PASS tick={} sameObject=true sameTick=true",createTick);

            // Explicit inert read fixture. It does not create an NPC, fabricate migration, or transfer property.
            var original=fixture();var authority=ResidentWorkMigrationData.load(original,server.registryAccess());
            storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),authority);
            for(int repeat=0;repeat<20;repeat++){
                h.assertTrue(ResidentWorkMigrationData.existing(server)==authority,"existing returns same original authority");
                h.assertTrue(ResidentWorkMigrationData.get(server)==authority,"formal get returns same original authority");
            }
            h.assertTrue(original.equals(authority.save(new CompoundTag(),server.registryAccess())),"persistent person, body, ownership and unresolved responsibility unchanged");
            NbtIo.writeCompressed(original,evidence.resolve("valid-ledger-payload.nbt"));
            Sagadyssey.LOGGER.info("LEDGER_READ case=valid_authority result=PASS person={} body={} responsibility=3 sameObject=true fixtureOnly=true",PERSON,BODY);

            fault(h,server,authority,main,evidence,"corrupt.dat",new byte[]{1,2,3,4,5});
            fault(h,server,authority,backup,evidence,"retained.dat_old",new byte[]{6,7,8,9});

            var unknown=fixture();unknown.putInt("Schema",99);
            rejected(h,()->{try{ResidentWorkMigrationData.load(unknown,server.registryAccess());}
                catch(IllegalArgumentException unsupported){throw new IllegalStateException(unsupported);}},"unknown schema parser");
            var unknownRoot=new CompoundTag();unknownRoot.put("data",unknown);Path encoded=evidence.resolve("unknown-schema-input.nbt");
            NbtIo.writeCompressed(unknownRoot,encoded);
            fault(h,server,authority,main,evidence,"unknown-schema.dat",Files.readAllBytes(encoded));

            // Fault injection deliberately documents the bounded, within-tick negative snapshot.
            h.assertTrue(ResidentWorkMigrationData.existing(server)==authority,"restore authority before cache boundary");
            storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),null);
            int checkedTick=server.getTickCount();h.assertTrue(ResidentWorkMigrationData.existing(server)==null,"absence confirmed before late file injection");
            byte[] late={10,11,12,13};Files.write(main,late,StandardOpenOption.CREATE_NEW);
            try{
                h.assertTrue(ResidentWorkMigrationData.existing(server)==null,"same tick uses earlier absence snapshot; live external edits are unsupported");
            }catch(RuntimeException failure){preserve(main,evidence,"late-file-first-failure.dat");storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),authority);throw failure;}
            h.runAfterDelay(1,()->{
                try{
                    h.assertTrue(server.getTickCount()!=checkedTick,"normal server tick advanced");
                    rejected(h,()->ResidentWorkMigrationData.existing(server),"next tick late file");
                    rejected(h,()->ResidentWorkMigrationData.get(server),"formal get after late file");
                    h.assertTrue(Arrays.equals(late,Files.readAllBytes(main)),"late failure bytes retained");
                    Sagadyssey.LOGGER.info("LEDGER_READ case=next_tick_recheck result=PASS startTick={} observedTick={} sha256={} faultInjected=true",checkedTick,server.getTickCount(),sha(late));
                }catch(Exception failure){throw new IllegalStateException("real server next-tick read boundary failed",failure);}
                finally{try{preserve(main,evidence,"late-file.dat");}catch(Exception failure){throw new IllegalStateException("cannot preserve late-file fault",failure);}storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),authority);}
                h.assertTrue(ResidentWorkMigrationData.existing(server)==authority,"restored fixture identity remains authoritative");
                h.assertTrue(original.equals(authority.save(new CompoundTag(),server.registryAccess())),"read diagnosis never changes estate obligation");
                Sagadyssey.LOGGER.info("LEDGER_READ_ACCEPT serverGetters=true cases=7 fixtureOnly=true fileFaultInjection=true normalNpcDispatch=false fullRestart=false evidence={}",evidence.toAbsolutePath());
                h.succeed();
            });
        }catch(Exception failure){throw new IllegalStateException("isolated server ledger read checks failed; preserve evidence",failure);}
    }
}
