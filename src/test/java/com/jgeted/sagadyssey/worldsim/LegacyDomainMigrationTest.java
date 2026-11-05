package com.jgeted.sagadyssey.worldsim;

import com.jgeted.sagadyssey.worldsim.lod.LodFingerprints;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real domain decoding, not just successful parsing of compressed NBT. Frozen input is read-only. */
class LegacyDomainMigrationTest {
    private static final Map<String,String> DOMAINS=Map.of(
        "characters","character.CharacterRegistryData", "fixed_scenarios","scenario.FixedScenarioData",
        "history","history.WorldHistoryData", "logistics","logistics.LogisticsData",
        "npc_life","life.NpcLifeData", "politics","politics.PoliticalStateData",
        "settlement_economy","economy.SettlementEconomyData", "settlements","settlement.SettlementRegistryData",
        "world_dynamics","world.WorldDynamicsData", "world_scenario","world.WorldScenarioData");
    @Test void frozenWorkerDomainsMigrateIdempotently() throws Exception {check("worker");}
    @Test void frozenFarmerDomainsMigrateIdempotently() throws Exception {check("farmer");}
    @Test void frozenBlacksmithDomainsMigrateIdempotently() throws Exception {check("blacksmith");}
    private void check(String world) throws Exception {
        for(var entry:DOMAINS.entrySet()) {
            String path="fixtures/v1_9/"+world+"/sagadyssey_"+entry.getKey()+".dat";
            CompoundTag input;
            try(var stream=getClass().getClassLoader().getResourceAsStream(path)){assertNotNull(stream,path);input=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap()).getCompound("data");}
            var before=LodFingerprints.of(input);
            var type=Class.forName("com.jgeted.sagadyssey.social."+entry.getValue());
            var decode=type.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);decode.setAccessible(true);
            var first=(SavedData)decode.invoke(null,input.copy(),null);var normalized=first.save(new CompoundTag(),null);
            var second=(SavedData)decode.invoke(null,normalized.copy(),null);
            assertEquals(LodFingerprints.of(normalized),LodFingerprints.of(second.save(new CompoundTag(),null)),path+" migration must be idempotent");
            assertEquals(before,LodFingerprints.of(input),path+" dry-run source must remain unchanged");
            if(entry.getKey().equals("characters"))assertEquals(input.getList("Characters",Tag.TAG_COMPOUND).size(),normalized.getList("Characters",Tag.TAG_COMPOUND).size(),"original person count must not change");
            System.out.println("PHASE10_LEGACY world="+world+" domain="+entry.getKey()+" before="+before+" migrated="+LodFingerprints.of(normalized));
        }
    }
}
