package com.jgeted.sagadyssey.social.needs;

import net.minecraft.world.level.gamerules.GameRules;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.brain.life.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.brain.plan.NpcPlanStatus;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.production.ProductionContainerIds;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Ordinary random ticks, real daylight, finite local ore and native machines; no injected food/growth. */
@GameTestHolder("sagadyssey_cycle") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NearSelfSupplyGameTests {
    private static final String MARKER="near-self-supply.nbt";
    private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind NATIVE_TICKS=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("sagadyssey-self-supply-test");
    private static CompoundTag previous;
    private static final Map<UUID,P1Audit> P1_AUDITS=new HashMap<>();
    private static final Set<UUID> RESTORED_BODIES=new HashSet<>();
    private static String phase(){return System.getProperty("sagadyssey.nearCycle.phase","").replace("self-p1-","p1-");}
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(phase().equals("p1-right"))return List.of(test("p1_right",NearSelfSupplyGameTests::normal,150000));
        if(phase().equals("p1-calibrate"))return List.of(test("p1_calibration",NearSelfSupplyGameTests::normal,150000));
        if(phase().equals("p1-steady"))return List.of(test("p1_steady",NearSelfSupplyGameTests::normal,150000));
        if(phase().equals("p1-competition"))return List.of(test("p1_competition",NearSelfSupplyGameTests::normal,150000));
        if(phase().equals("p1-fuel"))return List.of(test("p1_fuel",NearSelfSupplyGameTests::fuelExhaustion,130000));
        if(phase().equals("p1-full"))return List.of(test("p1_full",NearSelfSupplyGameTests::blockedOutput,100000));
        if(phase().equals("p1-resume"))return List.of(test("p1_resume",NearSelfSupplyGameTests::recover,48000));
        if(phase().equals("self-api"))return List.of(test("household_source_entry",NearSelfSupplyGameTests::entry,100));
        if(phase().equals("self-normal"))return List.of(test("ordinary_near_self_supply",NearSelfSupplyGameTests::normal,150000));
        if(phase().equals("self-restart"))return List.of(test("resume_original_running_food_chain",NearSelfSupplyGameTests::recover,48000));
        if(phase().equals("self-faults"))return List.of(test("real_fuel_exhaustion_then_new_local_supply",NearSelfSupplyGameTests::fuelExhaustion,130000),test("blocked_output_then_real_clearance",NearSelfSupplyGameTests::blockedOutput,100000));
        if(phase().equals("self-fuel"))return List.of(test("real_fuel_exhaustion_then_new_local_supply",NearSelfSupplyGameTests::fuelExhaustion,130000));
        return List.of();
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body,int ticks){return new TestFunction("self_"+name,"self."+name,"sagadyssey_cycle:fieldwork_empty",ticks,0,true,body);}
    @net.neoforged.bus.api.SubscribeEvent public static void restore(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        previous=null;if(!(e.getServer() instanceof GameTestServer)||!(phase().equals("self-restart")||phase().equals("p1-resume")))return;
        try{var s=e.getServer();previous=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve(MARKER),NbtAccounter.unlimitedHeap());
            var world=NbtIo.readCompressed(s.getWorldPath(LevelResource.ROOT).resolve("level.dat"),NbtAccounter.unlimitedHeap());
            s.getWorldData().overworldData().setGameTime(Math.max(world.getCompoundOrEmpty("Data").getLongOr("Time", 0L),previous.getLongOr("Time", 0L))+1);
            s.getWorldData().overworldData().setDayTime(world.getCompoundOrEmpty("Data").getLongOr("DayTime", 0L)+1);
            if(phase().equals("p1-resume")){
                var current=ledgers(s.overworld());var expected=previous.getCompoundOrEmpty("Ledgers");
                for(String key:current.keySet()){
                    var actualTag=current.get(key);var expectedTag=expected.get(key);
                    if(key.equals("Transfers")){actualTag=transferMapOrder((CompoundTag)actualTag);expectedTag=transferMapOrder((CompoundTag)expectedTag);}
                    if(!actualTag.equals(expectedTag))throw new IllegalStateException("P1 linked ledger mismatch before ticks: "+key);
                }
                RESTORED_BODIES.clear();Sagadyssey.LOGGER.info("P1_RESTORE linked ledgers verified before ticks");
                s.getCommands().getDispatcher().execute("tick sprint 48000",s.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());
            }
            force(s.overworld(),BlockPos.of(previous.getLongOr("Base", 0L)));
        }catch(Exception x){throw new IllegalStateException("original running self-supply checkpoint required",x);}
    }
    private static void force(ServerLevel l,BlockPos p){for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++){
        var cp=new ChunkPos((p.getX()>>4)+x,(p.getZ()>>4)+z);l.setChunkForced(cp.x,cp.z,true);l.getChunk(cp.x,cp.z);
        // No nearby players exist on GameTestServer; native block ticking is forced, not crop growth.
        com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), NATIVE_TICKS,cp,2,cp,true);
    }}
    private static Container box(ServerLevel l,BlockPos p){return (Container)l.getBlockEntity(p);}
    private static NpcBase actor(ServerLevel l,BlockPos p,NpcProfession profession){
        var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);n.setProfession(profession);
        n.getPersonalInventory().clearContent();n.getTransportInventory().clearContent();n.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);n.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        n.setPersistenceRequired();n.setCommand(NpcCommand.WORK);l.addFreshEntity(n);NpcLifeData.get(l.getServer()).ensureRegistered(n,l.getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;
    }
    private record Farm(BlockPos base,BlockPos raw,BlockPos input,BlockPos furnace,BlockPos output,BlockPos pantry,BlockPos oreStock,BlockPos fuel,
                        UUID home,NpcBase farmer,NpcBase courier,NpcBase miner){}
    private static void set(ServerLevel l,BlockPos p,net.minecraft.world.level.block.state.BlockState s){l.setBlockAndUpdate(p,s);}
    private static void barrel(ServerLevel l,BlockPos p){set(l,p,Blocks.BARREL.defaultBlockState());}
    private static int command(ServerLevel l,NpcBase actor,String text){try{return l.getServer().getCommands().getDispatcher().execute(text.replace(actor.getUUID().toString(),"@s"),l.getServer().createCommandSourceStack().withEntity(actor).withLevel(l).withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException("formal command failed: "+text,e);}}
    private static String at(BlockPos p){return p.getX()+" "+p.getY()+" "+p.getZ();}
    private static Farm setup(GameTestHelper h){
        var l=h.getLevel();var s=l.getServer();var base=h.absolutePos(new BlockPos(8192,1,8192));force(l,base);
        l.setDayTime(1000);l.getGameRules().set(GameRules.ADVANCE_TIME, true,s);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3,s);
        l.getGameRules().set(GameRules.SPAWN_MOBS, false,s);
        for(int x=0;x<30;x++)for(int z=0;z<24;z++)set(l,base.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        for(int x=2;x<=8;x+=2)for(int z=2;z<=6;z+=2){var p=base.offset(x,0,z);set(l,p.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
            set(l,p.south().below(),Blocks.WATER.defaultBlockState());set(l,p.south(),Blocks.STONE.defaultBlockState());set(l,p,Blocks.POTATOES.defaultBlockState());set(l,p.above(3),Blocks.GLOWSTONE.defaultBlockState());}
        var raw=base.offset(11,0,5);var furnace=base.offset(15,1,5);var input=furnace.above(2);var output=furnace.offset(1,-1,0);var pantry=base.offset(23,0,5);
        var fuel=furnace.offset(-2,1,0);var oreStock=base.offset(11,0,15);
        for(var p:List.of(raw,input,output,pantry,fuel,oreStock))barrel(l,p);
        set(l,furnace,Blocks.FURNACE.defaultBlockState());set(l,input.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN));
        set(l,furnace.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST));
        // Native hoppers feed the furnace side from the separate fuel barrel.
        set(l,fuel.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST));
        set(l,fuel.below().east(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST));
        var farmer=actor(l,base.offset(2,0,9),NpcProfession.FARMER);farmer.setFarmMode(FarmMode.CROP);farmer.setWorkZone(base.offset(2,0,2),base.offset(8,0,6));farmer.getPersonalInventory().setItem(0,new ItemStack(Items.POTATO,16));
        var courier=actor(l,base.offset(22,0,5),NpcProfession.COURIER);var miner=actor(l,base.offset(3,0,19),NpcProfession.WORKER);
        miner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));
        for(int x=2;x<=8;x++)for(int z=14;z<=16;z++)set(l,base.offset(x,0,z),Blocks.COAL_ORE.defaultBlockState());
        miner.setWorkZone(base.offset(2,0,14),base.offset(8,0,16));
        var life=NpcLifeData.get(s);String dim=l.dimension().identifier().toString();
        h.assertTrue(life.assignPrivateStorage(s,farmer.getCharacterId(),dim,raw,l.getGameTime()),"real farmer store");
        h.assertTrue(life.assignPrivateStorage(s,miner.getCharacterId(),dim,oreStock,l.getGameTime()),"real miner store");
        var homes=HouseholdData.get(s);var home=homes.create("ordinary self-supply",dim,null,courier.getCharacterId(),pantry,l.getGameTime());
        h.assertTrue(homes.addMember(home.id(),farmer.getCharacterId(),HouseholdRole.KIN,l.getGameTime())&&homes.addMember(home.id(),miner.getCharacterId(),HouseholdRole.KIN,l.getGameTime()),"one real working family");
        h.assertTrue(homes.assignSharedStorage(home.id(),dim,pantry,pantry),"formal household store");homes.setProvisionPlan(new HouseholdProvisionPlan(home.id(),6,0,0));
        int bedIndex=0;for(var n:List.of(farmer,courier,miner)) {
            var foot=base.offset(20+bedIndex++*2,0,11);var head=foot.south();
            set(l,foot,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.FOOT));
            set(l,head,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD));
            h.assertTrue(life.assignBed(n.getCharacterId(),dim,head,"near self-supply home",l.getGameTime()),"assigned real private bed for normal night rest");
        }
        for(var n:List.of(farmer,courier,miner))life.setHunger(n.getCharacterId(),120,l.getGameTime());
        // The following existing commands establish exact-item stores and knowledge through the dispatcher.
        command(l,farmer,"saga supply know-source "+farmer.getUUID()+" "+at(raw));
        h.assertTrue(ProductionContainerIds.resolve(s,dim,raw).equals(ContainerIds.block("private_storage",dim,raw)),"formally assigned private store must use the same asset identity for discovery and actual deposit");
        command(l,farmer,"saga supply household-source "+farmer.getUUID()+" "+at(raw)+" allow-transfer");
        command(l,courier,"saga supply household-buffer "+courier.getUUID()+" workshop minecraft:potato 8 "+at(input));
        command(l,miner,"saga supply know-source "+miner.getUUID()+" "+at(oreStock));
        command(l,miner,"saga supply household-source "+miner.getUUID()+" "+at(oreStock)+" allow-transfer");
        command(l,courier,"saga supply household-buffer "+courier.getUUID()+" workshop minecraft:coal 4 "+at(fuel));
        h.assertTrue(command(l,courier,"saga supply household-source "+courier.getUUID()+" "+at(output)+" register-empty")==1,"formal explicit household-owned output store");
        var farm=new Farm(base,raw,input,furnace,output,pantry,oreStock,fuel,home.id(),farmer,courier,miner);
        if(phase().equals("p1-competition")){
            NpcLifeData.get(s).setShift(courier.getCharacterId(),NpcWorkShift.NIGHT,l.getGameTime());
            var spare=base.offset(26,0,15);barrel(l,spare);
            h.assertTrue(command(l,courier,"saga supply household-source "+courier.getUUID()+" "+at(spare)+" register-empty")==1,"initial legal empty spare source");box(l,spare).setItem(0,new ItemStack(Items.IRON_PICKAXE));
            var rights=RightsStateData.get(s);String dim2=l.dimension().identifier().toString();var family=SubjectRef.household(home.id());
            var sourceId=ProductionContainerIds.resolve(s,dim2,spare);rights.ensureContext(sourceId,dim2+":"+spare.asLong(),family,family,AccessPolicy.HOUSEHOLD);
            var point=new FieldworkStorage(dim2,spare,sourceId,family,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"initial_p1_spare");
            SupplyFlowData.get(s).register(point);SupplyFlowData.get(s).reveal(family,sourceId);
            command(l,courier,"saga supply household-buffer "+courier.getUUID()+" workshop minecraft:iron_pickaxe 1 "+at(pantry));
            var pantryId=ContainerIds.block("household_storage",dim2,pantry);
            rights.grant(new RightGrant(ContainerIds.named("p1-tool-pickup:"+home.id()),pantryId,SubjectRef.character(miner.getCharacterId()),Set.of(AssetRight.ACCESS,AssetRight.USE,AssetRight.TRANSFER),"initial explicit P1 spare gift",l.getGameTime(),null,true));
            miner.getMainHandItem().setDamageValue(miner.getMainHandItem().getMaxDamage()-1);
        }
        if(phase().equals("p1-right"))RightsStateData.get(s).revoke(ContainerIds.named("household-supply-transfer:"+home.id()+":"+ProductionContainerIds.resolve(s,l.dimension().identifier().toString(),oreStock)));
        if(phase().startsWith("p1-")){P1_AUDITS.put(home.id(),new P1Audit(l,farm));try{s.getCommands().getDispatcher().execute("tick sprint 150000",s.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput());}catch(Exception e){throw new IllegalStateException(e);}}
        return farm;
    }
    private static void entry(GameTestHelper h){
        var l=h.getLevel();var f=setup(h);var id=ProductionContainerIds.resolve(l.getServer(),l.dimension().identifier().toString(),f.output());
        h.assertTrue(SupplyFlowData.get(l.getServer()).known(SubjectRef.household(f.home())).contains(id),"household, not only character, knows configured source");
        h.assertTrue(RightsStateData.get(l.getServer()).context(id).defaultBeneficialOwner().equals(SubjectRef.household(f.home())),"empty household output has explicit title");
        var rights=RightsStateData.get(l.getServer());var principal=SubjectRef.household(f.home());
        var rawId=ProductionContainerIds.resolve(l.getServer(),l.dimension().identifier().toString(),f.raw());
        rights.revoke(ContainerIds.named("household-supply-transfer:"+f.home()+":"+rawId));
        box(l,f.raw()).setItem(0,new ItemStack(Items.POTATO,2));
        h.assertTrue(command(l,f.farmer(),"saga supply household-source "+f.farmer().getUUID()+" "+at(f.raw()))==1,"knowledge-only source entry");
        h.assertTrue(rights.grantsFor(rawId,principal,l.getGameTime()).isEmpty(),"knowledge never grants disposition rights");
        h.assertTrue(command(l,f.courier(),"saga supply household-source "+f.courier().getUUID()+" "+at(f.raw())+" allow-transfer")==0,"another family member cannot reauthorize the farmer's private goods");
        h.assertTrue(rights.context(rawId).defaultBeneficialOwner().equals(SubjectRef.character(f.farmer().getCharacterId())),"private ownership preserved");
        var stranger=SubjectRef.character(UUID.randomUUID());
        rights.putClaim(new AssetClaim(UUID.randomUUID(),rawId,0,ItemFingerprint.of(new ItemStack(Items.POTATO)),2,stranger,SubjectRef.character(f.farmer().getCharacterId()),Set.of(),Set.of(),"third-party title in member custody",0));
        h.assertTrue(command(l,f.farmer(),"saga supply household-source "+f.farmer().getUUID()+" "+at(f.raw())+" allow-transfer")==0,"member's default container title cannot authorize a third party's asset claim");
        h.assertTrue(rights.grantsFor(rawId,principal,l.getGameTime()).isEmpty(),"rejected third-party authorization leaves no grant");
        var foreign=f.base().offset(26,0,20);barrel(l,foreign);
        var other=actor(l,f.base().offset(28,0,20),NpcProfession.COURIER);other.setNoAi(true);
        var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(l.getServer()).create("other household",l.dimension().identifier().toString(),null,other.getCharacterId(),foreign,l.getGameTime());
        h.assertTrue(HouseholdData.get(l.getServer()).assignSharedStorage(home.id(),home.dimension(),foreign,foreign),"existing other household assignment without a rights context");
        h.assertTrue(command(l,f.courier(),"saga supply household-source "+f.courier().getUUID()+" "+at(foreign)+" register-empty")==0,"empty declaration cannot take another household's registered store");
        h.assertTrue(rights.context(ContainerIds.block("household_storage",home.dimension(),foreign))==null,"rejected declaration leaves original ownership state unchanged");
        h.succeed();
    }
    private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private static int count(ServerLevel l,Farm f,Item item){int n=0;for(var p:List.of(f.raw(),f.input(),f.input().below(),f.furnace(),f.furnace().below(),f.output(),f.pantry(),f.oreStock(),f.fuel(),f.fuel().below(),f.fuel().below().east()))n+=count(box(l,p),item);
        for(var actor:List.of(f.farmer(),f.courier(),f.miner()))n+=count(actor.getPersonalInventory(),item)+count(actor.getTransportInventory(),item);return n;}
    private static int delta(NpcBase actor,Item item){String key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)+"#0";return FieldworkData.get(MinecraftApi.server(actor)).receiptsFor(actor.getUUID()).stream().flatMap(r->r.inventoryDeltas().stream()).filter(d->d.fingerprint().equals(key)).mapToInt(FieldworkActionReceipt.ItemDelta::quantity).sum();}
    private static int cooked(ServerLevel l,Farm f){return l.getBlockEntity(f.furnace()).saveWithoutMetadata(l.registryAccess()).getCompoundOrEmpty("RecipesUsed").getIntOr("minecraft:baked_potato", 0);}
    private static void observeMeals(Farm f,Set<String> meals){var s=MinecraftApi.server(f.farmer());for(var actor:List.of(f.farmer(),f.courier(),f.miner()))for(var p:NpcBrainRuntime.data(s).history(actor.getUUID()))
        if(p.providerId().equals(LifePlanFactory.PROVIDER)&&p.status()==NpcPlanStatus.SUCCEEDED&&"EAT".equals(p.steps().getFirst().params().get("operation"))) {
            String key=p.id()+":2";if("DONE".equals(LifeActivityData.get(s).settlement(key)))meals.add(actor.getUUID()+"/"+key);
        }}
    private static void normal(GameTestHelper h){
        var l=h.getLevel();var f=setup(h);long start=l.getGameTime();Set<String> meals=new HashSet<>();
        h.onEachTick(()->{long elapsed=l.getGameTime()-start;if(elapsed%20==0)observeMeals(f,meals);
            auditTick(h,f);
            if(elapsed%12000==0)Sagadyssey.LOGGER.info("SELF_SUPPLY ordinary ticks={} dayTime={} crops={} raw={} cooked={} meals={} coalProduced={} coal={} farmer={} miner={}",elapsed,l.getDayTime(),delta(f.farmer(),Items.POTATO),count(l,f,Items.POTATO),cooked(l,f),meals.size(),delta(f.miner(),Items.COAL),count(l,f,Items.COAL),f.farmer().getWorkActivityDetail(),f.miner().getWorkActivityDetail());
        });
        h.succeedWhen(()->{
            h.assertTrue(l.getGameTime()-start>=120000,"observe five complete real days before acceptance");
            observeMeals(f,meals);int raw=count(l,f,Items.POTATO),cooked=cooked(l,f),baked=count(l,f,Items.BAKED_POTATO);
            h.assertTrue(cooked>meals.size()&&meals.size()>=9,"five real days supply all three workers repeatedly");
            h.assertTrue(16+delta(f.farmer(),Items.POTATO)==raw+cooked,"raw inputs and crop receipts reconcile");
            h.assertTrue(cooked==baked+meals.size(),"all cooked food is physical or actually consumed");
            balance(h,f,meals);
            for(var n:List.of(f.farmer(),f.courier(),f.miner()))h.assertTrue(meals.stream().filter(k->k.startsWith(n.getUUID()+"/")).count()>=3&&NpcLifeData.get(l.getServer()).needs(n.getCharacterId()).hunger()<800,"each original worker is repeatedly fed");
            h.assertTrue(delta(f.miner(),Items.COAL)>0&&count(l,f,Items.COAL)<delta(f.miner(),Items.COAL),"zero initial coal; newly mined fuel was actually burned");
            h.assertTrue(l.getGameRules().get(GameRules.RANDOM_TICK_SPEED)==3&&l.getGameRules().get(GameRules.ADVANCE_TIME)&&l.getDayTime()>=121000,"default growth and real days remain enabled");
            boolean carrying=SupplyFlowData.get(l.getServer()).routes().stream().anyMatch(r->r.principal().equals(SubjectRef.household(f.home()))&&r.stage()==SupplyFlowData.Stage.CARRYING);
            boolean processing=l.getBlockState(f.furnace()).getValue(AbstractFurnaceBlock.LIT)&&count(box(l,f.furnace()),Items.POTATO)>0;
            h.assertTrue(carrying||processing,"checkpoint must capture real carrying or unfinished cooking, not a parked completed chain");
            Sagadyssey.LOGGER.info("SELF_SUPPLY ACCEPT ordinary ticks={} raw={} netCrop={} cooked={} baked={} meals={} minedCoal={} remainingCoal={} carrying={} processing={}",l.getGameTime()-start,raw,delta(f.farmer(),Items.POTATO),cooked,baked,meals.size(),delta(f.miner(),Items.COAL),count(l,f,Items.COAL),carrying,processing);
            finishAudit(h,f,meals);saveRunning(h,f,meals);
            if(phase().equals("p1-steady"))snapshot(l);
            h.succeed();
        });
    }
    private static void saveRunning(GameTestHelper h,Farm f,Set<String> meals){
        var l=h.getLevel();var s=l.getServer();var marker=new CompoundTag();marker.putLong("Base",f.base().asLong());marker.putLong("Time",l.getGameTime());marker.store("Home", UUIDUtil.CODEC,f.home());
        String[] names={"Raw","Input","Furnace","Output","Pantry","OreStock","Fuel"};BlockPos[] positions={f.raw(),f.input(),f.furnace(),f.output(),f.pantry(),f.oreStock(),f.fuel()};for(int i=0;i<names.length;i++)marker.putLong(names[i],positions[i].asLong());
        marker.store("Farmer", UUIDUtil.CODEC,f.farmer().getUUID());marker.store("Courier", UUIDUtil.CODEC,f.courier().getUUID());marker.store("Miner", UUIDUtil.CODEC,f.miner().getUUID());
        marker.store("FarmerPerson", UUIDUtil.CODEC,f.farmer().getCharacterId());marker.store("CourierPerson", UUIDUtil.CODEC,f.courier().getCharacterId());marker.store("MinerPerson", UUIDUtil.CODEC,f.miner().getCharacterId());
        SupplyFlowData.get(s).routes().stream().filter(r->r.principal().equals(SubjectRef.household(f.home()))&&r.stage()==SupplyFlowData.Stage.CARRYING).findFirst().ifPresent(r->marker.store("CarryingRoute", UUIDUtil.CODEC,r.id()));
        marker.putInt("Cooked",cooked(l,f));marker.putInt("Meals",meals.size());
        if(phase().startsWith("p1-")){
            marker.put("Ledgers",ledgers(l));marker.put("P1Audit",P1_AUDITS.get(f.home()).save());
            for(var n:List.of(f.farmer(),f.courier(),f.miner()))marker.put("Body:"+n.getUUID(),MinecraftApi.saveEntity(n));
            marker.putInt("PhysicalCoal",count(l,f,Items.COAL));
        }
        var keys=new ListTag();meals.forEach(k->keys.add(StringTag.valueOf(k)));marker.put("MealKeys",keys);
        try{NbtIo.writeCompressed(marker,s.getWorldPath(LevelResource.ROOT).resolve(MARKER));s.saveEverything(true,true,true);}catch(Exception e){throw new IllegalStateException("running food chain save",e);}
    }
    private static void balance(GameTestHelper h,Farm f,Set<String> meals){
        var l=h.getLevel();observeMeals(f,meals);
        h.assertTrue(16+delta(f.farmer(),Items.POTATO)==count(l,f,Items.POTATO)+cooked(l,f),"raw production, planting and cooking reconcile");
        h.assertTrue(cooked(l,f)==count(l,f,Items.BAKED_POTATO)+meals.size(),"every cooked item remains physical or has a completed meal");
        h.assertTrue(delta(f.farmer(),Items.POISONOUS_POTATO)==count(l,f,Items.POISONOUS_POTATO),"random non-food byproducts remain physical and never become ordinary food");
        int burned=delta(f.miner(),Items.COAL)-count(l,f,Items.COAL);
        h.assertTrue(burned>=0&&cooked(l,f)*200<=burned*1600,"actual mined fuel bounds cooking energy, allowing wasted idle burn time");
    }
    private static void pause(Farm f){for(var n:List.of(f.farmer(),f.courier(),f.miner()))n.setNoAi(true);}
    private static void fuelExhaustion(GameTestHelper h){
        var l=h.getLevel();var f=setup(h);var s=l.getServer();long start=l.getGameTime();Set<String> meals=new HashSet<>();int[] phase={0},oldMeals={0},stalledCooked={0};long[] waiting={0};
        // One initially accessible ore; the replacement deposits already exist outside the old work boundary.
        for(int x=2;x<=8;x++)for(int z=14;z<=16;z++)set(l,f.base().offset(x,0,z),Blocks.AIR.defaultBlockState());
        set(l,f.base().offset(2,0,14),Blocks.COAL_ORE.defaultBlockState());
        set(l,f.base().offset(9,0,14),Blocks.COAL_ORE.defaultBlockState());set(l,f.base().offset(10,0,14),Blocks.COAL_ORE.defaultBlockState());
        f.miner().setWorkZone(f.base().offset(2,0,14),f.base().offset(2,0,14));
        h.onEachTick(()->{auditTick(h,f);if((l.getGameTime()-start)%20==0)observeMeals(f,meals);});
        h.succeedWhen(()->{
            if(phase[0]==0){
                h.assertTrue(delta(f.miner(),Items.COAL)==1&&count(l,f,Items.COAL)==0&&cooked(l,f)>0&&cooked(l,f)<=8
                        &&count(box(l,f.furnace()),Items.POTATO)>0&&!l.getBlockState(f.furnace()).getValue(AbstractFurnaceBlock.LIT),"actual first coal depleted with unprocessed real food");
                stalledCooked[0]=cooked(l,f);waiting[0]=l.getGameTime();phase[0]=1;
            }
            if(phase[0]==1){
                h.assertTrue(l.getGameTime()-waiting[0]>=800,"observe actual fuel shortage");
                h.assertTrue(cooked(l,f)==stalledCooked[0]&&count(l,f,Items.COAL)==0,"no cooking without a real fuel source");balance(h,f,meals);oldMeals[0]=meals.size();
                h.assertTrue(l.getBlockState(f.base().offset(9,0,14)).is(Blocks.COAL_ORE),"recovery coal is a pre-existing actual deposit");
                f.miner().setWorkZone(f.base().offset(2,0,14),f.base().offset(10,0,14));phase[0]=2;
                Sagadyssey.LOGGER.info("SELF_SUPPLY fuel shortage verified; extend authorized mine zone, no fuel inserted; ticks={} meals={}",l.getGameTime()-start,oldMeals[0]);
            }
            h.assertTrue(delta(f.miner(),Items.COAL)>1&&cooked(l,f)>stalledCooked[0]&&meals.size()>oldMeals[0],"real new local coal is mined, transported, burned and feeding resumes");
            balance(h,f,meals);Sagadyssey.LOGGER.info("SELF_SUPPLY ACCEPT fuel depletion/recovery ticks={} mined={} cooked={} meals={}",l.getGameTime()-start,delta(f.miner(),Items.COAL),cooked(l,f),meals.size());finishAudit(h,f,meals);pause(f);
        });
    }
    private static void blockedOutput(GameTestHelper h){
        var l=h.getLevel();var f=setup(h);var s=l.getServer();var principal=SubjectRef.household(f.home());Set<String> meals=new HashSet<>();boolean[] cleared={false};long start=l.getGameTime();
        for(int i=0;i<box(l,f.output()).getContainerSize();i++)box(l,f.output()).setItem(i,new ItemStack(Items.COBBLESTONE,64));
        var escrow=f.base().offset(26,0,18);barrel(l,escrow);String dim=l.dimension().identifier().toString();var escrowId=ProductionContainerIds.resolve(s,dim,escrow);
        var rights=RightsStateData.get(s);rights.ensureContext(escrowId,dim+":"+escrow.asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
        var outputId=ProductionContainerIds.resolve(s,dim,f.output());
        h.onEachTick(()->{auditTick(h,f);if((l.getGameTime()-start)%20==0)observeMeals(f,meals);});
        h.succeedWhen(()->{
            if(!cleared[0]){
                h.assertTrue(cooked(l,f)>0&&count(l,f,Items.BAKED_POTATO)>0,"real food has reached a blocked production line");
                h.assertTrue(count(box(l,f.output()),Items.BAKED_POTATO)==0&&count(box(l,f.pantry()),Items.BAKED_POTATO)==0&&meals.isEmpty(),"blocked outflow never becomes fictitious delivery or food");
                balance(h,f,meals);long now=l.getGameTime();
                var request=new TransferRequest(UUID.randomUUID(),principal,TransferIntent.DELIVERY,List.of(new TransferLeg(outputId,escrowId,ItemFingerprint.of(new ItemStack(Items.COBBLESTONE)),64)),List.of(new ExpectedRevision(outputId,rights.context(outputId).revision()),new ExpectedRevision(escrowId,rights.context(escrowId).revision())),"explicit output obstruction clearance",now);
                h.assertTrue(new TransferService(rights,TransferLedgerData.get(s)).submit(request,Map.of(outputId,new TransferEndpoint(outputId,box(l,f.output())),escrowId,new TransferEndpoint(escrowId,box(l,escrow))),now).successful(),"clear one stack by the real authorized transfer service");cleared[0]=true;
            }
            h.assertTrue(!meals.isEmpty()&&count(box(l,f.pantry()),Items.BAKED_POTATO)>0,"original production and household transport recover after actual clearance");
            h.assertTrue(count(box(l,f.output()),Items.COBBLESTONE)+count(box(l,escrow),Items.COBBLESTONE)==1728,"cleared material moved to escrow rather than disappearing");
            balance(h,f,meals);Sagadyssey.LOGGER.info("SELF_SUPPLY ACCEPT blocked-output recovery ticks={} cooked={} meals={} obstructionItems=1728",l.getGameTime()-start,cooked(l,f),meals.size());finishAudit(h,f,meals);pause(f);
        });
    }
    private static void recover(GameTestHelper h){
        h.assertTrue(previous!=null,"required running chain");var l=h.getLevel();Farm[] farm={null};Set<String> meals=new HashSet<>();for(var key:MinecraftApi.listOfType(previous, "MealKeys",Tag.TAG_STRING))meals.add(key.asString().orElseThrow());long start=l.getGameTime();
        h.succeedWhen(()->{
            if(farm[0]==null){var a=l.getEntity(previous.read("Farmer", UUIDUtil.CODEC).orElseThrow());var b=l.getEntity(previous.read("Courier", UUIDUtil.CODEC).orElseThrow());var c=l.getEntity(previous.read("Miner", UUIDUtil.CODEC).orElseThrow());if(a==null||b==null||c==null)java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                h.assertTrue(a instanceof NpcBase&&b instanceof NpcBase&&c instanceof NpcBase,"original three bodies load from real entity regions");
                farm[0]=new Farm(BlockPos.of(previous.getLongOr("Base", 0L)),BlockPos.of(previous.getLongOr("Raw", 0L)),BlockPos.of(previous.getLongOr("Input", 0L)),BlockPos.of(previous.getLongOr("Furnace", 0L)),BlockPos.of(previous.getLongOr("Output", 0L)),BlockPos.of(previous.getLongOr("Pantry", 0L)),BlockPos.of(previous.getLongOr("OreStock", 0L)),BlockPos.of(previous.getLongOr("Fuel", 0L)),previous.read("Home", UUIDUtil.CODEC).orElseThrow(),(NpcBase)a,(NpcBase)b,(NpcBase)c);
            }
            var f=farm[0];observeMeals(f,meals);
            if(phase().equals("p1-resume")){
                h.assertTrue(RESTORED_BODIES.size()==3,"three original inventories verified before entity ticks");
                if(!P1_AUDITS.containsKey(f.home())){var audit=new P1Audit(l,f);audit.load(previous.getCompoundOrEmpty("P1Audit"));P1_AUDITS.put(f.home(),audit);}
                auditTick(h,f);
            }
            h.assertTrue(f.farmer().getCharacterId().equals(previous.read("FarmerPerson", UUIDUtil.CODEC).orElseThrow())&&f.courier().getCharacterId().equals(previous.read("CourierPerson", UUIDUtil.CODEC).orElseThrow())&&f.miner().getCharacterId().equals(previous.read("MinerPerson", UUIDUtil.CODEC).orElseThrow()),"original person identities survive independent restart");
            for(var n:List.of(f.farmer(),f.courier(),f.miner()))h.assertTrue(NpcLifeData.get(l.getServer()).record(n.getCharacterId()).fatigue()<1000,"five-day normal rest prevented permanent exhaustion");
            h.assertTrue(cooked(l,f)>previous.getIntOr("Cooked", 0)&&meals.size()>previous.getIntOr("Meals", 0),"same running chain cooks and consumes new food after actual restart");
            h.assertTrue(16+delta(f.farmer(),Items.POTATO)==count(l,f,Items.POTATO)+cooked(l,f),"running crop and recipe conservation after restart");
            h.assertTrue(cooked(l,f)==count(l,f,Items.BAKED_POTATO)+meals.size(),"no doubled consumption or output after restart");
            if(previous.read("CarryingRoute", UUIDUtil.CODEC).isPresent())h.assertTrue(SupplyFlowData.get(l.getServer()).route(previous.read("CarryingRoute", UUIDUtil.CODEC).orElseThrow()).stage()==SupplyFlowData.Stage.DONE,"the original saved shipment settles before acceptance");
            if(phase().equals("p1-resume")){balance(h,f,meals);finishAudit(h,f,meals);}
            Sagadyssey.LOGGER.info("SELF_SUPPLY ACCEPT independent running restart elapsed={} cookedBefore={} cookedNow={} mealsBefore={} mealsNow={}",l.getGameTime()-start,previous.getIntOr("Cooked", 0),cooked(l,f),previous.getIntOr("Meals", 0),meals.size());
        });
    }

    private static void auditTick(GameTestHelper h,Farm f){var a=P1_AUDITS.get(f.home());if(a!=null)a.tick(h,f);}
    private static void finishAudit(GameTestHelper h,Farm f,Set<String> meals){var a=P1_AUDITS.get(f.home());if(a!=null)a.finish(h,f,meals);}
    private static final class P1Audit {
        long start;int lastBurn,ignitions;long maxWait,maxRouteAge;int maxOpen,maxHeld,maxCarrying,maxTypes,restHeld;boolean restoredRight;
        final Set<UUID> seenClaims=new HashSet<>();int nightClaims;
        final Map<UUID,Long> routeStarts=new HashMap<>();
        final Map<Long,Integer> endBuckets=new TreeMap<>();
        final Map<String,Integer> completed=new TreeMap<>();
        P1Audit(ServerLevel l,Farm f){start=l.getGameTime();lastBurn=burn(l,f);}
        static int burn(ServerLevel l,Farm f){return l.getBlockEntity(f.furnace()).saveWithoutMetadata(l.registryAccess()).getIntOr("BurnTime", 0);}
        void tick(GameTestHelper h,Farm f){
            var l=h.getLevel();int current=burn(l,f);if(current>lastBurn){ignitions++;h.assertTrue(current<=1600,"native coal burn duration");}lastBurn=current;
            h.assertTrue(delta(f.miner(),Items.COAL)==count(l,f,Items.COAL)+ignitions,"P1 independent ignition/physical coal balance");
            if(phase().equals("p1-right")&&!restoredRight&&delta(f.miner(),Items.COAL)>0&&l.getGameTime()-start>=800){
                h.assertTrue(ignitions==0&&count(box(l,f.fuel()),Items.COAL)==0,"no fuel supply without source grant");
                h.assertTrue(command(l,f.miner(),"saga supply household-source "+f.miner().getUUID()+" "+at(f.oreStock())+" allow-transfer")==1,"restore only explicit source right");restoredRight=true;
                Sagadyssey.LOGGER.info("P1_INTERVENTION restore source right t={}",l.getGameTime()-start);
            }
            for(var r:SupplyFlowData.get(l.getServer()).routes())if(r.principal().equals(SubjectRef.household(f.home()))&&r.carrierEntity()!=null&&seenClaims.add(r.id())){
                h.assertTrue(r.carrierEntity().equals(f.courier().getUUID()),"one original courier owns all P1 routes");
                if(phase().equals("p1-competition")){h.assertTrue(NpcSchedulePlanner.allowsWork(f.courier()),"no new claim outside actual work schedule");nightClaims++;}
            }
            int open=0,held=0,carrying=0;var types=new HashSet<String>();completed.clear();var needs=NeedRequestData.get(l.getServer());
            for(var r:needs.requests())if(r.key().requester().equals(f.home())){
                maxWait=Math.max(maxWait,(r.status().terminal()||r.status()==NeedRequestStatus.DELIVERED?r.updatedAt():l.getGameTime())-r.createdAt());
                if(r.status()==NeedRequestStatus.DELIVERED||r.status()==NeedRequestStatus.CONSUMED||r.status()==NeedRequestStatus.CLOSED)completed.merge(r.key().item().itemId(),1,Integer::sum);
                else if(!r.status().terminal()){open++;maxWait=Math.max(maxWait,l.getGameTime()-r.createdAt());}
            }
            for(var r:SupplyFlowData.get(l.getServer()).routes())if(r.principal().equals(SubjectRef.household(f.home()))){
                if(!routeStarts.containsKey(r.id())){
                    var birth=RightsStateData.get(l.getServer()).reservationsForToken(r.token(),l.getGameTime()).stream().mapToLong(AssetReservation::createdAt).min();
                    h.assertTrue(birth.isPresent(),"new route has observed original asset-reservation timestamp");routeStarts.put(r.id(),birth.getAsLong());
                }
                maxRouteAge=Math.max(maxRouteAge,(r.closed()?r.retryAt():l.getGameTime())-routeStarts.get(r.id()));
                if(r.closed())continue;
                types.add(needs.request(r.need()).key().item().itemId());
                if(r.stage()==SupplyFlowData.Stage.HELD)held++;if(r.stage()==SupplyFlowData.Stage.CARRYING)carrying++;
                h.assertTrue(r.stage()!=SupplyFlowData.Stage.REVIEW,"known route cannot become unexplained review");
            }
            maxTypes=Math.max(maxTypes,types.size());if(held>0&&!NpcSchedulePlanner.allowsWork(f.courier()))restHeld++;
            if(!phase().equals("p1-calibrate")){
                h.assertTrue(open<=5&&held+carrying<=5,"bounded per-policy outstanding obligations");
                h.assertTrue(maxRouteAge<=24000,"route service within one full work/rest day");
                h.assertTrue(maxWait<=72000,"cold-start/growth demand within three days");
            }
            if(l.getGameTime()-start>=96000)endBuckets.put((l.getGameTime()-start)/12000,open);
            maxOpen=Math.max(maxOpen,open);maxHeld=Math.max(maxHeld,held);maxCarrying=Math.max(maxCarrying,carrying);
            if((l.getGameTime()-start)%12000==0)Sagadyssey.LOGGER.info("P1_SAMPLE phase={} t={} burn={} coal={} open={} held={} carrying={} maxWait={} routeAge={} completed={}",phase(),l.getGameTime()-start,ignitions,count(l,f,Items.COAL),open,held,carrying,maxWait,maxRouteAge,completed);
        }
        void finish(GameTestHelper h,Farm f,Set<String> meals){
            tick(h,f);var l=h.getLevel();
            if(!phase().equals("p1-calibrate")){
                var needs=NeedRequestData.get(l.getServer());
                for(var r:SupplyFlowData.get(l.getServer()).routes())if(r.principal().equals(SubjectRef.household(f.home()))){
                    h.assertTrue(r.stage()!=SupplyFlowData.Stage.REVIEW,"no unexplained in-flight obligations");
                    if(r.stage()==SupplyFlowData.Stage.DONE||r.stage()==SupplyFlowData.Stage.CANCELLED)h.assertTrue(RightsStateData.get(l.getServer()).reservationsForToken(r.token(),l.getGameTime()).isEmpty(),"closed route releases reservations");
                }
                for(var rawOrder:MinecraftApi.listOfType(com.jgeted.sagadyssey.social.production.ProductionOrderData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()), "Orders",Tag.TAG_COMPOUND))
                    h.assertTrue(com.jgeted.sagadyssey.social.production.ProductionOrderStatus.valueOf(((CompoundTag)rawOrder).getStringOr("Status", "")).terminal(),"no unexplained active production orders in native-processing contract");
                if(phase().equals("p1-competition"))h.assertTrue(maxTypes>=2&&restHeld>0&&nightClaims>0&&delta(f.miner(),Items.COAL)>=2,"transport types compete, lawful rest delays service, original miner resumes");
            }
            var counts=new TreeMap<Long,Integer>();int harvest=0,plant=0;
            for(var r:FieldworkData.get(l.getServer()).receiptsFor(f.farmer().getUUID())){
                if(r.action().operation()==FieldworkAction.Operation.HARVEST){harvest++;counts.merge(r.action().target().asLong(),1,Integer::sum);}
                if(r.action().operation()==FieldworkAction.Operation.PLANT)plant++;
            }
            Sagadyssey.LOGGER.info("P1_ACCEPT phase={} t={} ignitions={} coal={} harvested={} planted={} repeat={} meals={} maxWait={} maxRouteAge={} maxOpen={} maxHeld={} maxCarrying={} completed={}",phase(),l.getGameTime()-start,ignitions,count(l,f,Items.COAL),harvest,plant,counts.values().stream().mapToInt(i->i).max().orElse(0),meals.size(),maxWait,maxRouteAge,maxOpen,maxHeld,maxCarrying,completed);
            var tag=save();tag.putString("Phase",phase());tag.putInt("MaxTypes",maxTypes);tag.putInt("RestHeldSamples",restHeld);tag.putInt("NightClaims",nightClaims);tag.putInt("Cooked",cooked(l,f));tag.putInt("NetCrop",delta(f.farmer(),Items.POTATO));tag.putInt("Raw",count(l,f,Items.POTATO));tag.putInt("Baked",count(l,f,Items.BAKED_POTATO));tag.putInt("MinedCoal",delta(f.miner(),Items.COAL));
            tag.put("Needs",NeedRequestData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()));tag.put("Flows",SupplyFlowData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()));tag.put("Rights",RightsStateData.get(l.getServer()).save(new CompoundTag(),l.registryAccess()));
            if(!phase().equals("p1-calibrate")&&!phase().equals("p1-resume")&&(phase().equals("p1-steady")||phase().equals("p1-competition")||phase().equals("p1-right"))){
                h.assertTrue(harvest>=3&&plant>=3&&meals.size()>=9&&delta(f.farmer(),Items.POTATO)>meals.size(),"at least three real harvest/replant/processing/consumption rounds; new crop covers meals");
                h.assertTrue(endBuckets.size()>=2&&endBuckets.values().stream().mapToInt(i->i).max().orElse(99)<=5,"tail window has bounded backlog across samples");
                h.assertTrue(endBuckets.get(endBuckets.keySet().stream().max(Long::compare).orElseThrow())<=endBuckets.get(endBuckets.keySet().stream().min(Long::compare).orElseThrow()),"tail backlog does not grow between first and final buckets");
            }
            tag.putLong("Ticks",l.getGameTime()-start);tag.putInt("Ignitions",ignitions);tag.putInt("Coal",count(l,f,Items.COAL));tag.putInt("Harvests",harvest);tag.putInt("Plants",plant);tag.putInt("Meals",meals.size());tag.putLong("MaxWait",maxWait);tag.putLong("MaxRouteAge",maxRouteAge);tag.putInt("MaxOpen",maxOpen);tag.putInt("MaxHeld",maxHeld);tag.putInt("MaxCarrying",maxCarrying);
            try{NbtIo.writeCompressed(tag,l.getServer().getWorldPath(LevelResource.ROOT).resolve("p1-evidence.nbt"));}catch(Exception e){throw new IllegalStateException(e);}
        }
        CompoundTag save(){var t=new CompoundTag();t.putLong("Started",start);t.putInt("Ignitions",ignitions);t.putInt("LastBurn",lastBurn);t.putLong("MaxWait",maxWait);t.putLong("MaxRouteAge",maxRouteAge);var births=new ListTag();routeStarts.forEach((id,time)->{var b=new CompoundTag();b.store("Id", UUIDUtil.CODEC,id);b.putLong("Time",time);births.add(b);});t.put("RouteStarts",births);return t;}
        void load(CompoundTag t){start=t.getLongOr("Started", 0L);ignitions=t.getIntOr("Ignitions", 0);lastBurn=t.getIntOr("LastBurn", 0);maxWait=t.getLongOr("MaxWait", 0L);maxRouteAge=t.getLongOr("MaxRouteAge", 0L);for(var raw:MinecraftApi.listOfType(t, "RouteStarts",Tag.TAG_COMPOUND)){var b=(CompoundTag)raw;routeStarts.put(b.read("Id", UUIDUtil.CODEC).orElseThrow(),b.getLongOr("Time", 0L));}}
    }

    private static CompoundTag ledgers(ServerLevel l){
        var s=l.getServer();var t=new CompoundTag();var r=l.registryAccess();
        t.put("Needs",NeedRequestData.get(s).save(new CompoundTag(),r));t.put("Flows",SupplyFlowData.get(s).save(new CompoundTag(),r));t.put("Rights",RightsStateData.get(s).save(new CompoundTag(),r));t.put("Transfers",TransferLedgerData.get(s).save(new CompoundTag(),r));
        t.put("Households",HouseholdData.get(s).save(new CompoundTag(),r));t.put("Brain",NpcBrainRuntime.data(s).save(new CompoundTag(),r));t.put("Fieldwork",FieldworkData.get(s).save(new CompoundTag(),r));t.put("Life",NpcLifeData.get(s).save(new CompoundTag(),r));
        t.put("Logistics",com.jgeted.sagadyssey.social.logistics.LogisticsData.get(s).save(new CompoundTag(),r));return t;
    }
    private static CompoundTag transferMapOrder(CompoundTag source){
        var t=source.copy();for(var raw:MinecraftApi.listOfType(t, "Receipts",Tag.TAG_COMPOUND)){
            var receipt=(CompoundTag)raw;var entries=new ArrayList<CompoundTag>();for(var value:MinecraftApi.listOfType(receipt, "Revisions",Tag.TAG_COMPOUND))entries.add((CompoundTag)value);
            entries.sort(Comparator.comparing(v->v.read("Container", UUIDUtil.CODEC).orElseThrow().toString()));var list=new ListTag();entries.forEach(list::add);receipt.put("Revisions",list);
        }return t;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void restoredEntity(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(!phase().equals("p1-resume")||previous==null||!(e.getEntity() instanceof NpcBase n)||!previous.contains("Body:"+n.getUUID()))return;
        var a=MinecraftApi.saveEntity(n);var b=previous.getCompoundOrEmpty("Body:"+n.getUUID());
        for(String key:List.of("Items","HandItems","PersonalInventoryRevision","HomeSettlementId"))if(!Objects.equals(a.get(key),b.get(key)))throw new IllegalStateException("P1 original entity mismatch before tick: "+key);
        RESTORED_BODIES.add(n.getUUID());Sagadyssey.LOGGER.info("P1_RESTORE original body verified {}",n.getUUID());
    }
    private static void snapshot(ServerLevel l){try{
        var root=l.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath();var dest=root.resolveSibling("world-p1-snapshot");
        if(java.nio.file.Files.exists(dest))throw new IllegalStateException("fresh snapshot required");
        try(var paths=java.nio.file.Files.walk(root)){for(var path:paths.toList()){if(path.getFileName().toString().equals("session.lock"))continue;var out=dest.resolve(root.relativize(path));if(java.nio.file.Files.isDirectory(path))java.nio.file.Files.createDirectories(out);else java.nio.file.Files.copy(path,out);}}
        Sagadyssey.LOGGER.info("P1_SNAPSHOT complete linked world {}",dest);
    }catch(Exception e){throw new IllegalStateException(e);}}
}
