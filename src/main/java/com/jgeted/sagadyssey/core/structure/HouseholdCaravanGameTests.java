package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
public final class HouseholdCaravanGameTests {
 public static void run(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var from=NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.WOOD_V2);var to=NaturalVillageR1GameTests.fixture(h,96,NaturalVillageLayout.FARM_V2);int[] stage={0},last={-1},wait={0};var bags=new HashMap<UUID,ListTag>();var ids=new HashSet<UUID>();var homeId=NaturalVillageLifeData.home(from);var lostBed=NaturalVillageLayout.at(to,NaturalVillageLayout.spareBeds().get(0));var bed=l.getBlockState(lostBed);
  h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var data=NaturalHouseholdMigrationData.get(s);
   if(s.getTickCount()%1000==0)Sagadyssey.LOGGER.info("R2_MIGRATION_PROGRESS stage={} move={}",stage[0],data.moves().stream().map(t->t.getStringOr("State", "")+":"+t.getStringOr("Reason", "")).toList());if(s.getTickCount()%2000==0)for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,from,i);if(n!=null)Sagadyssey.LOGGER.info("R2_MIGRANT_LOCATION i={} at={} target={} nav={}",i,n.blockPosition(),NaturalVillageLayout.at(to,NaturalVillageLayout.ARRIVAL_PANTRY),n.getNavigation().isInProgress());}
   if(stage[0]==0&&h.getTick()>=220){for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,from,i);if(n==null)return;bags.put(n.getCharacterId(),NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()));ids.add(n.getCharacterId());NaturalVillageR1GameTests.actor(l,to,i).setNoAi(true);}
    h.assertTrue(!data.request(s,from,to,UUID.randomUUID()).equals("ACCEPTED"),"outsider cannot order another household to migrate");
    h.assertTrue(data.request(s,from,to,NaturalVillageLifeData.person(from,1)).equals("ACCEPTED"),"original household head accepts genuine spare housing");
    l.setBlock(lostBed,Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);wait[0]=s.getTickCount();stage[0]=1;
   }
   if(stage[0]==1){var t=data.moves().getFirst();h.assertTrue(!t.getStringOr("State", "").equals("DONE"),"destroyed destination cannot complete residence");for(var id:ids)h.assertTrue(NaturalHouseholdMigrationData.residentSettlement(s,id)==null,"in transit is neither source nor destination resident");
    if(s.getTickCount()-wait[0]<1000)return;h.assertTrue(t.getStringOr("State", "").equals("WAIT_DESTINATION"),"invalid destination retains the original moving household");
    for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,from,i);h.assertTrue(n!=null&&bags.get(n.getCharacterId()).equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess())),"waiting keeps original identity and personal property");}
    l.setBlock(lostBed,bed,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);stage[0]=2;
   }
   if(stage[0]==2){var t=data.moves().getFirst();if(!t.getStringOr("State", "").equals("DONE"))return;var home=HouseholdData.get(s).byId(homeId);h.assertTrue(home.settlementId().equals(to.settlementId())&&home.members().stream().map(HouseholdMemberRecord::characterId).collect(java.util.stream.Collectors.toSet()).equals(ids),"same household and three original people, no second membership");
    for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,from,i);h.assertTrue(n!=null&&bags.get(n.getCharacterId()).equals(NaturalVillageEconomyData.items(n.getPersonalInventory(),s.registryAccess()))&&NaturalHouseholdMigrationData.residentSettlement(s,n.getCharacterId()).equals(to.settlementId())&&n.getHomeSettlementId().equals(to.settlementId())&&NpcLifeData.get(s).record(n.getCharacterId()).bedPosition().equals(NaturalVillageLayout.at(to,NaturalVillageLayout.spareBeds().get(i))),"original body arrives by walking with unchanged belongings and its actual new bed");}
    var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(to,NaturalVillageLayout.ARRIVAL_PANTRY));h.assertTrue(pantry.isEmpty(),"new pantry is not filled by migration");
    var saved=NaturalHouseholdMigrationData.load(data.save(new CompoundTag(),s.registryAccess()),s.registryAccess());h.assertTrue(saved.moves().getFirst().getStringOr("State", "").equals("DONE")&&!data.request(s,from,to,NaturalVillageLifeData.person(from,1)).equals("ACCEPTED"),"reload and repeat application cannot create another household or grant");
    Sagadyssey.LOGGER.info("R2_MIGRATION_ACCEPT household={} originalPeople=3 sourceResidents=0 destinationResidents=6 copiedItems=0 invalidDestinationWaited=true",homeId);h.succeed();stage[0]=3;
   }
  });
 }
}
