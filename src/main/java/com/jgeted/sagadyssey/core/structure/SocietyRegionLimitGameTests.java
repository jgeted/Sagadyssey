package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import net.minecraft.gametest.framework.*;
public final class SocietyRegionLimitGameTests {
 public static void run(GameTestHelper h){var s=h.getLevel().getServer();var first=NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2);NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.WOOD_V2);NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.LIVING);NaturalVillageR1GameTests.fixture(h,896,NaturalVillageLayout.FARM_V2);var outside=NaturalVillageR1GameTests.fixture(h,1280,NaturalVillageLayout.WOOD_V2);h.runAfterDelay(420,()->{h.assertTrue(NaturalVillageLifeData.get(s).village(outside.buildingKey()).getBooleanOr("Ready", false),"fifth actual village is generated; no world objects are silently discarded");h.assertTrue(NaturalVillageEconomyData.get(s).rows().size()==4&&NaturalVillageEconomyData.get(s).row(outside.buildingKey())==null&&NaturalPolityData.get(s).sites().size()==4&&NaturalPolityData.get(s).site(outside.settlementId())==null,"political and economic scope use the same four canonical original settlements");h.assertTrue(NaturalHouseholdMigrationData.get(s).request(s,outside,first,NaturalVillageLifeData.person(outside,1)).contains("区域"),"a fifth household cannot silently increase the tested regional population and authority scope");Sagadyssey.LOGGER.info("R3_REGION_LIMIT_ACCEPT generated=5 regionalSites=4 regionalOriginalPeople=12 outsideMigrationRejected=true");h.succeed();});}
}
