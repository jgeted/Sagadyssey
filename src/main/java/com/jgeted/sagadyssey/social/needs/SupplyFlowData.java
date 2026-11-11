package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Scoped known supply network and durable transport intent. No remote inventory snapshots. */
public final class SupplyFlowData extends NbtSavedData {
    public static final String NAME = "sagadyssey_supply_flow_v3";
    public static final Factory<SupplyFlowData> FACTORY = new Factory<>(SupplyFlowData::new, SupplyFlowData::load);
    public static final int MAX_ENTRIES = 4096;
    private final Map<UUID, FieldworkStorage> points = new LinkedHashMap<>();
    private final Map<SubjectRef, Set<UUID>> known = new HashMap<>();
    private final Map<UUID, Demand> demands = new LinkedHashMap<>();
    private final Map<UUID, Route> routes = new LinkedHashMap<>();
    private final Map<String, Policy> policies = new LinkedHashMap<>();
    private final Map<UUID, UUID> production = new HashMap<>();
    public record Demand(SubjectRef principal, UUID destination) { public Demand {Objects.requireNonNull(principal);Objects.requireNonNull(destination);} }
    public record Policy(String id, SubjectRef principal, UUID destination, ItemFingerprint item,
                         int target, String category) {
        public Policy { Objects.requireNonNull(principal); Objects.requireNonNull(destination); Objects.requireNonNull(item);
            if(id==null||id.isBlank()||id.length()>512||target<1||target>1728
                    || !Set.of("household","workshop","institution").contains(category))throw new IllegalArgumentException("invalid stock policy"); }
    }
    public enum Stage { HELD, CARRYING, DONE, CANCELLED, REVIEW }
    public record Route(UUID id, UUID need, UUID source, UUID destination, SubjectRef principal,
                        int quantity, UUID carrierEntity, UUID carrierCharacter, Stage stage,
                        long retryAt, int failures, Pending pending, String detail) {
        public Route {
            Objects.requireNonNull(id); Objects.requireNonNull(need); Objects.requireNonNull(source);
            Objects.requireNonNull(destination); Objects.requireNonNull(principal); Objects.requireNonNull(stage);
            if(source.equals(destination)||quantity<1||quantity>1728||retryAt<0||failures<0||detail==null||detail.length()>2048
                    || (carrierEntity==null)!=(carrierCharacter==null)
                    || stage==Stage.CARRYING && carrierEntity==null)throw new IllegalArgumentException("invalid supply route");
        }
        public String token() { return "need-route:"+id; }
        public Route claim(UUID entity,UUID character) {
            if(carrierEntity!=null&&!carrierEntity.equals(entity))throw new IllegalStateException("route already claimed");
            return new Route(id,need,source,destination,principal,quantity,entity,character,stage,retryAt,failures,pending,detail);
        }
        public Route progress(Stage next,Pending transaction,long retry,int failed,String reason) {
            return new Route(id,need,source,destination,principal,quantity,carrierEntity,carrierCharacter,next,retry,failed,transaction,reason);
        }
        public boolean closed() { return stage==Stage.DONE||stage==Stage.CANCELLED||stage==Stage.REVIEW; }
    }
    /** Fixed transaction payload persisted before submit; no regenerated ids on recovery. */
    public record Pending(UUID id, boolean pickup, UUID bag, int quantity, ItemFingerprint item,
                          long sourceRevision,long targetRevision,long time) {
        public Pending { Objects.requireNonNull(id); Objects.requireNonNull(bag);Objects.requireNonNull(item);
            if(quantity<1||sourceRevision<0||targetRevision<0||time<0)throw new IllegalArgumentException("invalid route transaction"); }
        public TransferRequest request(Route route) {
            UUID from=pickup?route.source():bag,to=pickup?bag:route.destination();
            return new TransferRequest(id,pickup?route.principal():SubjectRef.character(route.carrierCharacter()),TransferIntent.DELIVERY,
                    List.of(new TransferLeg(from,to,item,quantity)),
                    List.of(new ExpectedRevision(from,sourceRevision),new ExpectedRevision(to,targetRevision)),route.token(),time);
        }
    }
    public static SupplyFlowData get(MinecraftServer server) {
        var storage=server.overworld().getDataStorage(); var data=storage.get(FACTORY.type(NAME));if(data!=null)return data;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))
            throw new IllegalStateException("unreadable supply ledger; recovery required");
        data=new SupplyFlowData();storage.set(FACTORY.type(NAME),data);return data;
    }
    public void register(FieldworkStorage point) {
        var previous=points.get(point.containerId());
        if(previous!=null&&(!previous.dimension().equals(point.dimension())||!previous.position().equals(point.position())
                ||!previous.owner().equals(point.owner())||previous.policy()!=point.policy()))throw new IllegalStateException("supply endpoint identity changed");
        if(previous==null){capacity(points.size());points.put(point.containerId(),point);setDirty();}
    }
    /** Knowledge reveal does NOT create rights grants. */
    public void reveal(SubjectRef actor,UUID point) {
        Objects.requireNonNull(actor);point(point);
        var values=known.computeIfAbsent(actor,k->new HashSet<>());
        if(!values.contains(point)&&values.size()>=64)throw new IllegalStateException("known supply sites full");
        if(values.add(point))setDirty();
    }
    public Set<UUID> known(SubjectRef actor){return Set.copyOf(known.getOrDefault(actor,Set.of()));}
    public FieldworkStorage point(UUID id){var p=points.get(id);if(p==null)throw new NoSuchElementException("unknown supply site "+id);return p;}
    public FieldworkStorage pointOrNull(UUID id){return points.get(id);}
    public void demand(UUID id,SubjectRef principal,UUID destination) {
        point(destination);var value=new Demand(principal,destination);var previous=demands.get(id);
        if(previous!=null&&!previous.equals(value))throw new IllegalStateException("demand scope changed");
        if(previous==null){capacity(demands.size());demands.put(id,value);setDirty();}
    }
    public Demand demand(UUID id){return demands.get(id);}
    public void policy(Policy policy){point(policy.destination());var old=policies.get(policy.id());
        if(old!=null&&!old.equals(policy))throw new IllegalStateException("stock policy identity conflict");
        if(old==null){capacity(policies.size());policies.put(policy.id(),policy);setDirty();}}
    public List<Policy> policies(){return List.copyOf(policies.values());}
    public void removePolicy(String id){if(policies.remove(id)!=null)setDirty();}
    public List<Route> routes(){return List.copyOf(routes.values());}
    public Route route(UUID id){var r=routes.get(id);if(r==null)throw new NoSuchElementException("unknown supply route");return r;}
    public Route routeOrNull(UUID id){return routes.get(id);}
    public void route(Route route){
        var old=routes.get(route.id());
        if(old!=null&&(!old.need().equals(route.need())||!old.source().equals(route.source())
                ||!old.destination().equals(route.destination())||!old.principal().equals(route.principal())
                ||old.quantity()!=route.quantity()||old.carrierEntity()!=null
                &&(!old.carrierEntity().equals(route.carrierEntity())||!old.carrierCharacter().equals(route.carrierCharacter()))))
            throw new IllegalStateException("supply route identity changed");
        if(old==null)capacity(routes.size());routes.put(route.id(),route);setDirty();
    }
    /** Publish a new route only after all linked order validation has succeeded. */
    void createRouteWithOrder(Route route, Runnable publishOrder) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(publishOrder);
        if (routes.containsKey(route.id())) throw new IllegalStateException("route already exists");
        capacity(routes.size());
        publishOrder.run();
        routes.put(route.id(), route);
        setDirty();
    }

    public UUID production(UUID need){return production.get(need);}
    public void production(UUID need,UUID order){if(production.putIfAbsent(need,order)==null)setDirty();else if(!production.get(need).equals(order))throw new IllegalStateException("production linkage conflict");}
    private static void capacity(int size){if(size>=MAX_ENTRIES)throw new IllegalStateException("supply ledger full; archiving required");}

    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){
        tag.putInt("Schema",1);var locations=new ListTag();points.values().forEach(p->locations.add(p.save()));tag.put("Points",locations);
        var knowledge=new ListTag();known.forEach((actor,ids)->ids.forEach(id->{var t=new CompoundTag();t.put("Actor",actor.save());t.store("Point", UUIDUtil.CODEC,id);knowledge.add(t);}));tag.put("Known",knowledge);
        var demandList=new ListTag();demands.forEach((id,d)->{var t=new CompoundTag();t.store("Id", UUIDUtil.CODEC,id);t.put("Principal",d.principal().save());t.store("Destination", UUIDUtil.CODEC,d.destination());demandList.add(t);});tag.put("Demands",demandList);
        var policyList=new ListTag();policies.values().forEach(p->{var t=new CompoundTag();t.putString("Id",p.id());t.put("Principal",p.principal().save());t.store("Destination", UUIDUtil.CODEC,p.destination());t.put("Item",p.item().save());t.putInt("Target",p.target());t.putString("Category",p.category());policyList.add(t);});tag.put("Policies",policyList);
        var routeList=new ListTag();routes.values().forEach(r->{var t=new CompoundTag();t.store("Id", UUIDUtil.CODEC,r.id());t.store("Need", UUIDUtil.CODEC,r.need());t.store("Source", UUIDUtil.CODEC,r.source());t.store("Destination", UUIDUtil.CODEC,r.destination());t.put("Principal",r.principal().save());t.putInt("Quantity",r.quantity());t.putString("Stage",r.stage().name());t.putLong("RetryAt",r.retryAt());t.putInt("Failures",r.failures());t.putString("Detail",r.detail());
            if(r.carrierEntity()!=null){t.store("CarrierEntity", UUIDUtil.CODEC,r.carrierEntity());t.store("CarrierCharacter", UUIDUtil.CODEC,r.carrierCharacter());}
            if(r.pending()!=null){var p=r.pending();var x=new CompoundTag();x.store("Id", UUIDUtil.CODEC,p.id());x.store("Bag", UUIDUtil.CODEC,p.bag());x.putBoolean("Pickup",p.pickup());x.putInt("Quantity",p.quantity());x.put("Item",p.item().save());x.putLong("SourceRevision",p.sourceRevision());x.putLong("TargetRevision",p.targetRevision());x.putLong("Time",p.time());t.put("Pending",x);}routeList.add(t);});tag.put("Routes",routeList);
        var links=new ListTag();production.forEach((need,order)->{var t=new CompoundTag();t.store("Need", UUIDUtil.CODEC,need);t.store("Order", UUIDUtil.CODEC,order);links.add(t);});tag.put("Production",links);return tag;
    }
    public static SupplyFlowData load(CompoundTag tag,HolderLookup.Provider lookup){
        if(!MinecraftApi.containsTag(tag, "Schema",Tag.TAG_INT)||tag.getIntOr("Schema", 0)!=1)throw new IllegalArgumentException("unknown supply schema");
        for(String field:List.of("Points","Known","Demands","Policies","Routes","Production"))
            if(!(tag.get(field) instanceof ListTag list)||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND||list.size()>MAX_ENTRIES*64)
                throw new IllegalArgumentException("invalid supply list "+field);
        var d=new SupplyFlowData();
        for(Tag value:MinecraftApi.listOfType(tag, "Points",Tag.TAG_COMPOUND)){var p=FieldworkStorage.load((CompoundTag)value);if(d.points.containsKey(p.containerId()))throw new IllegalArgumentException("duplicate supply point");d.register(p);}
        for(Tag value:MinecraftApi.listOfType(tag, "Known",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;var actor=SubjectRef.load(t.getCompoundOrEmpty("Actor"));UUID id=uuid(t,"Point");if(d.known(actor).contains(id))throw new IllegalArgumentException("duplicate knowledge");d.reveal(actor,id);}
        for(Tag value:MinecraftApi.listOfType(tag, "Demands",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;UUID id=uuid(t,"Id");if(d.demands.containsKey(id))throw new IllegalArgumentException("duplicate demand scope");d.demand(id,SubjectRef.load(t.getCompoundOrEmpty("Principal")),uuid(t,"Destination"));}
        for(Tag value:MinecraftApi.listOfType(tag, "Policies",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;required(t,"Target",Tag.TAG_INT);var p=new Policy(t.getStringOr("Id", ""),SubjectRef.load(t.getCompoundOrEmpty("Principal")),uuid(t,"Destination"),fingerprint(t.getCompoundOrEmpty("Item")),t.getIntOr("Target", 0),t.getStringOr("Category", ""));if(d.policies.containsKey(p.id()))throw new IllegalArgumentException("duplicate policy");d.policy(p);}
        for(Tag value:MinecraftApi.listOfType(tag, "Routes",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;required(t,"Quantity",Tag.TAG_INT);required(t,"RetryAt",Tag.TAG_LONG);required(t,"Failures",Tag.TAG_INT);required(t,"Detail",Tag.TAG_STRING);Pending p=null;
            if(t.contains("Pending")){required(t,"Pending",Tag.TAG_COMPOUND);var x=t.getCompoundOrEmpty("Pending");for(String k:List.of("SourceRevision","TargetRevision","Time"))required(x,k,Tag.TAG_LONG);required(x,"Quantity",Tag.TAG_INT);required(x,"Pickup",Tag.TAG_BYTE);p=new Pending(uuid(x,"Id"),x.getBooleanOr("Pickup", false),uuid(x,"Bag"),x.getIntOr("Quantity", 0),fingerprint(x.getCompoundOrEmpty("Item")),x.getLongOr("SourceRevision", 0L),x.getLongOr("TargetRevision", 0L),x.getLongOr("Time", 0L));}
            var r=new Route(uuid(t,"Id"),uuid(t,"Need"),uuid(t,"Source"),uuid(t,"Destination"),SubjectRef.load(t.getCompoundOrEmpty("Principal")),t.getIntOr("Quantity", 0),t.contains("CarrierEntity")?uuid(t,"CarrierEntity"):null,t.contains("CarrierCharacter")?uuid(t,"CarrierCharacter"):null,Stage.valueOf(t.getStringOr("Stage", "")),t.getLongOr("RetryAt", 0L),t.getIntOr("Failures", 0),p,t.getStringOr("Detail", ""));
            d.point(r.source());d.point(r.destination());if(!d.demands.containsKey(r.need())||d.routes.containsKey(r.id()))throw new IllegalArgumentException("orphan or duplicate route");d.route(r);}
        for(Tag value:MinecraftApi.listOfType(tag, "Production",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;UUID need=uuid(t,"Need");if(!d.demands.containsKey(need)||d.production.containsKey(need))throw new IllegalArgumentException("invalid production link");d.production(need,uuid(t,"Order"));}
        return d;
    }
    private static UUID uuid(CompoundTag t,String key){if(!t.read(key, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing supply UUID "+key);return t.read(key, UUIDUtil.CODEC).orElseThrow();}
    private static void required(CompoundTag t,String key,int type){if(!MinecraftApi.containsTag(t, key,type))throw new IllegalArgumentException("invalid supply field "+key);}
    private static ItemFingerprint fingerprint(CompoundTag t){required(t,"Item",Tag.TAG_STRING);required(t,"Components",Tag.TAG_INT);return ItemFingerprint.load(t);}
}
