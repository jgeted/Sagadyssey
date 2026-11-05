package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.*;
import java.util.*;

/** One owner for a bounded region's registered crops and stores. No entity or identity copy. */
public final class NaturalVillageEconomyData extends SavedData {
    public static final String NAME="sagadyssey_natural_region_economy_v1";
    public static final Factory<NaturalVillageEconomyData> FACTORY=new Factory<>(NaturalVillageEconomyData::new,NaturalVillageEconomyData::load);
    public static final int REGION_LIMIT=4;
    private static final ItemFingerprint CARROT=new ItemFingerprint("minecraft:carrot",0);
    private static final List<BlockPos> STORES=List.of(NaturalVillageLayout.RAW,NaturalVillageLayout.PANTRY,NaturalVillageLayout.WOOD,NaturalVillageLayout.PUBLIC);
    private final Map<String,CompoundTag> rows=new LinkedHashMap<>();
    private final Map<UUID,String> residents=new HashMap<>();
    private int cursor;
    // At most the six enrolled originals, only until their final job facts enter a snapshot.
    private final Map<UUID,NpcBase> departingBodies=new HashMap<>();
    public static NaturalVillageEconomyData get(MinecraftServer s){return com.jgeted.sagadyssey.worldsim.PersistentDataAccess.getOrCreate(s,FACTORY,NAME);}
    public static NaturalVillageEconomyData existing(MinecraftServer s){
        var d=s.overworld().getDataStorage().get(FACTORY,NAME);
        if(d==null&&java.nio.file.Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/"+NAME+".dat")))throw new IllegalStateException("Region economy unreadable; do not replace authoritative assets");
        return d;
    }
    public CompoundTag row(String key){var r=rows.get(key);return r==null?null:r.copy();}
    public List<CompoundTag> rows(){return rows.values().stream().map(CompoundTag::copy).toList();}
    private void index(NaturalVillageBuilding b){for(int i=0;i<3;i++)residents.put(NaturalVillageLifeData.person(b,i),b.buildingKey());}
    public static boolean controls(MinecraftServer s,UUID person){
        if(com.jgeted.sagadyssey.social.combat.NaturalConflictData.physicalParticipant(s,person,s.overworld().getGameTime())||SettlementExchangeData.physicalCourier(s,person,s.overworld().getGameTime())||com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(s,person))return false;
        var d=existing(s);if(d==null)return false;var home=HouseholdData.get(s).forMember(person);var r=home==null?d.rows.get(d.residents.get(person)):d.rows.values().stream().filter(row->NaturalVillageLifeData.decode(row.getCompound("Village")).settlementId().equals(home.settlementId())).findFirst().orElse(null);return r!=null&&!r.getString("Phase").equals("NEAR");
    }
    public static boolean protects(MinecraftServer s,String dimension,BlockPos p){
        var d=existing(s);if(d==null)return false;
        for(var r:d.rows.values())if(!r.getString("Phase").equals("NEAR")){
            var b=NaturalVillageLifeData.decode(r.getCompound("Village"));if(!b.dimension().equals(dimension))continue;
            for(var relative:NaturalVillageLayout.stores(b))if(NaturalVillageLayout.at(b,relative).equals(p))return true;
            // Covers the real crop yard, including hydrated support soil while the mirror is quarantined.
            var a=NaturalVillageLayout.at(b,new BlockPos(13,0,3));var z=NaturalVillageLayout.at(b,new BlockPos(21,2,9));
            if(p.getX()>=Math.min(a.getX(),z.getX())&&p.getX()<=Math.max(a.getX(),z.getX())&&p.getZ()>=Math.min(a.getZ(),z.getZ())&&p.getZ()<=Math.max(a.getZ(),z.getZ())&&p.getY()>=a.getY()&&p.getY()<=z.getY())return true;
        }
        return false;
    }
    public static UUID storeId(NaturalVillageBuilding b,int index){return ContainerIds.block(NaturalVillageLayout.storageRole(index),b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.stores(b).get(index)));}
    public static ListTag items(Container c,HolderLookup.Provider lookup){var out=new ListTag();for(int i=0;i<c.getContainerSize();i++)if(!c.getItem(i).isEmpty()){
        var t=new CompoundTag();t.putInt("Slot",i);t.put("Stack",c.getItem(i).save(lookup));out.add(t);}return out;}
    public static SimpleContainer inventory(ListTag items,HolderLookup.Provider lookup){var c=new SimpleContainer(27);for(var e:items){var t=(CompoundTag)e;int slot=t.getInt("Slot");if(slot<0||slot>=27||!c.getItem(slot).isEmpty())throw new IllegalArgumentException("Invalid region inventory slot");c.setItem(slot,ItemStack.parseOptional(lookup,t.getCompound("Stack")));}return c;}
    public interface RetainedChunks {LevelChunk sagadyssey$retained(long position);}
    private static LevelChunk snapshotChunk(ServerLevel l,BlockPos p,LevelChunk leaving){
        var c=chunk(l,p,leaving);if(c!=null)return c;
        return ((RetainedChunks)(Object)l.getChunkSource().chunkMap).sagadyssey$retained(new ChunkPos(p).toLong());
    }
    private static LevelChunk chunk(ServerLevel l,BlockPos p,LevelChunk leaving){return leaving!=null&&leaving.getPos().equals(new ChunkPos(p))?leaving:l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);}
    private static boolean alive(MinecraftServer s,UUID person){var c=CharacterRegistryData.get(s).byId(person);return c!=null&&c.alive();}
    public void enroll(MinecraftServer s){var life=NaturalVillageLifeData.existing(s);if(life==null)return;
        for(var v:life.villages()){
            if(rows.size()>=REGION_LIMIT)return;if(!v.getBoolean("Ready"))continue;var b=NaturalVillageLifeData.decode(v);if(rows.containsKey(b.buildingKey()))continue;
            var r=new CompoundTag();r.put("Village",v.copy());r.putString("Phase","NEAR");r.putLong("Epoch",0);r.putString("Reason","实体层独占生产与库存");rows.put(b.buildingKey(),r);index(b);setDirty();
        }
    }
    /** Called before the first original entity/footprint chunk leaves; never loads a missing chunk. */
    public boolean handoff(MinecraftServer s,String key,LevelChunk leaving,NpcBase departing){
        var r=rows.get(key);if(r==null||!r.getString("Phase").equals("NEAR"))return false;var b=NaturalVillageLifeData.decode(r.getCompound("Village"));var l=SupplyFlowService.level(s,b.dimension());if(l==null)return false;
        long began=System.nanoTime();var stores=new ListTag();var plants=new ListTag();var ages=new int[12];int i=0;
        for(var p:NaturalVillageLayout.stores(b)){var at=NaturalVillageLayout.at(b,p);var c=snapshotChunk(l,at,leaving);if(c==null||!(c.getBlockEntity(at) instanceof Container inventory))return false;
            var t=new CompoundTag();t.putLong("Position",at.asLong());t.putUUID("Id",storeId(b,i++));t.putString("Block",BuiltInRegistries.BLOCK.getKey(c.getBlockState(at).getBlock()).toString());var original=items(inventory,s.registryAccess());t.put("Before",original.copy());t.put("Items",original);stores.add(t);
        }
        i=0;for(var p:NaturalVillageLayout.crops()){
            var at=NaturalVillageLayout.at(b,p);var c=snapshotChunk(l,at,leaving);if(c==null)return false;var state=c.getBlockState(at);var soil=c.getBlockState(at.below());
            var t=new CompoundTag();t.putLong("Position",at.asLong());t.put("Before",NbtUtils.writeBlockState(state));t.put("GroundBefore",NbtUtils.writeBlockState(soil));
            t.putBoolean("Growing",soil.is(Blocks.FARMLAND)&&(state.isAir()||state.is(Blocks.CARROTS))&&l.getRawBrightness(at,0)>=9);plants.add(t);ages[i++]=state.is(Blocks.CARROTS)?state.getValue(CropBlock.AGE):-1;
        }
        boolean farm=false,carry=false;for(i=0;i<3;i++){
            var person=NaturalVillageLifeData.person(b,i);
            var e=departing!=null&&departing.getUUID().equals(NaturalVillageLifeData.body(b,i))?departing:l.getEntity(NaturalVillageLifeData.body(b,i));
            if(e==null)e=departingBodies.get(person);
            if(e instanceof NpcBase n&&n.getHealth()>0&&alive(s,person)&&(!n.isRemoved()||n==departing||n.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)&&!n.isNoAi()&&n.getCommand()==NpcCommand.WORK&&NpcBrainRuntime.isPlanned(n)&&n.getTarget()==null){if(i==0&&n.getProfession()==NpcProfession.FARMER&&Objects.equals(com.jgeted.sagadyssey.social.work.NpcWorkContext.from(n).workplaceSettlementId(),b.settlementId()))farm=true;if(n.getTransportSlotLimit()>0)carry=true;}
        }
        var home=SubjectRef.household(NaturalVillageLifeData.home(b));boolean committed=SupplyFlowData.get(s).routes().stream().anyMatch(route->route.principal().equals(home)&&!route.closed());
        for(i=0;i<3;i++){var person=NaturalVillageLifeData.person(b,i);var household=HouseholdData.get(s).forMember(person);if(household==null||Objects.equals(household.settlementId(),b.settlementId()))departingBodies.remove(person);}
        boolean arrivalCarrier=false;for(var other:rows.values()){var origin=NaturalVillageLifeData.decode(other.getCompound("Village"));for(int j=0;j<3;j++){var person=NaturalVillageLifeData.person(origin,j);var household=HouseholdData.get(s).forMember(person);if(household==null||household.id().equals(NaturalVillageLifeData.home(b))||!Objects.equals(household.settlementId(),b.settlementId()))continue;var entity=l.getEntity(NaturalVillageLifeData.body(origin,j));var n=entity instanceof NpcBase actor?actor:departingBodies.get(person);boolean oldCargo=SupplyFlowData.get(s).routes().stream().anyMatch(route->!route.closed()&&route.principal().equals(SubjectRef.household(household.id())));if(n!=null&&alive(s,person)&&(!n.isRemoved()||n.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)&&n.getHealth()>0&&!n.isNoAi()&&n.getCommand()==NpcCommand.WORK&&NpcBrainRuntime.isPlanned(n)&&n.getTarget()==null&&n.getTransportSlotLimit()>0&&n.blockPosition().distSqr(b.buildingOrigin())<64*64&&!oldCargo)arrivalCarrier=true;}}
        r.putBoolean("ArrivalCarrier",arrivalCarrier);departingBodies.keySet().removeIf(person->{var household=HouseholdData.get(s).forMember(person);return household!=null&&Objects.equals(household.settlementId(),b.settlementId());});
        r.putBoolean("Farmer",farm);r.putBoolean("Carrier",carry&&!committed);r.putString("TransportBoundary",committed?"原有运输义务暂停，货物与预留原样恢复":"无既有运输承诺，可用有时长的同楼供给批次");
        if(r.contains("Clock")){var history=r.getList("History",Tag.TAG_COMPOUND);var prior=new CompoundTag();prior.putLong("Epoch",r.getLong("Epoch"));prior.put("Clock",r.getCompound("Clock").copy());prior.putLong("Meals",r.getLong("Meals"));prior.put("Receipts",r.getList("Receipts",Tag.TAG_COMPOUND).copy());history.add(prior);r.put("History",history);}
        r.putBoolean("ObservedAway",false);r.putBoolean("ReturnRequested",false);r.putLong("Epoch",r.getLong("Epoch")+1);r.put("Stores",stores);r.put("Plants",plants);r.put("Clock",new RemoteCropClock(l.getGameTime(),ages).save());r.putLong("Meals",0);r.put("Receipts",new ListTag());r.remove("Batch");r.remove("ArrivalBatch");r.putString("Phase","REMOTE");r.putString("Reason","远方层独占登记农田与仓储；原实体背包和旧义务冻结");setDirty();
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("REGION_HANDOFF settlement={} farmer={} carrier={} epoch={}",b.settlementId(),farm,carry&&!committed,r.getLong("Epoch"));
        LodMetrics.record(s,LodMetrics.Kind.REGION_HANDOFF,System.nanoTime()-began);return true;
    }
    private CompoundTag receipt(CompoundTag r,String kind,long at,UUID person,int output,int input){
        var list=r.getList("Receipts",Tag.TAG_COMPOUND);if(list.size()>=4096)throw new IllegalStateException("Region receipt capacity reached; archive required");
        var t=new CompoundTag();t.putString("Kind",kind);t.putLong("At",at);t.putInt("Output",output);t.putInt("Input",input);if(person!=null)t.putUUID("Person",person);list.add(t);r.put("Receipts",list);return t;
    }
    private final class Port implements RemoteCropClock.Port {
        final MinecraftServer server;final CompoundTag row;final NaturalVillageBuilding village;final ListTag stores,plants;final SimpleContainer[] inventories;final RightsStateData rights;
        Port(MinecraftServer s,CompoundTag r){server=s;row=r;village=NaturalVillageLifeData.decode(r.getCompound("Village"));stores=r.getList("Stores",Tag.TAG_COMPOUND);plants=r.getList("Plants",Tag.TAG_COMPOUND);rights=RightsStateData.get(s);inventories=new SimpleContainer[stores.size()];for(int i=0;i<stores.size();i++)inventories[i]=inventory(stores.getCompound(i).getList("Items",Tag.TAG_COMPOUND),s.registryAccess());}
        UUID id(int i){return stores.getCompound(i).getUUID("Id");}
        SubjectRef owner(int i){return rights.context(id(i)).defaultBeneficialOwner();}
        void persist(int i){stores.getCompound(i).put("Items",items(inventories[i],server.registryAccess()));setDirty();}
        boolean worker(){var person=NaturalVillageLifeData.person(village,0);var home=HouseholdData.get(server).forMember(person);var life=NpcLifeData.get(server).record(person);if(life==null||!Objects.equals(life.activeWorkplaceSettlementId(),village.settlementId()))return false;return owner(0).equals(SubjectRef.character(person))&&row.getBoolean("Farmer")&&!com.jgeted.sagadyssey.social.politics.NaturalPolityData.paused(server,person)&&!com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(server,person)&&alive(server,person)&&home!=null&&Objects.equals(home.settlementId(),village.settlementId());}
        public boolean growing(int i){return plants.getCompound(i).getBoolean("Growing");}
        /** Local production/consumption preserves owner metadata and cannot spend an encumbered stack. */
        boolean change(int index,int delta,SubjectRef actor,long at){
            var c=inventories[index];UUID id=id(index);if(rights.context(id)==null)return false;
            if(delta<0&&SupplyFlowService.count(c,CARROT)-rights.reservedQuantity(id,CARROT,null,at)<-delta)return false;
            for(int slot=0;slot<c.getContainerSize();slot++){
                var stack=c.getItem(slot);boolean empty=stack.isEmpty();if(!empty&&!CARROT.matches(stack))continue;
                var claim=empty?null:rights.claimAt(id,slot,CARROT);
                if(claim!=null&&(!claim.encumbrances().isEmpty()||!claim.reservations().isEmpty()||claim.quantity()!=stack.getCount()||!claim.beneficialOwner().equals(owner(index))))continue;
                if(delta<0&&(empty||stack.getCount()<-delta))continue;
                if(delta>0&&(!empty&&stack.getCount()+delta>stack.getMaxStackSize()))continue;
                if(!empty&&new RightsService(rights).can(actor,AssetRight.TRANSFER,id,slot,CARROT,at).status()!=RightsVerdict.Status.ALLOW)continue;
                int count=(empty?0:stack.getCount())+delta;if(count<0)continue;
                c.setItem(slot,count==0?ItemStack.EMPTY:new ItemStack(Items.CARROT,count));rights.removeClaimsForSlot(id,slot);
                if(count>0)rights.putClaim(new AssetClaim(ContainerIds.named("remote-stock:"+village.buildingKey()+":"+row.getLong("Epoch")+":"+at+":"+index+":"+slot),id,slot,CARROT,count,owner(index),rights.context(id).custodian(),Set.of(),Set.of(),"registered remote carrot conversion",rights.context(id).revision()+1));
                rights.bumpContext(id);persist(index);return true;
            }
            return false;
        }
        public boolean harvestAndReplant(int plot,long at){if(!worker())return false;
            // Four drops must fit before using one as a seed; the atomic conversion leaves three.
            boolean capacity=false;for(int i=0;i<inventories[0].getContainerSize();i++){var stack=inventories[0].getItem(i);if(stack.isEmpty()||CARROT.matches(stack)&&stack.getCount()<=60){capacity=true;break;}}
            if(!capacity||!change(0,3,SubjectRef.character(NaturalVillageLifeData.person(village,0)),at))return false;
            receipt(row,"HARVEST_REPLANT",at,NaturalVillageLifeData.person(village,0),4,1).putLong("Plot",plants.getCompound(plot).getLong("Position"));return true;
        }
        public boolean plant(int plot,long at){if(!worker()||!change(0,-1,SubjectRef.character(NaturalVillageLifeData.person(village,0)),at))return false;receipt(row,"PLANT",at,NaturalVillageLifeData.person(village,0),0,1).putLong("Plot",plants.getCompound(plot).getLong("Position"));return true;}
        public void finishStep(long from,long until){
            var life=NpcLifeData.get(server);
            for(var household:HouseholdData.get(server).households())if(Objects.equals(household.settlementId(),village.settlementId()))for(var member:household.members()){var person=member.characterId();if(!NaturalVillageLifeData.individualRations(server,person)||!alive(server,person)||com.jgeted.sagadyssey.social.combat.NaturalConflictData.physicalParticipant(server,person,until)||SettlementExchangeData.physicalCourier(server,person,until)||com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.moving(server,person))continue;life.increaseHunger(person,2,until);
                if(life.needs(person).hunger()<350)continue;
                int source=person.equals(NaturalVillageLifeData.person(village,0))&&SupplyFlowService.count(inventories[0],CARROT)>8?0:Objects.equals(household.sharedStorage(),NaturalVillageLayout.at(village,NaturalVillageLayout.PANTRY))?1:inventories.length>4&&Objects.equals(household.sharedStorage(),NaturalVillageLayout.at(village,NaturalVillageLayout.ARRIVAL_PANTRY))?4:-1;if(source<0)continue;
                if(change(source,-1,source==0?SubjectRef.character(person):SubjectRef.household(household.id()),until)){
                    life.feed(person,220,"远方登记仓储的真实胡萝卜",until);row.putLong("Meals",row.getLong("Meals")+1);receipt(row,"MEAL",until,person,0,1);
                }
            }
            deliver(until);deliverArrival(until);
        }
        void deliverArrival(long at){
            if(inventories.length<5||!row.getBoolean("ArrivalCarrier"))return;
            var household=HouseholdData.get(server).households().stream().filter(h->Objects.equals(h.settlementId(),village.settlementId())&&Objects.equals(h.sharedStorage(),NaturalVillageLayout.at(village,NaturalVillageLayout.ARRIVAL_PANTRY))).findFirst().orElse(null);if(household==null)return;var principal=SubjectRef.household(household.id());
            if(row.contains("ArrivalBatch")){var batch=row.getCompound("ArrivalBatch");if(batch.getLong("Due")>at)return;var tx=batch.getUUID("Id");if(batch.getBoolean("Submitting")||TransferLedgerData.get(server).receipt(tx)!=null){review(row,"迁入户供给转移中断，保留回执与库存核对，不重放");return;}batch.putBoolean("Submitting",true);setDirty();var request=new TransferRequest(tx,principal,TransferIntent.DELIVERY,List.of(new TransferLeg(id(0),id(4),CARROT,batch.getInt("Quantity"))),List.of(new ExpectedRevision(id(0),rights.context(id(0)).revision()),new ExpectedRevision(id(4),rights.context(id(4)).revision())),batch.getString("Token"),at);var result=new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(id(0),new TransferEndpoint(id(0),inventories[0]),id(4),new TransferEndpoint(id(4),inventories[4])),at);persist(0);persist(4);rights.releaseReservations(batch.getString("Token"));row.remove("ArrivalBatch");var proof=receipt(row,result.successful()?"ARRIVAL_SUPPLY_DELIVERED":"ARRIVAL_SUPPLY_REJECTED",at,null,0,0);proof.putUUID("Transaction",tx);proof.putInt("Quantity",batch.getInt("Quantity"));return;}
            int missing=Math.max(0,16-SupplyFlowService.count(inventories[4],CARROT));int available=Math.max(0,SupplyFlowService.count(inventories[0],CARROT)-8-rights.reservedQuantity(id(0),CARROT,null,at));int amount=Math.min(missing,available);if(amount==0)return;boolean permitted=false;for(int slot=0;slot<27;slot++)if(CARROT.matches(inventories[0].getItem(slot))&&new RightsService(rights).can(principal,AssetRight.TRANSFER,id(0),slot,CARROT,at).status()==RightsVerdict.Status.ALLOW){permitted=true;break;}if(!permitted)return;var tx=ContainerIds.named("remote-arrival-batch:"+village.buildingKey()+":"+row.getLong("Epoch")+":"+at);String token="remote-arrival:"+tx;var batch=new CompoundTag();batch.putUUID("Id",tx);batch.putLong("Due",at+1200);batch.putInt("Quantity",amount);batch.putString("Token",token);if(rights.reserve(new AssetReservation(tx,id(0),CARROT,amount,principal,token,"consented same-building supply to actual arriving household",at,Math.max(at+26000,server.overworld().getGameTime()+26000)),SupplyFlowService.count(inventories[0],CARROT),at))row.put("ArrivalBatch",batch);
        }
        void deliver(long at){
            var record=HouseholdData.get(server).byId(NaturalVillageLifeData.home(village));if(record==null||!Objects.equals(record.settlementId(),village.settlementId()))return;var home=SubjectRef.household(record.id());if(!row.getBoolean("Carrier"))return;
            if(row.contains("Batch")){
                var batch=row.getCompound("Batch");if(batch.getLong("Due")>at)return;
                var tx=batch.getUUID("Id");
                if(batch.getBoolean("Submitting")||TransferLedgerData.get(server).receipt(tx)!=null){review(row,"供给转移中断，先核对回执和库存，不自动重放");return;}
                batch.putBoolean("Submitting",true);setDirty();
                var request=new TransferRequest(tx,home,TransferIntent.DELIVERY,List.of(new TransferLeg(id(0),id(1),CARROT,batch.getInt("Quantity"))),List.of(new ExpectedRevision(id(0),rights.context(id(0)).revision()),new ExpectedRevision(id(1),rights.context(id(1)).revision())),batch.getString("Token"),at);
                var result=new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(id(0),new TransferEndpoint(id(0),inventories[0]),id(1),new TransferEndpoint(id(1),inventories[1])),at);
                persist(0);persist(1);rights.releaseReservations(batch.getString("Token"));row.remove("Batch");var evidence=receipt(row,result.successful()?"SUPPLY_DELIVERED":"SUPPLY_REJECTED",at,null,0,0);evidence.putUUID("Transaction",tx);evidence.putInt("Quantity",batch.getInt("Quantity"));return;
            }
            int missing=Math.max(0,16-SupplyFlowService.count(inventories[1],CARROT));int available=Math.max(0,SupplyFlowService.count(inventories[0],CARROT)-8-rights.reservedQuantity(id(0),CARROT,null,at));int amount=Math.min(missing,available);if(amount==0)return;
            boolean permitted=false;for(int slot=0;slot<27;slot++)if(CARROT.matches(inventories[0].getItem(slot))&&new RightsService(rights).can(home,AssetRight.TRANSFER,id(0),slot,CARROT,at).status()==RightsVerdict.Status.ALLOW){permitted=true;break;}
            if(!permitted)return;var id=ContainerIds.named("remote-batch:"+village.buildingKey()+":"+row.getLong("Epoch")+":"+at);String token="remote-batch:"+id;
            var b=new CompoundTag();b.putUUID("Id",id);b.putLong("Due",at+1200);b.putInt("Quantity",amount);b.putString("Token",token);row.put("Batch",b);
            if(!rights.reserve(new AssetReservation(id,id(0),CARROT,amount,home,token,"same-building remote household supply",at,Math.max(at+26000,server.overworld().getGameTime()+26000)),SupplyFlowService.count(inventories[0],CARROT),at))row.remove("Batch");
        }
    }
    public int advance(MinecraftServer s,String key,long until,int budget){var r=rows.get(key);if(r==null||!Set.of("REMOTE","RETURNING").contains(r.getString("Phase")))return 0;
        if(budget<0||budget>64)throw new IllegalArgumentException("Invalid regional catch-up budget");
        var clock=RemoteCropClock.load(r.getCompound("Clock"));
        if(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentLayout.supports(NaturalVillageLifeData.decode(r.getCompound("Village")))){
            // The v7 charter pauses all physical production AND its matching consumption.
            // No unregistered new fields are simulated; old v1-v6 remote rules remain unchanged.
            var paused=clock.save();paused.putLong("At",Math.max(clock.settledAt(),until));r.put("Clock",paused);
            r.putString("Reason","v7 物理生活生产暂停；原人原货原工程回载续接，不补算远端建造");
            setDirty();return 0;
        }
        var port=new Port(s,r);int n=0;
        while(n<budget&&clock.backlog(until)>0&&Set.of("REMOTE","RETURNING").contains(r.getString("Phase")))n+=clock.advance(until,1,port);
        r.put("Clock",clock.save());if(n>0)setDirty();return n;
    }
    public long backlog(long now){return rows.values().stream().filter(r->Set.of("REMOTE","RETURNING").contains(r.getString("Phase"))).mapToLong(r->RemoteCropClock.load(r.getCompound("Clock")).backlog(now)).sum();}
    private boolean allLoaded(ServerLevel l,CompoundTag r){for(var e:r.getList("Stores",Tag.TAG_COMPOUND))if(chunk(l,BlockPos.of(((CompoundTag)e).getLong("Position")),null)==null)return false;for(var e:r.getList("Plants",Tag.TAG_COMPOUND))if(chunk(l,BlockPos.of(((CompoundTag)e).getLong("Position")),null)==null)return false;return true;}
    /** A retained terrain chunk can regain tracking without another ChunkEvent.Load. */
    public static void originalReturned(NpcBase npc){
        var s=npc.getServer();if(s==null||npc.isRemoved()||!npc.isAlive())return;
        var d=existing(s);if(d==null)return;var key=d.residents.get(npc.getCharacterId());var r=key==null?null:d.rows.get(key);
        if(r==null||r.getBoolean("ReturnRequested")||!Set.of("REMOTE","RETURNING").contains(r.getString("Phase")))return;
        var b=NaturalVillageLifeData.decode(r.getCompound("Village"));var home=HouseholdData.get(s).forMember(npc.getCharacterId());
        if(!npc.level().dimension().location().toString().equals(b.dimension())||home==null||!Objects.equals(home.settlementId(),b.settlementId())||npc.blockPosition().distSqr(b.buildingOrigin())>64*64)return;
        boolean original=false;for(int i=0;i<3;i++)if(npc.getUUID().equals(NaturalVillageLifeData.body(b,i))&&npc.getCharacterId().equals(NaturalVillageLifeData.person(b,i)))original=true;
        if(!original)return;r.putBoolean("ReturnRequested",true);d.setDirty();
        com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("REGION_ORIGINAL_RETURN person={} body={} originalCheckpointRevalidated=true",npc.getCharacterId(),npc.getUUID());
    }
    private void observeRetainedReturn(MinecraftServer s,CompoundTag r){
        if(r==null||r.getBoolean("ReturnRequested")||!Set.of("REMOTE","RETURNING").contains(r.getString("Phase")))return;
        var b=NaturalVillageLifeData.decode(r.getCompound("Village"));var l=SupplyFlowService.level(s,b.dimension());if(l==null)return;
        // Covers a tracking-only disappearance whose original object reappears before disk eviction.
        // Never load a chunk, create a body, or execute a plan while region stores remain authoritative.
        for(int i=0;i<3;i++){var id=NaturalVillageLifeData.body(b,i);var checkpoint=NpcLodData.get(s).find(id);
            if(checkpoint!=null&&checkpoint.unloaded()&&l.getEntity(id) instanceof NpcBase n)NpcLodRuntime.loaded(n);}
    }
    public boolean resume(MinecraftServer s,String key){
        observeRetainedReturn(s,rows.get(key));
        var r=rows.get(key);if(r!=null)for(String batchKey:List.of("Batch","ArrivalBatch"))if(r.contains(batchKey)){var pending=r.getCompound(batchKey);if(pending.getBoolean("Submitting")||TransferLedgerData.get(s).receipt(pending.getUUID("Id"))!=null){review(r,"供给转移状态待核对，不因返回近处而抹去义务");return false;}}if(r==null||!r.getBoolean("ReturnRequested")||!Set.of("REMOTE","RETURNING").contains(r.getString("Phase")))return false;var b=NaturalVillageLifeData.decode(r.getCompound("Village"));var l=SupplyFlowService.level(s,b.dimension());if(l==null||!allLoaded(l,r))return false;
        r.putString("Phase","RETURNING");setDirty();var clock=RemoteCropClock.load(r.getCompound("Clock"));if(clock.backlog(l.getGameTime())>0)return false;long began=System.nanoTime();
        var stores=r.getList("Stores",Tag.TAG_COMPOUND);var plants=r.getList("Plants",Tag.TAG_COMPOUND);
        for(var entry:stores){var t=(CompoundTag)entry;var p=BlockPos.of(t.getLong("Position"));var current=l.getBlockEntity(p);
            if(!(current instanceof Container c)||!BuiltInRegistries.BLOCK.getKey(l.getBlockState(p).getBlock()).toString().equals(t.getString("Block"))||!items(c,s.registryAccess()).equals(t.get("Before"))&&!items(c,s.registryAccess()).equals(t.get("Items"))){review(r,"仓储镜像既非交接前也非预期投影，保留双方证据");return false;}}
        for(int i=0;i<12;i++){var t=plants.getCompound(i);if(t.contains("GroundBefore")&&!NbtUtils.writeBlockState(l.getBlockState(BlockPos.of(t.getLong("Position")).below())).equals(t.get("GroundBefore"))){review(r,"登记耕地已改变，保留物资和作物快照，不投影到无效土壤");return false;}var expected=!t.getBoolean("Growing")?NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompound("Before")):clock.age(i)<0?Blocks.AIR.defaultBlockState():Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,clock.age(i));var actual=NbtUtils.writeBlockState(l.getBlockState(BlockPos.of(t.getLong("Position"))));
            if(!actual.equals(t.get("Before"))&&!actual.equals(NbtUtils.writeBlockState(expected))){review(r,"作物镜像与交接快照冲突，不补造作物");return false;}}
        for(var entry:stores){var t=(CompoundTag)entry;var target=(Container)l.getBlockEntity(BlockPos.of(t.getLong("Position")));var desired=inventory(t.getList("Items",Tag.TAG_COMPOUND),s.registryAccess());for(int i=0;i<27;i++)target.setItem(i,desired.getItem(i).copy());target.setChanged();}
        for(int i=0;i<12;i++){var t=plants.getCompound(i);var desired=!t.getBoolean("Growing")?NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),t.getCompound("Before")):clock.age(i)<0?Blocks.AIR.defaultBlockState():Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,clock.age(i));var p=BlockPos.of(t.getLong("Position"));if(!l.getBlockState(p).equals(desired)&&!l.setBlock(p,desired,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE)){review(r,"作物投影被拒绝，停止执行并保留进度");return false;}}
        // A started abstract trip has not moved stock yet. Relinquish only its own hold at return.
        if(r.contains("Batch")){RightsStateData.get(s).releaseReservations(r.getCompound("Batch").getString("Token"));r.remove("Batch");}
        if(r.contains("ArrivalBatch")){RightsStateData.get(s).releaseReservations(r.getCompound("ArrivalBatch").getString("Token"));r.remove("ArrivalBatch");}
        r.putString("Phase","NEAR");r.putString("Reason","原方块与原容器完成唯一投影，实体继续原计划并重新核验实物");setDirty();LodMetrics.record(s,LodMetrics.Kind.REGION_HANDOFF,System.nanoTime()-began);return true;
    }
    private void review(CompoundTag r,String reason){r.putString("Phase","REVIEW");r.putString("Reason",reason);setDirty();}
    public void tick(MinecraftServer s){
        long began=System.nanoTime(),now=s.overworld().getGameTime();if(now%100==0)enroll(s);var keys=new ArrayList<>(rows.keySet());int remaining=2;
        for(int i=0;i<keys.size()&&remaining>0;i++){
            String key=keys.get(Math.floorMod(cursor++,keys.size()));var r=rows.get(key);
            if(Set.of("REMOTE","RETURNING").contains(r.getString("Phase"))){remaining-=advance(s,key,now,1);resume(s,key);}
            if(System.nanoTime()-began>=2000000)break;
        }
        LodMetrics.record(s,LodMetrics.Kind.REGION_ECONOMY,System.nanoTime()-began);
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",1);var list=new ListTag();rows.values().forEach(r->list.add(r.copy()));t.put("Regions",list);return t;}
    public static NaturalVillageEconomyData load(CompoundTag t,HolderLookup.Provider lookup){
        if(t.getInt("Schema")!=1||!(t.get("Regions") instanceof ListTag)||t.getList("Regions",Tag.TAG_COMPOUND).size()>REGION_LIMIT)throw new IllegalArgumentException("Invalid regional authority schema");
        var d=new NaturalVillageEconomyData();for(var e:t.getList("Regions",Tag.TAG_COMPOUND)){var r=((CompoundTag)e).copy();var b=NaturalVillageLifeData.decode(r.getCompound("Village"));
            if(!Set.of("NEAR","REMOTE","RETURNING","REVIEW").contains(r.getString("Phase"))||d.rows.putIfAbsent(b.buildingKey(),r)!=null)throw new IllegalArgumentException("Conflicting regional authority");
            if(r.contains("Clock")){RemoteCropClock.load(r.getCompound("Clock"));if(r.getList("Plants",Tag.TAG_COMPOUND).size()!=12||r.getList("Stores",Tag.TAG_COMPOUND).size()!=NaturalVillageLayout.stores(b).size())throw new IllegalArgumentException("Invalid regional inventory or land snapshot");for(int i=0;i<NaturalVillageLayout.stores(b).size();i++)if(!storeId(b,i).equals(r.getList("Stores",Tag.TAG_COMPOUND).getCompound(i).getUUID("Id")))throw new IllegalArgumentException("Changed canonical inventory identity");}
            else if(!r.getString("Phase").equals("NEAR"))throw new IllegalArgumentException("Missing remote authority snapshot");r.putBoolean("ObservedAway",true);r.putBoolean("ReturnRequested",false);d.index(b);
        }return d;
    }
    @SubscribeEvent public static void post(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var s=e.getServer();var life=NaturalVillageLifeData.existing(s);if(life!=null)get(s).tick(s);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void leave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e){
        if(e.getEntity() instanceof NpcBase n&&e.getLevel() instanceof ServerLevel l){var d=existing(l.getServer());if(d==null)return;var key=d.residents.get(n.getCharacterId());if(key==null)return;
            if(com.jgeted.sagadyssey.social.household.ResidentWorkMigrationData.departed(l.getServer(),n.getCharacterId()))return;
            d.departingBodies.put(n.getCharacterId(),n);
            com.jgeted.sagadyssey.Sagadyssey.LOGGER.info("REGION_ORIGINAL_LEAVING person={} reason={} command={} profession={}",n.getCharacterId(),n.getRemovalReason(),n.getCommand(),n.getProfession());
            // NeoForge also emits this event when a live entity first loses tracking during
            // chunk demotion. Its removal reason is still null at that point; waiting for
            // UNLOADED_TO_CHUNK misses that sole tracking callback and the complete snapshot.
            if(n.getRemovalReason()==null||n.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)d.handoff(l.getServer(),key,null,n);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void unload(net.neoforged.neoforge.event.level.ChunkEvent.Unload e){if(e.getLevel() instanceof ServerLevel l&&e.getChunk() instanceof LevelChunk c){var d=existing(l.getServer());if(d==null)return;for(String key:List.copyOf(d.rows.keySet())){var b=NaturalVillageLifeData.decode(d.rows.get(key).getCompound("Village"));if(!b.dimension().equals(l.dimension().location().toString()))continue;boolean inside=java.util.stream.Stream.concat(NaturalVillageLayout.stores(b).stream(),NaturalVillageLayout.crops().stream()).anyMatch(p->new ChunkPos(NaturalVillageLayout.at(b,p)).equals(c.getPos()));if(inside){d.handoff(l.getServer(),key,c,null);var r=d.rows.get(key);r.putBoolean("ObservedAway",true);d.setDirty();}}}}
    @SubscribeEvent public static void chunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load e){if(e.getLevel() instanceof ServerLevel l){var d=existing(l.getServer());if(d==null)return;for(var r:d.rows.values())if(r.getBoolean("ObservedAway")&&!r.getString("Phase").equals("NEAR")){var b=NaturalVillageLifeData.decode(r.getCompound("Village"));if(b.dimension().equals(l.dimension().location().toString())&&NaturalVillageLayout.stores(b).stream().anyMatch(p->new ChunkPos(NaturalVillageLayout.at(b,p)).equals(e.getChunk().getPos()))){r.putBoolean("ReturnRequested",true);d.setDirty();}}}}
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent e){var d=existing(e.getServer());if(d!=null)for(String key:List.copyOf(d.rows.keySet()))d.handoff(e.getServer(),key,null,null);}
    @SubscribeEvent public static void interact(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e){if(e.getLevel() instanceof ServerLevel l&&protects(l.getServer(),l.dimension().location().toString(),e.getPos())){e.setCanceled(true);e.getEntity().displayClientMessage(net.minecraft.network.chat.Component.literal("聚落正在核对保存的物资，请稍候"),true);}}
    @SubscribeEvent public static void breakBlock(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e){if(e.getLevel() instanceof ServerLevel l&&protects(l.getServer(),l.dimension().location().toString(),e.getPos()))e.setCanceled(true);}
}
