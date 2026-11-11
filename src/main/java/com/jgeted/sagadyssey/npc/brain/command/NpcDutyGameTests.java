package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.social.service.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import static com.jgeted.sagadyssey.npc.brain.command.NpcDutyData.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
public final class NpcDutyGameTests {
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=180)
    public static void realRangedGoalCannotFireAgainAfterLastPhysicalArrow(GameTestHelper h){var n=npc(h,NpcProfession.ARCHER,2);var target=h.spawn(EntityType.COW,new BlockPos(9,1,5));target.setNoAi(true);target.setInvulnerable(true);n.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));n.getPersonalInventory().setItem(0,new ItemStack(Items.ARROW));n.ensureRangedMode();n.setTarget(target);
        var launched=new java.util.HashSet<UUID>();h.onEachTick(()->h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,h.getBounds().inflate(256),a->a.getOwner()==n).forEach(a->launched.add(a.getUUID())));
        h.runAtTickTime(120,()->{h.assertTrue(n.getPersonalInventory().count(s->s.is(Items.ARROW))==0,"real ranged goal spent final physical arrow");h.assertTrue(launched.size()==1,"one observed physical projectile UUID, no unlimited ammo replay: "+launched);h.succeed();});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=120)
    public static void strangersOrderDoesNotGrantAutomaticWorkAuthority(GameTestHelper h){var n=npc(h,NpcProfession.BLACKSMITH,4);var order=order(h,n);NpcBrainRuntime.cancel(n,PlanCancellationReason.MANUAL,"close explicit fixture before stranger order");var stranger=SubjectRef.character(UUID.randomUUID());var foreign=ProductionOrder.open(UUID.randomUUID(),stranger,stranger,order.recipeId(),order.inputs(),order.output(),80,UUID.randomUUID(),order.workstationDimension(),order.workstationPosition(),order.targetContainerId(),order.targetContainerDimension(),order.targetContainerPosition(),h.getLevel().getGameTime(),"stranger request is not work authority");ProductionOrderData.get(MinecraftApi.server(n)).put(foreign);
        h.runAtTickTime(100,()->{h.assertTrue(ProductionOrderData.get(MinecraftApi.server(n)).byId(foreign.id()).orElseThrow().status()==ProductionOrderStatus.OPEN&&!NpcBrainRuntime.mayWorkOrder(n,foreign,h.getLevel().getGameTime()),"global order existence did not enroll unrelated worker");h.succeed();});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=120)
    public static void patientRevocationStopsWorkWithoutConsumingBandage(GameTestHelper h){var doctor=npc(h,NpcProfession.MEDIC,4);var patient=npc(h,NpcProfession.WARRIOR,5);patient.setNoAi(true);var d=medical(doctor,patient);
        h.runAtTickTime(8,()->{h.assertTrue(NpcDutyEvidenceData.get(MinecraftApi.server(doctor)).revoke(patient.getCharacterId(),d.id()),"original patient revoked consent");});
        h.succeedWhen(()->{h.assertTrue(NpcDutyData.get(MinecraftApi.server(doctor)).find(d.id()).status()==Status.CANCELLED,"revoked consent terminates duty before settlement");h.assertTrue(doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==1&&InjuryData.get(MinecraftApi.server(doctor)).treatment(d.id())==null,"no supply spent against patient refusal");h.assertTrue(RightsStateData.get(MinecraftApi.server(doctor)).reservationsForToken(d.token(),h.getLevel().getGameTime()).isEmpty(),"revocation releases dressing rights");});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=180)
    public static void severeHungerPreemptsOrdinaryHoldUsingOnlyRealFood(GameTestHelper h){var n=npc(h,NpcProfession.WARRIOR,4);var p=player(h);ServiceRelationshipData.get(p.level().getServer()).beginVoluntaryEmployment(CharacterRegistry.ensureCurrent(p).id(),n.getCharacterId(),h.getLevel().getGameTime());
        var command=NpcCommandService.issue(p,n,NpcCommand.STAY,n.blockPosition(),4,500).intent();n.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD));
        h.runAtTickTime(15,()->NpcLifeData.get(MinecraftApi.server(n)).setHunger(n.getCharacterId(),850,h.getLevel().getGameTime()));
        h.succeedWhen(()->{h.assertTrue(h.getTick()>30&&NpcLifeData.get(MinecraftApi.server(n)).needs(n.getCharacterId()).hunger()<800,"actual eating relieved survival need while ordinary STAY requested");h.assertTrue(n.getPersonalInventory().count(s->s.is(Items.BREAD))==0,"one physical food consumed");h.assertTrue(NpcCommandData.get(MinecraftApi.server(n)).find(command.id()).status()==NpcCommandIntent.Status.ACTIVE,"original authorized command remains finite and resumes after urgent need");});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=100)
    public static void backstageDutyTruthIsNotAnOrdinaryCommand(GameTestHelper h){var dispatcher=h.getLevel().getServer().getCommands().getDispatcher();h.assertTrue(dispatcher.parse("saga npc brain duties @e",h.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL)).getReader().canRead(),"ordinary player cannot read all duties or interruption receipts");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=450)
    public static void consentedMedicalDutyActuallyMovesAndConsumesOnce(GameTestHelper h){var doctor=npc(h,NpcProfession.MEDIC,2);var patient=npc(h,NpcProfession.WARRIOR,9);patient.setNoAi(true);var job=medical(doctor,patient);
        h.succeedWhen(()->{h.assertTrue(NpcDutyData.get(MinecraftApi.server(doctor)).find(job.id()).status()==Status.COMPLETED,"medical job completed at original patient");h.assertTrue(doctor.distanceToSqr(patient)<=6.25&&doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==0,"real movement and bandage consumption");
            h.assertTrue(InjuryData.get(MinecraftApi.server(doctor)).treatment(job.id())!=null&&InjuryData.get(MinecraftApi.server(doctor)).wound(job.source()).bleeding()<=20,"exact durable treatment reduced real bleeding");});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=350)
    public static void twoDoctorsCannotSpendTwiceOnOneWound(GameTestHelper h){var a=npc(h,NpcProfession.MEDIC,4);var b=npc(h,NpcProfession.MEDIC,6);var patient=npc(h,NpcProfession.WARRIOR,5);patient.setNoAi(true);var first=medical(a,patient);
        var second=copyMedical(b,patient,first.source());
        h.succeedWhen(()->{var injuries=InjuryData.get(MinecraftApi.server(a));h.assertTrue(injuries.treatment(first.id())!=null||injuries.treatment(second.id())!=null,"one doctor committed");
            h.assertTrue(injuries.treatments().stream().filter(t->t.woundId().equals(first.source())).count()==1,"one treatment receipt, no double dressing");
            h.assertTrue(a.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))+b.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==1,"only one of two real bandages consumed");});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=350)
    public static void threatReleasesBandageAndResumesOriginalMedicalReceipt(GameTestHelper h){var doctor=npc(h,NpcProfession.MEDIC,4);var patient=npc(h,NpcProfession.WARRIOR,5);patient.setNoAi(true);var d=medical(doctor,patient);var cow=h.spawn(EntityType.COW,new BlockPos(7,1,5));cow.setNoAi(true);cow.setInvulnerable(true);
        h.runAtTickTime(10,()->doctor.setTarget(cow));h.runAtTickTime(30,()->{h.assertTrue(RightsStateData.get(MinecraftApi.server(doctor)).reservationsForToken(d.token(),h.getLevel().getGameTime()).isEmpty(),"combat releases unconsumed bandage");h.assertTrue(NpcDutyData.get(MinecraftApi.server(doctor)).find(d.id()).worked()<28,"battle did not count as treatment");});
        h.runAtTickTime(80,()->{cow.discard();doctor.setTarget(null);});h.succeedWhen(()->h.assertTrue(h.getTick()>100&&NpcDutyData.get(MinecraftApi.server(doctor)).find(d.id()).status()==Status.COMPLETED,"same duty identity resumed and settled once"));}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=180)
    public static void vanishedPatientCannotReceiveRemoteTreatment(GameTestHelper h){var doctor=npc(h,NpcProfession.MEDIC,2);var patient=npc(h,NpcProfession.WARRIOR,9);var d=medical(doctor,patient);patient.discard();
        h.succeedWhen(()->{h.assertTrue(NpcDutyData.get(MinecraftApi.server(doctor)).find(d.id()).status()==Status.CANCELLED,"original patient missing cancels, not retargets");h.assertTrue(doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==1&&InjuryData.get(MinecraftApi.server(doctor)).treatment(d.id())==null,"no remote supply consumption or treatment");});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=200)
    public static void plannedRetreatHasFiniteMovementWithoutFreeHealing(GameTestHelper h){var n=npc(h,NpcProfession.WARRIOR,4);n.setHealth(4);var cow=h.spawn(EntityType.COW,new BlockPos(2,1,5));cow.setNoAi(true);cow.setInvulnerable(true);n.setTarget(cow);double before=n.getX();
        h.runAtTickTime(100,()->{h.assertTrue(n.getTarget()==null&&n.getX()>before+2,"real escape, not continuing attack or teleport");h.assertTrue(n.getHealth()<=4,"retreat does not create free healing");h.assertTrue(!new com.jgeted.sagadyssey.npc.ai.LowHpRetreatGoal(n).canUse(),"legacy heal goal disabled");h.succeed();});}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=60)
    public static void noEmergencyAuthorityMeansNoWorkOrTargetMutation(GameTestHelper h){var n=npc(h,NpcProfession.WARRIOR,4);var p=player(h);var plan=NpcBrainRuntime.createDiagnosticWait(n,100);
        h.assertTrue(!NpcDutyTask.requestWithdrawal(p,n)&&NpcBrainRuntime.data(MinecraftApi.server(n)).active(n.getUUID()).orElseThrow().id().equals(plan.id()),"unauthorized emergency cannot cancel existing obligation");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=60)
    public static void lastRealArrowLaunchConsumesExactlyOneAndCannotRepeat(GameTestHelper h){var n=npc(h,NpcProfession.ARCHER,4);n.setNoAi(true);n.getPersonalInventory().setItem(0,new ItemStack(Items.ARROW));var ammo=n.getPersonalInventory().getItem(0).copy();var a=arrow(h,n);
        h.assertTrue(NpcAmmunitionService.launch(n,a,ammo)&&n.getPersonalInventory().getItem(0).isEmpty(),"one physical arrow launched and consumed");h.assertTrue(!NpcAmmunitionService.launch(n,arrow(h,n),ammo),"no ghost arrow after original stock consumed");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=60)
    public static void failedPhysicalArrowSpawnRollsBackInventoryAndRights(GameTestHelper h){var n=npc(h,NpcProfession.ARCHER,4);n.setNoAi(true);n.getPersonalInventory().setItem(0,new ItemStack(Items.ARROW));var occupying=arrow(h,n);h.getLevel().addFreshEntity(occupying);var duplicate=arrow(h,n);duplicate.setUUID(occupying.getUUID());
        h.assertTrue(!NpcAmmunitionService.launch(n,duplicate,new ItemStack(Items.ARROW))&&n.getPersonalInventory().count(s->s.is(Items.ARROW))==1,"failed real spawn returned original ammo and title");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=60)
    public static void reservedArrowCannotBeUsedAsFreeCombatAmmo(GameTestHelper h){var n=npc(h,NpcProfession.ARCHER,4);n.setNoAi(true);n.getPersonalInventory().setItem(0,new ItemStack(Items.ARROW));var rights=RightsStateData.get(MinecraftApi.server(n));var bag=ContainerIds.npcPersonal(n.getCharacterId());var actor=SubjectRef.character(n.getCharacterId());long now=h.getLevel().getGameTime();rights.ensureContext(bag,"npc:ammo-test",actor,actor,AccessPolicy.PRIVATE);
        rights.reserve(new AssetReservation(UUID.randomUUID(),bag,ItemFingerprint.of(new ItemStack(Items.ARROW)),1,actor,"other-promise","another legitimate obligation",now,now+100),1,now);
        h.assertTrue(!NpcAmmunitionService.launch(n,arrow(h,n),new ItemStack(Items.ARROW))&&n.getPersonalInventory().count(s->s.is(Items.ARROW))==1,"reserved physical ammo is not silently stolen by combat");h.succeed();}
    @GameTest(template="fieldwork_empty",batch="phase9_duties",timeoutTicks=500)
    public static void blacksmithBattleDoesNotAdvanceWorkAndReleasesThenReacquiresMaterials(GameTestHelper h){var n=npc(h,NpcProfession.BLACKSMITH,4);var order=order(h,n);var cow=h.spawn(EntityType.COW,new BlockPos(8,1,5));cow.setNoAi(true);cow.setInvulnerable(true);int[] before={0};
        h.runAtTickTime(35,()->{before[0]=ProductionLaborData.get(MinecraftApi.server(n)).worked(order.id());h.assertTrue(before[0]>0&&before[0]<80,"partly worked original order before interruption");n.setTarget(cow);});
        h.runAtTickTime(100,()->{h.assertTrue(ProductionLaborData.get(MinecraftApi.server(n)).worked(order.id())<=before[0]+2,"battle time is not labor");h.assertTrue(RightsStateData.get(MinecraftApi.server(n)).reservationsForToken("production:"+order.batchId(),h.getLevel().getGameTime()).isEmpty(),"unconsumed input reservations released");cow.discard();n.setTarget(null);});
        h.succeedWhen(()->{h.assertTrue(h.getTick()>150&&ProductionOrderData.get(MinecraftApi.server(n)).byId(order.id()).orElseThrow().status()==ProductionOrderStatus.COMPLETED,"original obligation resumed, worked and settled; order="+ProductionOrderData.get(MinecraftApi.server(n)).byId(order.id())+" schedule="+NpcSchedulePlanner.current(n)+" plan="+NpcBrainRuntime.data(MinecraftApi.server(n)).active(n.getUUID())+" history="+NpcBrainRuntime.data(MinecraftApi.server(n)).history(n.getUUID()));h.assertTrue(NpcInterruptionData.get(MinecraftApi.server(n)).all().stream().anyMatch(r->r.candidate().equals("production-order:"+order.id())&&r.continuation()!=null),"durable link to newly validated continuation, terminal old plan not resurrected");});}
    static ProductionOrder order(GameTestHelper h,NpcBase npc){h.setBlock(5,1,5,Blocks.ANVIL);h.setBlock(3,1,5,Blocks.BARREL);var station=h.absolutePos(new BlockPos(5,1,5));var pos=h.absolutePos(new BlockPos(3,1,5));var box=(net.minecraft.world.Container)h.getLevel().getBlockEntity(pos);box.setItem(0,new ItemStack(Items.IRON_INGOT,3));box.setItem(1,new ItemStack(Items.COAL));var owner=SubjectRef.character(npc.getCharacterId());var container=ProductionContainerIds.resolve(MinecraftApi.server(npc),"minecraft:overworld",pos);
        RightsStateData.get(MinecraftApi.server(npc)).ensureContext(container,"test:phase9-forge",owner,owner,AccessPolicy.CONTRACT);long now=h.getLevel().getGameTime();var d=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:test_iron_pickaxe",List.of(new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_INGOT)),3),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.COAL)),1)),new ProductionMaterial(ItemFingerprint.of(new ItemStack(Items.IRON_PICKAXE)),1),80,UUID.randomUUID(),"minecraft:overworld",station,container,"minecraft:overworld",pos,now,"real original-order work interruption fixture");ProductionOrderData.get(MinecraftApi.server(npc)).put(d);npc.setCommand(NpcCommand.WORK);NpcBrainRuntime.createBlacksmithOrderPlan(npc,d.id());return d;}
    static Duty medical(NpcBase doctor,NpcBase patient){return medical(doctor,patient,1000);}
    static Duty medical(NpcBase doctor,NpcBase patient,int lifetime){var injuries=InjuryData.get(MinecraftApi.server(doctor));long now=doctor.level().getGameTime();var wound=InjuryService.recordHit(injuries,UUID.randomUUID(),patient.getCharacterId(),null,WoundType.CUT,6,0,now,null);return copyMedical(doctor,patient,wound.id(),lifetime);}
    static Duty copyMedical(NpcBase doctor,NpcBase patient,UUID wound){return copyMedical(doctor,patient,wound,1000);}
    private static Duty copyMedical(NpcBase doctor,NpcBase patient,UUID wound,int lifetime){doctor.getPersonalInventory().setItem(0,new ItemStack(Items.WHITE_WOOL));doctor.setCommand(NpcCommand.WORK);long now=doctor.level().getGameTime();var d=new Duty(UUID.randomUUID(),doctor.getUUID(),doctor.getCharacterId(),patient.getUUID(),patient.getCharacterId(),Kind.MEDICAL,wound,List.of(),"minecraft:overworld",patient.blockPosition(),28,now,now+lifetime,0,now,Status.OPEN,"explicit consent fixture, real execution still required");NpcDutyData.get(MinecraftApi.server(doctor)).put(d);NpcDutyEvidenceData.get(MinecraftApi.server(doctor)).put(new NpcDutyEvidenceData.Evidence(d.id(),Kind.MEDICAL,wound,patient.getCharacterId(),doctor.getCharacterId(),patient.getCharacterId(),d.expires(),false));return d;}
    static Arrow arrow(GameTestHelper h,NpcBase n){var a=new Arrow(EntityType.ARROW,h.getLevel());a.setPos(n.getX(),n.getY()+1,n.getZ());a.setOwner(n);return a;}
    static NpcBase npc(GameTestHelper h,NpcProfession profession,int x){for(int i=0;i<12;i++)for(int j=0;j<12;j++)h.setBlock(i,0,j,Blocks.STONE);var n=h.spawn(NpcEntityTypes.NPC_BASE.get(),new BlockPos(x,1,5));n.setProfession(profession);n.getPersonalInventory().clearContent();n.setCommand(NpcCommand.IDLE);n.setPersistenceRequired();NpcLifeData.get(MinecraftApi.server(n)).ensureRegistered(n,h.getLevel().getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static ServerPlayer player(GameTestHelper h){return new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"p9duty"),ClientInformation.createDefault());}
}
