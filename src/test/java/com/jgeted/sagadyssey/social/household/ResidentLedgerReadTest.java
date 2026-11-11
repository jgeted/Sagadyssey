package com.jgeted.sagadyssey.social.household;

import net.minecraft.core.UUIDUtil;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real storage/files, not a duplicate cache implementation. No MinecraftServer mock or new dependency. */
class ResidentLedgerReadTest {
    private static final UUID PERSON=UUID.fromString("3fd107c1-787c-4661-ad09-346f1acc5d07");
    private static final UUID BODY=UUID.fromString("11a7f75c-367c-4f2f-98b0-70277d1e4fa4");
    @TempDir Path directory;
    @BeforeAll static void version(){SharedConstants.tryDetectVersion();}

    private DimensionDataStorage storage(){return new DimensionDataStorage(null, directory, null, net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty()));}
    private Path main(){return directory.resolve(ResidentWorkMigrationData.NAME+".dat");}
    private Path backup(){return directory.resolve(ResidentWorkMigrationData.NAME+".dat_old");}
    private ResidentWorkMigrationData read(DimensionDataStorage storage,int tick){
        return ResidentWorkMigrationData.existing(storage,directory,tick);
    }
    private static CompoundTag payload(){
        var data=new CompoundTag();data.putInt("Schema",1);data.put("Applications",new ListTag());
        var goods=new ListTag();var row=new CompoundTag();row.store("Person", UUIDUtil.CODEC,PERSON);row.store("Body", UUIDUtil.CODEC,BODY);
        row.putString("State","REVIEW");row.putString("Reason","original unresolved estate responsibility");
        row.putInt("OutstandingQuantity",3);row.putString("InventoryOwner",PERSON.toString());goods.add(row);
        data.put("DeathGoods",goods);return data;
    }
    private void write(CompoundTag data)throws Exception{
        var root=new CompoundTag();root.put("data",data);NbtIo.writeCompressed(root,main());
    }

    @Test void missingRepeatedReadsDoNotCreateLedgerOrFiles()throws Exception{
        var storage=storage();
        for(int i=0;i<100;i++)assertNull(read(storage,5));
        assertNull(storage.get(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME)));
        assertNull(read(storage,6));
        try(var paths=Files.list(directory)){assertEquals(0,paths.count());}
    }
    @Test void validDiskLedgerKeepsSameAuthorityAndUnresolvedIdentity()throws Exception{
        var original=payload();write(original);byte[] file=Files.readAllBytes(main());var storage=storage();
        var first=read(storage,8);assertNotNull(first);
        assertEquals(original,first.save(new CompoundTag(),null));
        for(int tick:new int[]{8,9,100}){
            assertSame(first,read(storage,tick));assertEquals(BODY,read(storage,tick).deathGoods(PERSON).read("Body", UUIDUtil.CODEC).orElseThrow());
            assertEquals(3,read(storage,tick).deathGoods(PERSON).getIntOr("OutstandingQuantity", 0));
        }
        assertArrayEquals(file,Files.readAllBytes(main()));assertFalse(first.isDirty());
    }
    @Test void corruptMainIsRejectedRepeatedlyAndPreserved()throws Exception{
        byte[] bad={1,2,3,4};Files.write(main(),bad);var storage=storage();
        for(int tick:new int[]{10,10,11})assertThrows(IllegalStateException.class,()->read(storage,tick));
        assertNull(storage.get(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME)));
        assertArrayEquals(bad,Files.readAllBytes(main()));assertFalse(Files.exists(backup()));
    }
    @Test void unknownSchemaCannotBecomeEmptyWritableLedger()throws Exception{
        var data=payload();data.putInt("Schema",99);write(data);var original=Files.readAllBytes(main());var storage=storage();
        assertThrows(IllegalStateException.class,()->read(storage,20));
        assertThrows(IllegalStateException.class,()->read(storage,20));
        assertNull(storage.get(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME)));
        assertArrayEquals(original,Files.readAllBytes(main()));
    }
    @Test void oldBackupAloneRetainsRecoveryResponsibility()throws Exception{
        byte[] old={12,13,14};Files.write(backup(),old);var storage=storage();
        assertThrows(IllegalStateException.class,()->read(storage,30));
        assertThrows(IllegalStateException.class,()->read(storage,31));
        assertArrayEquals(old,Files.readAllBytes(backup()));assertFalse(Files.exists(main()));
        assertNull(storage.get(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME)));
    }
    @Test void sameTickSetWinsOverAbsentMemoWithoutCopyingState(){
        var storage=storage();assertNull(read(storage,40));
        var original=ResidentWorkMigrationData.load(payload(),null);storage.set(ResidentWorkMigrationData.FACTORY.type(ResidentWorkMigrationData.NAME),original);
        assertSame(original,read(storage,40));assertEquals(payload(),read(storage,40).save(new CompoundTag(),null));
    }
    @Test void storageLifetimeDoesNotShareNegativeResult()throws Exception{
        var first=storage();assertNull(read(first,50));Files.write(main(),new byte[]{4,5,6});
        var second=storage();assertThrows(IllegalStateException.class,()->read(second,50));
        // The same world path with a new storage object represents a new storage lifetime.
        assertThrows(IllegalStateException.class,()->read(first,51));
    }
    @Test void absentSnapshotExpiresNextTickIncludingCounterChange()throws Exception{
        var storage=storage();assertNull(read(storage,60));byte[] bad={7,8,9};Files.write(main(),bad);
        // Fault injection only: editing a running world's files is not a supported user operation.
        assertNull(read(storage,60));
        assertThrows(IllegalStateException.class,()->read(storage,61));
        assertThrows(IllegalStateException.class,()->read(storage,59));
        assertArrayEquals(bad,Files.readAllBytes(main()));
    }
}
