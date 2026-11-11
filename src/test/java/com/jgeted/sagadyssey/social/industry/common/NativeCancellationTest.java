package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeCancellationTest {
    private final UUID needId=UUID.randomUUID();
    private CompoundTag source(){var t=new CompoundTag();for(var k:List.of("Id","Target","Owner","Actor"))t.store(k, UUIDUtil.CODEC,UUID.randomUUID());t.store("Need", UUIDUtil.CODEC,needId);t.putString("Kind","SHEAR");t.put("Output",new CompoundTag());return t;}
    private CompoundTag action(CompoundTag source){var t=new CompoundTag();for(var k:List.of("Id","Person","Body","Plan"))t.store(k, UUIDUtil.CODEC,UUID.randomUUID());t.store("Need", UUIDUtil.CODEC,needId);t.store("Source", UUIDUtil.CODEC,source.read("Id", UUIDUtil.CODEC).orElseThrow());t.putString("State","READY");t.putLong("StartedAt",10);t.put("Drops",new ListTag());return t;}
    private NeedRequest need(NeedRequestStatus status){return new NeedRequest(needId,new NeedRequest.Key(UUID.randomUUID(),"closed-original",new ItemFingerprint("minecraft:white_wool",0),UUID.randomUUID(),0,1000),3,3,0,0,0,status,1,20,0,32,"original cancelled without a native effect");}
    private EntityResourceActionData data(CompoundTag source){var d=new EntityResourceActionData();d.putSource(source);return d;}
    @Test void liveUnstartedEndedNeedCancelsOnceWithoutPretendingNativeExecution(){
        var src=source();var a=action(src);var d=data(src);d.createAction(a);assertTrue(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),25,"request ended"));
        var cancelled=d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow());assertEquals("CANCELLED",cancelled.getStringOr("State", ""));assertTrue(EntityResourceActionData.settled("CANCELLED"));assertTrue(MinecraftApi.listOfType(cancelled, "Drops",Tag.TAG_COMPOUND).isEmpty());assertFalse(cancelled.contains("ToolBefore"));
        d.setDirty(false);assertTrue(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),30,"repeat"));assertEquals(cancelled,d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()));assertFalse(d.isDirty());
        var changed=cancelled.copy();changed.putString("State","DONE");assertThrows(IllegalStateException.class,()->d.putAction(changed));
    }
    @Test void activeOrDifferentNeedCannotBeCancelled(){
        var src=source();var a=action(src);var d=data(src);d.createAction(a);assertFalse(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.OPEN),25,"still active"));
        var other=new NativeCancellationTest();assertFalse(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),other.need(NeedRequestStatus.CANCELLED),25,"wrong request"));assertEquals("READY",d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("State", ""));
    }
    @Test void nativeIntentOrEvidenceInvalidatesFreshProcessProof(){
        for(var state:List.of("ACTING","ACTED")){
            var src=source();var a=action(src);var d=data(src);d.createAction(a);a.putString("State",state);d.putAction(a);assertFalse(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),25,"unsafe"));assertEquals(state,d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("State", ""));
        }
        var src=source();var a=action(src);var d=data(src);d.createAction(a);a.put("ToolBefore",new CompoundTag());d.putAction(a);assertFalse(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),25,"unsafe before image"));
    }
    @Test void oldOrNewLoadedReadyLosesLiveProofAndCannotAssumeNoEffect(){
        var src=source();var a=action(src);var d=data(src);d.createAction(a);
        for(int schema:List.of(1,2)){
            var root=d.save(new CompoundTag(),null);root.putInt("Schema",schema);var loaded=EntityResourceActionData.load(root,null);assertFalse(loaded.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),25,"not live"));assertFalse(loaded.isDirty());assertEquals("READY",loaded.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("State", ""));
        }
    }
    @Test void schema2CancellationRoundTripsWithoutLosingIdentityOrReopening(){
        var src=source();var a=action(src);var d=data(src);d.createAction(a);assertTrue(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),25,"finished"));
        var root=d.save(new CompoundTag(),null);assertEquals(2,root.getIntOr("Schema", 0));var loaded=EntityResourceActionData.load(root,null);assertEquals(d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()),loaded.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()));assertEquals(src,loaded.source(src.read("Id", UUIDUtil.CODEC).orElseThrow()));assertFalse(loaded.isDirty());
        root.putInt("Schema",1);assertThrows(IllegalArgumentException.class,()->EntityResourceActionData.load(root,null));
    }
    @Test void genericWriterCannotInventCancelledStateOrEraseReview(){
        var src=source();var a=action(src);var d=data(src);d.createAction(a);a.putString("State","CANCELLED");a.putString("Detail","NO_NATIVE_EFFECT: fake");a.putLong("UpdatedAt",25);assertThrows(IllegalStateException.class,()->d.putAction(a));
        a.putString("State","REVIEW");d.putAction(a);assertFalse(d.cancelUnstarted(a.read("Id", UUIDUtil.CODEC).orElseThrow(),need(NeedRequestStatus.CANCELLED),30,"do not repair old review"));assertEquals("REVIEW",d.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("State", ""));
    }
}
