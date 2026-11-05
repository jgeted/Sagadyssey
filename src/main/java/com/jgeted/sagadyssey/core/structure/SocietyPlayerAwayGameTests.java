package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.settlement.SettlementMemberCommand;
import com.jgeted.sagadyssey.worldsim.lod.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.GameType;
import java.util.*;
public final class SocietyPlayerAwayGameTests {
 public static void run(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var b=NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2);int[] stage={0},last={-1};UUID[] player={null};h.onEachTick(()->{if(last[0]==s.getTickCount())return;last[0]=s.getTickCount();var d=NaturalVillageEconomyData.get(s);
  if(stage[0]==0&&h.getTick()>=240){var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"household_member"));p.setGameMode(GameType.SURVIVAL);p.moveTo(b.buildingOrigin().getX(),b.buildingOrigin().getY(),b.buildingOrigin().getZ(),0,0);p.getFoodData().setFoodLevel(4);player[0]=CharacterRegistry.ensureCurrent(p).id();h.assertTrue(SettlementMemberCommand.join(p)&&NpcLifeData.get(s).needs(player[0])==null,"ordinary player belongs to the household but retains vanilla player hunger");NaturalVillageR1GameTests.visiting(l,b,false);stage[0]=1;}
  if(stage[0]==1){if(!d.row(b.buildingKey()).getString("Phase").equals("REMOTE"))return;for(int i=0;i<3;i++)if(NaturalVillageR1GameTests.actor(l,b,i)!=null)return;s.getWorldData().overworldData().setGameTime(l.getGameTime()+60000);l.setDayTime(l.getDayTime()+60000);stage[0]=2;}
  if(stage[0]==2){if(d.backlog(l.getGameTime())>0)return;var row=d.row(b.buildingKey());h.assertTrue(row.getLong("Meals")>0&&NpcLifeData.get(s).needs(player[0])==null,"only original NPCs consume receipted remote meals; membership does not mint simulated hunger or spend food for an offline player");Sagadyssey.LOGGER.info("R3_PLAYER_AWAY_ACCEPT originalNpcMeals={} playerNpcNeeds=false playerMembershipRetained=true",row.getLong("Meals"));h.succeed();stage[0]=3;}
 });}
}
