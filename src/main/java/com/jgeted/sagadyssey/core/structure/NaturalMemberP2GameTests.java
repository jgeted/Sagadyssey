package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** P2 automated protocol evidence, explicitly not a human play acceptance. */
@GameTestHolder("sagadyssey_p2") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NaturalMemberP2GameTests {
    private static final Identifier EMPTY=Identifier.parse("sagadyssey_p2:empty");
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void install(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;
        try{var l=e.getServer().overworld();l.getStructureManager().getOrCreate(EMPTY).load(l.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully("{size:[16,6,16],entities:[],blocks:[],palette:[{Name:\"minecraft:air\"}]}"));}
        catch(Exception ex){throw new IllegalStateException(ex);}
        previous=null;
        if(System.getProperty("sagadyssey.society.phase","").startsWith("p2-")&&System.getProperty("sagadyssey.society.phase","").endsWith("resume"))try{
            var s=e.getServer();previous=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("p2-member.nbt"),NbtAccounter.unlimitedHeap());
            var saved=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            s.getWorldData().overworldData().setGameTime(saved.getLongOr("Time", 0L)+1);s.getWorldData().overworldData().setDayTime(saved.getLongOr("DayTime", 0L)+1);
            NaturalVillageR1GameTests.visiting(s.overworld(),NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Village")),true);
            Sagadyssey.LOGGER.info("P2_RESTORE original complete world loaded before test ticks");
        }catch(Exception ex){throw new IllegalStateException("P2 complete original checkpoint required",ex);}
    }
    @GameTestGenerator public static Collection<TestFunction> tests(){
        return switch(System.getProperty("sagadyssey.society.phase","")){
            case "p2-demand"->List.of(test("fulfilled_public_need_rejects_exchange",NaturalMemberP2GameTests::demand,1200));
            case "p2-layout-living"->List.of(test("existing_living_initialization",h->layout(h,NaturalVillageLayout.LIVING),800));
            case "p2-layout-farm"->List.of(test("existing_farm_initialization",h->layout(h,NaturalVillageLayout.FARM_V2),800));
            case "p2-layout-wood"->List.of(test("existing_wood_initialization",h->layout(h,NaturalVillageLayout.WOOD_V2),800));
            case "p2-cancel"->List.of(test("cancel_unsubmitted_preserves_property",NaturalMemberP2GameTests::cancel,1200));
            case "p2-missing"->List.of(test("changed_or_missing_destination_rejects",NaturalMemberP2GameTests::missing,1200));
            case "p2-unknown"->List.of(test("unknown_pending_is_not_replayed",NaturalMemberP2GameTests::unknown,1200));
            case "p2-full"->List.of(test("full_destination_and_no_reward_are_atomic",NaturalMemberP2GameTests::full,1200));
            case "p2-rights"->List.of(test("current_member_title_required_for_reward",NaturalMemberP2GameTests::rights,1200));
            case "p2-member-save"->List.of(test("ordinary_work_with_naturally_produced_reward",NaturalMemberP2GameTests::naturalMember,48000));
            case "p2-member-resume"->List.of(test("accepted_work_and_original_player_resume",h->resume(h,false),1200));
            case "p2-paid-resume"->List.of(test("paid_work_and_original_player_resume",h->resume(h,true),1200));
            default->List.of();
        };
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> f,int ticks){return new TestFunction("p2",name,EMPTY.toString(),ticks,0,true,f);}
    static int dispatch(ServerPlayer p,String command){try{return p.level().getServer().getCommands().getDispatcher().execute(command,p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
    static int count(Container c,Item i){int n=0;for(int s=0;s<c.getContainerSize();s++)if(c.getItem(s).is(i))n+=c.getItem(s).getCount();return n;}
    static ServerPlayer player(GameTestHelper h,NaturalVillageBuilding b){var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),"P2Member"));p.setGameMode(GameType.SURVIVAL);p.snapTo(b.buildingOrigin().getX()+.5,b.buildingOrigin().getY(),b.buildingOrigin().getZ()+.5,0,0);p.getInventory().clearContent();CharacterRegistry.ensureCurrent(p);return p;}
    private static void demand(GameTestHelper h){var b=NaturalVillageR1GameTests.fixture(h,0);h.runAfterDelay(240,()->{
        var l=h.getLevel();for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(l,b,i).setNoAi(true);
        var p=player(h,b);p.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,4));
        var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));pantry.setItem(0,new ItemStack(Items.CARROT,2));
        var pub=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC));pub.setItem(0,new ItemStack(Items.OAK_LOG,4));
        h.assertTrue(dispatch(p,"saga society join")==1,"ordinary membership");
        h.assertTrue(dispatch(p,"saga society deliver")==0,"fulfilled actual public wood need must not purchase more logs");
        h.assertTrue(count(pub,Items.OAK_LOG)==4&&count(pantry,Items.CARROT)==2&&count(p.getInventory(),Items.OAK_LOG)==4,"rejection preserves all three real inventories");
        Sagadyssey.LOGGER.info("P2_ACCEPT fulfilled public need rejected without moving goods");h.succeed();
    });}
    static void verify(ServerLevel l,NaturalVillageBuilding b){
        var s=l.getServer();var data=NaturalVillageLifeData.get(s);var v=data.village(b.buildingKey());check(v!=null&&v.getBooleanOr("Ready", false),"formal initializer ready");
        var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));check(home!=null&&home.members().size()==3,"three original household members");
        var principal=SubjectRef.household(home.id());var flows=SupplyFlowData.get(s);var rights=RightsStateData.get(s);var rs=new RightsService(rights);
        for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);check(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i))&&NpcBrainRuntime.isPlanned(n),"original planned actor");var r=NpcLifeData.get(s).record(n.getCharacterId());check(r!=null&&r.bedPosition().equals(NaturalVillageLayout.at(b,NaturalVillageLayout.BEDS.get(i))),"template bed and job");}
        for(int i:new int[]{0,2}){var pos=NaturalVillageLayout.at(b,i==0?NaturalVillageLayout.RAW:NaturalVillageLayout.WOOD);var id=ContainerIds.block("private_storage",b.dimension(),pos);check(flows.known(principal).contains(id)&&flows.point(id).position().equals(pos),"formal household source knowledge");check(rs.can(principal,AssetRight.TRANSFER,id,0,new ItemFingerprint("minecraft:carrot",0),l.getGameTime()).status()==RightsVerdict.Status.ALLOW,"explicit source transfer grant");}
        var pantry=ContainerIds.block("household_storage",b.dimension(),home.sharedStorage());check(rights.context(pantry).defaultBeneficialOwner().equals(principal),"household ownership");
        check(flows.policies().stream().anyMatch(p->p.principal().equals(principal)&&p.destination().equals(pantry)&&p.item().equals(new ItemFingerprint("minecraft:carrot",0))&&p.target()==16),"existing exact carrot target 16");
        check(flows.policies().stream().filter(p->p.principal().equals(principal)).noneMatch(p->Set.of("minecraft:wheat_seeds","minecraft:iron_pickaxe").contains(p.item().itemId())),"no fictitious wheat/miner natural package");
    }
    private static void check(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    private static void layout(GameTestHelper h,Identifier template){var b=NaturalVillageR1GameTests.fixture(h,0,template);h.runAfterDelay(300,()->{verify(h.getLevel(),b);NaturalVillageLifeData.get(h.getLevel().getServer()).schedule(b);verify(h.getLevel(),b);Sagadyssey.LOGGER.info("P2_ACCEPT template={} initialization=true household=3 sourceRights=true carrotTarget=16 repeatedRegistrationStable=true",template);saveEvidence(h,"layout",b,null);h.succeed();});}
    private record F(NaturalVillageBuilding b,ServerPlayer p,Container pantry,Container pub){}
    private static void controlled(GameTestHelper h,java.util.function.Consumer<F> body){var b=NaturalVillageR1GameTests.fixture(h,0);h.runAfterDelay(240,()->{
        for(int i=0;i<3;i++)NaturalVillageR1GameTests.actor(h.getLevel(),b,i).setNoAi(true);var p=player(h,b);p.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,4));
        var pantry=(Container)h.getLevel().getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));pantry.clearContent();pantry.setItem(0,new ItemStack(Items.CARROT,4));var pub=(Container)h.getLevel().getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC));pub.clearContent();
        h.assertTrue(dispatch(p,"saga society join")==1,"controlled member setup");body.accept(new F(b,p,pantry,pub));
    });}
    private static CompoundTag payment(F f){return NaturalVillageLifeData.get(f.p().level().getServer()).payment(CharacterRegistry.ensureCurrent(f.p()).id(),f.b().settlementId());}
    private static void cancel(GameTestHelper h){controlled(h,f->{
        h.assertTrue(dispatch(f.p(),"saga society work")==1,"accept existing work");var tx=payment(f).read("Transaction", UUIDUtil.CODEC).orElseThrow();h.assertTrue(dispatch(f.p(),"saga society work")==1&&tx.equals(payment(f).read("Transaction", UUIDUtil.CODEC).orElseThrow()),"repeat acceptance keeps original identity");
        h.assertTrue(dispatch(f.p(),"saga society cancel-work")==1&&payment(f).getStringOr("State", "").equals("CANCELLED"),"cancel only unsubmitted work");h.assertTrue(count(f.p().getInventory(),Items.OAK_LOG)==4&&count(f.pantry(),Items.CARROT)==4&&TransferLedgerData.get(f.p().level().getServer()).receipt(tx)==null,"cancellation does not remove or pay goods");
        h.assertTrue(dispatch(f.p(),"saga society work")==1&&!tx.equals(payment(f).read("Transaction", UUIDUtil.CODEC).orElseThrow()),"fresh explicit acceptance after cancellation");h.assertTrue(dispatch(f.p(),"saga society leave")==1&&payment(f).getStringOr("State", "").equals("CANCELLED"),"leaving cancels only unsubmitted intent");Sagadyssey.LOGGER.info("P2_ACCEPT cancel/reaccept/leave no phantom goods or obligation");h.succeed();});}
    private static void missing(GameTestHelper h){controlled(h,f->{
        h.assertTrue(dispatch(f.p(),"saga society work")==1,"accepted work");var pos=NaturalVillageLayout.at(f.b(),NaturalVillageLayout.PUBLIC);var state=h.getLevel().getBlockState(pos);h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&payment(f).getStringOr("State", "").equals("ACCEPTED")&&count(f.p().getInventory(),Items.OAK_LOG)==4,"missing target preserves accepted intent and goods");h.getLevel().setBlockAndUpdate(pos,state);
        var home=HouseholdData.get(f.p().level().getServer()).byId(NaturalVillageLifeData.home(f.b()));var alternative=pos.offset(0,0,2);h.getLevel().setBlockAndUpdate(alternative,Blocks.BARREL.defaultBlockState());
        HouseholdData.get(f.p().level().getServer()).assignSharedStorage(home.id(),f.b().dimension(),alternative,alternative);
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&payment(f).getStringOr("State", "").equals("ACCEPTED")&&count(f.p().getInventory(),Items.OAK_LOG)==4,"changed household destination cannot redirect old work");Sagadyssey.LOGGER.info("P2_ACCEPT missing and changed destination no retarget or payout");h.succeed();});}
    private static void unknown(GameTestHelper h){controlled(h,f->{
        var id=CharacterRegistry.ensureCurrent(f.p()).id();var d=NaturalVillageLifeData.get(f.p().level().getServer());var tx=d.beginPayment(id,f.b().settlementId(),h.getLevel().getGameTime()/24000);
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&payment(f).getStringOr("State", "").equals("REVIEW"),"unknown pending must enter review");h.assertTrue(dispatch(f.p(),"saga society cancel-work")==0&&dispatch(f.p(),"saga society work")==0&&dispatch(f.p(),"saga society deliver")==0,"review cannot cancel or replay");
        h.assertTrue(TransferLedgerData.get(f.p().level().getServer()).receipt(tx)==null&&count(f.p().getInventory(),Items.OAK_LOG)==4&&count(f.pantry(),Items.CARROT)==4,"no goods or reward created");Sagadyssey.LOGGER.info("P2_ACCEPT unknown pending REVIEW no replay");h.succeed();});}
    private static void full(GameTestHelper h){controlled(h,f->{
        h.assertTrue(dispatch(f.p(),"saga society work")==1,"accept");for(int i=0;i<f.pub().getContainerSize();i++)f.pub().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&count(f.p().getInventory(),Items.OAK_LOG)==4&&count(f.pantry(),Items.CARROT)==4,"full destination rolls back both legs");f.pub().clearContent();f.pantry().clearContent();
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&count(f.p().getInventory(),Items.OAK_LOG)==4,"no reward cannot take work");f.pantry().setItem(0,new ItemStack(Items.CARROT,4));
        h.assertTrue(dispatch(f.p(),"saga society deliver")==1&&count(f.p().getInventory(),Items.CARROT)==2&&count(f.pub(),Items.OAK_LOG)==4,"actual restored capacity/reward completes original process");Sagadyssey.LOGGER.info("P2_ACCEPT full/no-reward refusal and actual recovery atomic");h.succeed();});}
    private static void rights(GameTestHelper h){controlled(h,f->{
        h.assertTrue(dispatch(f.p(),"saga society work")==1,"accept");var id=CharacterRegistry.ensureCurrent(f.p()).id();var home=NaturalVillageLifeData.home(f.b());HouseholdData.get(f.p().level().getServer()).removeMember(home,id);
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&count(f.p().getInventory(),Items.OAK_LOG)==4&&count(f.pantry(),Items.CARROT)==4,"revoked membership cannot spend home assets");
        h.assertTrue(dispatch(f.p(),"saga society join")==1&&dispatch(f.p(),"saga society deliver")==1,"valid membership restored settles only real exchange");
        h.assertTrue(dispatch(f.p(),"saga society deliver")==0&&dispatch(f.p(),"saga society leave")==1&&dispatch(f.p(),"saga society join")==1&&dispatch(f.p(),"saga society deliver")==0,"duplicate and leave/rejoin do not pay twice");
        h.assertTrue(count(f.p().getInventory(),Items.CARROT)==2&&count(f.pantry(),Items.CARROT)==2,"one payout exactly");Sagadyssey.LOGGER.info("P2_ACCEPT membership revoke/restore and repeated settlement safe");h.succeed();});}
    private static void naturalMember(GameTestHelper h){var b=NaturalVillageR1GameTests.fixture(h,0);var l=h.getLevel();ServerPlayer[] p={null};boolean[] collected={false};
        try{l.getServer().getCommands().getDispatcher().execute("tick sprint 48000",l.getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception ex){throw new IllegalStateException(ex);}
        h.onEachTick(()->{if(h.getTick()<300)return;if(p[0]==null){verify(l,b);p[0]=player(h,b);h.assertTrue(dispatch(p[0],"saga society nearby")==1&&dispatch(p[0],"saga society join")==1,"ordinary permission 0 join");
                for(int i=0;i<4;i++){var at=NaturalVillageLayout.at(b,new BlockPos(6+i,1,24));l.setBlockAndUpdate(at,Blocks.OAK_LOG.defaultBlockState());h.assertTrue(p[0].gameMode.destroyBlock(at),"real survival break of declared work material");}
            }
            if(!collected[0]){var area=new AABB(NaturalVillageLayout.at(b,new BlockPos(3,0,21))).inflate(12);for(var item:l.getEntitiesOfClass(ItemEntity.class,area)){if(item.getItem().is(Items.OAK_LOG)){item.setNoPickUpDelay();item.playerTouch(p[0]);}}collected[0]=count(p[0].getInventory(),Items.OAK_LOG)==4;if(!collected[0])return;}
            var pantry=(Container)l.getBlockEntity(NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY));
            var receipts=FieldworkData.get(l.getServer()).receiptsFor(NaturalVillageR1GameTests.actor(l,b,0).getUUID());
            if(count(pantry,Items.CARROT)<2||receipts.stream().noneMatch(r->r.action().operation()==com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkAction.Operation.HARVEST))return;
            if(dispatch(p[0],"saga society work")!=1)return;
            var pay=NaturalVillageLifeData.get(l.getServer()).payment(CharacterRegistry.ensureCurrent(p[0]).id(),b.settlementId());h.assertTrue(pay.getStringOr("State", "").equals("ACCEPTED")&&count(p[0].getInventory(),Items.CARROT)==0,"only accepted, not paid early");
            Sagadyssey.LOGGER.info("P2_ACCEPT natural production/transport reward available ticks={} realCollectedLogs=4 pantryCarrots={} acceptedTransaction={}",h.getTick(),count(pantry,Items.CARROT),pay.read("Transaction", UUIDUtil.CODEC).orElseThrow());saveEvidence(h,"ACCEPTED",b,p[0]);h.succeed();
        });}
    private static void saveEvidence(GameTestHelper h,String state,NaturalVillageBuilding b,ServerPlayer p){try{
        var s=h.getLevel().getServer();var t=new CompoundTag();t.putString("State",state);t.putLong("Time",h.getLevel().getGameTime());t.put("Village",NaturalVillageLifeData.get(s).village(b.buildingKey()));
        if(p!=null){var save=net.minecraft.server.players.PlayerList.class.getDeclaredMethod("save",ServerPlayer.class);save.setAccessible(true);save.invoke(s.getPlayerList(),p);t.store("Account", UUIDUtil.CODEC,p.getUUID());t.store("Character", UUIDUtil.CODEC,CharacterRegistry.ensureCurrent(p).id());t.put("Player",com.jgeted.sagadyssey.core.MinecraftApi.saveEntity(p));t.put("Payment",NaturalVillageLifeData.get(s).payment(t.read("Character", UUIDUtil.CODEC).orElseThrow(),b.settlementId()));}
        NbtIo.writeCompressed(t,s.getWorldPath(LevelResource.ROOT).resolve("p2-member.nbt"));s.saveEverything(true,true,true);
    }catch(Exception ex){throw new IllegalStateException("Save full P2 world and actual player data",ex);}}
    private static void resume(GameTestHelper h,boolean paid){h.assertTrue(previous!=null,"original checkpoint");var b=NaturalVillageLifeData.decode(previous.getCompoundOrEmpty("Village"));var l=h.getLevel();h.runAfterDelay(300,()->{
        var p=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new GameProfile(previous.read("Account", UUIDUtil.CODEC).orElseThrow(),"P2Member"));l.getServer().getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(p.getGameProfile())).ifPresent(tag -> com.jgeted.sagadyssey.core.MinecraftApi.loadEntity(p, tag));
        h.assertTrue(CharacterRegistry.ensureCurrent(p).id().equals(previous.read("Character", UUIDUtil.CODEC).orElseThrow()),"same saved player character, no replacement");
        var home=HouseholdData.get(l.getServer()).forMember(previous.read("Character", UUIDUtil.CODEC).orElseThrow());h.assertTrue(home!=null&&home.id().equals(NaturalVillageLifeData.home(b)),"original household membership restored");
        for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);h.assertTrue(n!=null&&n.getCharacterId().equals(NaturalVillageLifeData.person(b,i)),"three original residents persist through the same full-world reload");}
        var pay=NaturalVillageLifeData.get(l.getServer()).payment(previous.read("Character", UUIDUtil.CODEC).orElseThrow(),b.settlementId());h.assertTrue(pay.read("Transaction", UUIDUtil.CODEC).orElseThrow().equals(previous.getCompoundOrEmpty("Payment").read("Transaction", UUIDUtil.CODEC).orElseThrow())&&pay.getStringOr("State", "").equals(paid?"PAID":"ACCEPTED"),"same durable work/payment state");
        h.assertTrue(count(p.getInventory(),Items.OAK_LOG)==(paid?0:4)&&count(p.getInventory(),Items.CARROT)==(paid?2:0),"real player data inventory restored");
        if(!paid){h.assertTrue(dispatch(p,"saga society deliver")==1,"accepted original task executes after independent restart");var receipt=TransferLedgerData.get(l.getServer()).receipt(pay.read("Transaction", UUIDUtil.CODEC).orElseThrow());h.assertTrue(receipt!=null&&receipt.successful(),"original accepted transaction has actual successful receipt");}
        h.assertTrue(dispatch(p,"saga society deliver")==0&&dispatch(p,"saga society leave")==1&&dispatch(p,"saga society join")==1&&dispatch(p,"saga society deliver")==0,"no duplicate settlement across reload or rejoin");
        h.assertTrue(count(p.getInventory(),Items.CARROT)==2&&count(p.getInventory(),Items.OAK_LOG)==0,"exact final payout");
        Sagadyssey.LOGGER.info("P2_ACCEPT independent restart stage={} originalAccount={} originalCharacter={} sameTransaction={} carrots=2 duplicateRejected=true",paid?"PAID":"ACCEPTED",p.getUUID(),previous.read("Character", UUIDUtil.CODEC).orElseThrow(),pay.read("Transaction", UUIDUtil.CODEC).orElseThrow());saveEvidence(h,"PAID",b,p);h.succeed();
    });}
}
