package com.jgeted.sagadyssey.social.household;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.life.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.level.*;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** One original three-person household, one consented destination and a bounded walking caravan. */
public final class NaturalHouseholdMigrationData extends NbtSavedData {
 public static final String NAME="sagadyssey_natural_household_moves_v1";
 public static final Factory<NaturalHouseholdMigrationData> FACTORY=new Factory<>(NaturalHouseholdMigrationData::new,NaturalHouseholdMigrationData::load);
 private final Map<UUID,CompoundTag> moves=new LinkedHashMap<>();
 private final Map<UUID,Long> shortageSince=new HashMap<>();
 private static final Map<MinecraftServer,Set<Lease>> LEASES=new WeakHashMap<>();
 private static final com.jgeted.sagadyssey.worldsim.ChunkTickets.Kind TICKET=com.jgeted.sagadyssey.worldsim.ChunkTickets.kind("sagadyssey-household-caravan");
 private record Lease(String dimension,ChunkPos chunk,UUID body){}
 private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:carrot",0);
 public static NaturalHouseholdMigrationData get(MinecraftServer s){return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(s,FACTORY,NAME);}
 public static NaturalHouseholdMigrationData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY.type(NAME));}
 public List<CompoundTag> moves(){return moves.values().stream().map(CompoundTag::copy).toList();}
 private static boolean traveling(CompoundTag t){return Set.of("WALKING","WAIT_DESTINATION").contains(t.getStringOr("State", ""));}
 private static NaturalVillageBuilding source(CompoundTag t){return NaturalVillageLifeData.decode(t.getCompoundOrEmpty("Source"));}
 private static NaturalVillageBuilding destination(CompoundTag t){return NaturalVillageLifeData.decode(t.getCompoundOrEmpty("Destination"));}
 private CompoundTag move(UUID person,MinecraftServer s){var h=HouseholdData.get(s).forMember(person);return h==null?null:moves.get(h.id());}
 public static boolean moving(MinecraftServer s,UUID person){if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.moving(s,person))return true;if(ResidentWorkMigrationData.moving(s,person))return true;var d=existing(s);var t=d==null?null:d.move(person,s);return t!=null&&!t.getStringOr("State", "").equals("DONE");}
 public static UUID residentSettlement(MinecraftServer s,UUID person){if(moving(s,person))return null;var h=HouseholdData.get(s).forMember(person);return h==null?null:h.settlementId();}
 private static NpcBase actor(MinecraftServer s,NaturalVillageBuilding b,int i){var l=SupplyFlowService.level(s,b.dimension());return l!=null&&l.getEntity(NaturalVillageLifeData.body(b,i)) instanceof NpcBase n?n:null;}
 private static Container store(MinecraftServer s,NaturalVillageBuilding b,int i){var l=SupplyFlowService.level(s,b.dimension());var p=NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(i));if(l==null||l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null||NaturalVillageEconomyData.protects(s,b.dimension(),p))return null;return l.getBlockEntity(p) instanceof Container c?c:null;}
 private boolean room(MinecraftServer s,NaturalVillageBuilding b,UUID own){if(!NaturalVillageLayout.chartered(b)||store(s,b,4)==null)return false;var households=HouseholdData.get(s);var p=NaturalVillageLayout.at(b,NaturalVillageLayout.ARRIVAL_PANTRY);
  if(households.households().stream().anyMatch(h->!h.id().equals(own)&&b.dimension().equals(h.dimension())&&p.equals(h.sharedStorage())))return false;
  if(moves.values().stream().anyMatch(t->!t.read("Household", UUIDUtil.CODEC).orElseThrow().equals(own)&&traveling(t)&&destination(t).buildingKey().equals(b.buildingKey())))return false;
  var l=SupplyFlowService.level(s,b.dimension());for(var bed:NaturalVillageLayout.spareBeds()){var at=NaturalVillageLayout.at(b,bed);if(!l.getBlockState(at).is(net.minecraft.tags.BlockTags.BEDS))return false;if(NpcLifeData.get(s).bedAssigned(at,b.dimension())&&households.byId(own).members().stream().noneMatch(m->at.equals(NpcLifeData.get(s).record(m.characterId()).bedPosition())))return false;}return true;
 }
 /** The household head proposes; the destination's versioned charter consents to one spare home. */
 public String request(MinecraftServer s,NaturalVillageBuilding from,NaturalVillageBuilding to,UUID head){
  if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.supports(to)){
   var d=com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.existing(s);
   return d==null?"成长接纳尚未初始化":d.apply(s,from,to,head,s.overworld().getGameTime());
  }
  var individual=ResidentWorkMigrationData.existing(s);if(individual!=null&&individual.applications().stream().anyMatch(t->!Set.of("DONE","REJECTED").contains(t.getStringOr("State", ""))))return "已有个人迁移，等待原义务结束";
  var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(from));if(home==null||!home.headCharacterId().equals(head))return "仅户主可申请";
  if(home.members().stream().anyMatch(m->com.jgeted.sagadyssey.social.combat.NaturalConflictData.engaged(s,m.characterId())))return "先收尾现有机构行动";var region=NaturalVillageEconomyData.existing(s);if(region==null||region.row(from.buildingKey())==null||region.row(to.buildingKey())==null)return "本版只接入已登记的有限区域";
  if(moves.containsKey(home.id()))return "本户已有迁移记录";if(moves.values().stream().anyMatch(t->!t.getStringOr("State", "").equals("DONE")))return "本版同一时刻只支持一户迁移";
  if(!Objects.equals(home.settlementId(),from.settlementId())||!Objects.equals(home.sharedStorage(),NaturalVillageLayout.at(from,NaturalVillageLayout.PANTRY))||home.members().size()!=3||!from.dimension().equals(to.dimension())||from.settlementId().equals(to.settlementId())||from.buildingOrigin().distSqr(to.buildingOrigin())>1024*1024)return "迁移对象不在本版范围";
  if(!room(s,to,home.id()))return "目的地缺少空置住宅和粮仓";
  var flows=SupplyFlowData.get(s);if(flows.routes().stream().anyMatch(r->!r.closed()&&(r.principal().equals(SubjectRef.household(home.id()))||home.members().stream().anyMatch(m->m.characterId().equals(r.carrierCharacter())))))return "先结清现有运输义务";
  var exchange=SettlementExchangeData.existing(s);if(exchange!=null&&exchange.deals().stream().anyMatch(t->!Set.of("DONE").contains(t.getStringOr("State", ""))&&(t.getCompoundOrEmpty("Buyer").equals(NaturalVillageLifeData.get(s).village(from.buildingKey()))||NaturalVillageLifeData.decode(t.getCompoundOrEmpty("Buyer")).settlementId().equals(from.settlementId())||NaturalVillageLifeData.decode(t.getCompoundOrEmpty("Seller")).settlementId().equals(from.settlementId()))))return "先处理交换债务";
  for(int i=0;i<3;i++){var n=actor(s,from,i);if(n==null||!n.isAlive()||n.isNoAi()||!NpcBrainRuntime.isPlanned(n)||n.getCommand()!=NpcCommand.WORK||!Objects.equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(n).workplaceSettlementId(),from.settlementId())||!n.getTransportInventory().isEmpty()||n.getTarget()!=null)return "原居民或货舱尚未就绪";}
  var t=new CompoundTag();t.store("Household", UUIDUtil.CODEC,home.id());t.put("Source",NaturalVillageLifeData.get(s).village(from.buildingKey()));t.put("Destination",NaturalVillageLifeData.get(s).village(to.buildingKey()));t.putString("State","WALKING");t.putLong("Started",s.overworld().getGameTime());t.putString("Reason","户主接受空置住房机会；原三人步行迁居，原私产留归原主");
  // A new home starts empty. Existing bags, tools and stored private goods are never copied.
  flows.removePolicy("natural-diet:"+home.id());for(var need:NeedRequestData.get(s).requests())if(need.key().requester().equals(home.id())&&!need.status().terminal()&&need.reserved()==0&&need.inTransit()==0)NeedRequestData.get(s).transition(need.id(),NeedRequestStatus.CANCELLED,s.overworld().getGameTime(),"家庭迁移，旧住址不再请求新增补货");
  moves.put(home.id(),t);for(int i=0;i<3;i++)NpcBrainRuntime.cancel(actor(s,from,i),PlanCancellationReason.AUTHORIZED_COMMAND,"家庭迁移接续，已确认没有未了运输");setDirty();return "ACCEPTED";
 }
 public static boolean tickActor(NpcBase n){if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.travel(n))return true;var s=MinecraftApi.server(n);var d=existing(s);var t=d==null?null:d.move(n.getCharacterId(),s);if(t==null||t.getStringOr("State", "").equals("DONE"))return false;if(t.getStringOr("State", "").equals("REVIEW")){n.getNavigation().stop();return true;}
  var to=destination(t);var l=SupplyFlowService.level(s,to.dimension());var target=NaturalVillageLayout.at(to,NaturalVillageLayout.ARRIVAL_PANTRY);
  if(!n.isAlive()||n.getTarget()!=null||n.getCommand()!=NpcCommand.WORK){n.getNavigation().stop();return true;}
  if(n.blockPosition().distSqr(target)<49){n.getNavigation().stop();return true;}
  if(n.tickCount%20!=0)return true;
  var key="Waypoint"+n.getUUID();BlockPos p=t.contains(key)?BlockPos.of(t.getLongOr(key, 0L)):null;
  if(p==null||n.blockPosition().distSqr(p)<=16){p=SettlementExchangeData.waypoint(n,target);if(p!=null){t.putLong(key,p.asLong());d.setDirty();}}
  if(FieldworkRoutes.move(n,target)){t.putString("Reason","沿实际可行路段迁移，保留原居民及随身物品");}else {t.remove(key);t.putString("Reason","道路或前方区块不可用，在原位置等待");}return true;
 }
 private void arrive(MinecraftServer s,CompoundTag t){var from=source(t);var to=destination(t);UUID id=t.read("Household", UUIDUtil.CODEC).orElseThrow();
  if(!room(s,to,id)){t.putString("State","WAIT_DESTINATION");t.putString("Reason","目的地住宅失效，家庭保留途中位置和财物");setDirty();return;}
  var at=NaturalVillageLayout.at(to,NaturalVillageLayout.ARRIVAL_PANTRY);var people=new ArrayList<NpcBase>();
  for(int i=0;i<3;i++){var n=actor(s,from,i);if(n==null||!n.isAlive()||n.blockPosition().distSqr(at)>=49)return;people.add(n);}
  var houses=HouseholdData.get(s);var current=houses.byId(id);boolean already=Objects.equals(current.settlementId(),to.settlementId())&&at.equals(current.sharedStorage());
  if(!already&&!houses.relocate(id,from.settlementId(),NaturalVillageLayout.at(from,NaturalVillageLayout.PANTRY),to.dimension(),to.settlementId(),at,at)){t.putString("State","REVIEW");t.putString("Reason","家庭住所并非预期旧址或已提交新址，保留记录");setDirty();return;}
  var principal=SubjectRef.household(id);UUID pantry=NaturalVillageEconomyData.storeId(to,4);var rights=RightsStateData.get(s);rights.ensureContext(pantry,to.dimension()+":"+at.asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
  var flows=SupplyFlowData.get(s);flows.register(new FieldworkStorage(to.dimension(),at,pantry,principal,AccessPolicy.HOUSEHOLD,TransferIntent.DELIVERY,"migrant-pantry"));flows.reveal(principal,pantry);flows.removePolicy("natural-diet:"+id);flows.policy(new SupplyFlowData.Policy("natural-diet:"+id,principal,pantry,FOOD,16,"household"));
  // The version-2 crop owner consents at generation to feed an accepted incoming household from real surplus.
  if(!NaturalVillageLayout.woodcamp(to)){var farmer=NaturalVillageLifeData.person(to,0);var owner=HouseholdData.get(s).forMember(farmer);var person=CharacterRegistryData.get(s).byId(farmer);if(person!=null&&person.alive()&&owner!=null&&owner.id().equals(NaturalVillageLifeData.home(to))){UUID source=NaturalVillageEconomyData.storeId(to,0);rights.grant(new RightGrant(ContainerIds.named("admission-food:"+to.settlementId()+":"+id),source,principal,Set.of(AssetRight.TRANSFER),"版本化接纳约定：农户同意从专用作物仓提供实际余粮，保留八份种用储备",s.overworld().getGameTime(),null,true));flows.reveal(principal,source);}}
  for(int i=0;i<3;i++){var n=people.get(i);n.setHomeSettlementId(to.settlementId());var bed=NaturalVillageLayout.at(to,NaturalVillageLayout.spareBeds().get(i));NpcLifeData.get(s).assignBed(n.getCharacterId(),to.dimension(),bed,"接纳户住宅",s.overworld().getGameTime());n.setWorkZone(at,at);n.getNavigation().stop();}
  t.putString("State","DONE");t.putLong("Completed",s.overworld().getGameTime());t.putString("Reason","同一家庭与三名原居民抵达，空置床位和独立粮仓接续，私产没有复制");setDirty();WorldHistoryData.get(s).append(s.overworld().getGameTime(),"household_migrated",to.settlementId(),"家庭迁入","户主接受住宅机会；原家庭由 "+from.settlementId()+" 迁入，原私产保留",40);
 }
 private record ForestOpportunity(int remaining,int blocked){boolean available(){return remaining==blocked;}}
 private static ForestOpportunity forestryOpportunity(MinecraftServer s,NaturalVillageBuilding b){if(!NaturalVillageLayout.woodcamp(b))return null;var l=SupplyFlowService.level(s,b.dimension());if(l==null)return null;int remaining=0,blocked=0;var facts=com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkData.get(s);for(int x=14;x<=20;x++)for(int z=14;z<=18;z++)for(int y=1;y<=3;y++){var p=NaturalVillageLayout.at(b,new BlockPos(x,y,z));if(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)return null;if(l.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)){remaining++;if(facts.unreachable(NaturalVillageLifeData.body(b,x<=16?0:2),b.dimension(),p,s.overworld().getGameTime()).isPresent())blocked++;}}return new ForestOpportunity(remaining,blocked);}
 public static int incomingCropReserve(MinecraftServer s,com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage point,SubjectRef principal,ItemFingerprint item){int development=com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.sourceProtection(s,point,principal,item);if(development>0)return development;if(principal.type()!=SubjectRef.SubjectType.HOUSEHOLD||!item.itemId().equals("minecraft:carrot"))return 0;var h=HouseholdData.get(s).byId(principal.id());var data=NaturalVillageEconomyData.existing(s);if(h==null||data==null)return 0;for(var row:data.rows()){var b=NaturalVillageLifeData.decode(row.getCompoundOrEmpty("Village"));if(NaturalVillageLayout.chartered(b)&&Objects.equals(h.settlementId(),b.settlementId())&&!h.id().equals(NaturalVillageLifeData.home(b))&&NaturalVillageEconomyData.storeId(b,0).equals(point.containerId()))return 8;}return 0;}
 private void propose(MinecraftServer s,long now){var life=NaturalVillageLifeData.existing(s);if(life==null)return;var known=life.villages().stream().filter(v->v.getBooleanOr("Ready", false)).map(NaturalVillageLifeData::decode).filter(NaturalVillageLayout::chartered).limit(16).toList();
  for(var from:known){UUID id=NaturalVillageLifeData.home(from);if(moves.containsKey(id))continue;var home=HouseholdData.get(s).byId(id);if(home==null)continue;boolean hungry=home.members().stream().filter(m->{var n=NpcLifeData.get(s).needs(m.characterId());return n!=null&&n.hunger()>=900;}).count()>=2;
   var forest=forestryOpportunity(s,from);boolean opportunity=forest!=null&&forest.available();if(!hungry&&!opportunity){if(shortageSince.remove(id)!=null)setDirty();continue;}if(hungry&&!shortageSince.containsKey(id)){shortageSince.put(id,now);setDirty();}if(!opportunity&&now-shortageSince.getOrDefault(id,now)<48000)continue;
   for(var to:known){var pantry=store(s,to,1);if(!from.settlementId().equals(to.settlementId())&&pantry!=null&&SupplyFlowService.count(pantry,FOOD)>=16&&request(s,from,to,home.headCharacterId()).equals("ACCEPTED")){var intent=moves.get(id);intent.putString("ApplicationCause",opportunity?"已分配林地当前无可达的已知作业；接受实际空置住房机会":"持续严重饥饿达到观察期");intent.putLong("ObservedAt",now);if(forest!=null){intent.putInt("ForestRemaining",forest.remaining());intent.putInt("ForestBlocked",forest.blocked());}intent.putInt("HostPantryFood",SupplyFlowService.count(pantry,FOOD));WorldHistoryData.get(s).append(now,"household_migration_application",home.headCharacterId(),"家庭迁居申请",intent.getStringOr("ApplicationCause", "")+"；剩余原木="+(forest==null?"未核验":forest.remaining())+"，有效受阻记录="+(forest==null?"未核验":forest.blocked()),40);setDirty();return;}}
  }
 }
 private void tick(MinecraftServer s){long now=s.overworld().getGameTime();if(now%200==0)propose(s,now);for(var t:moves.values())if(traveling(t)){var b=source(t);boolean dead=false;for(int i=0;i<3;i++){var c=CharacterRegistryData.get(s).byId(NaturalVillageLifeData.person(b,i));if(c==null||!c.alive())dead=true;}if(dead){t.putString("State","REVIEW");t.putString("Reason","原成员死亡，保留家庭、遗产与途中位置，不补造人");setDirty();}else arrive(s,t);}traffic(s);}
 private void traffic(MinecraftServer s){Set<Lease> desired=new HashSet<>();for(var t:moves.values())if(traveling(t)){var b=source(t);for(int i=0;i<3;i++){var n=actor(s,b,i);var checkpoint=NpcLodData.get(s).find(NaturalVillageLifeData.body(b,i));BlockPos p=n!=null?n.blockPosition():checkpoint==null?null:BlockPos.of(checkpoint.position());if(p==null)continue;var cp=new ChunkPos(p);for(int x=cp.x-1;x<=cp.x+1;x++)for(int z=cp.z-1;z<=cp.z+1;z++)desired.add(new Lease(b.dimension(),new ChunkPos(x,z),NaturalVillageLifeData.body(b,i)));}}
  var individual=ResidentWorkMigrationData.existing(s);if(individual!=null)for(var t:individual.applications())if(Set.of("WALKING","WAIT_DESTINATION").contains(t.getStringOr("State", ""))){
   var b=source(t);var n=actor(s,b,2);var checkpoint=NpcLodData.get(s).find(t.read("Body", UUIDUtil.CODEC).orElseThrow());BlockPos p=n!=null?n.blockPosition():checkpoint==null?null:BlockPos.of(checkpoint.position());if(p==null)continue;
   var cp=new ChunkPos(p);for(int x=cp.x-1;x<=cp.x+1;x++)for(int z=cp.z-1;z<=cp.z+1;z++)desired.add(new Lease(b.dimension(),new ChunkPos(x,z),t.read("Body", UUIDUtil.CODEC).orElseThrow()));
  }
  if(desired.size()>27)throw new IllegalStateException("Migration ticket budget exceeded");for(var lease:LEASES.getOrDefault(s,Set.of()))if(!desired.contains(lease)){var l=SupplyFlowService.level(s,lease.dimension());if(l!=null)com.jgeted.sagadyssey.worldsim.ChunkTickets.remove(l.getChunkSource(), TICKET,lease.chunk(),2,lease.body(),true);}for(var lease:desired){var l=SupplyFlowService.level(s,lease.dimension());if(l!=null)com.jgeted.sagadyssey.worldsim.ChunkTickets.add(l.getChunkSource(), TICKET,lease.chunk(),2,lease.body(),true);}LEASES.put(s,desired);
 }
 @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();moves.values().forEach(m->list.add(m.copy()));t.put("Moves",list);var waits=new ListTag();shortageSince.forEach((id,time)->{var a=new CompoundTag();a.store("Household", UUIDUtil.CODEC,id);a.putLong("Since",time);waits.add(a);});t.put("Shortage",waits);return t;}
 public static NaturalHouseholdMigrationData load(CompoundTag t,HolderLookup.Provider lookup){if(t.getIntOr("Schema", 0)!=1)throw new IllegalArgumentException("Unknown household relocation schema");var d=new NaturalHouseholdMigrationData();for(var e:MinecraftApi.listOfType(t, "Moves",Tag.TAG_COMPOUND)){var a=((CompoundTag)e).copy();if(!Set.of("WALKING","WAIT_DESTINATION","DONE","REVIEW").contains(a.getStringOr("State", ""))||d.moves.putIfAbsent(a.read("Household", UUIDUtil.CODEC).orElseThrow(),a)!=null)throw new IllegalArgumentException("Conflicting relocation");source(a);destination(a);}for(var e:MinecraftApi.listOfType(t, "Shortage",Tag.TAG_COMPOUND)){var a=(CompoundTag)e;d.shortageSince.put(a.read("Household", UUIDUtil.CODEC).orElseThrow(),a.getLongOr("Since", 0L));}return d;}
 @SubscribeEvent public static void post(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var s=e.getServer();if(s.overworld().getGameTime()%20==0&&NaturalVillageLifeData.existing(s)!=null)get(s).tick(s);}
 @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){LEASES.remove(e.getServer());}
}
