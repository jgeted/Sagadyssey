package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.politics.NaturalPolityData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Declared independent counterexamples, not replacements for the positive causal world. */
@GameTestHolder("sagadyssey_development") @PrefixGameTestTemplate(false)
public final class SettlementDevelopmentRecoveryGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        String phase=System.getProperty("sagadyssey.development.phase","");
        if(!Set.of("counter-guards","counter-obstruction","counter-withdraw","counter-shortage").contains(phase))return List.of();
        return List.of(new TestFunction("development-counter",phase,"sagadyssey_development:empty",100000,0,true,h->new Run(h,phase).start()));
    }
    static void check(boolean v,String why){if(!v)throw new GameTestAssertException(why);}
    static final class Run {
        final GameTestHelper h;final ServerLevel l;final String phase;final List<NaturalVillageBuilding> villages;
        long started=-1,changed=-1,eligible=-1;int stage,placedBefore,heldBefore;String project;UUID home;
        BlockPos obstruction;net.minecraft.world.level.block.state.BlockState before;CompoundTag proof=new CompoundTag();
        Run(GameTestHelper h,String p){this.h=h;l=h.getLevel();phase=p;villages=List.of(NaturalVillageR1GameTests.fixture(h,0,SettlementDevelopmentLayout.TEMPLATE),NaturalVillageR1GameTests.fixture(h,256,NaturalVillageLayout.FARM_V3),NaturalVillageR1GameTests.fixture(h,512,NaturalVillageLayout.FARM_V3));}
        NaturalVillageBuilding target(){return villages.getFirst();}
        void start(){
            var s=l.getServer();s.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true,s);l.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,s);s.tickRateManager().requestGameToSprint(100000);h.onEachTick(this::tick);
        }
        void tick(){
            var s=l.getServer();long now=l.getGameTime();var d=SettlementDevelopmentData.existing(s);var natural=NaturalVillageLifeData.existing(s);
            if(d==null||natural==null||d.site(target().buildingKey())==null||villages.stream().anyMatch(b->!natural.village(b.buildingKey()).getBoolean("Ready")))return;
            if(started<0){
                SettlementDevelopmentGameTests.observeRoute(l,villages);SettlementDevelopmentGameTests.configureInitial(l,target(),villages.get(1),villages.get(2));
                if(phase.equals("counter-guards"))for(var b:villages.subList(1,3)){
                    var farmer=NaturalVillageR1GameTests.actor(l,b,0);var stash=NaturalPolityData.inventory(s,b,0);
                    check(stash.getItem(20).isEmpty(),"declared original-source tool storage initially empty");
                    stash.setItem(20,farmer.getPersonalInventory().getItem(1));farmer.getPersonalInventory().setItem(1,ItemStack.EMPTY);
                }
                if(phase.equals("counter-shortage")){
                    var stock=NaturalPolityData.inventory(s,target(),3);int removed=0;
                    for(int slot=0;slot<stock.getContainerSize();slot++)if(stock.getItem(slot).is(Items.WHITE_WOOL)){removed+=stock.getItem(slot).getCount();stock.setItem(slot,ItemStack.EMPTY);}
                    stock.setItem(15,new ItemStack(Items.WHITE_WOOL,3));proof.putInt("InitialPublicWool",3);proof.putInt("ExcludedFromInitialStock",removed-3);
                }
                started=now;proof.putString("Phase",phase);proof.putLong("Started",now);proof.put("InitialDevelopment",d.save(new CompoundTag(),s.registryAccess()));
                event("INITIAL_FINITE_CONFIGURATION",now);return;
            }
            check(now-started<98000,"bounded counterexample deadline; no silent time extension");
            for(var b:villages)for(int i=0;i<3;i++)check(CharacterRegistryData.get(s).byId(NaturalVillageLifeData.person(b,i)).alive(),"original people retained");
            var row=d.site(target().buildingKey());var projects=row.getList("Projects",Tag.TAG_COMPOUND);
            if(phase.equals("counter-guards")){
                var old=SettlementCoordinatorData.existing(s).site(target().buildingKey());
                if(stage==0){
                    check(projects.isEmpty(),"food surplus cannot authorize expansion without actual incoming farmer tool");
                    if(!old.getCompound("Jobs").getCompound("HOUSE").getString("State").equals("DONE"))return;
                    if(eligible<0){eligible=now;return;}if(now-eligible<2400)return;
                    check(row.getString("Reason").contains("合法锄具"),"specific production condition visible");
                    var b=villages.get(1);var farmer=NaturalVillageR1GameTests.actor(l,b,0);var from=NaturalVillageEconomyData.storeId(b,0);var to=ContainerIds.npcPersonal(farmer.getCharacterId());
                    // Declared work-conflict intervention, after the founder's original
                    // house is complete. Food and the original tool remain real.
                    NaturalVillageR1GameTests.actor(l,target(),1).setCommand(NpcCommand.STAY);
                    NaturalVillageR1GameTests.actor(l,target(),2).setCommand(NpcCommand.STAY);
                    var transfer=new CompoundTag();
                    check(SettlementGrowthData.get(s).transfer(farmer,transfer,ContainerIds.named("development-counter:restore-original-hoe:"+b.buildingKey()),SubjectRef.household(NaturalVillageLifeData.home(b)),TransferIntent.GIFT,List.of(new TransferLeg(from,to,ItemFingerprint.of(new ItemStack(Items.IRON_HOE)),1)),Map.of(from,NaturalPolityData.inventory(s,b,0),to,farmer.getPersonalInventory())),"declared condition restoration moves the original physical hoe by lawful transfer, never creates a tool");
                    proof.put("RestorationReceipt",transfer);changed=now;stage=1;event("ORIGINAL_TOOL_RESTORED_NONFARM_WORK_CONFLICT",now);return;
                }
                if(stage==1){
                    check(projects.isEmpty(),"real food and tools cannot replace unavailable nonfarm labour");
                    if(now-changed<2400)return;
                    check(SettlementDevelopmentData.capacity(s,HouseholdData.get(s).byId(NaturalVillageLifeData.home(target()))).availableWorkers()==1,"actual command/work-state constraint, not guessed absence");
                    NaturalVillageR1GameTests.actor(l,target(),1).setCommand(NpcCommand.WORK);
                    NaturalVillageR1GameTests.actor(l,target(),2).setCommand(NpcCommand.WORK);
                    changed=now;stage=2;event("ORIGINAL_NONFARM_WORK_AVAILABLE",now);return;
                }
                check(now-changed<=12000,"normal scheduler must resume the same intent after tool restoration");
                if(!projects.isEmpty()&&projects.getCompound(0).getCompound("Jobs").getCompound("BUILD").getInt("Placed")>0){
                    check(d.application(NaturalVillageLifeData.home(villages.get(1))).getLong("Applied")<=changed,"old intent, no replacement request");finish("NO_ILLEGAL_START_THEN_REAL_WORK",now);
                }return;
            }
            if(projects.isEmpty())return;
            var p=projects.getCompound(0);var build=p.getCompound("Jobs").getCompound("BUILD");int placed=build.getInt("Placed");
            if(phase.equals("counter-shortage")){
                if(placed<SettlementDevelopmentLayout.stoneCost(target())+1)return;
                if(changed<0){changed=now;placedBefore=placed;proof.put("BlockedProject",p.copy());event("REAL_WOOL_SHORTAGE",now);return;}
                check(placed==placedBefore&&placed<SettlementDevelopmentLayout.house(target(),0).size(),"no free bed or housing completion without wool");
                check(!p.getString("State").equals("READY")&&!d.application(p.getUUID("Household")).contains("Departed"),"no population move into incomplete house");
                if(now-changed>=4800){check(NaturalPolityData.inventory(s,target(),3)!=null&&SettlementDevelopmentGameTests.count(NaturalPolityData.inventory(s,target(),3),Items.WHITE_WOOL)<3,"actual missing material remains missing");finish("PERSISTENT_SHORTAGE_NO_FREE_BEDS_OR_PEOPLE",now);}return;
            }
            if(stage==0){
                if(placed<5||placed>=54||!build.getBoolean("MaterialCarried")||build.getInt("MaterialsHeld")<=0)return;
                placedBefore=placed;heldBefore=build.getInt("MaterialsHeld");project=p.getString("Id");home=p.getUUID("Household");proof.put("BeforeInterruption",p.copy());changed=now;
                if(phase.equals("counter-obstruction")){
                    obstruction=SettlementDevelopmentLayout.house(target(),p.getInt("Lot")).get(placed).positions().getFirst();before=l.getBlockState(obstruction);check(before.isAir(),"only a declared empty next construction block is temporarily obstructed");
                    l.setBlock(obstruction,Blocks.GLASS.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);stage=1;event("DECLARED_TEST_OBSTRUCTION",now);return;
                }
                var original=HouseholdData.get(s).byId(home);check(d.withdraw(s,home,original.headCharacterId(),now).equals("WITHDRAWING"),"formal original-family withdrawal keeps actual carrying responsibility");
                var first=d.save(new CompoundTag(),s.registryAccess());d.withdraw(s,home,original.headCharacterId(),now);check(first.equals(d.save(new CompoundTag(),s.registryAccess())),"repeat withdrawal is idempotent");stage=1;event("FORMAL_PREDEPARTURE_WITHDRAWAL",now);return;
            }
            check(p.getString("Id").equals(project),"original engineering history retained");
            if(phase.equals("counter-obstruction")){
                if(stage==1){
                    check(placed==placedBefore,"construction cannot overwrite actual obstruction");
                    check(build.getBoolean("MaterialCarried")&&build.getInt("MaterialsHeld")==heldBefore,"unspent actual cargo retained during wait");
                    if(now-changed<1200)return;
                    proof.put("DuringPause",p.copy());check(l.getBlockState(obstruction).is(Blocks.GLASS),"only test-owned obstruction removed");l.setBlock(obstruction,before,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);changed=now;stage=2;event("SAME_SITE_REOPENED",now);return;
                }
                check(now-changed<=12000,"normal work resumes without replacement project");
                if(placed>placedBefore)finish("ORIGINAL_MATERIAL_AND_PROJECT_RESUMED",now);return;
            }
            check(now-changed<=12000,"unspent return completes by normal walking and transfer");
            check(placed==placedBefore,"withdrawal never refunds or dismantles consumed construction");
            check(!d.application(home).contains("Departed"),"original household never teleported out");
            if(p.getString("State").equals("CANCELLED")){
                check(d.application(home).getString("State").equals("WITHDRAWN"),"withdrawal finalized after physical obligations resolved");
                int returned=0;for(var kind:p.getCompound("Jobs").getAllKeys()){
                    var job=p.getCompound("Jobs").getCompound(kind);check(!job.getBoolean("MaterialCarried")&&!job.getBoolean("Cargo"),"no hidden unspent obligation deleted");
                    if(job.hasUUID("ReturnReceipt")){var receipt=TransferLedgerData.get(s).receipt(job.getUUID("ReturnReceipt"));check(receipt!=null&&receipt.successful()&&receipt.lawful(),"original physical material has lawful return receipt");returned+=job.getInt("ReturnedQuantity");}
                }check(returned>=heldBefore,"actual carried construction material returned");
                var beforeRepeat=d.save(new CompoundTag(),s.registryAccess());d.withdraw(s,home,HouseholdData.get(s).byId(home).headCharacterId(),now);check(beforeRepeat.equals(d.save(new CompoundTag(),s.registryAccess())),"terminal repeat cannot refund again");finish("ACTUAL_UNSPENT_RETURN_NO_PHANTOM_REFUND",now);
            }
        }
        void event(String what,long now){Sagadyssey.LOGGER.info("GROWTH_COUNTER phase={} event={} elapsed={}",phase,what,now-started);}
        void finish(String result,long now){
            proof.putLong("Finished",now);proof.putString("Result",result);proof.put("FinalDevelopment",SettlementDevelopmentData.get(l.getServer()).save(new CompoundTag(),l.getServer().registryAccess()));
            try{NbtIo.writeCompressed(proof,l.getServer().getWorldPath(LevelResource.ROOT).resolve(phase+"-proof.nbt"));}catch(Exception e){throw new IllegalStateException(e);}event(result,now);h.succeed();
        }
    }
}
