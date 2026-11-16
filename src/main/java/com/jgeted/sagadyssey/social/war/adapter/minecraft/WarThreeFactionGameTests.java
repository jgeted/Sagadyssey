package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.combat.CombatEncounterData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.politics.PoliticalRelation;
import com.jgeted.sagadyssey.social.politics.PoliticalStateData;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.social.service.ServiceBasis;
import com.jgeted.sagadyssey.social.war.api.WarFieldReport;
import com.jgeted.sagadyssey.social.war.api.WarMapSheet;
import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry.Shipment;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry;
import com.jgeted.sagadyssey.social.war.domain.WarUnit;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerData;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Ordinary dedicated server fixture. It seeds civilians and observes real player submissions and world outcomes. */
@EventBusSubscriber(modid="sagadyssey")
public final class WarThreeFactionGameTests {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] NAMES = {"A", "B", "C"};
    private static final String[] FACTIONS = {"sagadyssey:kingdom", "sagadyssey:merchant_guild", "sagadyssey:church"};
    private static final int FLOOR = 79;
    private static final int[] HOMES_X = {-43, 18, 41};
    private static final int[] HOMES_Z = {-30, 58, -34};
    private static final int[] GRAND_STRENGTH = {300, 600, 300};
    private static final int[] GRAND_COMPANIES = {5, 9, 5};
    private static final int COMPANY_SLOTS = 60;
    private static final Map<MinecraftServer, Campaign> CAMPAIGNS = new WeakHashMap<>();

    private record Step(String name, BooleanSupplier finished) {}
    private record Candidate(UUID body, UUID person, int startCost) {}

    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("sagadyssey.war.campaign.dedicated")) return;
        var server = event.getServer();
        var storage = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var control = Path.of(System.getProperty("sagadyssey.war.campaign.control")).toAbsolutePath().normalize();
        if (!server.isDedicatedServer() || !storage.startsWith(control) || Files.exists(control.resolve("result.json")))
            throw new IllegalStateException("isolated campaign server required");
        var campaign = new Campaign(server,storage,control,System.getProperty("sagadyssey.war.campaign.phase","opening"));
        CAMPAIGNS.put(server,campaign);
        campaign.setup();
        Files.writeString(control.resolve("ready"),"ready");
    }

    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var campaign = CAMPAIGNS.get(player.level().getServer());
        if (campaign != null) campaign.join(player);
    }

    @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var campaign = CAMPAIGNS.get(player.level().getServer());
        if (campaign != null && !campaign.stopped
                && Arrays.stream(campaign.teams).anyMatch(team -> team.player == player))
            campaign.finish(false,"original TCP client disconnected: "+player.getGameProfile().name());
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var campaign = CAMPAIGNS.get(event.getServer());
        if (campaign != null) campaign.tick();
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { CAMPAIGNS.remove(event.getServer()); }

    @SubscribeEvent public static void damage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof NpcBase target) || !(event.getSource().getEntity() instanceof NpcBase attacker)) return;
        var campaign = CAMPAIGNS.get(((net.minecraft.server.level.ServerLevel)target.level()).getServer());
        if (campaign != null && event.getNewDamage() > 0) campaign.contact(attacker,target,event.getNewDamage());
    }

    private static final class Campaign {
        final MinecraftServer server;
        final Path world, control;
        final String phase;
        final Team[] teams = { new Team(0), new Team(1), new Team(2) };
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        final List<Map<String,Object>> timeline = new ArrayList<>();
        final List<Map<String,Object>> damage = new ArrayList<>();
        final Set<UUID> recruits = new HashSet<>();
        final Map<UUID,Integer> sides = new HashMap<>();
        final Map<UUID,Candidate> peopleById = new HashMap<>();
        final List<Map<String,Object>> orders = new ArrayList<>(), load = new ArrayList<>(), battle = new ArrayList<>();
        final Map<UUID,Map<String,Object>> signing = new HashMap<>();
        final List<Map<String,Object>> funding = new ArrayList<>();
        long stepStarted;
        long combatAt;
        long supportAt;
        long sampledFrom;
        boolean advance, stopped;
        int clients;
        List<Set<UUID>> savedBattleRoster=List.of();

        Campaign(MinecraftServer server, Path world, Path control, String phase) {
            this.server=server;this.world=world;this.control=control;this.phase=phase;
        }

        void setup() throws Exception {
            var level=server.overworld();
            Files.writeString(control.resolve("fixture-java-pid"),Long.toString(ProcessHandle.current().pid()),StandardOpenOption.CREATE_NEW);
            level.getGameRules().set(GameRules.SPAWN_MOBS,false,server);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)level.setChunkForced(x,z,true);
            var roster=world.resolve("campaign-civilians.json");
            if(Files.exists(roster)){
                var rows=JsonParser.parseString(Files.readString(roster)).getAsJsonArray();
                for(var raw:rows){
                    var value=raw.getAsJsonObject();int side=value.get("side").getAsInt();
                    var candidate=new Candidate(UUID.fromString(value.get("body").getAsString()),UUID.fromString(value.get("person").getAsString()),value.get("cost").getAsInt());
                    register(teams[side],candidate);
                }
            }else{
                if(!phase.equals("opening"))throw new IllegalStateException("opening world required");
                for(int x=-62;x<=61;x++)for(int z=-60;z<=69;z++)level.setBlock(new BlockPos(x,FLOOR,z),Blocks.STONE.defaultBlockState(),3);
                int[] population={25,145,40};
                for(int side=0;side<3;side++) for(int i=0;i<population[side];i++)spawn(teams[side],i);
                saveCivilians();
            }
            if(phase.equals("grand")) prepareGrandLand();
            if(isGrandBattle()||phase.equals("grand-stage")) restoreCompanies();
            if(isGrandBattle()){
                var record=JsonParser.parseString(Files.readString(control.getParent().resolve("parent.json"))).getAsJsonObject();
                savedBattleRoster=record.getAsJsonArray("people").asList().stream().map(group->group.getAsJsonArray().asList().stream()
                        .map(id->UUID.fromString(id.getAsString())).collect(java.util.stream.Collectors.toUnmodifiableSet())).toList();
                if(savedBattleRoster.size()!=teams.length||savedBattleRoster.stream().mapToInt(Set::size).sum()<1000)
                    throw new IllegalStateException("large battles require the confirmed pre-battle roster");
            }
            if(isPilot()){
                for(var team:teams)team.units.add("Line");
                teams[1].units.add("Reserve");
            }
            for(int i=0;i<3;i++)for(int j=i+1;j<3;j++)relation(i,j,PoliticalRelation.NEUTRAL);
        }

        void spawn(Team team,int index) {
            int width=team.index==1?16:7;
            var at=BlockPos.containing(HOMES_X[team.index]+(index%width)*2.5-12,FLOOR+1,HOMES_Z[team.index]-(index/width)*2.6);
            spawn(team,index,NpcProfession.WORKER,team.index==1&&index>=130,at);
        }

        void spawn(Team team,int index,NpcProfession profession,boolean banner,BlockPos at) {
            var level=server.overworld();
            var npc=new NpcBase(NpcEntityTypes.NPC_BASE.get(),level);
            npc.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            npc.setNpcName("Campaign "+team.name()+" Civil "+index);
            npc.setProfession(profession);npc.setIdentity(NpcSocialClass.COMMONER,NpcOffice.NONE);
            npc.setFaction(FactionRegistry.get(FACTIONS[team.index]));npc.setCommand(NpcCommand.STAY);npc.setPersistenceRequired();
            for(var slot:EquipmentSlot.values())npc.setItemSlot(slot,ItemStack.EMPTY);
            npc.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
            if(banner)npc.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.WHITE_BANNER));
            npc.getPersonalInventory().clearContent();npc.getPersonalInventory().setItem(0,new ItemStack(Items.BREAD,24));
            if(!level.addFreshEntity(npc))throw new IllegalStateException("original civilian could not spawn");
            var life=NpcLifeData.get(server);life.ensureRegistered(npc,now());life.setHunger(npc.getCharacterId(),100,now());
            register(team,new Candidate(npc.getUUID(),npc.getCharacterId(),npc.getAdjustedRecruitmentCost()));
        }

        void register(Team team,Candidate candidate) {
            team.pool.add(candidate);
            sides.put(candidate.person(),team.index);
            peopleById.put(candidate.person(),candidate);
        }

        private void saveCivilians() {
            var rows=new ArrayList<Map<String,Object>>();
            for(var team:teams)for(var entry:team.pool)
                rows.add(Map.of("side",team.index,"person",entry.person(),"body",entry.body(),"cost",entry.startCost()));
            try{
                Files.writeString(world.resolve("campaign-civilians.json"),JSON.toJson(rows));
            }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        }

        void join(ServerPlayer player) {
            var team=Arrays.stream(teams).filter(t->player.getGameProfile().name().equals("Campaign"+t.name())).findFirst().orElseThrow();
            if(player.connection.getConnection().channel()==null||!player.connection.getConnection().channel().getClass().getName().contains("SocketChannel"))
                throw new IllegalStateException("campaign requires original native TCP clients");
            team.player=player;team.owner=CharacterRegistry.ensureCurrent(player).id();clients++;
            CharacterRegistry.updateIdentity(player,NpcSocialClass.COMMONER,NpcOffice.NONE,FACTIONS[team.index]);
            if(phase.equals("opening")){
                player.getInventory().clearContent();
                for(int slot=1;slot<18;slot++)player.getInventory().setItem(slot,new ItemStack(Items.EMERALD,64));
                player.getInventory().setItem(0,MapItem.create(server.overworld(),0,0,(byte)1,true,true));
                player.teleportTo(HOMES_X[team.index],FLOOR+1,HOMES_Z[team.index]);
                player.inventoryMenu.broadcastChanges();
            }
        }

        private void plan() {
            if (phase.equals("survival-ui")) { survivalUi(); return; }
            if (phase.equals("survival-joint-ui")) { survivalJointUi(); return; }
            if(isGrandBattle()){grandBattle();return;}
            if(isPilot()){pilot();return;}
            if(phase.equals("joint")){jointOrders();return;}
            if(phase.equals("joint-resume")){resumeJointOrders();return;}
            if(phase.equals("grand-stage")){
                step("all original army members restored",()->counts().equals(List.of(300,600,300)));
                stageGrandArmy();
                return;
            }
            action("original army creation",()->{for(var team:teams)team.send("CMD saga war muster create "+team.name());});
            step("original armies durable",()->Arrays.stream(teams).allMatch(t->WarMusterRuntime.existing(server).filter(m->m.book().owns(t.owner,t.army())).isPresent()));
            createUnits("Line",teams);
            if(phase.equals("opening")){
                recruit(3,5,4);
                appointmentLook(teams[0],"temporary_deputy",NpcMilitaryRole.DEPUTY.id(),"opening-deputy");
                order("opening hold A",teams[0],"Line","HOLD",-17,-3);
                order("opening hold B",teams[1],"Line","HOLD",-17,3);
                order("opening neutral reserve C",teams[2],"Line","HOLD",-4,-3);
                fight("opening A–B engagement",0,1);
                order("small A orders retreat",teams[0],"Line","RETREAT",-39,-23);
                step("small A begins actual withdrawal",()->reports(teams[0]).stream().anyMatch(r->r.state()==WarFieldReport.State.RETREATING||r.state()==WarFieldReport.State.REGROUPING));
            }else if(phase.equals("growth")||phase.equals("late-growth")){
                createUnits("Reserve",teams[1]);
                if(phase.equals("growth")){
                    recruit(10,21,12);
                    review(teams[1],21);
                    appointmentLook(teams[1],"captain",NpcMilitaryRole.TROOP_CAPTAIN.id(),"growth-captain");
                    appointmentLook(teams[1],"deputy",NpcMilitaryRole.DEPUTY.id(),"growth-deputy");
                    recruit(10,50,24);
                    review(teams[1],50);
                    recruit(10,51,24);
                    review(teams[1],51);
                }
                action("civilian mobilization reserve",()->{
                    var b=teams[1];
                    int start=b.pool.size();
                    for(int i=0;i<56;i++)
                        spawn(b,start+i,NpcProfession.WORKER,false,new BlockPos(25+2*(i%14),FLOOR+1,18+2*(i/14)));
                    saveCivilians();
                });
                recruit(10,100,24);
                review(teams[1],100);
                Team b = teams[1];
                int[] flag={0},staff={0},engineer={0},end={0};
                action("newly arrived civilian specialists",()->{
                    flag[0]=b.pool.size();
                    for(int i=0;i<6;i++)spawn(b,b.pool.size(),NpcProfession.WORKER,true,new BlockPos(49+2*i,FLOOR+1,44));
                    staff[0]=b.pool.size();
                    for(int i=0;i<6;i++)spawn(b,b.pool.size(),NpcProfession.COURIER,false,new BlockPos(49+2*i,FLOOR+1,50));
                    engineer[0]=b.pool.size();
                    for(int i=0;i<6;i++)spawn(b,b.pool.size(),NpcProfession.WORKER,false,new BlockPos(49+2*i,FLOOR+1,54));
                    end[0]=b.pool.size();
                    saveCivilians();
                });
                step("actual voluntary support recruitment",()->b.recruitSpecial(WarMilitiaRequest.Role.STAFF,flag[0],staff[0]));
                appointmentLook(teams[1],"standard_bearer",NpcMilitaryRole.STANDARD_BEARER.id(),"growth-flag");
                step("actual voluntary courier enlistment",()->b.recruitSpecial(WarMilitiaRequest.Role.STAFF,staff[0],engineer[0]));
                transportLook(b);
                order("A attacks from small roster",teams[0],"Line","HOLD",-19,-7);
                order("B line opposite A",teams[1],"Line","HOLD",-19,-1);
                order("B reserve on C front",teams[1],"Reserve","HOLD",26,6);
                order("C approaches B flank",teams[2],"Line","HOLD",26,0);
                action("two battle fronts",()->{relation(0,1,PoliticalRelation.WAR);relation(1,2,PoliticalRelation.WAR);combatAt=now();});
                step("actual A–B and B–C wounds",()->paired(0,1)&&paired(1,2));
                action("stand down",()->{checkNoNeutralHits();relation(0,1,PoliticalRelation.NEUTRAL);relation(1,2,PoliticalRelation.NEUTRAL);});
                recruit(10,121,24);
                review(b,121);
                appointmentLook(b,"quartermaster",NpcMilitaryRole.QUARTERMASTER.id(),"growth-quartermaster");
                step("actual voluntary engineering enlistment",()->b.recruitSpecial(WarMilitiaRequest.Role.ENGINEER,engineer[0],end[0]));
                look("growth-engineer",b,()->b.original(b.specialist),NpcMilitaryRole.ENGINEER.id(),()->true);
                appointmentLook(b,"engineering_lead",NpcMilitaryRole.ENGINEERING_LEAD.id(),"growth-engineering-lead");
            }else if(phase.equals("post-growth")){
                createUnits("Reserve",teams[1]);
                Team b=teams[1];
                action("recover original completed transporter",()->{
                    b.specialist=WarCampaignRuntime.existing(server).orElseThrow().entries().stream()
                            .filter(Shipment.class::isInstance).map(Shipment.class::cast)
                            .filter(row->row.run().army().equals(b.army())&&row.run().terminal()&&row.run().delivered()==1)
                            .map(row->row.run().person()).findFirst().orElseThrow();
                });
                look("growth-carrier-resume",b,()->b.original(b.specialist),NpcMilitaryRole.STAFF_OFFICER.id(),()->true);
                action("new volunteering pool after contact",()->{
                    for(int i=0;i<24;i++)spawn(b,b.pool.size(),NpcProfession.WORKER,false,new BlockPos(-24+3*(i%12),FLOOR+1,42+3*(i/12)));
                    saveCivilians();
                });
                recruit(10,121,24);
                review(b,121);
                appointmentLook(b,"quartermaster",NpcMilitaryRole.QUARTERMASTER.id(),"growth-quartermaster");
                int[] first={0},end={0};
                action("additional civilian engineering candidates",()->{
                    first[0]=b.pool.size();
                    for(int i=0;i<6;i++)spawn(b,b.pool.size(),NpcProfession.WORKER,false,new BlockPos(47+i*2,FLOOR+1,61));
                    end[0]=b.pool.size();
                    saveCivilians();
                });
                step("actual voluntary engineering enlistment",()->b.recruitSpecial(WarMilitiaRequest.Role.ENGINEER,first[0],end[0]));
                look("growth-engineer",b,()->b.original(b.specialist),NpcMilitaryRole.ENGINEER.id(),()->true);
                appointmentLook(b,"engineering_lead",NpcMilitaryRole.ENGINEERING_LEAD.id(),"growth-engineering-lead");
            }else if(phase.equals("grand")){
                grandMuster();
            }else{
                throw new IllegalArgumentException("unknown isolated campaign phase "+phase);
            }
        }

        private void survivalUi() {
            var b = teams[1];
            for (var team : teams) team.units.add("Line");
            b.units.add("Reserve");
            UUID[] recruit = {null}, contract = {null}, appointment = {null};
            int[] stock = {0};
            var patrol = WarMusterEntry.armyId(b.owner, "Patrol");
            var foot = WarMusterEntry.unitId(patrol, "Foot");
            var engineer = UUID.fromString("ca0cda49-765d-4e3c-a21c-f5c95940812c");
            var carrier = UUID.fromString("3b07d59d-4a16-49bc-8d84-5925f8a5f8ab");
            var receiver = UUID.fromString("73825511-169f-4aa1-b3ba-bde55f28e81c");
            var floor = new BlockPos(50, FLOOR, 62);
            var flag = new BlockPos(47, FLOOR + 1, 64);
            var depot = new BlockPos(49, FLOOR + 1, 46);
            action("real non-operator survival and new civilian", () -> {
                for (var team : teams) {
                    if (team.player.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL
                            || com.jgeted.sagadyssey.core.MinecraftApi.hasPermission(team.player.createCommandSourceStack(), 2))
                        throw new IllegalStateException("survival UI requires original non-operator players");
                }
                spawn(b, b.pool.size(), NpcProfession.WORKER, false, new BlockPos(46, FLOOR + 1, 63));
                var n = body(b.pool.getLast());
                recruit[0] = n.getCharacterId();
                approach(b, n);
                ui(b, "MAP", "BUTTON 军籍簿", "BUTTON 新建军队", "FORM Patrol", "BUTTON 登记军队");
            });
            step("named patrol created with the held map UI", () -> muster().book().owns(b.owner, patrol));
            action("choose actual loadout through map book", () -> ui(b, "BUTTON Patrol", "BUTTON 兵团与编组",
                    "BUTTON 编组新兵团", "BUTTON 无盾无甲剑士步兵", "FORM Foot", "BUTTON 登记兵团"));
            step("real foot unit recorded", () -> muster().book().unit(foot).filter(WarMusterEntry.Unit::recorded)
                    .filter(muster().book()::durable).isPresent());
            action("civilian UI signing on the real NPC", () -> ui(b, "CLOSE", "CIVIL " + b.original(recruit[0]).getId(), "BUTTON 提出服务约定"));
            step("civilian signature and actual emerald outflow", () -> {
                var npc = b.original(recruit[0]);
                if (!npc.isOwnedBy(b.player.getUUID())) return false;
                if (npc.getPersonalInventory().countItem(Items.EMERALD) != b.pool.getLast().startCost())
                    throw new IllegalStateException("original recruitment payment mismatch");
                return true;
            });
            action("offer separate military terms with actual NPC conversation", () -> ui(b, "CLOSE",
                    "TALK " + b.original(recruit[0]).getId(), "BUTTON 提出军役", "BUTTON Patrol", "BUTTON Foot", "BUTTON 战斗军役",
                    "FORM 64|30", "BUTTON 预览条款", "BUTTON 递交本人决定"));
            step("original soldier and clothing active", () -> {
                var service = muster().book().latest(recruit[0]).orElse(null);
                if (service == null) return false;
                if (service.stage() == WarMusterEntry.Stage.DECLINED) throw new IllegalStateException("original candidate declined");
                if (service.stage() != WarMusterEntry.Stage.ACTIVE || !muster().book().durable(service)
                        || !b.original(recruit[0]).getClothingOutfit().equals("foot_sword")) return false;
                contract[0] = service.id();
                return service.request().unitId().equals(foot);
            });
            action("take field command by map, register real flag", () -> {
                server.overworld().setBlockAndUpdate(flag, Blocks.WHITE_BANNER.defaultBlockState());
                b.player.getInventory().setItem(1, net.minecraft.world.item.InstrumentItem.create(Items.GOAT_HORN,
                        b.player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.INSTRUMENT)
                                .listElements().findFirst().orElseThrow()));
                ui(b, "CLOSE", "MAP", "BUTTON ~Patrol / Foot", "BUTTON 定为现场", "CLOSE", "BLOCK 47 80 64");
            });
            step("original flag saved by actual item interaction", () -> fieldCore().commandMedia()
                    .flatMap(media -> media.post(b.player)).filter(p -> p.unit().equals(foot)
                            && p.position().x() == flag.getX() && p.position().z() == flag.getZ()).isPresent()
                    && fieldCore().field().flatMap(f -> f.localRoute(b.owner, patrol, foot)).isPresent());
            action("sound the real horn", () -> ui(b, "HOTBAR 1", "USE", "HOTBAR 0"));
            step("selected troop received the original rally order", () -> fieldCore().session().flatMap(s -> s.unit(foot))
                    .flatMap(WarUnit::currentOrder).filter(o -> o.intent().kind().equals(
                            com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions.RALLY)).isPresent());
            action("retire through NPC and settle original duty", () -> {
                var npc = b.original(recruit[0]);
                approach(b, npc);
                ui(b, "TALK " + npc.getId(), "BUTTON 办理退役", "BUTTON 确认退役");
            });
            step("original contract released", () -> muster().book().offer(contract[0]).filter(o -> o.stage() == WarMusterEntry.Stage.ENDED)
                    .filter(muster().book()::durable).isPresent() && !com.jgeted.sagadyssey.social.work.WorkCommitmentRuntime.blocksCivil(b.original(recruit[0])));
            action("prepare original engineer and recheck staff roster via book", () -> {
                var n = b.original(engineer);
                n.getPersonalInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 8));
                server.overworld().setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                approach(b, n);
                ui(b, "CLOSE", "MAP", "BUTTON 军籍簿", "BUTTON B", "BUTTON 核定编制与军职", "BUTTON 核定战斗编制");
            });
            step("establishment durably reviewed from survival UI", () -> WarStaffRuntime.existing(server)
                    .flatMap(r -> r.book().establishment(b.army()).filter(r.book()::durable))
                    .filter(e -> e.reviewedAt() >= stepStarted - 20).isPresent());
            action("appointment registered from actual engineer conversation", () -> ui(b, "CLOSE",
                    "TALK " + b.original(engineer).getId(), "BUTTON 任用军职", "BUTTON 工程负责人", "BUTTON 代理任用",
                    "FORM 2|现场验收", "BUTTON 确认任用"));
            step("real engineering lead uniform", () -> {
                var current = WarStaffRuntime.existing(server).flatMap(r -> r.book().forPerson(engineer).filter(r::effective))
                        .filter(a -> a.terms().role() == WarStaffEntry.Role.ENGINEERING_LEAD);
                if (current.isEmpty() || !b.original(engineer).getClothingOutfit().equals(NpcMilitaryRole.ENGINEERING_LEAD.id())) return false;
                appointment[0] = current.get().id();
                return true;
            });
            action("consult and revoke through duty conversation", () -> ui(b, "BUTTON 刷新", "BUTTON 听取本人的建议",
                    "BUTTON 返回", "BUTTON 撤销任命", "BUTTON 确认撤职"));
            step("revocation persisted and same engineer changed back", () -> WarStaffRuntime.existing(server)
                    .flatMap(r -> r.book().appointment(appointment[0]).filter(r.book()::durable))
                    .filter(a -> a.phase() == WarStaffEntry.Phase.REVOKED).isPresent()
                    && b.original(engineer).getClothingOutfit().equals(NpcMilitaryRole.ENGINEER.id()));
            action("real engineering marks and preview on actual ground", () -> {
                b.player.teleportTo(50.5, FLOOR + 1, 64.5);
                ui(b, "BUTTON 刷新", "BUTTON 商议施工", "BUTTON 构筑工事", "BUTTON Cobblestone|圆石",
                        "BLOCK 50 79 62", "BLOCK 50 79 62", "BUTTON 批准施工");
            });
            step("one original block placed using real material", () -> server.overworld().getBlockState(floor.above()).is(Blocks.COBBLESTONE)
                    && b.original(engineer).getPersonalInventory().countItem(Items.COBBLESTONE) == 7
                    && WarEngineeringRuntime.existing(server).stream().flatMap(r -> r.entries().stream())
                    .anyMatch(j -> j.person().equals(engineer) && j.phase() == com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob.Phase.DONE));
            action("commission carrier using real NPC and its original recipient", () -> {
                var donor = b.original(carrier);
                approach(b, donor);
                var box = (ChestBlockEntity) server.overworld().getBlockEntity(depot);
                stock[0] = box.countItem(Items.BREAD);
                ui(b, "CLOSE", "TALK " + donor.getId(), "BUTTON 商议运输", "BUTTON 送给军士", "MARKING recipient");
            });
            step("actual world picker ready for selecting recipient", () -> Files.exists(control.resolve("ui-recipient")));
            action("reach and click the actual recipient", () -> {
                var donor = b.original(carrier);
                var target = b.original(receiver);
                target.getNavigation().stop();
                target.teleportTo(donor.getX() + 1.2, donor.getY(), donor.getZ() + .3);
                approach(b, target);
                ui(b, "TALK " + target.getId(), "BUTTON Bread|面包", "FORM 2|1", "BUTTON 确认运单");
            });
            step("real carrier delivered with original source decrement", () -> {
                var result = WarCampaignRuntime.existing(server).stream().flatMap(r -> r.entries().stream())
                        .filter(Shipment.class::isInstance).map(Shipment.class::cast)
                        .anyMatch(row -> row.run().person().equals(carrier) && row.run().quantity() == 2 && row.run().delivered() == 1);
                return result && ((ChestBlockEntity) server.overworld().getBlockEntity(depot)).countItem(Items.BREAD) == stock[0] - 2;
            });
        }

        private void survivalJointUi() {
            var a = teams[0]; var b = teams[1]; var c = teams[2];
            for (var team : teams) team.units.add("Line");
            b.units.add("Reserve");
            UUID[] id = {null};
            action("actual map and original alliance for UI", () -> {
                relation(0, 2, PoliticalRelation.ALLIED);
                a.player.teleportTo(-5, FLOOR + 1, -5);
                c.player.teleportTo(6, FLOOR + 1, -5);
                ui(a, "MAP", "BUTTON 军籍簿", "BUTTON 联合计划", "BUTTON 邀请盟友", "BUTTON " + CharacterRegistry.current(c.player).name(),
                        "FORM 35|60", "BUTTON 建立计划");
            });
            step("plan created from military book", () -> {
                var plans = WarJointOrderRuntime.existing(server).map(r -> r.plans(a.player)).orElse(List.of());
                if (plans.isEmpty()) return false;
                id[0] = plans.getFirst().id();
                return jointRuntime().isDurable(plans.getFirst());
            });
            action("each original commander chooses own map target", () -> {
                ui(a, "BUTTON 添加己方军令", "BUTTON A", "BUTTON Line", "BUTTON 坚守", "PIXEL 30 29", "BUTTON 加入计划 · 军报私有");
                ui(c, "MAP", "BUTTON 军籍簿", "BUTTON 联合计划",
                        "BUTTON " + CharacterRegistry.current(a.player).name() + " / " + CharacterRegistry.current(c.player).name(),
                        "BUTTON 添加己方军令", "BUTTON C", "BUTTON Line", "BUTTON 坚守", "PIXEL 34 29", "BUTTON 加入计划 · 共享");
            });
            step("distinct original orders with sharing choices", () -> {
                var plan = jointRuntime().view(a.player, id[0]);
                return plan.instructions().size() == 2 && jointRuntime().isDurable(plan);
            });
            action("original organizer signs saved version through UI", () -> ui(a, "BUTTON 刷新", "BUTTON 签署本版计划"));
            step("organizer signature saved", () -> {
                var plan = jointRuntime().view(a.player, id[0]);
                return jointRuntime().isDurable(plan) && plan.signatures().equals(Set.of(a.owner));
            });
            action("partner signs current version through UI", () -> ui(c, "BUTTON 刷新", "BUTTON 签署本版计划"));
            step("coordinated orders received and scoped reports", () -> {
                var plan = jointRuntime().view(a.player, id[0]);
                if (plan.phase() != com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase.SUBMITTED
                        || !jointRuntime().isDurable(plan)) return false;
                var field = fieldCore().field().orElseThrow();
                var transmissions = plan.instructions().stream().map(leg -> {
                    var cmd = leg.command();
                    return field.transmission(cmd.actorId(), cmd.armyId(), cmd.operationId()).orElseThrow();
                }).toList();
                if (transmissions.stream().anyMatch(t -> t.received().isEmpty())) return false;
                var reports = jointRuntime().reports(a.player, id[0]);
                if (reports.stream().noneMatch(line -> line.contains(c.unit("Line").toString()))
                        || jointRuntime().reports(c.player, id[0]).stream().anyMatch(line -> line.contains(a.unit("Line").toString()))
                        || !jointRuntime().plans(b.player).isEmpty() || transmissions.getFirst().route().sentAt() != transmissions.getLast().route().sentAt())
                    throw new IllegalStateException("survival joint command or report authority violated");
                battle.add(Map.of("joint", id[0], "reports", reports, "originalIssuers", plan.signatures()));
                return true;
            });
            action("withdraw via participant panel", () -> ui(c, "BUTTON 刷新", "BUTTON 关闭计划", "BUTTON 确认关闭"));
            step("participant closed plan", () -> {
                var plan = jointRuntime().view(a.player, id[0]);
                return jointRuntime().isDurable(plan) && plan.phase() == com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase.CLOSED;
            });
        }

        private static void ui(Team team, String... actions) {
            for (String item : actions) team.send("UI " + item);
        }

        private static void approach(Team t, NpcBase npc) {
            t.player.teleportTo(npc.getX(), npc.getY(), npc.getZ() + 2);
        }

        private void jointOrders() {
            var a = teams[0];
            var b = teams[1];
            var c = teams[2];
            for (var team : teams) team.units.add("Line");
            b.units.add("Reserve");
            UUID[] plan = {null};
            action("alliance and original maps for joint orders", () -> {
                relation(0, 2, PoliticalRelation.ALLIED);
                a.player.teleportTo(-5, FLOOR + 1, -5);
                c.player.teleportTo(6, FLOOR + 1, -5);
                a.send("CMD saga war joint new " + c.owner + " 360 1200");
            });
            step("original alliance plan durable", () -> {
                var runtime = jointRuntime();
                if (runtime == null) return false;
                var draft = runtime.plans(a.player).stream().findFirst().orElse(null);
                if (draft == null || !runtime.isDurable(draft)) return false;
                plan[0] = draft.id();
                return true;
            });
            step("select both original units using actual maps", () -> {
                var mapA = WarMapRuntime.sheet(a.player).orElse(null);
                var mapC = WarMapRuntime.sheet(c.player).orElse(null);
                if (mapA == null || mapC == null || reports(a).isEmpty() || reports(c).isEmpty()) return false;
                var commandA = jointSelection(a, mapA, -4, -6, plan[0], false);
                var commandC = jointSelection(c, mapC, 4, -6, plan[0], true);
                if (commandA.isEmpty() || commandC.isEmpty()) return false;
                a.send(commandA.orElseThrow());
                c.send(commandC.orElseThrow());
                return true;
            });
            step("both independent instructions recorded", () -> {
                var runtime = jointRuntime();
                var value = runtime.view(a.player, plan[0]);
                if (value.instructions().size() != 2 || !runtime.isDurable(value)) return false;
                b.send("CMD saga war joint sign " + plan[0] + " " + value.revision());
                a.send("CMD saga war joint sign " + plan[0] + " " + value.revision());
                return true;
            });
            step("first participant signature and second explicit confirmation", () -> {
                var runtime = jointRuntime();
                var value = runtime.view(a.player, plan[0]);
                if (!value.signatures().equals(Set.of(a.owner)) || !runtime.isDurable(value)) return false;
                c.send("CMD saga war joint sign " + plan[0] + " " + value.revision());
                return true;
            });
            step("one cycle submits two directions under their original authority", () -> {
                var runtime = jointRuntime();
                var value = runtime.view(a.player, plan[0]);
                if (value.phase() != com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase.SUBMITTED || !runtime.isDurable(value))
                    return false;
                var field = fieldCore().field().orElseThrow();
                var orders = value.instructions().stream().map(leg -> {
                    var command = leg.command();
                    return field.transmission(command.actorId(), command.armyId(), command.operationId())
                            .filter(transfer -> transfer.command().equals(command)).orElseThrow();
                }).toList();
                if (orders.stream().anyMatch(order -> order.received().isEmpty())) return false;
                if (orders.getFirst().route().sentAt() != orders.getLast().route().sentAt())
                    throw new IllegalStateException("joint plan did not submit on one tick");
                var shared = runtime.reports(a.player, plan[0]);
                var privateReport = runtime.reports(c.player, plan[0]);
                if (shared.stream().noneMatch(line -> line.contains(c.unit("Line").toString()))
                        || privateReport.stream().anyMatch(line -> line.contains(a.unit("Line").toString()))
                        || !runtime.plans(b.player).isEmpty())
                    throw new IllegalStateException("joint report consent or membership violated");
                battle.add(Map.of("jointPlan", plan[0], "sentAt", orders.getFirst().route().sentAt(), "received",
                        orders.stream().map(o -> o.received().orElseThrow().deliveredAtTick()).toList(),
                        "originalIssuers", value.signatures(), "optInReport", shared, "excludedOffice", b.owner));
                c.send("CMD saga war joint close " + plan[0]);
                return true;
            });
            step("shared report withdrawn without changing the independent orders", () -> {
                var runtime = jointRuntime();
                var value = runtime.view(a.player, plan[0]);
                if (value.phase() != com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase.CLOSED || !runtime.isDurable(value))
                    return false;
                return runtime.reports(a.player, plan[0]).equals(List.of("军报共享当前未生效"))
                        && fieldCore().session().orElseThrow().unit(c.unit("Line")).orElseThrow().currentOrder()
                        .filter(order -> order.issuerId().equals(c.owner)).isPresent();
            });
        }

        private void resumeJointOrders() {
            for (var team : teams) team.units.add("Line");
            teams[1].units.add("Reserve");
            step("closed joint order reload preserves the original independent receipts", () -> {
                var runtime = jointRuntime();
                var field = fieldCore().field().orElse(null);
                if (runtime == null || field == null) return false;
                var a = teams[0];
                var c = teams[2];
                var plan = runtime.plans(a.player).stream().findFirst().orElseThrow();
                if (!runtime.isDurable(plan) || plan.phase() != com.jgeted.sagadyssey.social.war.domain.WarJointOrder.Phase.CLOSED
                        || !plan.signatures().equals(Set.of(a.owner, c.owner))
                        || !runtime.reports(a.player, plan.id()).equals(List.of("军报共享当前未生效"))) return false;
                for (var instruction : plan.instructions()) {
                    var command = instruction.command();
                    var stored = field.transmission(command.actorId(), command.armyId(), command.operationId()).orElseThrow();
                    if (stored.received().filter(receipt -> receipt.issuerId().equals(command.actorId())).isEmpty()
                            || !stored.command().equals(command)
                            || fieldCore().session().orElseThrow().unit(command.unitId()).orElseThrow().lastIssuedSequence() != stored.sequence())
                        throw new IllegalStateException("joint recovery changed an original order or re-issued one");
                }
                battle.add(Map.of("jointRecovered", plan.id(), "originalReceiptCount", plan.receipts().size(),
                        "reportRevocationPreserved", true, "unchangedOriginalIssuers", plan.signatures()));
                return true;
            });
        }

        private WarJointOrderRuntime jointRuntime() { return WarJointOrderRuntime.existing(server).orElse(null); }

        private Optional<String> jointSelection(Team owner, WarMapSheet map, int x, int z, UUID plan, boolean share) {
            int px = (int) Math.floor(map.pixelX(x)), pz = (int) Math.floor(map.pixelZ(z));
            try { WarMapRuntime.target(owner.player, map, px, pz); }
            catch (IllegalArgumentException missing) { return Optional.empty(); }
            return Optional.of("CMD saga war joint select " + plan + " " + owner.name() + " Line hold " + px + " " + pz + " " + share);
        }

        private boolean isGrandBattle() {return Set.of("coalition","reserve").contains(phase);}
        private boolean isPilot() {return Set.of("pilot","pilot-reserve").contains(phase);}

        private void pilot() {
            step("restored three established armies before their large contact",()->counts().equals(List.of(10,92,24)));
            action("enable native pilot measurement",()->fieldCore().telemetry().enabled(true));
            Team c=teams[2];
            review(c,24);
            UUID[] officer={null};
            action("same original C pilot captain in both copies",()->{
                officer[0]=c.members("Line").stream().sorted().filter(id->{
                    var npc=c.original(id);
                    return npc.getHealth()>=npc.getMaxHealth()*.75F&&!npc.isSleeping();
                }).findFirst().orElseThrow();
                var npc=c.original(officer[0]);
                c.player.teleportTo(npc.getX(),npc.getY(),npc.getZ()+2);
                c.send("CMD saga war staff unitappoint C Line "+officer[0]+" 2 acting Matched defensive trial");
            });
            step("real C captain on line",()->WarStaffRuntime.existing(server)
                    .flatMap(staff->staff.fieldCaptain(c.army(),c.unit("Line")))
                    .filter(a->a.terms().holder().equals(officer[0])).isPresent());
            String[] initialPoints={null};
            action("observe original leadership first",()->initialPoints[0]=fieldCore().field().orElseThrow().leadershipSummary(officer[0]));
            order("A main line",teams[0],"Line","HOLD",-4,-6);
            order("B main line",teams[1],"Line","HOLD",-2,-2);
            order("B separate reserve",teams[1],"Reserve","HOLD",39,28);
            order("C allied support",teams[2],"Line","HOLD",4,phase.equals("pilot")?-6:-42);
            step("front companies actually at their positions",()->stationed(teams[0],"Line")>=6
                    &&stationed(teams[1],"Line")>=7&&stationed(teams[2],"Line")>=15);
            measure("mustered before skirmish",100,100,false);
            coalitionHostilities();
            measure("equal skirmish window",600,0,true);
            step("actual skirmish between serving fighters",()->paired(0,1)&&(!phase.equals("pilot")||paired(1,2)));
            if(phase.equals("pilot"))step("original pilot allied encounter",this::alliedEngagement);
            action("record original captain field growth",()->{
                var field=fieldCore().field().orElseThrow();
                var ready=field.cohesion(c.owner,c.army(),c.unit("Line"));
                battle.add(Map.of("captain",officer[0],"before",initialPoints[0],"after",field.leadershipSummary(officer[0]),
                        "organization",ready.map(com.jgeted.sagadyssey.social.war.domain.WarCohesion::organization).orElse(-1)));
            });
            action("recording pilot ends",()->{checkNoNeutralHits();relation(0,1,PoliticalRelation.NEUTRAL);relation(1,2,PoliticalRelation.NEUTRAL);});
        }

        private long stationed(Team team,String unit){
            return reports(team).stream().filter(r->r.state()==WarFieldReport.State.HOLDING&&r.unit().equals(team.unit(unit))
                    &&now()-r.observedAt()<=25).count();
        }

        private void prepareGrandLand() {
            var level=server.overworld();
            for(int x=-6;x<=6;x++)for(int z=-5;z<=5;z++)level.setChunkForced(x,z,true);
            for(int x=-88;x<=89;x++)for(int z=-67;z<=67;z++)
                level.setBlock(new BlockPos(x,FLOOR,z),Blocks.STONE.defaultBlockState(),3);
        }

        private void restoreCompanies() {
            for(var t:teams) {
                t.units.add("Line");
                if(t.index==1)t.units.add("Reserve");
                for(int i=0;i<GRAND_COMPANIES[t.index];i++)t.units.add("Company"+i);
            }
        }

        private void grandMuster() {
            action("fixture expansion budget and faster recruitment clock",()->{
                seedExpansionBudgets();
                server.tickRateManager().setTickRate(40);
            });
            createUnits("Reserve",teams[1]);
            for(int i=0;i<GRAND_COMPANIES[1];i++)createUnits("Company"+i,
                    i<GRAND_COMPANIES[0]?teams:new Team[]{teams[1]});
            for(var totals:List.of(new int[]{34,121,24},new int[]{64,128,64},new int[]{128,256,128},
                    new int[]{200,400,200},new int[]{250,500,250},GRAND_STRENGTH)){
                if(totals[1]==256||totals[1]==400)
                    action("fixture staging tranche "+totals[1],this::seedExpansionBudgets);
                action("new voluntary candidates "+Arrays.toString(totals),()->{
                    for(var team:teams)expand(team,totals[team.index]);
                    saveCivilians();
                });
                recruit(totals[0],totals[1],totals[2]);
            }
            stageGrandArmy();
        }

        private void stageGrandArmy() {
            action("restore normal clock and enable pre-battle measurement",()->{
                server.tickRateManager().setTickRate(20);
                fieldCore().telemetry().enabled(true);
            });
            deploy("staging",false);
            measure("original war orders before battle",300,1000,false);
        }

        private void seedExpansionBudgets() {
            for(var team:teams){
                var storage=team.player.getInventory();
                int added=0;
                for(int slot=1;slot<36;slot++)if(storage.getItem(slot).isEmpty()){
                    storage.setItem(slot,new ItemStack(Items.EMERALD,64));
                    added+=64;
                }
                team.player.inventoryMenu.broadcastChanges();
                funding.add(Map.of("actor",team.owner,"amount",added,"tick",now()));
            }
        }

        private void expand(Team team,int target) {
            int newPopulation=target+Math.max(8,target/12);
            addExpansionCandidates(team,Math.max(0,newPopulation-team.pool.size()));
        }

        private void addExpansionCandidates(Team team,int count) {
            int before=team.pool.size();
            for(int i=before;i<before+count;i++){
                int column=(i%60)%10, row=(i%60)/10;
                int ordinal=(i/60)%GRAND_COMPANIES[team.index];
                int x=frontX(team.index,ordinal)+2*column-9;
                int z=(team.index==1?35:-46)+2*row;
                spawn(team,i,NpcProfession.WORKER,false,new BlockPos(x,FLOOR+1,z));
            }
        }

        private static int frontX(int side,int company) {
            return switch(side){
                case 0 -> -64+company*16;
                case 1 -> -64+company*16;
                default -> company*16;
            };
        }

        private void deploy(String label,boolean liveFront) {
            for(int i=-2;i<GRAND_COMPANIES[1];i++) {
                for(var team:teams){
                    String unit=i==-2?"Line":i==-1?"Reserve":"Company"+i;
                    var target=deploymentPoint(team,i,liveFront);
                    order(label+" "+team.name()+" "+unit,team,unit,"HOLD",target.getX(),target.getZ(),true);
                }
            }
        }

        private BlockPos deploymentPoint(Team team,int company,boolean liveFront) {
            if(company==-1)return new BlockPos(0,FLOOR,44);
            if(company==-2)return switch(team.index) {
                case 0 -> new BlockPos(-38,FLOOR,-24);
                case 1 -> new BlockPos(0,FLOOR,17);
                default -> new BlockPos(42,FLOOR,-52);
            };
            int side=team.index;
            int x=switch(side) {
                case 0 -> -64+16*company;
                case 1 -> -64+18*company;
                default -> 16+16*company;
            };
            int z=switch(side) {
                case 0 -> liveFront?-10:-52;
                case 1 -> liveFront?2:27;
                default -> liveFront&&!phase.equals("reserve")?-10:-32;
            };
            return new BlockPos(x,FLOOR,z);
        }

        private void grandBattle() {
            step("original armies match the stopped pre-battle roster",()->Arrays.stream(teams).allMatch(team->{
                var saved=savedBattleRoster.get(team.index);
                var live=new HashSet<>(team.people());
                if(!saved.containsAll(live))return false;
                var characters=com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(server);
                return saved.stream().allMatch(id->live.contains(id)||characters.byId(id)!=null&&!characters.byId(id).alive());
            }));
            action("enable native battle measurement",()->fieldCore().telemetry().enabled(true));
            deploy("battle",true);
            measure("matched starting deployment",200,1000,false);
            coalitionHostilities();
            measure("same battle window",600,1000,true);
            step("serving soldiers actually fought on both fronts",()->paired(0,1)&&(!phase.equals("coalition")||paired(1,2)));
            if(phase.equals("coalition"))step("shared allied encounter with independent owners",this::alliedEngagement);
            action("cease hostilities after comparison window",()->{
                checkNoNeutralHits();
                relation(0,1,PoliticalRelation.NEUTRAL);
                relation(1,2,PoliticalRelation.NEUTRAL);
            });
            for(var team:teams)order("signed withdrawal "+team.name(),team,"Line","RETREAT",0,team.index==1?42:-47);
            step("actual original retreat or regroup confirmed",()->Arrays.stream(teams).allMatch(team->reports(team).stream()
                    .anyMatch(r->r.unit().equals(team.unit("Line"))&&now()-r.observedAt()<=25
                            &&(r.state()==WarFieldReport.State.RETREATING||r.state()==WarFieldReport.State.REGROUPING))));
        }

        private void coalitionHostilities(){
            action("alliance and first authorized front",()->{
                relation(0,2,PoliticalRelation.ALLIED);
                relation(0,1,PoliticalRelation.WAR);
                combatAt=now();
            });
            step("original A and B direct damage before allied activation",()->paired(0,1));
            action("authorized allied attack begins",()->{relation(1,2,PoliticalRelation.WAR);supportAt=now();});
        }

        private boolean alliedEngagement() {
            var data=CombatEncounterData.get(server);
            for(var e:data.encounters()) {
                if(e.startedAt()<combatAt)continue;
                var a=data.binding(e.id(),WarBattleRuntime.KIND,teams[0].army());
                var c=data.binding(e.id(),WarBattleRuntime.KIND,teams[2].army());
                if(a==null||c==null||!a.side().equals(c.side())||!a.principal().equals(teams[0].owner)||!c.principal().equals(teams[2].owner))continue;
                var side=e.sides().stream().filter(s->s.id().equals(a.side())).findFirst().orElseThrow();
                var foes=e.sides().stream().filter(s->!s.id().equals(a.side())).findFirst().orElseThrow();
                if(!side.members().stream().anyMatch(p->Objects.equals(sides.get(p),0))
                        ||!side.members().stream().anyMatch(p->Objects.equals(sides.get(p),2))
                        ||!foes.members().stream().anyMatch(p->Objects.equals(sides.get(p),1)))continue;
                battle.add(Map.of("encounter",e.id(),"coalitionSide",a.side(),"A",a.principal(),"C",c.principal(),"officialMembers",side.members(),"opponents",foes.members()));
                return true;
            }
            return false;
        }

        private void measure(String label,int ticks,int minimum,boolean fixed) {
            int[] sustained={0,0,0,0};
            var tickTimes=new ArrayList<Long>();
            long[] monotonicStart={0};
            action(label+" starts",()->{sampledFrom=now();monotonicStart[0]=System.nanoTime();});
            step(label,()->{
                long main=server.getTickTimesNanos()[server.getTickCount()%100];
                if(main>0)tickTimes.add(main);
                if((now()-sampledFrom)%20!=0)return false;
                var telemetry=fieldCore().telemetry().sample(now());
                int loaded=0,executing=0,holding=0,physicalAction=0;
                var statuses=new EnumMap<WarFieldReport.State,Integer>(WarFieldReport.State.class);
                var blockedReasons=new TreeMap<String,Integer>();
                for(var team:teams)for(var r:reports(team)){
                    if(now()-r.observedAt()>25)continue;
                    var original=peopleById.get(r.character());
                    if(original==null||!original.body().equals(r.body()))continue;
                    var npc=body(original);
                    if(npc==null)continue;
                    loaded++;
                    var state=r.state();
                    statuses.merge(state,1,Integer::sum);
                    if(r.order().isEmpty()||state==WarFieldReport.State.BLOCKED||state==WarFieldReport.State.REVIEW||state==WarFieldReport.State.WAITING){
                        blockedReasons.merge(r.reason(),1,Integer::sum);
                        continue;
                    }
                    executing++;
                    if(state==WarFieldReport.State.HOLDING)holding++;
                    if(state==WarFieldReport.State.HOLDING||state==WarFieldReport.State.ENGAGING
                            ||state==WarFieldReport.State.REGROUPING||!npc.getNavigation().isDone())physicalAction++;
                }
                var sample=new LinkedHashMap<String,Object>();
                sample.put("label",label);sample.put("tick",now());sample.put("overworldTickNanos",main);
                sample.put("fieldBodies",telemetry.bodies());sample.put("warNanos",telemetry.fieldNanos()+telemetry.coordinatorNanos());
                sample.put("loadedAndReporting",loaded);sample.put("executing",executing);sample.put("holding",holding);
                sample.put("physicalAction",physicalAction);sample.put("states",statuses);sample.put("contacts",damage.size());
                sample.put("blockedReasons",blockedReasons);
                sample.put("queues",fieldCore().field().orElseThrow().budgets());load.add(sample);
                if(executing>=minimum&&telemetry.bodies()>=minimum)sustained[0]+=20;else sustained[0]=0;
                sustained[1]=Math.max(sustained[1],sustained[0]);
                if(physicalAction>=minimum&&telemetry.bodies()>=minimum)sustained[2]+=20;else sustained[2]=0;
                sustained[3]=Math.max(sustained[3],sustained[2]);
                if((now()-sampledFrom)%200==0)
                    System.out.println("CAMPAIGN_LOAD "+label+" live="+loaded+" executing="+executing+" physical="+physicalAction
                            +" states="+statuses+" sustained="+sustained[0]);
                long elapsed=now()-sampledFrom;
                boolean measuringPhysicalReadiness=minimum>=1000&&!fixed;
                if(fixed?elapsed<ticks:measuringPhysicalReadiness?sustained[2]<ticks&&elapsed<1800:sustained[0]<ticks)return false;
                tickTimes.sort(Long::compare);
                battle.add(Map.of("measurement",label,"sampledTicks",elapsed,"executionThreshold",minimum,"sustainedTicks",sustained[1],
                        "physicallyConfirmedTicks",sustained[3],
                        "P50",quantile(tickTimes,.50),"P95",quantile(tickTimes,.95),"P99",quantile(tickTimes,.99),"maximum",quantile(tickTimes,1),
                        "durationNanos",System.nanoTime()-monotonicStart[0]));
                return true;
            });
        }

        private static long quantile(List<Long> sorted,double q) {
            return sorted.isEmpty()?0:sorted.get(Math.clamp((int)Math.ceil(sorted.size()*q)-1,0,sorted.size()-1));
        }

        private void review(Team team,int strength) {
            action("review "+team.name()+" at "+strength,()->team.send("CMD saga war staff review "+team.name()));
            step("durable establishment at "+strength,()->WarStaffRuntime.existing(server)
                    .flatMap(staff->staff.book().establishment(team.army()).filter(staff.book()::durable))
                    .filter(e->e.strength()==strength).isPresent());
        }

        private void transportLook(Team team) {
            BlockPos source = new BlockPos(49,FLOOR+1,46);
            BlockPos destination = new BlockPos(60,FLOOR+1,28);
            UUID[] recipient = new UUID[1];
            action("seed actual military provisions",()->{
                server.overworld().setBlockAndUpdate(source,Blocks.CHEST.defaultBlockState());
                var bin = (ChestBlockEntity)server.overworld().getBlockEntity(source);
                bin.setItem(0,new ItemStack(Items.BREAD,32));
                team.player.teleportTo(source.getX()+1.5,source.getY(),source.getZ()+.5);
                team.send("CMD saga war support depot "+team.name()+" "+source.getX()+" "+source.getY()+" "+source.getZ());
            });
            step("military depot registered",()->WarCampaignRuntime.existing(server).flatMap(c->c.depot(team.army())).isPresent());
            action("real staff visible before transport",()->{
                var candidate=team.original(team.specialist);
                team.player.teleportTo(candidate.getX(),candidate.getY(),candidate.getZ()+2);
                recipient[0]=team.people().getLast();
                var target=team.original(recipient[0]);
                target.teleportTo(destination.getX()+.5,destination.getY(),destination.getZ()+.5);
            });
            look("growth-carrier-before",team,()->team.original(team.specialist),NpcMilitaryRole.STAFF_OFFICER.id(),()->true);
            action("authorize actual bread transport",()->{
                var offer=muster().book().latest(team.specialist).orElseThrow();
                team.send("CMD saga war support supply "+offer.id()+" "+recipient[0]+" minecraft:bread 8 1");
            });
            look("growth-carrier",team,()->team.original(team.specialist),NpcMilitaryRole.MILITARY_CARRIER.id(),
                    ()->team.original(team.specialist).getTransportInventory().countItem(Items.BREAD)==8);
            step("real delivery and source accounting",()->WarCampaignRuntime.existing(server).orElseThrow().entries().stream()
                    .filter(Shipment.class::isInstance).map(Shipment.class::cast).anyMatch(row->row.run().person().equals(team.specialist)
                            &&row.run().delivered()==1&&row.run().terminal())
                    &&((ChestBlockEntity)server.overworld().getBlockEntity(source)).countItem(Items.BREAD)==24);
            look("growth-carrier-after",team,()->team.original(team.specialist),NpcMilitaryRole.STAFF_OFFICER.id(),()->true);
        }

        private void appointmentLook(Team team,String role,String outfit,String label){
            UUID[] person={null};UUID[] appointment={null};
            action("review for "+label,()->team.send("CMD saga war staff review "+team.name()));
            step("durable establishment for "+label,()->WarStaffRuntime.existing(server).filter(staff->staff.book().establishment(team.army()).filter(staff.book()::durable).isPresent()).isPresent());
            action("appoint "+label,()->{
                person[0]=switch(role){
                    case "standard_bearer", "quartermaster", "engineering_lead" -> team.specialist;
                    default -> team.people().stream().filter(id->muster().book().latest(id).filter(offer->offer.request().unitId().equals(team.unit("Line"))).isPresent()).findFirst().orElseThrow();
                };
                var n=team.original(person[0]);
                team.player.teleportTo(n.getX(),n.getY(),n.getZ()+2);
                String post=role.equals("captain")?"unitappoint "+team.name()+" Line":"appoint "+team.name()+" "+role;
                team.send("CMD saga war staff "+post+" "+person[0]+" 2 acting Campaign appearance witness");
            });
            step("real serving outfit "+label,()->{
                var n=team.original(person[0]);
                var staff=WarStaffRuntime.existing(server).orElseThrow();
                var a=staff.book().forPerson(person[0]).filter(staff::effective);
                if(a.isEmpty()||!n.getClothingOutfit().equals(outfit))return false;
                appointment[0]=a.orElseThrow().id();
                team.send("LOOK "+n.getId()+" "+outfit+" "+label);
                return true;
            });
            step("real client renders "+label,()->Files.isRegularFile(control.resolve("look-"+label+".json")));
            action("revoke "+label,()->team.send("CMD saga war staff revoke "+appointment[0]));
            String fallback=switch(role){
                case "standard_bearer", "quartermaster" -> NpcMilitaryRole.STAFF_OFFICER.id();
                case "engineering_lead" -> NpcMilitaryRole.ENGINEER.id();
                default -> "foot_sword";
            };
            look(label+"-after",team,()->team.original(person[0]),fallback,()->true);
            step("durable original release "+label,()->WarStaffRuntime.existing(server).orElseThrow().book()
                    .appointment(appointment[0]).filter(a->a.phase()==WarStaffEntry.Phase.REVOKED)
                    .filter(WarStaffRuntime.existing(server).orElseThrow().book()::durable).isPresent());
        }

        private void look(String label,Team team,java.util.function.Supplier<NpcBase> subject,String outfit,BooleanSupplier valid) {
            step("observe serving outfit "+label,()->{
                if(!valid.getAsBoolean())return false;
                var n=subject.get();
                if(!n.getClothingOutfit().equals(outfit))return false;
                team.player.teleportTo(n.getX(),n.getY(),n.getZ()+1.8);
                team.send("LOOK "+n.getId()+" "+outfit+" "+label);
                return true;
            });
            step("real client renders "+label,()->Files.isRegularFile(control.resolve("look-"+label+".json")));
        }

        private void createUnits(String unit,Team... selected){
            for(var team:selected)team.units.add(unit);
            action("original units "+unit,()->{for(var team:selected)team.send("CMD saga war muster unit "+team.name()+" "+unit+" sword");});
            step("durable units "+unit,()->Arrays.stream(selected).allMatch(t->muster().book().unit(t.unit(unit)).filter(u->u.recorded()&&muster().book().durable(u)).isPresent()));
        }

        private void recruit(int a,int b,int c){
            int[] targets={a,b,c};
            step("recruit "+Arrays.toString(targets),()->{
                for(var team:teams)team.recruitTo(targets[team.index]);
                return Arrays.stream(teams).allMatch(t->t.active()==targets[t.index]);
            });
        }

        private void order(String description,Team owner,String unit,String kind,int x,int z){
            order(description,owner,unit,kind,x,z,false);
        }
        private void order(String description,Team owner,String unit,String kind,int x,int z,boolean skipEmpty){
            long[] previous={-1};
            action(description,()->{
                if(skipEmpty&&owner.members(unit).isEmpty())return;
                previous[0]=fieldCore().session().orElseThrow().unit(owner.unit(unit)).orElseThrow().lastIssuedSequence();
                owner.player.teleportTo(x+3,FLOOR+1,z+9);
            });
            step(description+" map and reports visible",()->{
                if(previous[0]<0)return true;
                var sheet=WarMapRuntime.sheet(owner.player);
                if(sheet.isEmpty())return false;
                int px=(int)Math.round(sheet.get().pixelX(x)),pz=(int)Math.round(sheet.get().pixelZ(z));
                var physicalMap=MapItem.getSavedData(owner.player.getMainHandItem(),owner.player.level());
                if(physicalMap==null||px<0||px>=128||pz<0||pz>=128)throw new IllegalStateException("no actual map for "+description);
                if((Byte.toUnsignedInt(physicalMap.colors[pz*128+px])>>2)==0)return false;
                if(fieldCore().field().orElseThrow().route(owner.owner,owner.army(),owner.unit(unit)).isEmpty())return false;
                owner.send("ORDER "+owner.unit(unit)+" "+kind+" "+x+" "+z);
                return true;
            });
            step(description+" received",()->previous[0]<0||fieldCore().session().orElseThrow().unit(owner.unit(unit)).flatMap(WarUnit::currentOrder)
                    .filter(o->o.sequence()>previous[0]).map(o->{
                        orders.add(Map.of("description",description,"team",owner.name(),"order",o.id(),"unit",unit,
                                "mode",kind,"issuedAt",o.issuedAtTick(),"deliveredAt",o.deliveredAtTick(),"x",x,"z",z));
                        return true;
                    }).orElse(false));
        }

        private void fight(String title,int a,int b){
            action(title+" authorization",()->{relation(a,b,PoliticalRelation.WAR);combatAt=now();});
            step(title+" actual damage",()->paired(a,b));
            action(title+" stand down",()->{checkNoNeutralHits();relation(a,b,PoliticalRelation.NEUTRAL);});
        }

        private void relation(int a,int b,PoliticalRelation relation){
            var p=PoliticalStateData.get(server);
            p.setRelation(server,FACTIONS[a],FACTIONS[b],relation,now());
            p.setRelation(server,FACTIONS[b],FACTIONS[a],relation,now());
        }

        private boolean paired(int a,int b){return damage.stream().anyMatch(row->(long)row.get("tick")>=combatAt
                &&Boolean.TRUE.equals(row.get("attackerServing"))&&Boolean.TRUE.equals(row.get("targetServing"))
                &&Set.of(a,b).contains(row.get("attackerSide"))&&Set.of(a,b).contains(row.get("targetSide"))&&!row.get("attackerSide").equals(row.get("targetSide")));}

        private void checkNoNeutralHits(){
            for(var row:damage)if((long)row.get("tick")>=combatAt){
                int a=(int)row.get("attackerSide"),b=(int)row.get("targetSide");
                if(a==b||row.get("relation")!=PoliticalRelation.WAR)
                    throw new IllegalStateException("neutral or friendly harm during campaign: "+row);
            }
        }

        void contact(NpcBase attacker,NpcBase target,float amount){
            var a=sides.get(attacker.getCharacterId());var b=sides.get(target.getCharacterId());
            if(a!=null&&b!=null&&damage.size()<50_000)damage.add(Map.of("tick",now(),"attacker",attacker.getCharacterId(),
                    "target",target.getCharacterId(),"attackerSide",a,"targetSide",b,"damage",amount,
                    "attackerServing",serving(attacker),"targetServing",serving(target),
                    "relation",PoliticalStateData.get(server).relation(FACTIONS[a],FACTIONS[b])));
        }

        private boolean serving(NpcBase npc) {
            return WarMusterRuntime.existing(server).flatMap(m->m.book().latest(npc.getCharacterId()))
                    .filter(o->o.body().equals(npc.getUUID())&&o.stage()==WarMusterEntry.Stage.ACTIVE
                            &&o.request().role()==WarMilitiaRequest.Role.COMBATANT).isPresent();
        }
        private void action(String name,Runnable run){step(name,()->{run.run();return true;});}
        private void step(String name,BooleanSupplier finished){steps.add(new Step(name,finished));}
        private long now(){return server.overworld().getGameTime();}
        private WarMusterRuntime muster(){return WarMusterRuntime.existing(server).orElseThrow();}
        private WarServerRuntime fieldCore(){return WarServerEvents.existing(server).orElseThrow();}
        private List<WarFieldReport> reports(Team t){return fieldCore().field().orElseThrow().reports(t.owner,t.army());}
        private NpcBase body(Candidate candidate){var value=server.overworld().getEntity(candidate.body());return value instanceof NpcBase npc&&npc.isAlive()?npc:null;}

        void tick(){
            if(stopped)return;
            try {
                if(Files.isRegularFile(control.resolve("abort"))){finish(false,"aborted");return;}
                if(!advance){if(Arrays.stream(teams).anyMatch(t->t.player==null))return;plan();advance=true;stepStarted=now();}
                if(steps.isEmpty()){finish(true,"");return;}
                var next=steps.peekFirst();
                if(now()-stepStarted>(phase.startsWith("survival-")?1800:12_000))throw new IllegalStateException("step timed out: "+next.name()+" "+counts()+" "+Arrays.stream(teams).map(Team::attemptDescription).toList());
                if(next.finished().getAsBoolean()){
                    timeline.add(Map.of("step",next.name(),"since",stepStarted,"at",now(),"combatants",counts()));
                    System.out.println("CAMPAIGN_STEP "+next.name()+" "+counts());
                    steps.removeFirst();stepStarted=now();
                }
            }catch(Exception failure){failure.printStackTrace();finish(false,failure.toString());}
        }

        private List<Integer> counts(){return Arrays.stream(teams).map(Team::active).toList();}

        private void finish(boolean success,String reason){
            stopped=true;
            try{
                var proof=new LinkedHashMap<String,Object>();
                proof.put("pass",success);proof.put("phase",phase);proof.put("reason",reason);proof.put("gameTime",now());
                proof.put("realClients",clients);proof.put("combatants",counts());proof.put("steps",timeline);proof.put("damage",damage);
                proof.put("people",Arrays.stream(teams).map(Team::people).toList());proof.put("newRecruitments",recruits);proof.put("encounters",CombatEncounterData.get(server).encounters().size());
                proof.put("orders",orders);proof.put("load",load);proof.put("alliedEncounter",battle);
                proof.put("engagementStartedAt",combatAt);
                proof.put("alliedActivation",supportAt);
                proof.put("originalSigningReceipts",signing);proof.put("fixtureStagingFunds",funding);
                var pending=control.resolve("pending-result.json");
                Files.writeString(pending,JSON.toJson(proof),StandardOpenOption.CREATE_NEW);
                Files.move(pending,control.resolve("result.json"),StandardCopyOption.ATOMIC_MOVE);
            }catch(Exception failure){failure.printStackTrace();}
            for(var t:teams)if(t.player!=null)t.send("STOP");
            server.halt(false);
        }

        final class Team {
            final int index;
            final List<Candidate> pool=new ArrayList<>();
            final Set<String> units=new LinkedHashSet<>();
            ServerPlayer player;
            UUID owner;
            Candidate current;
            int cursor;
            int supportCursor;
            UUID specialist;
            long until;
            long nextApproach;
            int attempts;
            boolean offered;
            Team(int index){this.index=index;}
            String name(){return NAMES[index];}
            UUID army(){return WarMusterEntry.armyId(owner,name());}
            UUID unit(String name){return WarMusterEntry.unitId(army(),name);}
            void send(String input){player.sendSystemMessage(Component.literal("[WarCampaign] "+input));}
            List<UUID> people(){
                if(owner==null)return List.of();
                return units.stream().flatMap(unit->members(unit).stream()).distinct().toList();
            }
            List<UUID> members(String name){
                var core=WarServerEvents.existing(server).flatMap(WarServerRuntime::mobilization);
                if(core.isEmpty())return List.of();
                return core.orElseThrow().activeUnit(unit(name)).stream()
                        .filter(o->o.request().role()==WarMilitiaRequest.Role.COMBATANT)
                        .filter(o->muster().book().offer(o.request().id()).filter(a->a.stage()==WarMusterEntry.Stage.ACTIVE&&muster().book().durable(a)).isPresent())
                        .map(o->o.request().characterId()).toList();
            }
            int active(){return people().size();}
            String attemptDescription(){return current==null?name()+" idle":name()+" "+current.person()+" offered="+offered;}
            NpcBase original(UUID person){return Objects.requireNonNull(body(Objects.requireNonNull(peopleById.get(person))));}

            boolean recruitSpecial(WarMilitiaRequest.Role role,int first,int end){
                if(specialist!=null&&pool.subList(first,end).stream().anyMatch(c->c.person().equals(specialist)))return true;
                if(current==null){
                    if(now()<nextApproach)return false;
                    supportCursor=Math.max(supportCursor,first);
                    if(supportCursor>=end)throw new IllegalStateException("no eligible civilian specialists for "+role);
                    var next=pool.get(supportCursor++);
                    if(muster().book().latest(next.person()).isEmpty()&&body(next)!=null)approach(next);
                }else progressRecruit(role);
                return specialist!=null&&pool.subList(first,end).stream().anyMatch(c->c.person().equals(specialist));
            }

            void recruitTo(int count){
                if(current==null){
                    if(active()>=count||now()<nextApproach)return;
                    if(cursor>=pool.size()){
                        if(!phase.equals("grand"))throw new IllegalStateException("local civilians exhausted in "+name());
                        addExpansionCandidates(this,Math.max(12,(count-active())/3));
                        saveCivilians();
                    }
                    var candidate=pool.get(cursor++);var n=body(candidate);
                    if(n==null||muster().book().latest(candidate.person()).isPresent())return;
                    approach(candidate);
                    return;
                }
                progressRecruit(WarMilitiaRequest.Role.COMBATANT);
            }
            private void approach(Candidate candidate){
                var n=body(candidate);
                current=candidate;offered=false;attempts=0;until=now()+400;nextApproach=now()+45;
                player.teleportTo(n.getX(),n.getY(),n.getZ()+1.8);
                if(!ServiceRelationshipData.get(server).hasActiveBetween(owner,candidate.person(),now()))send("RECRUIT "+n.getId());
            }
            private void observeSigning(Candidate candidate){
                if(signing.containsKey(candidate.person()))return;
                var law=ServiceRelationshipData.get(server).activeForAgent(candidate.person(),now()).stream()
                        .filter(rule->rule.principalId().equals(owner)&&rule.basis()!=ServiceBasis.TEMPORARY_DELEGATION).findFirst();
                if(law.isEmpty())return;
                UUID operation=ContainerIds.named("signing_fee:"+owner+":"+candidate.person()+":"+law.get().startedAt());
                var ledger=TransferLedgerData.get(server);
                var occurred=ledger.receipt(operation);
                var identity=occurred!=null&&occurred.successful()?ledger.requestIdentity(operation):null;
                if(identity!=null&&identity.getStringOr("Authority","").equals("voluntary_employment_signing_gift")) {
                    int quantity=identity.getListOrEmpty("Legs").getCompoundOrEmpty(0).getIntOr("Quantity",0);
                    signing.put(candidate.person(),Map.of("operation",operation,"parentService",law.get().id(),"quantity",quantity,
                            "actor",owner,"at",law.get().startedAt()));
                }
            }
            private void progressRecruit(WarMilitiaRequest.Role role){
                var n=body(current);
                if(n==null)throw new IllegalStateException("civilian unloaded during consent "+current);
                var latest=muster().book().latest(current.person());
                if(latest.isPresent()){
                    var offer=latest.orElseThrow();
                    if(until<now()+800&&attempts>0&&now()-offer.changedAt()<1000)until=now()+800;
                    if(muster().book().durable(offer)&&(offer.stage()==WarMusterEntry.Stage.ACTIVE||offer.terminal())){
                        if(role!=WarMilitiaRequest.Role.COMBATANT&&offer.stage()==WarMusterEntry.Stage.ACTIVE)specialist=current.person();
                        current=null;return;
                    }
                }
                if(now()>until){
                    if(attempts==1&&latest.isEmpty()){
                        offered=false;until=now()+200;
                    }else if(latest.isEmpty()){
                        System.out.println("CAMPAIGN_CANDIDATE_UNAVAILABLE "+name()+" "+current.person()+" attempts="+attempts);
                        current=null;return;
                    }else throw new IllegalStateException("native mobilization did not settle: "+current+" "+latest.orElseThrow().stage());
                }
                if(!offered&&ServiceRelationshipData.get(server).hasActiveBetween(owner,current.person(),now())){
                    recruits.add(current.person());
                    observeSigning(current);
                    player.teleportTo(n.getX(),n.getY()+2.4,n.getZ());
                    player.lookAt(EntityAnchorArgument.Anchor.EYES,n.getEyePosition());
                    String unit=phase.equals("grand")?expansionUnit():index==1&&active()>=12?"Reserve":"Line";
                    String command=switch(role){case STAFF->"staffoffer";case ENGINEER->"engineeroffer";default->"offer";};
                    send("OFFER "+n.getId()+" "+command+" "+name()+" "+unit+" 3");offered=true;
                    attempts++;until=now()+120;
                }
            }
            private String expansionUnit() {
                return units.stream().filter(name->name.startsWith("Company")).filter(name->members(name).size()<COMPANY_SLOTS)
                        .findFirst().orElseThrow(()->new IllegalStateException("new companies fully occupied in "+name()));
            }
        }
    }
}
