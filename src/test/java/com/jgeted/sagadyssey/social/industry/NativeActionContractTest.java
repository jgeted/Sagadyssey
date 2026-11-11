package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import com.jgeted.sagadyssey.social.industry.common.*;
import net.minecraft.nbt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeActionContractTest {
    private CompoundTag source(){var t=new CompoundTag();for(String k:List.of("Id","Target","Owner","Actor","Need"))t.store(k, UUIDUtil.CODEC,UUID.randomUUID());t.putString("Kind","SHEAR");t.put("Output",new CompoundTag());return t;}
    private CompoundTag action(CompoundTag s){var a=new CompoundTag();for(String k:List.of("Id","Person","Body","Plan"))a.store(k, UUIDUtil.CODEC,UUID.randomUUID());a.store("Source", UUIDUtil.CODEC,s.read("Id", UUIDUtil.CODEC).orElseThrow());a.store("Need", UUIDUtil.CODEC,s.read("Need", UUIDUtil.CODEC).orElseThrow());a.putString("State","READY");return a;}
    @Test void observationCopiesNeverChangeAuthority(){var d=new EntityResourceActionData();var s=source();d.putSource(s);s.putString("Kind","FISH_COD");assertEquals("SHEAR",d.sources().getFirst().getStringOr("Kind", ""));d.setDirty(false);d.sources().getFirst().putString("Kind","invalid");assertFalse(d.isDirty());assertEquals("SHEAR",d.source(s.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("Kind", ""));}
    @Test void sourceBindingConflictsReject(){var d=new EntityResourceActionData();var s=source();d.putSource(s);s.store("Actor", UUIDUtil.CODEC,UUID.randomUUID());assertThrows(IllegalStateException.class,()->d.putSource(s));}
    @Test void sameBodyCanRebindPlanButNotPerson(){var d=new EntityResourceActionData();var s=source();d.putSource(s);var a=action(s);d.putAction(a);a.store("Plan", UUIDUtil.CODEC,UUID.randomUUID());d.putAction(a);a.store("Person", UUIDUtil.CODEC,UUID.randomUUID());assertThrows(IllegalStateException.class,()->d.putAction(a));}
    @Test void terminalFactsCannotBecomeNewActions(){var d=new EntityResourceActionData();var s=source();d.putSource(s);var a=action(s);a.putString("State","DONE");d.putAction(a);a.putString("State","READY");assertThrows(IllegalStateException.class,()->d.putAction(a));}
    @Test void uncertainNativeResultSurvivesNbtWithoutClaimingSuccess(){var d=new EntityResourceActionData();var s=source();d.putSource(s);var a=action(s);a.putString("State","ACTING");d.putAction(a);var r=EntityResourceActionData.load(d.save(new CompoundTag(),null),null);assertFalse(r.isDirty());assertEquals("ACTING",r.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()).getStringOr("State", ""));assertEquals(a,r.action(a.read("Id", UUIDUtil.CODEC).orElseThrow()));}
    @Test void duplicatePersistedOperationsReject(){var d=new EntityResourceActionData();var s=source();d.putSource(s);var a=action(s);d.putAction(a);var root=d.save(new CompoundTag(),null);MinecraftApi.listOfType(root, "Actions",Tag.TAG_COMPOUND).add(a.copy());assertThrows(IllegalArgumentException.class,()->EntityResourceActionData.load(root,null));}
    @Test void unsupportedSchemaRejects(){var t=new CompoundTag();t.putInt("Schema",4);t.put("Sources",new ListTag());t.put("Actions",new ListTag());assertThrows(IllegalArgumentException.class,()->EntityResourceActionData.load(t,null));}
    @Test void wildOpportunityRequiresItsVersion(){
        var source=source();source.putString("Kind","ANGLE");source.putString("Dimension","minecraft:overworld");
        source.store("Actor", UUIDUtil.CODEC,source.read("Owner", UUIDUtil.CODEC).orElseThrow());source.putLong("Shore",0);source.putLong("Water",1);
        var data=new EntityResourceActionData();data.putSource(source);
        var saved=data.save(new CompoundTag(),null);assertEquals(3,saved.getIntOr("Schema", 0));
        assertEquals(source,EntityResourceActionData.load(saved,null).source(source.read("Id", UUIDUtil.CODEC).orElseThrow()));
        saved.putInt("Schema",2);assertThrows(IllegalArgumentException.class,()->EntityResourceActionData.load(saved,null));
    }
    @Test void cacheIdentityIgnoresOnlyRevisions(){var a=new CompoundTag();a.putString("Actor","a");a.putInt("Quantity",3);a.putString("Destination","x");a.putString("Intent","DELIVERY");a.putString("ExpectedRevisions","old");var b=a.copy();b.putString("ExpectedRevisions","new");assertTrue(IndustryTransfers.sameEffect(a,b));for(String k:List.of("Actor","Destination","Intent")){var c=b.copy();c.putString(k,"changed");assertFalse(IndustryTransfers.sameEffect(a,c));}b.putInt("Quantity",4);assertFalse(IndustryTransfers.sameEffect(a,b));assertFalse(IndustryTransfers.sameEffect(null,a));}
    @Test void generatorSelectsOneExactNamespacePerFrozenPhase(){var a=System.getProperty("sagadyssey.industry.native");var b=System.getProperty("sagadyssey.industry.ab.phase");try{System.setProperty("sagadyssey.industry.native","true");for(String p:List.of("guards","wool-start","wool-resume","fish","smelt-start","smelt-resume")){System.setProperty("sagadyssey.industry.ab.phase",p);var tests=EntityFurnaceGameTests.tests();assertEquals(1,tests.size());assertTrue(tests.iterator().next().structureName().startsWith("sagadyssey_industries_ab:"));}System.setProperty("sagadyssey.industry.native","false");assertTrue(EntityFurnaceGameTests.tests().isEmpty());}finally{if(a==null)System.clearProperty("sagadyssey.industry.native");else System.setProperty("sagadyssey.industry.native",a);if(b==null)System.clearProperty("sagadyssey.industry.ab.phase");else System.setProperty("sagadyssey.industry.ab.phase",b);}}
}
