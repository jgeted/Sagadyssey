package com.jgeted.sagadyssey.core.structure;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.settlement.SettlementMemberCommand;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.politics.*;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.*;
public final class SocietyOrdinaryParticipationGameTests {
 private static int dispatch(ServerPlayer p,String cmd){try{return p.level().getServer().getCommands().getDispatcher().execute(cmd,p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
 public static void run(GameTestHelper h){var l=h.getLevel();var s=l.getServer();var b=NaturalVillageR1GameTests.fixture(h,0,NaturalVillageLayout.FARM_V2);h.runAfterDelay(240,()->{var n=NaturalVillageR1GameTests.actor(l,b,0);for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(true);var pub=NaturalPolityData.inventory(s,b,3);var pantry=NaturalPolityData.inventory(s,b,1);NaturalVillageR3GameTests.gift(h,n.getCharacterId(),ContainerIds.npcPersonal(n.getCharacterId()),n.getPersonalInventory(),NaturalVillageEconomyData.storeId(b,3),pub,"carrot",4);
  var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"society_ordinary"));p.setGameMode(GameType.SURVIVAL);p.snapTo(b.buildingOrigin().getX()+.5,b.buildingOrigin().getY(),b.buildingOrigin().getZ()+.5,0,0);p.getFoodData().setFoodLevel(4);var character=CharacterRegistry.ensureCurrent(p);
  h.assertTrue(dispatch(p,"saga society join")==1&&dispatch(p,"saga society institutions")==1&&!character.id().equals(PoliticalStateData.get(s).titleHolder(b.settlementId())),"ordinary member can observe real organization without becoming its ruler");var data=NaturalPolityData.get(s);h.assertTrue(!data.authorized(s,b,character.id())&&!data.relief(s,b,character.id(),NaturalVillageLifeData.home(b)).equals("DONE"),"ordinary membership cannot directly order public spending");
  h.assertTrue(dispatch(p,"saga society relief")==1&&SupplyFlowService.count(pub,new ItemFingerprint("minecraft:carrot",0))==2&&SupplyFlowService.count(pantry,new ItemFingerprint("minecraft:carrot",0))==2,"current NPC office holder verifies a member petition and allocates two real carrots");h.assertTrue(dispatch(p,"saga society relief")==1&&SupplyFlowService.count(pub,new ItemFingerprint("minecraft:carrot",0))==2&&data.decisions().size()==1,"repeated user request uses one durable public decision");h.assertTrue(new RightsService(RightsStateData.get(s)).can(SubjectRef.character(character.id()),AssetRight.MANAGE,NaturalVillageEconomyData.storeId(b,3),0,new ItemFingerprint("minecraft:white_wool",0),l.getGameTime()).status()!=RightsVerdict.Status.ALLOW&&dispatch(p,"saga society peace")==0,"petitioning never grants public container management or fictitious conflict authority");Sagadyssey.LOGGER.info("R3_ORDINARY_PARTICIPATION_ACCEPT permissionLevel=0 publicCarrotsBefore=4 publicAfter=2 householdAfter=2 repeatedRequestPaidOnce=true defaultRuler=false");h.succeed();});}
}
