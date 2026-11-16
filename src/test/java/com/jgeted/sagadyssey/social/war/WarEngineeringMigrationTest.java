package com.jgeted.sagadyssey.social.war;
import com.jgeted.sagadyssey.social.war.adapter.persistence.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarEngineeringMigrationTest{
    @TempDir Path dir;
    static CompoundTag payload(byte[] bytes)throws Exception{return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes,12,bytes.length-44)),NbtAccounter.create(64L*1024*1024));}
    static byte[] envelope(CompoundTag root)throws Exception{var p=new ByteArrayOutputStream();NbtIo.write(root,new DataOutputStream(p));var bytes=p.toByteArray();var all=new ByteArrayOutputStream();var o=new DataOutputStream(all);o.writeInt(0x53474453);o.writeInt(1);o.writeInt(bytes.length);o.write(bytes);o.write(MessageDigest.getInstance("SHA-256").digest(bytes));return all.toByteArray();}
    @Test void oldM6SnapshotRetainsExactPeopleGoodsAndEffectBoundaryOnV2Write()throws Exception{
        var j=WarEngineeringRulesTest.job().change(WarEngineeringJob.Phase.ISSUING,101,"old intent already durable");var codec=new WarEngineeringCodec();var root=payload(codec.write(List.of(j)));root.putString("Kind","sagadyssey:war_engineering/v1");for(var row:root.getList("Records").orElseThrow())((CompoundTag)row).remove("Program");
        var old=dir.resolve("old-v1.sgw");Files.write(old,envelope(root));var recovered=List.copyOf(codec.read(Files.readAllBytes(old)));assertEquals(List.of(j),recovered);assertTrue(recovered.getFirst().program().isEmpty());
        var updated=codec.write(recovered);assertEquals("sagadyssey:war_engineering/v2",payload(updated).getStringOr("Kind",""));assertEquals(recovered,List.copyOf(codec.read(updated)));assertEquals(root,payload(Files.readAllBytes(old)));
    }
    @Test void hiddenTunnelAndOnlyCurrentObservationSurviveDiskReopen()throws Exception{
        var j=WarEngineeringProgramTest.job(WarEngineeringLayouts.tunnel(WarEngineeringProgramTest.p(0,10,0),WarEngineeringProgramTest.p(2,10,0),WarEngineeringProgramTest.p(2,10,0)));var c=j.cells().getFirst();j=j.observeCell(new WarEngineeringJob.Cell(c.position(),"real exposed stone",c.after(),75),101,"original body sees current face");
        var codec=new WarEngineeringCodec();var p=dir.resolve("mixed.sgw");Files.write(p,codec.write(List.of(j)));assertEquals(List.of(j),List.copyOf(codec.read(Files.readAllBytes(p))));assertEquals(7,j.cells().stream().filter(x->x.before().equals(WarEngineeringJob.UNOBSERVED)).count());
    }
    @Test void unknownCodecOrTemplateIsPreservedAsFailureEvenWithValidChecksum()throws Exception{
        var codec=new WarEngineeringCodec();var j=WarEngineeringProgramTest.job(WarEngineeringLayouts.tunnel(WarEngineeringProgramTest.p(0,0,0),WarEngineeringProgramTest.p(1,0,0),WarEngineeringProgramTest.p(1,0,0)));var root=payload(codec.write(List.of(j)));root.putString("Kind","sagadyssey:war_engineering/v3");assertThrows(IOException.class,()->codec.read(envelope(root)));
        root.putString("Kind","sagadyssey:war_engineering/v2");((CompoundTag)root.getList("Records").orElseThrow().getFirst()).getCompound("Program").orElseThrow().putString("Template","future:tunnel/v100");assertThrows(IOException.class,()->codec.read(envelope(root)));
    }
    @Test void checksumFailureIsNeverTreatedAsLegacyOrEmptyWork()throws Exception{var codec=new WarEngineeringCodec();var b=codec.write(List.of(WarEngineeringRulesTest.job()));b[16]^=1;assertThrows(IOException.class,()->codec.read(b));}
    @Test void ownArmyReportsAndCautionSurviveFileReopen()throws Exception{var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100).observe(WarHazardMemoryTest.seen(a,1,101),101).policy(false,102);var codec=new WarHazardCodec();var p=dir.resolve("hazards.sgw");Files.write(p,codec.write(List.of(m)));assertEquals(List.of(m),List.copyOf(codec.read(Files.readAllBytes(p))));}
    @Test void extraFieldsInIntelCannotSilentlyIntroduceWorldOrDamageAuthority()throws Exception{var a=UUID.randomUUID();var m=WarHazardMemory.empty(a,100).observe(WarHazardMemoryTest.seen(a,1,101),101);var codec=new WarHazardCodec();var root=payload(codec.write(List.of(m)));((CompoundTag)root.getList("Records").orElseThrow().getFirst()).putInt("FreeDamage",1000);assertThrows(IOException.class,()->codec.read(envelope(root)));}
}
