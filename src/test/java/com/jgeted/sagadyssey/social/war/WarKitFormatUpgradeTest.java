package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarKitFormatUpgradeTest {
    /** v2 and v3 retain the same loan-plan fields. Re-envelope those exact old fields with the historical type. */
    private static byte[] kind(byte[] bytes,String kind)throws Exception{
        var in=new DataInputStream(new ByteArrayInputStream(bytes));int magic=in.readInt(),version=in.readInt(),length=in.readInt();
        var tag=NbtIo.read(new DataInputStream(new ByteArrayInputStream(in.readNBytes(length))),NbtAccounter.create(1024*1024));tag.putString("Kind",kind);
        var payload=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(payload));var data=payload.toByteArray();var out=new ByteArrayOutputStream();var wire=new DataOutputStream(out);wire.writeInt(magic);wire.writeInt(version);wire.writeInt(data.length);wire.write(data);wire.write(MessageDigest.getInstance("SHA-256").digest(data));return out.toByteArray();
    }
    @Test void oldNonemptyBlockArmoryPlansReadWithoutChangingAnyOriginalIntentAndWriteAsV3()throws Exception{
        var r=WarMilitiaTestSupport.request();var old=new WarKitRecord(r,UUID.randomUUID(),ItemLocation.block(UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO),List.of(new WarKitRecord.Line(new ItemFingerprint("minecraft:iron_sword",0),1)),List.of(),List.of());
        var c=new WarKitCodec();var bytes=kind(c.write(List.of(old)),"sagadyssey:war_kit/v2");assertEquals(List.of(old),c.read(bytes));var upgraded=c.write(c.read(bytes));assertFalse(Arrays.equals(bytes,upgraded));assertEquals(List.of(old),c.read(upgraded));
    }
    @Test void historicalV2DoesNotRetroactivelyPermitZeroLoansOrPlayerInventories()throws Exception{
        var r=WarMilitiaTestSupport.request();var plan=new WarKitRecord(r,UUID.randomUUID(),ItemLocation.player(r.actorId(),UUID.randomUUID()),List.of(),List.of(),List.of());var c=new WarKitCodec();var current=c.write(List.of(plan));assertEquals(List.of(plan),c.read(current));
        var legacy=kind(current,"sagadyssey:war_kit/v2");assertThrows(IOException.class,()->c.read(legacy));
    }
    @Test void unknownFutureKitFormatCannotFallBackToAnEmptyOrLegacyPlan()throws Exception{
        var r=WarMilitiaTestSupport.request();var plan=new WarKitRecord(r,UUID.randomUUID(),ItemLocation.player(r.actorId(),UUID.randomUUID()),List.of(),List.of(),List.of());var c=new WarKitCodec();var future=kind(c.write(List.of(plan)),"sagadyssey:war_kit/v4");var copy=future.clone();assertThrows(IOException.class,()->c.read(future));assertArrayEquals(copy,future);
    }
}
