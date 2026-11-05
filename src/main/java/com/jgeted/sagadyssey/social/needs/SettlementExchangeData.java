package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.logistics.LogisticsData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.worldsim.lod.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** Standing charter: two real logs prepaid for four carrots. One original courier and one live route bubble. */
public final class SettlementExchangeData extends SavedData {
 public static final String NAME="sagadyssey_regional_exchange_v1";
 public static final Factory<SettlementExchangeData> FACTORY=new Factory<>(SettlementExchangeData::new,SettlementExchangeData::load);
 private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:carrot",0),WOOD=new ItemFingerprint("minecraft:oak_log",0);
 private static final TicketType<UUID> TICKET=TicketType.create("sagadyssey-regional-courier",UUID::compareTo,60);
 private static final Map<MinecraftServer,Set<Lease>> LEASES=new WeakHashMap<>();
 private record Lease(String dimension,ChunkPos chunk,UUID body){}
 private final Map<UUID,CompoundTag> deals=new LinkedHashMap<>();
 private final Map<String,Long> cooldown=new HashMap<>();
 private long sequence;
 public static SettlementExchangeData get(MinecraftServer s){return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(s,FACTORY,NAME);}
 public static SettlementExchangeData existing(MinecraftServer s){return s.overworld().getDataStorage().get(FACTORY,NAME);}
 public List<CompoundTag> deals(){return deals.values().stream().map(CompoundTag::copy).toList();}
 private static boolean active(CompoundTag t){return Set.of("AWAIT_CARRIER","PAY","FOOD").contains(t.getString("State"));}
 private static NaturalVillageBuilding site(CompoundTag t,String which){return NaturalVillageLifeData.decode(t.getCompound(which));}
 private static UUID person(CompoundTag t){return NaturalVillageLifeData.person(site(t,"Buyer"),1);}
 private static UUID body(CompoundTag t){return NaturalVillageLifeData.body(site(t,"Buyer"),1);}
 private static NpcBase carrier(MinecraftServer s,CompoundTag t){var l=SupplyFlowService.level(s,site(t,"Buyer").dimension());return l!=null&&l.getEntity(body(t)) instanceof NpcBase n?n:null;}
 private static boolean alive(MinecraftServer s,UUID person){var c=CharacterRegistryData.get(s).byId(person);return c!=null&&c.alive();}
 public static boolean physicalCourier(MinecraftServer s,UUID person,long at){var d=existing(s);if(d==null)return false;for(var t:d.deals.values())if(active(t)&&person(t).equals(person)&&t.contains("MobileAt")&&at>=t.getLong("MobileAt")){var n=carrier(s,t);return n!=null&&!n.isRemoved()&&n.isAlive();}return false;}
 public static boolean managedNeed(NeedRequest r){return r.key().purpose().startsWith("HOUSEHOLD:regional-exchange:");}
 private CompoundTag forRoute(UUID route){for(var t:deals.values())if((t.hasUUID("PayRoute")&&t.getUUID("PayRoute").equals(route))||(t.hasUUID("FoodRoute")&&t.getUUID("FoodRoute").equals(route)))return t;return null;}
 public static boolean emergencyFood(NpcBase n,SupplyFlowData.Route r){var d=existing(n.getServer());if(d==null)return false;var t=d.forRoute(r.id());return t!=null&&active(t)&&body(t).equals(n.getUUID())&&r.principal().equals(SubjectRef.household(NaturalVillageLifeData.home(site(t,"Buyer"))));}
 public static boolean tradeRoute(MinecraftServer s,UUID route){var d=existing(s);return d!=null&&d.forRoute(route)!=null;}
 public static boolean validRoute(MinecraftServer s,UUID route){var d=existing(s);if(d==null)return true;var t=d.forRoute(route);if(t==null)return true;
  var buyer=HouseholdData.get(s).byId(NaturalVillageLifeData.home(site(t,"Buyer")));var seller=HouseholdData.get(s).byId(NaturalVillageLifeData.home(site(t,"Seller")));
  return active(t)&&buyer!=null&&seller!=null&&Objects.equals(buyer.sharedStorage(),NaturalVillageLayout.at(site(t,"Buyer"),NaturalVillageLayout.PANTRY))&&Objects.equals(seller.sharedStorage(),NaturalVillageLayout.at(site(t,"Seller"),NaturalVillageLayout.PANTRY));
 }
 /** A known itinerary may travel towards an unloaded endpoint, without inspecting or force-loading it. */
 public static net.minecraft.core.BlockPos waypoint(NpcBase n,BlockPos target){
  double dx=target.getX()+.5-n.getX(),dz=target.getZ()+.5-n.getZ(),distance=Math.sqrt(dx*dx+dz*dz);if(distance<=24)return target;
  double angle=Math.atan2(dz,dx);
  for(double turn:new double[]{0,.45,-.45,.9,-.9}){int x=Mth.floor(n.getX()+Math.cos(angle+turn)*16),z=Mth.floor(n.getZ()+Math.sin(angle+turn)*16);var l=(ServerLevel)n.level();if(l.getChunkSource().getChunkNow(x>>4,z>>4)==null)continue;int y=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);var p=new BlockPos(x,y,z);if(l.getBlockState(p.below()).getFluidState().isEmpty()&&l.getBlockState(p).getCollisionShape(l,p).isEmpty()&&l.getBlockState(p.above()).getCollisionShape(l,p.above()).isEmpty()){var route=com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes.plan(n,p,false);if(route!=null)return route.stand();}}
  return null;
 }
 private static Container ownInventory(MinecraftServer s,NaturalVillageBuilding b,int i){var regional=NaturalVillageEconomyData.existing(s);var row=regional==null?null:regional.row(b.buildingKey());
  if(row!=null&&!row.getString("Phase").equals("NEAR")){if(!Set.of("REMOTE","RETURNING").contains(row.getString("Phase")))return null;return NaturalVillageEconomyData.inventory(row.getList("Stores",Tag.TAG_COMPOUND).getCompound(i).getList("Items",Tag.TAG_COMPOUND),s.registryAccess());}
  try{return SupplyFlowService.container(s,SupplyFlowData.get(s).point(NaturalVillageEconomyData.storeId(b,i)));}catch(NoSuchElementException e){return null;}
 }
 private static int count(Container c,ItemFingerprint item){return c==null?0:SupplyFlowService.count(c,item);}
 /** A standing charter can commit only presently verifiable, authorized and unencumbered stock. */
 private static int disposable(MinecraftServer s,NaturalVillageBuilding b,int index,ItemFingerprint item,long now){
  var flows=SupplyFlowData.get(s);var id=NaturalVillageEconomyData.storeId(b,index);
  if(!flows.known(SubjectRef.household(NaturalVillageLifeData.home(b))).contains(id))return 0;
  return SupplyFlowService.available(s,flows.point(id),SubjectRef.household(NaturalVillageLifeData.home(b)),item,now);
 }
 private void propose(MinecraftServer s,long now){if(deals.values().stream().anyMatch(SettlementExchangeData::active)||deals.size()>=128)return;var life=NaturalVillageLifeData.existing(s);if(life==null)return;
  var known=life.villages().stream().filter(v->v.getBoolean("Ready")).map(NaturalVillageLifeData::decode).filter(NaturalVillageLayout::chartered).filter(b->{var region=NaturalVillageEconomyData.existing(s);return region!=null&&region.row(b.buildingKey())!=null;}).filter(b->{var h=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));return h!=null&&Objects.equals(h.settlementId(),b.settlementId())&&Objects.equals(h.sharedStorage(),NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY))&&!com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(s,h.headCharacterId());}).limit(16).toList();
  for(var seller:known){if(NaturalVillageLayout.woodcamp(seller)||disposable(s,seller,1,FOOD,now)<12||count(ownInventory(s,seller,1),WOOD)>=12)continue;
   for(var buyer:known){if(!NaturalVillageLayout.woodcamp(buyer)||!seller.dimension().equals(buyer.dimension())||seller.buildingOrigin().distSqr(buyer.buildingOrigin())>1024*1024||count(ownInventory(s,buyer,1),FOOD)>=8||!alive(s,NaturalVillageLifeData.person(buyer,1)))continue;
    String pair=buyer.settlementId()+"/"+seller.settlementId();if(cooldown.getOrDefault(pair,0L)>now)continue;int source=disposable(s,buyer,2,WOOD,now)>=2?2:disposable(s,buyer,0,WOOD,now)>=2?0:-1;if(source<0){com.jgeted.sagadyssey.social.politics.CharterTradeGovernance.refusal(s,buyer,seller,"没有已知、有权且未占用的两块原木；协议不代替库存与物权检查");continue;}
    var admission=com.jgeted.sagadyssey.social.politics.CharterTradeGovernance.admission(s,buyer,seller);if(!admission.accepted()){com.jgeted.sagadyssey.social.politics.CharterTradeGovernance.refusal(s,buyer,seller,admission.reason());continue;}
    var t=new CompoundTag();UUID id=ContainerIds.named("regional-barter:v1:"+pair+":"+sequence++);t.putUUID("Id",id);t.put("Buyer",life.village(buyer.buildingKey()));t.put("Seller",life.village(seller.buildingKey()));t.putInt("WoodSource",source);t.putString("Pair",pair);t.putString("State","AWAIT_CARRIER");t.putLong("Created",now);t.putString("Reason","双方生活户的固定交换约定：两块原木预付，交付四个胡萝卜后结清");deals.put(id,t);setDirty();return;
   }
  }
 }
 private UUID route(MinecraftServer s,CompoundTag deal,boolean food,long now){String field=food?"FoodRoute":"PayRoute";if(deal.hasUUID(field))return deal.getUUID(field);
  var buyer=site(deal,"Buyer");var seller=site(deal,"Seller");var principal=SubjectRef.household(NaturalVillageLifeData.home(buyer));var flows=SupplyFlowData.get(s);var rights=RightsStateData.get(s);
  UUID source=food?NaturalVillageEconomyData.storeId(seller,1):NaturalVillageEconomyData.storeId(buyer,deal.getInt("WoodSource"));UUID destination=food?NaturalVillageEconomyData.storeId(buyer,1):NaturalVillageEconomyData.storeId(seller,1);var item=food?FOOD:WOOD;int quantity=food?4:2;
  var point=flows.point(source);var inventory=SupplyFlowService.container(s,point);if(inventory==null)return null;
  if(food){if(disposable(s,seller,1,FOOD,now)<quantity)return null;UUID grant=ContainerIds.named("regional-barter-consent:"+deal.getUUID("Id"));rights.grant(new RightGrant(grant,source,principal,Set.of(AssetRight.TRANSFER),"已收到两块原木，按固定约定提取四个胡萝卜",now,now+96000,true));deal.putUUID("FoodGrant",grant);}
  if(SupplyFlowService.available(s,point,principal,item,now)<quantity)return null;
  String needField=food?"FoodNeed":"PayNeed";NeedRequest need;
  if(deal.hasUUID(needField))need=NeedRequestData.get(s).request(deal.getUUID(needField));else{
   need=SupplyFlowService.request(s,principal,"regional-exchange:"+deal.getUUID("Id")+":"+(food?"food":"wood"),flows.point(destination),item,quantity,now,now+96000);deal.putUUID(needField,need.id());setDirty();
  }
  UUID id=ContainerIds.named("regional-barter-route:"+deal.getUUID("Id")+":"+food+":"+deal.getInt("Attempt"));var r=new SupplyFlowData.Route(id,need.id(),source,destination,principal,quantity,body(deal),person(deal),SupplyFlowData.Stage.HELD,now,0,null,"按已登记交换约定搬运真实物资");
  var reservation=new AssetReservation(SupplyFlowService.receipt(id,"lease"),source,item,quantity,point.owner(),r.token(),"固定实物交换",now,now+96000);
  if(!SupplyFlowService.reserveRoute(rights,NeedRequestData.get(s),flows,LogisticsData.get(s),reservation,r,count(inventory,item),now))return null;
  deal.putUUID(field,id);setDirty();return id;
 }
 public void tick(MinecraftServer s){long start=System.nanoTime(),now=s.overworld().getGameTime();
  if(now%200==0)propose(s,now);
  for(var t:deals.values())if(active(t)){
   if(!alive(s,person(t))){t.putString("State","REVIEW");t.putString("Reason","原运输者死亡，保留掉落与未清偿义务，不补造人员或货物");setDirty();continue;}
   if((t.hasUUID("PayRoute")&&!validRoute(s,t.getUUID("PayRoute")))||(t.hasUUID("FoodRoute")&&!validRoute(s,t.getUUID("FoodRoute")))){t.putString("State","REVIEW");t.putString("Reason","接收户迁居或解散，保留货物和义务，等待重新约定");setDirty();continue;}
   var n=carrier(s,t);if(n==null||n.isNoAi()||!NpcBrainRuntime.isPlanned(n)||n.getCommand()!=NpcCommand.WORK)continue;
   if(!t.contains("MobileAt")){t.putLong("MobileAt",now);setDirty();}
   if(t.getString("State").equals("AWAIT_CARRIER")){if(route(s,t,false,now)!=null){t.putString("State","PAY");setDirty();}}
   if(t.getString("State").equals("PAY")){
    var r=SupplyFlowData.get(s).route(t.getUUID("PayRoute"));if(r.stage()==SupplyFlowData.Stage.CANCELLED){retireUnpicked(s,t,"PayRoute","PayNeed",now);t.putString("State","AWAIT_CARRIER");continue;}if(r.stage()==SupplyFlowData.Stage.REVIEW){t.putString("State","REVIEW");t.putString("Reason",r.detail());setDirty();continue;}
    if(r.stage()==SupplyFlowData.Stage.DONE){t.putInt("FoodOwed",4);t.putString("Reason","两块原木已真实交付；仍欠四个胡萝卜");if(route(s,t,true,now)!=null){t.putString("State","FOOD");setDirty();}}
   }
   if(t.getString("State").equals("FOOD")){
    var r=SupplyFlowData.get(s).route(t.getUUID("FoodRoute"));if(r.stage()==SupplyFlowData.Stage.CANCELLED){retireUnpicked(s,t,"FoodRoute","FoodNeed",now);t.putString("State","PAY");continue;}if(r.stage()==SupplyFlowData.Stage.CARRYING&&t.hasUUID("FoodGrant"))RightsStateData.get(s).revoke(t.getUUID("FoodGrant"));
    if(r.stage()==SupplyFlowData.Stage.DONE){t.putInt("FoodOwed",0);t.putString("State","DONE");t.putLong("Completed",now);t.putString("Reason","两块原木与四个胡萝卜均已扣库、携带并实际交付");cooldown.put(t.getString("Pair"),now+24000);RightsStateData.get(s).revoke(t.getUUID("FoodGrant"));WorldHistoryData.get(s).append(now,"regional_exchange_completed",site(t,"Buyer").settlementId(),"聚落实物交换","两块原木换四个胡萝卜；由原有运输者完成真实往返",40);setDirty();}
    else if(r.stage()==SupplyFlowData.Stage.REVIEW){t.putString("State","REVIEW");t.putString("Reason",r.detail());setDirty();}
   }
  }
  for(var t:deals.values())if(t.getString("State").equals("REVIEW")&&t.hasUUID("FoodGrant"))RightsStateData.get(s).revoke(t.getUUID("FoodGrant"));
  LodMetrics.record(s,LodMetrics.Kind.REGION_TRADE,System.nanoTime()-start);
 }
 private void retireUnpicked(MinecraftServer s,CompoundTag t,String route,String need,long now){var r=NeedRequestData.get(s).request(t.getUUID(need));if(r.reserved()!=0||r.inTransit()!=0)throw new IllegalStateException("Cannot retire unsettled goods");if(!r.status().terminal())NeedRequestData.get(s).transition(r.id(),NeedRequestStatus.CANCELLED,now,"unpicked exchange is revalidated; paid debt remains");t.remove(route);t.remove(need);t.putInt("Attempt",t.getInt("Attempt")+1);setDirty();}
 public void traffic(MinecraftServer s){Set<Lease> desired=new HashSet<>();
  for(var t:deals.values())if(active(t)){
   var b=site(t,"Buyer");var n=carrier(s,t);var checkpoint=NpcLodData.get(s).find(body(t));BlockPos center=n!=null?n.blockPosition():checkpoint==null?null:BlockPos.of(checkpoint.position());if(center==null)continue;
   var cp=new ChunkPos(center);for(int x=cp.x-1;x<=cp.x+1;x++)for(int z=cp.z-1;z<=cp.z+1;z++)desired.add(new Lease(b.dimension(),new ChunkPos(x,z),body(t)));
  }
  if(desired.size()>9)throw new IllegalStateException("Regional courier ticket budget exceeded");var previous=LEASES.getOrDefault(s,Set.of());
  for(var lease:desired)if(!previous.contains(lease)){var l=SupplyFlowService.level(s,lease.dimension());if(l!=null)l.getChunkSource().addRegionTicket(TICKET,lease.chunk(),2,lease.body(),true);}
  for(var lease:previous)if(!desired.contains(lease)){var l=SupplyFlowService.level(s,lease.dimension());if(l!=null)l.getChunkSource().removeRegionTicket(TICKET,lease.chunk(),2,lease.body(),true);}
  // Timeout tickets need renewal even while their center stays unchanged.
  for(var lease:desired){var l=SupplyFlowService.level(s,lease.dimension());if(l!=null)l.getChunkSource().addRegionTicket(TICKET,lease.chunk(),2,lease.body(),true);}
  LEASES.put(s,desired);
 }
 @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("Schema",1);tag.putLong("Sequence",sequence);var list=new ListTag();deals.values().forEach(t->list.add(t.copy()));tag.put("Deals",list);var times=new ListTag();cooldown.forEach((k,v)->{var t=new CompoundTag();t.putString("Pair",k);t.putLong("Until",v);times.add(t);});tag.put("Cooldown",times);return tag;}
 public static SettlementExchangeData load(CompoundTag tag,HolderLookup.Provider lookup){if(tag.getInt("Schema")!=1)throw new IllegalArgumentException("Unknown exchange ledger");var d=new SettlementExchangeData();d.sequence=tag.getLong("Sequence");for(var e:tag.getList("Deals",Tag.TAG_COMPOUND)){var t=((CompoundTag)e).copy();site(t,"Buyer");site(t,"Seller");if(!Set.of("AWAIT_CARRIER","PAY","FOOD","DONE","REVIEW").contains(t.getString("State"))||d.deals.putIfAbsent(t.getUUID("Id"),t)!=null||d.deals.size()>128)throw new IllegalArgumentException("Invalid exchange intent");}for(var e:tag.getList("Cooldown",Tag.TAG_COMPOUND)){var t=(CompoundTag)e;d.cooldown.put(t.getString("Pair"),t.getLong("Until"));}return d;}
 @SubscribeEvent public static void pre(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e){if(e.getServer().overworld().getGameTime()%20==0){var d=existing(e.getServer());if(d!=null)d.traffic(e.getServer());}}
 @SubscribeEvent public static void post(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var s=e.getServer();if(s.overworld().getGameTime()%20!=0)return;var life=NaturalVillageLifeData.existing(s);if(life!=null&&life.villages().stream().anyMatch(v->v.getBoolean("Ready")&&NaturalVillageLayout.chartered(NaturalVillageLifeData.decode(v))))get(s).tick(s);}
 @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){LEASES.remove(e.getServer());}
}
