package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.*;
import com.jgeted.sagadyssey.npc.brain.plan.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.settlement.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class ReligionGameTests{
    private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:bread",0);
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void checkpoint(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        previous=null;if(!(e.getServer() instanceof GameTestServer))return;var server=e.getServer();var d=ReligionData.get(server);
        long last=Math.max(d.cases().stream().mapToLong(AlmsCase::updatedAt).max().orElse(0),d.donations().stream().mapToLong(DonationRecord::updatedAt).max().orElse(0));server.getWorldData().overworldData().setGameTime(Math.max(server.overworld().getGameTime(),last+1));
        if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart"))return;
        try{previous=NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("phase7-restart-checkpoint.nbt"),NbtAccounter.unlimitedHeap());server.getWorldData().overworldData().setGameTime(Math.max(server.overworld().getGameTime(),previous.getLongOr("Time", 0L)+1));}catch(Exception x){throw new IllegalStateException("phase7 previous-process checkpoint required",x);}
    }
    @BeforeBatch(batch="phase7") public static void day(ServerLevel level){level.setDayTime(1000);var d=ReligionData.get(level.getServer());long now=level.getGameTime();
        for(var c:d.cases())if(!c.closed()&&c.stage()!=AlmsCase.Stage.CARRYING&&c.pending()==null){RightsStateData.get(level.getServer()).releaseReservations(c.token());d.aid(c.update(AlmsCase.Stage.CANCELLED,c.reviewer(),now,now,null,"previous isolated test fixture retired",true));}
    }
    @BeforeBatch(batch="phase7_reload") public static void reloadDay(ServerLevel level){day(level);}
    @BeforeBatch(batch="phase7_restart") public static void restartDay(ServerLevel level){level.setDayTime(1000);}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void physicalChapelBindingIsIdempotentAndCannotMintStock(GameTestHelper h){var f=fixture(h,0,2,0,true);var t=f.temple();int offices=InstitutionData.get(h.getLevel().getServer()).offices().size();
        var again=ChapelBootstrapService.bind(h.getLevel().getServer(),t.building(),t.treasury().position(),t.alms().position(),t.counter(),SubjectRef.institution(t.id()),2,3);
        h.assertTrue(again.equals(t)&&InstitutionData.get(h.getLevel().getServer()).offices().size()==offices,"one institution and no repeated office bootstrap");h.assertTrue(count(f.alms())==0&&count(f.treasury())==0,"building and institution do not create food");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1200)
    public static void reviewedReliefIsActuallyCarriedGiftedAndConsumable(GameTestHelper h){var f=fixture(h,0,2,6,true);var c=ask(h,f,3);
        h.succeedWhen(()->{var d=ReligionData.get(h.getLevel().getServer());h.assertTrue(d.aid(c.id()).stage()==AlmsCase.Stage.ISSUED,"actual approved charity: "+diagnostic(f.deacon()));h.assertTrue(count(f.alms())==3&&count(f.recipient().getPersonalInventory())==3,"food conserved and reserve retained");h.assertTrue(f.deacon().getX()>h.absolutePos(new BlockPos(9,1,5)).getX(),"deacon physically carried from store to window");
            h.assertTrue(d.receipt(c.id(),true)!=null&&d.receipt(c.id(),false)!=null,"both custody and gift receipts persisted");UUID bag=ContainerIds.npcPersonal(f.recipient().getCharacterId());h.assertTrue(new RightsService(RightsStateData.get(h.getLevel().getServer())).can(SubjectRef.character(f.recipient().getCharacterId()),AssetRight.CONSUME,bag,0,FOOD,h.getLevel().getGameTime()).status()==RightsVerdict.Status.ALLOW,"recipient owns legally consumable food");});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=400)
    public static void clergyIsNotAnAutomaticAlmsOrTreasuryManager(GameTestHelper h){var f=fixture(h,0,2,6,false);var c=ask(h,f,3);h.runAtTickTime(250,()->{h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.APPROVED,"clergy only reviews");h.assertTrue(count(f.alms())==6&&count(f.recipient().getPersonalInventory())==0,"review authority cannot transfer alms");h.assertTrue(!ReligionService.authorized(f.clergy(),f.temple().id(),OfficeAuthority.MANAGE_ALMS)&&!ReligionService.authorized(f.clergy(),f.temple().id(),OfficeAuthority.MANAGE_TREASURY),"narrow office powers");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1600)
    public static void reserveFloorWaitsForRealRefillWithoutSpammingRequests(GameTestHelper h){var f=fixture(h,0,2,2,true);var c=ask(h,f,3);
        h.runAtTickTime(250,()->{h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.WAITING_STOCK&&count(f.alms())==2,"retained stock not given away");h.assertTrue(NeedRequestData.get(h.getLevel().getServer()).requests().stream().filter(r->r.key().requester().equals(f.temple().id())).count()==1,"one durable replenishment request");f.alms().setItem(0,new ItemStack(Items.BREAD,5));});
        h.succeedWhen(()->h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.ISSUED&&count(f.alms())==2,"actual refill unblocks original request and preserves floor"));}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1600)
    public static void fullRecipientKeepsInstitutionCargoThenReceivesOnce(GameTestHelper h){var f=fixture(h,0,2,6,true);fill(f.recipient().getPersonalInventory());var c=ask(h,f,3);
        h.runAtTickTime(250,()->{h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.CARRYING&&count(f.deacon().getTransportInventory())==3,"full recipient preserves institutional cargo");f.recipient().getPersonalInventory().setItem(0,ItemStack.EMPTY);});
        h.succeedWhen(()->h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.ISSUED&&count(f.recipient().getPersonalInventory())==3&&count(f.alms())==3,"same cargo eventually delivered once"));}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1700)
    public static void revocationStopsDeliveryAndReappointmentResumesSameCargo(GameTestHelper h){var f=fixture(h,0,2,6,true);fill(f.recipient().getPersonalInventory());var c=ask(h,f,3);
        h.runAtTickTime(250,()->{h.assertTrue(count(f.deacon().getTransportInventory())==3,"real cargo before revocation");for(var o:InstitutionData.get(h.getLevel().getServer()).officesHeld(f.temple().id(),SubjectRef.character(f.deacon().getCharacterId()),h.getLevel().getGameTime()))InstitutionData.get(h.getLevel().getServer()).revokeOffice(o.id());f.recipient().getPersonalInventory().setItem(0,ItemStack.EMPTY);});
        h.runAtTickTime(650,()->{h.assertTrue(count(f.recipient().getPersonalInventory())==0&&count(f.deacon().getTransportInventory())==3,"old revoked office cannot give institution goods");ChapelBootstrapService.appoint(h.getLevel().getServer(),f.temple(),SubjectRef.institution(f.temple().id()),f.deacon(),TempleRecord.Role.DEACON_STEWARD);});
        h.succeedWhen(()->h.assertTrue(ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()==AlmsCase.Stage.ISSUED&&count(f.alms())==3,"new valid office resumes same delivery"));}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1300)
    public static void twoDeaconsCannotIssueSameCaseTwice(GameTestHelper h){var f=fixture(h,0,2,6,true);var other=npc(h,new BlockPos(3,1,9),NpcProfession.COURIER,true);ChapelBootstrapService.appoint(MinecraftApi.server(other),f.temple(),SubjectRef.institution(f.temple().id()),other,TempleRecord.Role.DEACON_STEWARD);var c=ask(h,f,3);
        h.succeedWhen(()->h.assertTrue(ReligionData.get(MinecraftApi.server(other)).aid(c.id()).stage()==AlmsCase.Stage.ISSUED&&count(f.alms())==3&&count(f.recipient().getPersonalInventory())==3&&f.deacon().getTransportInventory().isEmpty()&&other.getTransportInventory().isEmpty(),"one case, one take, one gift despite two deacons"));}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=800)
    public static void twoApplicantsCannotConsumeTheProtectedReserve(GameTestHelper h){var f=fixture(h,0,2,6,true);var first=ask(h,f,3);var second=npc(h,new BlockPos(14,1,9),NpcProfession.WORKER,false);var c2=ReligionService.request(MinecraftApi.server(second),f.temple(),second,FOOD,3,"declared second need");
        h.runAtTickTime(650,()->{long issued=List.of(first.id(),c2.id()).stream().filter(id->ReligionData.get(MinecraftApi.server(second)).aid(id).stage()==AlmsCase.Stage.ISSUED).count();h.assertTrue(issued==1&&count(f.alms())==3&&count(second.getPersonalInventory())+count(f.recipient().getPersonalInventory())==3,"exactly one portion, remaining stock not double-promised below floor");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void realDonationReplayIsOneTransferAndPurposeBound(GameTestHelper h){var f=fixture(h,0,2,0,false);var donor=npc(h,new BlockPos(8,1,5),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));UUID id=UUID.randomUUID();var gift=ReligionService.donate(MinecraftApi.server(donor),id,donor,f.temple(),FOOD,6,DonationRecord.Purpose.ALMS);var replay=ReligionService.donate(MinecraftApi.server(donor),id,donor,f.temple(),FOOD,6,DonationRecord.Purpose.ALMS);
        h.assertTrue(gift.status()==DonationRecord.Status.RECORDED&&replay.equals(gift)&&count(f.alms())==6&&count(donor.getPersonalInventory())==0,"real donor-to-alms transfer exactly once");boolean rejected=false;try{ReligionService.donate(MinecraftApi.server(donor),id,donor,f.temple(),FOOD,5,DonationRecord.Purpose.ALMS);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"different quantity cannot reuse consent id");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void fullDonationStoreDoesNotDeductOrRecordSuccess(GameTestHelper h){var f=fixture(h,0,2,0,false);fill(f.alms());var donor=npc(h,new BlockPos(8,1,5),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));var gift=ReligionService.donate(MinecraftApi.server(donor),UUID.randomUUID(),donor,f.temple(),FOOD,6,DonationRecord.Purpose.ALMS);h.assertTrue(gift.status()==DonationRecord.Status.FAILED&&count(donor.getPersonalInventory())==6&&count(f.alms())==0,"capacity failure neither deducts nor falsely acknowledges");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void borrowedFoodCannotBeDonatedWithoutTransferRight(GameTestHelper h){var f=fixture(h,0,2,0,false);var donor=npc(h,new BlockPos(8,1,5),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));var rights=RightsStateData.get(MinecraftApi.server(donor));UUID bag=ContainerIds.npcPersonal(donor.getCharacterId());var custodian=SubjectRef.character(donor.getCharacterId());rights.ensureContext(bag,"donor",custodian,custodian,AccessPolicy.PRIVATE);rights.putClaim(new AssetClaim(UUID.randomUUID(),bag,0,FOOD,6,SubjectRef.character(UUID.randomUUID()),custodian,Set.of(),Set.of(),"borrowed",0));
        var gift=ReligionService.donate(MinecraftApi.server(donor),UUID.randomUUID(),donor,f.temple(),FOOD,6,DonationRecord.Purpose.ALMS);h.assertTrue(gift.status()==DonationRecord.Status.FAILED&&count(donor.getPersonalInventory())==6&&count(f.alms())==0,"physical possession is not donation authority");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=800)
    public static void generalTreasuryDonationIsNotAutomaticallyAlmsStock(GameTestHelper h){var f=fixture(h,0,2,2,true);var donor=npc(h,new BlockPos(12,1,3),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));ReligionService.donate(MinecraftApi.server(donor),UUID.randomUUID(),donor,f.temple(),FOOD,6,DonationRecord.Purpose.GENERAL);ask(h,f,3);
        h.runAtTickTime(650,()->{h.assertTrue(count(f.treasury())==6&&count(f.alms())==2&&count(f.recipient().getPersonalInventory())==0,"deacon does not appropriate general treasury for alms");h.succeed();});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1200)
    public static void sameFactionTemplesHaveIndependentAssetsAndOffices(GameTestHelper h){var first=fixture(h,0,2,6,true);var second=fixture(h,32,2,6,false);var c=ask(h,first,3);
        h.succeedWhen(()->{h.assertTrue(ReligionData.get(MinecraftApi.server(first.deacon())).aid(c.id()).stage()==AlmsCase.Stage.ISSUED,"first local case issued");h.assertTrue(count(first.alms())==3&&count(second.alms())==6,"same church category never shares stock");h.assertTrue(!ReligionService.authorized(first.deacon(),second.temple().id(),OfficeAuthority.MANAGE_ALMS),"first deacon cannot manage second temple");});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=300)
    public static void ordinaryKnowledgeAndNpcViewExcludePrivateCaseTruth(GameTestHelper h){var f=fixture(h,0,2,6,false);var c=ReligionService.request(MinecraftApi.server(f.clergy()),f.temple(),f.recipient(),FOOD,3,"PHASE7_PRIVATE_STATEMENT");
        h.assertTrue(ReligionService.knownRecords(MinecraftApi.server(f.clergy()),UUID.randomUUID(),f.temple().id()).isEmpty(),"unknown event not globally public");var viewer=new net.minecraft.server.level.ServerPlayer(MinecraftApi.server(f.clergy()),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"religionviewer"),net.minecraft.server.level.ClientInformation.createDefault());viewer.setPos(f.clergy().getX()+1,f.clergy().getY(),f.clergy().getZ());String view=com.jgeted.sagadyssey.knowledge.projection.NpcProjectionService.forViewer(viewer,f.clergy()).toString();h.assertTrue(!view.contains("PHASE7_PRIVATE_STATEMENT")&&!view.contains(c.id().toString()),"ordinary NPC projection excludes private statement, exact stock and backstage ids");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7_reload",timeoutTicks=1700)
    public static void actualCarryingCaseDiskReloadResumesWithoutSecondPickup(GameTestHelper h){var f=fixture(h,0,2,6,true);fill(f.recipient().getPersonalInventory());var c=ask(h,f,3);NpcBase[] deacon={f.deacon()};
        h.runAtTickTime(250,()->{h.assertTrue(count(deacon[0].getTransportInventory())==3,"real cargo before disk reload");deacon[0]=reload(h,deacon[0]);f.recipient().getPersonalInventory().setItem(0,ItemStack.EMPTY);});
        h.succeedWhen(()->h.assertTrue(ReligionData.get(MinecraftApi.server(deacon[0])).aid(c.id()).stage()==AlmsCase.Stage.ISSUED&&count(f.alms())==3&&count(f.recipient().getPersonalInventory())==3&&deacon[0].getTransportInventory().isEmpty(),"same case after real disk reload, one pickup and one gift"));}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1600)
    public static void storesOriginalDonationAndCarryingDeaconForNewProcess(GameTestHelper h){var origin=h.absolutePos(new BlockPos(12288,1,12288));force(h.getLevel(),origin,origin.offset(24,0,12));var f=fixture(h,12288,2,0,true);var donor=npc(h,new BlockPos(12296,1,12293),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));UUID gift=UUID.randomUUID();ReligionService.donate(MinecraftApi.server(donor),gift,donor,f.temple(),FOOD,6,DonationRecord.Purpose.ALMS);fill(f.recipient().getPersonalInventory());var c=ask(h,f,3);
        h.succeedWhen(()->{h.assertTrue(count(f.deacon().getTransportInventory())==3,"original real charity cargo before checkpoint: stage="+ReligionData.get(h.getLevel().getServer()).aid(c.id()).stage()+" reason="+ReligionData.get(h.getLevel().getServer()).aid(c.id()).detail());f.deacon().setCommand(NpcCommand.STAY);NpcBrainRuntime.cancel(f.deacon(),PlanCancellationReason.MANUAL,"park original charity body");var v=new CompoundTag();v.store("Deacon", UUIDUtil.CODEC,f.deacon().getUUID());v.store("Recipient", UUIDUtil.CODEC,f.recipient().getUUID());v.store("Donor", UUIDUtil.CODEC,donor.getUUID());v.store("Temple", UUIDUtil.CODEC,f.temple().id());v.store("Case", UUIDUtil.CODEC,c.id());v.store("Donation", UUIDUtil.CODEC,gift);v.putLong("Position",f.deacon().blockPosition().asLong());v.putLong("Counter",f.temple().counter().asLong());v.putLong("Alms",f.temple().alms().position().asLong());v.putLong("Time",h.getLevel().getGameTime());
            try{NbtIo.writeCompressed(v,h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("phase7-restart-checkpoint.nbt"));h.getLevel().getServer().saveEverything(true,true,true);h.succeed();}catch(Exception x){throw new IllegalStateException("phase7 checkpoint save",x);}});}

    @GameTest(template="fieldwork_empty",batch="phase7_restart",timeoutTicks=1700)
    public static void mandatoryNewProcessKeepsDonationAndSameCarryingDeacon(GameTestHelper h){if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}h.assertTrue(previous!=null,"mandatory previous-process checkpoint");var level=h.getLevel();var pos=BlockPos.of(previous.getLongOr("Position", 0L));var counter=BlockPos.of(previous.getLongOr("Counter", 0L));force(level,BlockPos.of(previous.getLongOr("Alms", 0L)),counter.offset(3,0,5));boolean[] resumed={false};
        h.succeedWhen(()->{var deacon=(NpcBase)level.getEntity(previous.read("Deacon", UUIDUtil.CODEC).orElseThrow());var recipient=(NpcBase)level.getEntity(previous.read("Recipient", UUIDUtil.CODEC).orElseThrow());var donor=(NpcBase)level.getEntity(previous.read("Donor", UUIDUtil.CODEC).orElseThrow());h.assertTrue(deacon!=null&&recipient!=null&&donor!=null,"original three physical bodies restored, no replacements");var d=ReligionData.get(level.getServer());var t=d.temple(previous.read("Temple", UUIDUtil.CODEC).orElseThrow());
            if(!resumed[0]){h.assertTrue(count(deacon.getTransportInventory())==3&&count(donor.getPersonalInventory())==0,"saved institutional cargo and actual donor deduction");h.assertTrue(ReligionService.donate(level.getServer(),previous.read("Donation", UUIDUtil.CODEC).orElseThrow(),donor,t,FOOD,6,DonationRecord.Purpose.ALMS).status()==DonationRecord.Status.RECORDED,"old consent replay does not deduct twice");recipient.getPersonalInventory().setItem(0,ItemStack.EMPTY);deacon.setCommand(NpcCommand.WORK);resumed[0]=true;}
            h.assertTrue(d.aid(previous.read("Case", UUIDUtil.CODEC).orElseThrow()).stage()==AlmsCase.Stage.ISSUED&&count(recipient.getPersonalInventory())==3&&count(SupplyFlowService.container(level.getServer(),t.alms()))==3,"same saved portion, no cloned food or replayed donation");});}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=2000)
    public static void shortageCreatesRealLongRangeSupplyThenOriginalRelief(GameTestHelper h){
        var f=fixture(h,0,2,2,true);for(int x=24;x<48;x++)for(int z=0;z<12;z++)h.setBlock(x,0,z,Blocks.STONE);h.setBlock(40,1,5,Blocks.BARREL);
        var server=h.getLevel().getServer();var position=h.absolutePos(new BlockPos(40,1,5));String dim=h.getLevel().dimension().identifier().toString();UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dim,position);var owner=SubjectRef.institution(f.temple().id());RightsStateData.get(server).ensureContext(id,"known temple supply",owner,owner,AccessPolicy.INSTITUTION);
        var point=new com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage(dim,position,id,owner,AccessPolicy.INSTITUTION,TransferIntent.DELIVERY,"known_temple_supply");SupplyFlowData.get(server).register(point);SupplyFlowData.get(server).reveal(owner,id);var source=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(40,1,5));source.setItem(0,new ItemStack(Items.BREAD,6));var c=ask(h,f,3);double[] furthest={f.deacon().getX()};
        h.succeedWhen(()->{furthest[0]=Math.max(furthest[0],f.deacon().getX());h.assertTrue(ReligionData.get(server).aid(c.id()).stage()==AlmsCase.Stage.ISSUED,"original relief after actual restocking: "+diagnostic(f.deacon()));h.assertTrue(count(source)==3&&count(f.alms())==2&&count(f.recipient().getPersonalInventory())==3,"supply and alms all conserve food");h.assertTrue(furthest[0]>h.absolutePos(new BlockPos(32,1,5)).getX(),"actually walked to known supply beyond 20 blocks");h.assertTrue(NeedRequestData.get(server).requests().stream().anyMatch(r->r.key().requester().equals(f.temple().id())&&r.status()==NeedRequestStatus.DELIVERED),"traceable durable supply receipt");});
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void playerDonationUsesExistingPlayerInventoryTitleNotANpcAlias(GameTestHelper h){
        var f=fixture(h,0,2,0,false);var player=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"actualdonor"),net.minecraft.server.level.ClientInformation.createDefault());var pos=h.absolutePos(new BlockPos(8,1,5));player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);player.getInventory().setItem(0,new ItemStack(Items.BREAD,6));
        var server=h.getLevel().getServer();UUID character=ReligionService.character(player),bag=ContainerIds.playerPersonal(character);var actor=SubjectRef.character(character);var rights=RightsStateData.get(server);rights.ensureContext(bag,"existing-player-inventory",actor,actor,AccessPolicy.PRIVATE);UUID claim=UUID.randomUUID();rights.putClaim(new AssetClaim(claim,bag,0,FOOD,6,SubjectRef.character(UUID.randomUUID()),actor,Set.of(),Set.of(),"borrowed player food",0));
        var refused=ReligionService.donate(server,UUID.randomUUID(),player,f.temple(),FOOD,3,DonationRecord.Purpose.ALMS);h.assertTrue(refused.status()==DonationRecord.Status.FAILED&&count(player.getInventory())==6&&count(f.alms())==0,"real player inventory respects existing borrowed title");h.assertTrue(rights.context(ContainerIds.npcPersonal(character))==null,"no second title ledger for the same physical player bag");
        rights.removeClaim(claim);var gift=ReligionService.donate(server,UUID.randomUUID(),player,f.temple(),FOOD,3,DonationRecord.Purpose.ALMS);h.assertTrue(gift.status()==DonationRecord.Status.RECORDED&&gift.source().equals(bag)&&count(player.getInventory())==3&&count(f.alms())==3,"owned real player food uses the original title identity");h.assertTrue(ReligionService.personalContainer(player).equals(bag),"player relief targets the same existing personal container");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void unresolvedRequestSurvivesChangingWindowWithoutASecondCase(GameTestHelper h){
        var f=fixture(h,0,2,0,false);var d=ReligionData.get(h.getLevel().getServer());long now=h.getLevel().getGameTime();
        var original=new AlmsCase(UUID.randomUUID(),f.temple().id(),f.recipient().getCharacterId(),FOOD,3,"earlier unresolved declaration",AlmsCase.Stage.REQUESTED,null,null,null,0,Math.max(0,now-24000),now,now,null,"prior window awaiting review");d.aid(original);int before=d.cases().size();
        var replay=ReligionService.request(MinecraftApi.server(f.recipient()),f.temple(),f.recipient(),FOOD,3,original.statement());h.assertTrue(replay.equals(original)&&d.cases().size()==before,"a fresh window identity cannot duplicate an unresolved request");
        boolean rejected=false;try{ReligionService.request(MinecraftApi.server(f.recipient()),f.temple(),f.recipient(),FOOD,2,original.statement());}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected&&d.cases().size()==before,"changing quantity cannot circumvent the outstanding case");
        var review=original.update(AlmsCase.Stage.REVIEW,null,now,now,null,"uncertain case awaits reconciliation",true);d.aid(review);h.assertTrue(ReligionService.request(MinecraftApi.server(f.recipient()),f.temple(),f.recipient(),FOOD,3,original.statement()).equals(review),"uncertain settlement is not silently forgotten");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void ordinaryServiceCommandsAreSeparateFromTesterTruth(GameTestHelper h){
        var server=h.getLevel().getServer();var dispatcher=server.getCommands().getDispatcher();var ordinary=server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL);var tester=server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
        var binding=dispatcher.parse("saga religion bind \"minecraft:overworld|-12644383039548|chapel_misty_moon\" -52 -60 165 -52 -60 169 -50 -60 167 2 3",tester);
        h.assertTrue(!binding.getReader().canRead()&&binding.getExceptions().isEmpty(),"quoted complete building key parses through every facility coordinate");
        var donation=dispatcher.parse("saga religion donate "+UUID.randomUUID()+" minecraft:bread 1 ALMS "+UUID.randomUUID(),ordinary);
        h.assertTrue(!donation.getReader().canRead()&&donation.getExceptions().isEmpty(),"nearby-menu donation with namespaced item is executable syntax");
        h.assertTrue(dispatcher.parse("saga religion audit",ordinary).getReader().canRead()&&dispatcher.parse("saga religion buildings",ordinary).getReader().canRead(),"ordinary sources cannot read all institutions or exact ledgers");h.assertTrue(!dispatcher.parse("saga religion audit",tester).getReader().canRead(),"tester separately accesses truth");h.assertTrue(!dispatcher.parse("saga religion records "+UUID.randomUUID(),ordinary).getReader().canRead()&&!dispatcher.parse("saga religion here",ordinary).getReader().canRead(),"ordinary own-knowledge and nearby service require no operator powers");h.succeed();
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=1200)
    public static void nearbyWitnessLearnsOnlyObservedEventAndDistantPlayerLearnsNothing(GameTestHelper h){
        var f=fixture(h,0,2,6,true);var c=ask(h,f,3);var near=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"localwitness"),net.minecraft.server.level.ClientInformation.createDefault());var remote=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"remotewitness"),net.minecraft.server.level.ClientInformation.createDefault());near.setPos(h.absolutePos(new BlockPos(14,1,4)).getX()+.5,h.absolutePos(new BlockPos(14,1,4)).getY(),h.absolutePos(new BlockPos(14,1,4)).getZ()+.5);remote.setPos(near.getX()+100,near.getY(),near.getZ());
        // Explicit fixture identities; observing an event must never create either observer.
        ReligionService.character(near);ReligionService.character(remote);
        h.succeedWhen(()->{var issued=ReligionData.get(h.getLevel().getServer()).aid(c.id());h.assertTrue(issued.stage()==AlmsCase.Stage.ISSUED,"real event must happen first");long now=h.getLevel().getGameTime();var forged=issued.update(AlmsCase.Stage.ISSUED,issued.reviewer(),now,now,null,"forged witness payload",false);h.assertTrue(!ReligionService.witness(near,f.deacon(),forged,now),"caller-supplied case truth cannot manufacture testimony");h.assertTrue(ReligionService.witness(near,f.deacon(),issued,now),"actual nearby visible witness learns event");h.assertTrue(!ReligionService.witness(remote,f.deacon(),issued,now),"remote player cannot become witness by querying ledger");var visible=ReligionService.knownRecords(h.getLevel().getServer(),ReligionService.character(near),f.temple().id());h.assertTrue(visible.size()==1&&!visible.getFirst().contains("minecraft:bread")&&!visible.getFirst().contains("qty="),"witness receives narrative evidence, not exact hidden inventory");h.assertTrue(ReligionService.knownRecords(h.getLevel().getServer(),ReligionService.character(remote),f.temple().id()).isEmpty(),"no global knowledge broadcast");});
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=500)
    public static void deathDropsRealAlmsWithoutClaimingRecipientWasPaid(GameTestHelper h){var f=fixture(h,0,2,6,true);fill(f.recipient().getPersonalInventory());var c=ask(h,f,3);
        h.runAtTickTime(250,()->{h.assertTrue(count(f.deacon().getTransportInventory())==3,"real institutional cargo before death");MinecraftApi.kill(f.deacon());});h.runAtTickTime(280,()->{var d=ReligionData.get(h.getLevel().getServer());h.assertTrue(d.aid(c.id()).stage()==AlmsCase.Stage.REVIEW&&d.receipt(c.id(),false)==null,"death is not charity payment");int drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,h.getBounds()).stream().filter(e->e.getItem().is(Items.BREAD)).mapToInt(e->e.getItem().getCount()).sum();h.assertTrue(drops==3&&count(f.alms())==3,"cargo drops once, unallocated reserve remains");h.succeed();});
    }

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void donationCannotReuseAnUnrelatedSuccessfulTransferReceipt(GameTestHelper h){var f=fixture(h,0,2,0,false);var donor=npc(h,new BlockPos(8,1,5),NpcProfession.WORKER,false);donor.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,6));UUID receipt=UUID.randomUUID();var rights=RightsStateData.get(MinecraftApi.server(donor));UUID from=ContainerIds.npcPersonal(donor.getCharacterId()),to=ContainerIds.npcPersonal(f.recipient().getCharacterId());var actor=SubjectRef.character(donor.getCharacterId());rights.ensureContext(from,"donor",actor,actor,AccessPolicy.PRIVATE);var target=SubjectRef.character(f.recipient().getCharacterId());rights.ensureContext(to,"recipient",target,target,AccessPolicy.PRIVATE);long now=h.getLevel().getGameTime();
        var transfer=new TransferRequest(receipt,actor,TransferIntent.GIFT,List.of(new TransferLeg(from,to,FOOD,3)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"unrelated voluntary gift",now);var actual=new TransferService(rights,TransferLedgerData.get(MinecraftApi.server(donor))).submit(transfer,Map.of(from,new TransferEndpoint(from,donor.getPersonalInventory()),to,new TransferEndpoint(to,f.recipient().getPersonalInventory())),now);h.assertTrue(actual.successful(),"a real unrelated transaction preceded attempted receipt reuse");boolean rejected=false;
        try{ReligionService.donate(MinecraftApi.server(donor),receipt,donor,f.temple(),FOOD,3,DonationRecord.Purpose.ALMS);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected&&count(f.alms())==0&&count(donor.getPersonalInventory())==3,"foreign receipt cannot create donated food or fake acknowledgment");h.succeed();}

    @GameTest(template="fieldwork_empty",batch="phase7",timeoutTicks=100)
    public static void missingOrForeignFacilitiesCannotBecomeSecondTempleInventory(GameTestHelper h){var f=fixture(h,0,2,0,false);int before=ReligionData.get(h.getLevel().getServer()).temples().size();boolean rejected=false;
        try{ChapelBootstrapService.bind(h.getLevel().getServer(),f.temple().building(),f.temple().treasury().position(),f.temple().alms().position().offset(5,0,0),f.temple().counter(),SubjectRef.institution(f.temple().id()),2,3);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected&&ReligionData.get(h.getLevel().getServer()).temples().size()==before,"existing binding cannot silently move to missing/foreign container");h.succeed();}

    private record Fixture(TempleRecord temple,NpcBase clergy,NpcBase deacon,NpcBase recipient,Container alms,Container treasury){}
    private static Fixture fixture(GameTestHelper h,int shift,int retained,int stock,boolean withDeacon){
        for(int x=0;x<24;x++)for(int z=0;z<12;z++)h.setBlock(x+shift,0,z+(shift>=1000?shift:0),Blocks.STONE);int z=shift>=1000?shift:0;
        if(shift>=1000)for(int x=0;x<24;x++)for(int dz=0;dz<12;dz++)for(int y=1;y<=6;y++)h.setBlock(x+shift,y,z+dz,Blocks.AIR); // Outside template: clear only this isolated fixture's natural obstacles.
        var anchor=h.absolutePos(new BlockPos(shift,1,z));String dim=h.getLevel().dimension().identifier().toString();var buildings=SettlementRegistryData.get(h.getLevel().getServer());buildings.registerGeneratedBuilding(h.getLevel().getServer(),dim,anchor,"sagadyssey:chapel_misty_moon","church","small",h.getLevel().getGameTime());var building=buildings.buildingAt(dim,anchor,"sagadyssey:chapel_misty_moon");
        h.setBlock(shift+12,1,z+2,Blocks.BARREL);h.setBlock(shift+4,1,z+5,Blocks.BARREL);var owner=SubjectRef.character(ContainerIds.named("phase7-founder:"+anchor.asLong()));var t=ChapelBootstrapService.bind(h.getLevel().getServer(),building.key(),h.absolutePos(new BlockPos(shift+12,1,z+2)),h.absolutePos(new BlockPos(shift+4,1,z+5)),h.absolutePos(new BlockPos(shift+14,1,z+5)),owner,retained,3);
        var clergy=npc(h,new BlockPos(shift+12,1,z+3),NpcProfession.BARD,true);ChapelBootstrapService.appoint(MinecraftApi.server(clergy),t,owner,clergy,TempleRecord.Role.CLERGY_OFFICIANT);NpcBase deacon=null;if(withDeacon){deacon=npc(h,new BlockPos(shift+3,1,z+7),NpcProfession.COURIER,true);ChapelBootstrapService.appoint(MinecraftApi.server(clergy),t,owner,deacon,TempleRecord.Role.DEACON_STEWARD);}var recipient=npc(h,new BlockPos(shift+14,1,z+7),NpcProfession.WORKER,false);var alms=(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(shift+4,1,z+5));if(stock>0)alms.setItem(0,new ItemStack(Items.BREAD,stock));return new Fixture(t,clergy,deacon,recipient,alms,(Container)com.jgeted.sagadyssey.gametest.PortedGameTests.blockEntity(h, new BlockPos(shift+12,1,z+2)));
    }
    private static AlmsCase ask(GameTestHelper h,Fixture f,int qty){return ReligionService.request(h.getLevel().getServer(),f.temple(),f.recipient(),FOOD,qty,"at-counter declared hardship");}
    private static NpcBase npc(GameTestHelper h,BlockPos p,NpcProfession profession,boolean work){var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),p);n.setCharacterId(UUID.randomUUID());n.setProfession(profession);n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);n.setPersistenceRequired();n.setCommand(work?NpcCommand.WORK:NpcCommand.STAY);var registry=CharacterRegistryData.get(MinecraftApi.server(n));registry.ensureNpcCharacter(n.getCharacterId(),n.getName().getString(),n.getSocialClass(),n.getOffice(),"sagadyssey:church",h.getLevel().getGameTime());registry.bindNpcBody(n.getCharacterId(),h.getLevel().dimension().identifier().toString(),n.getUUID(),h.getLevel().getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static int count(Container c){return SupplyFlowService.count(c,FOOD);}
    private static void fill(Container c){for(int i=0;i<c.getContainerSize();i++)c.setItem(i,new ItemStack(Items.COBBLESTONE,64));}
    private static String diagnostic(NpcBase n){return "pos="+n.blockPosition()+" active="+NpcBrainRuntime.data(MinecraftApi.server(n)).active(n.getUUID()).map(p->p.currentStep().detail()).orElse("none")+" history="+NpcBrainRuntime.data(MinecraftApi.server(n)).history(n.getUUID()).stream().map(NpcPlan::explanation).toList();}
    private static void force(ServerLevel level,BlockPos from,BlockPos to){for(int x=(Math.min(from.getX(),to.getX())>>4)-1;x<=(Math.max(from.getX(),to.getX())>>4)+1;x++)for(int z=(Math.min(from.getZ(),to.getZ())>>4)-1;z<=(Math.max(from.getZ(),to.getZ())>>4)+1;z++){level.setChunkForced(x,z,true);level.getChunk(x,z);}}
    private static NpcBase reload(GameTestHelper h,NpcBase n){var server=MinecraftApi.server(n);var storage=server.overworld().getDataStorage();try{var file=server.getWorldPath(LevelResource.ROOT).resolve("phase7-body-"+n.getUUID()+".nbt");NbtIo.writeCompressed(MinecraftApi.saveEntity(n),file);storage.saveAndJoin();int version=SharedConstants.getCurrentVersion().dataVersion().version();storage.set(NpcBrainData.FACTORY.type(NpcBrainData.DATA_NAME),NpcBrainData.FACTORY.deserializer().apply(storage.readTagFromDisk(NpcBrainData.DATA_NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));storage.set(ReligionData.FACTORY.type(ReligionData.NAME),ReligionData.load(storage.readTagFromDisk(ReligionData.NAME,null,version).getCompoundOrEmpty("data"),server.registryAccess()));n.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);var replacement=new NpcBase(NpcEntityTypes.NPC_BASE.get(),h.getLevel());MinecraftApi.loadEntity(replacement, NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()));if(!com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(n).equals(com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventory(replacement)))throw new IllegalStateException("religion original inventory serialization mismatch before="+com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventoryTag(n)+" after="+com.jgeted.sagadyssey.worldsim.lod.LodFingerprints.inventoryTag(replacement));h.getLevel().addFreshEntity(replacement);NpcBrainRuntime.rebuildAfterDataReload(server);return replacement;}catch(Exception x){throw new IllegalStateException("real religion disk reload",x);}}
}
