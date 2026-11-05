package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Local bindings, declarations, fixed transaction intents and accountable ledgers. */
public final class ReligionData extends SavedData{
    public static final String NAME="sagadyssey_religion_v3";
    public static final Factory<ReligionData> FACTORY=new Factory<>(ReligionData::new,ReligionData::load);
    private static final int MAX=4096;
    private final Map<UUID,TempleRecord> temples=new LinkedHashMap<>();
    private final Map<UUID,AlmsCase> cases=new LinkedHashMap<>();
    private final Map<UUID,DonationRecord> donations=new LinkedHashMap<>();
    private final Map<UUID,UUID> pickups=new HashMap<>(),issues=new HashMap<>();
    public UUID receipt(UUID caseId,boolean pickup){return (pickup?pickups:issues).get(caseId);}
    public void receipt(UUID caseId,boolean pickup,UUID transfer){aid(caseId);Objects.requireNonNull(transfer);var book=pickup?pickups:issues;
        var old=book.putIfAbsent(caseId,transfer);if(old!=null&&!old.equals(transfer))throw new IllegalStateException("alms receipt changed");setDirty();}
    public static ReligionData get(MinecraftServer server){var s=server.overworld().getDataStorage();var d=s.get(FACTORY,NAME);if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("religion ledger unreadable; recovery required");
        d=new ReligionData();s.set(NAME,d);return d;}
    public List<TempleRecord> temples(){return List.copyOf(temples.values());}
    public TempleRecord temple(UUID id){var t=temples.get(id);if(t==null)throw new NoSuchElementException("unknown local temple");return t;}
    public TempleRecord forBuilding(String key){return temples.values().stream().filter(t->t.building().equals(key)).findFirst().orElse(null);}
    public void canAddTemple(){capacity(temples.size());}
    public void temple(TempleRecord t){var old=temples.get(t.id());if(old!=null&&!old.equals(t))throw new IllegalStateException("temple binding changed");
        var same=forBuilding(t.building());if(same!=null&&!same.id().equals(t.id()))throw new IllegalStateException("building has another temple");
        if(old==null){capacity(temples.size());temples.put(t.id(),t);setDirty();}}
    public List<AlmsCase> cases(){return List.copyOf(cases.values());}
    public AlmsCase aid(UUID id){var v=cases.get(id);if(v==null)throw new NoSuchElementException("unknown alms case");return v;}
    public AlmsCase findAid(UUID id){return cases.get(id);}
    public void aid(AlmsCase c){temple(c.temple());var old=cases.get(c.id());
        if(old!=null&&(!old.temple().equals(c.temple())||!old.recipient().equals(c.recipient())||!old.item().equals(c.item())||old.quantity()!=c.quantity()
                ||!old.statement().equals(c.statement())||old.createdAt()!=c.createdAt()||c.updatedAt()<old.updatedAt()
                ||old.workerEntity()!=null&&c.workerEntity()!=null&&!old.workerEntity().equals(c.workerEntity())
                ||old.stage()==AlmsCase.Stage.CARRYING&&(!Objects.equals(old.workerEntity(),c.workerEntity())||!Objects.equals(old.workerCharacter(),c.workerCharacter()))
                ||old.closed()&&!old.equals(c)))throw new IllegalStateException("alms identity or final receipt changed");
        if(old==null)capacity(cases.size());cases.put(c.id(),c);setDirty();}
    public List<DonationRecord> donations(){return List.copyOf(donations.values());}
    public DonationRecord donation(UUID id){return donations.get(id);}
    public void donation(DonationRecord d){temple(d.temple());var old=donations.get(d.id());
        if(old!=null&&(!old.temple().equals(d.temple())||!old.donor().equals(d.donor())||!old.source().equals(d.source())||!old.destination().equals(d.destination())
                ||!old.item().equals(d.item())||old.quantity()!=d.quantity()||old.purpose()!=d.purpose()||old.createdAt()!=d.createdAt()
                ||old.sourceRevision()!=d.sourceRevision()||old.targetRevision()!=d.targetRevision()||d.updatedAt()<old.updatedAt()
                ||old.status()!=DonationRecord.Status.PENDING&&!old.equals(d)))throw new IllegalStateException("donation identity or receipt changed");
        if(old==null)capacity(donations.size());donations.put(d.id(),d);setDirty();}
    private static void capacity(int size){if(size>=MAX)throw new IllegalStateException("religion ledger full; archive required");}
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider lookup){
        root.putInt("Schema",1);var sites=new ListTag();for(var t:temples.values()){var v=new CompoundTag();v.putUUID("Id",t.id());v.putString("Building",t.building());v.putString("Name",t.name());v.put("Treasury",t.treasury().save());v.put("Alms",t.alms().save());v.putLong("Counter",t.counter().asLong());v.putInt("Retained",t.retained());v.putInt("Portion",t.portion());v.putLong("Created",t.createdAt());sites.add(v);}root.put("Temples",sites);
        var appeals=new ListTag();for(var c:cases.values()){var v=new CompoundTag();v.putUUID("Id",c.id());v.putUUID("Temple",c.temple());v.putUUID("Recipient",c.recipient());v.put("Item",c.item().save());v.putInt("Quantity",c.quantity());v.putString("Statement",c.statement());v.putString("Stage",c.stage().name());v.putInt("Attempts",c.attempts());v.putLong("Created",c.createdAt());v.putLong("Updated",c.updatedAt());v.putLong("Retry",c.retryAt());v.putString("Detail",c.detail());
            if(c.workerEntity()!=null){v.putUUID("WorkerEntity",c.workerEntity());v.putUUID("WorkerCharacter",c.workerCharacter());}if(c.reviewer()!=null)v.putUUID("Reviewer",c.reviewer());
            if(c.pending()!=null){var p=c.pending();var x=new CompoundTag();x.putUUID("Transfer",p.transfer());x.putUUID("Destination",p.destination());x.putBoolean("Pickup",p.pickup());x.putLong("SourceRevision",p.sourceRevision());x.putLong("TargetRevision",p.targetRevision());x.putLong("Time",p.time());v.put("Pending",x);}appeals.add(v);}root.put("Cases",appeals);
        var gifts=new ListTag();for(var d:donations.values()){var v=new CompoundTag();v.putUUID("Id",d.id());v.putUUID("Temple",d.temple());v.putUUID("Donor",d.donor());v.putUUID("Source",d.source());v.putUUID("Destination",d.destination());v.put("Item",d.item().save());v.putInt("Quantity",d.quantity());v.putString("Purpose",d.purpose().name());v.putString("Status",d.status().name());v.putLong("SourceRevision",d.sourceRevision());v.putLong("TargetRevision",d.targetRevision());v.putLong("Created",d.createdAt());v.putLong("Updated",d.updatedAt());v.putString("Detail",d.detail());gifts.add(v);}root.put("Donations",gifts);
        var transactions=new ListTag();var ids=new HashSet<>(pickups.keySet());ids.addAll(issues.keySet());for(UUID id:ids){var v=new CompoundTag();v.putUUID("Case",id);if(pickups.containsKey(id))v.putUUID("Pickup",pickups.get(id));if(issues.containsKey(id))v.putUUID("Issue",issues.get(id));transactions.add(v);}root.put("Transactions",transactions);return root;
    }
    public static ReligionData load(CompoundTag root,HolderLookup.Provider lookup){
        require(root,"Schema",Tag.TAG_INT);if(root.getInt("Schema")!=1)throw new IllegalArgumentException("unsupported religion schema");var d=new ReligionData();
        for(Tag entry:list(root,"Temples")){var v=(CompoundTag)entry;for(String n:List.of("Building","Name"))require(v,n,Tag.TAG_STRING);for(String n:List.of("Counter","Created"))require(v,n,Tag.TAG_LONG);for(String n:List.of("Retained","Portion"))require(v,n,Tag.TAG_INT);require(v,"Treasury",Tag.TAG_COMPOUND);require(v,"Alms",Tag.TAG_COMPOUND);
            var t=new TempleRecord(uuid(v,"Id"),v.getString("Building"),v.getString("Name"),FieldworkStorage.load(v.getCompound("Treasury")),FieldworkStorage.load(v.getCompound("Alms")),BlockPos.of(v.getLong("Counter")),v.getInt("Retained"),v.getInt("Portion"),v.getLong("Created"));if(d.temples.containsKey(t.id()))throw new IllegalArgumentException("duplicate temple");d.temple(t);}
        for(Tag entry:list(root,"Cases")){var v=(CompoundTag)entry;for(String n:List.of("Statement","Stage","Detail"))require(v,n,Tag.TAG_STRING);for(String n:List.of("Quantity","Attempts"))require(v,n,Tag.TAG_INT);for(String n:List.of("Created","Updated","Retry"))require(v,n,Tag.TAG_LONG);
            AlmsCase.Pending p=null;if(v.contains("Pending")){require(v,"Pending",Tag.TAG_COMPOUND);var x=v.getCompound("Pending");for(String n:List.of("SourceRevision","TargetRevision","Time"))require(x,n,Tag.TAG_LONG);require(x,"Pickup",Tag.TAG_BYTE);p=new AlmsCase.Pending(uuid(x,"Transfer"),uuid(x,"Destination"),x.getBoolean("Pickup"),x.getLong("SourceRevision"),x.getLong("TargetRevision"),x.getLong("Time"));}
            var c=new AlmsCase(uuid(v,"Id"),uuid(v,"Temple"),uuid(v,"Recipient"),fingerprint(v),v.getInt("Quantity"),v.getString("Statement"),AlmsCase.Stage.valueOf(v.getString("Stage")),v.contains("WorkerEntity")?uuid(v,"WorkerEntity"):null,v.contains("WorkerCharacter")?uuid(v,"WorkerCharacter"):null,v.contains("Reviewer")?uuid(v,"Reviewer"):null,v.getInt("Attempts"),v.getLong("Created"),v.getLong("Updated"),v.getLong("Retry"),p,v.getString("Detail"));if(d.cases.containsKey(c.id()))throw new IllegalArgumentException("duplicate case");d.aid(c);}
        for(Tag entry:list(root,"Donations")){var v=(CompoundTag)entry;for(String n:List.of("Purpose","Status","Detail"))require(v,n,Tag.TAG_STRING);require(v,"Quantity",Tag.TAG_INT);for(String n:List.of("SourceRevision","TargetRevision","Created","Updated"))require(v,n,Tag.TAG_LONG);
            var gift=new DonationRecord(uuid(v,"Id"),uuid(v,"Temple"),uuid(v,"Donor"),uuid(v,"Source"),uuid(v,"Destination"),fingerprint(v),v.getInt("Quantity"),DonationRecord.Purpose.valueOf(v.getString("Purpose")),DonationRecord.Status.valueOf(v.getString("Status")),v.getLong("SourceRevision"),v.getLong("TargetRevision"),v.getLong("Created"),v.getLong("Updated"),v.getString("Detail"));if(d.donations.containsKey(gift.id()))throw new IllegalArgumentException("duplicate donation");d.donation(gift);}
        for(Tag entry:list(root,"Transactions")){var v=(CompoundTag)entry;UUID id=uuid(v,"Case");d.aid(id);if(d.pickups.containsKey(id)||d.issues.containsKey(id)||!v.contains("Pickup")&&!v.contains("Issue"))throw new IllegalArgumentException("duplicate or empty alms receipt entry");if(v.contains("Pickup"))d.receipt(id,true,uuid(v,"Pickup"));if(v.contains("Issue"))d.receipt(id,false,uuid(v,"Issue"));}
        for(var c:d.cases.values())if(c.stage()==AlmsCase.Stage.CARRYING&&d.receipt(c.id(),true)==null||c.stage()==AlmsCase.Stage.ISSUED&&(d.receipt(c.id(),true)==null||d.receipt(c.id(),false)==null))throw new IllegalArgumentException("unproven charity settlement");
        return d;
    }
    private static ItemFingerprint fingerprint(CompoundTag v){require(v,"Item",Tag.TAG_COMPOUND);var item=v.getCompound("Item");require(item,"Item",Tag.TAG_STRING);require(item,"Components",Tag.TAG_INT);return ItemFingerprint.load(item);}
    private static ListTag list(CompoundTag root,String name){if(!(root.get(name) instanceof ListTag l)||l.size()>MAX||!l.isEmpty()&&l.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid religion list "+name);return l;}
    private static void require(CompoundTag v,String n,int type){if(!v.contains(n,type))throw new IllegalArgumentException("missing religion field "+n);}
    private static UUID uuid(CompoundTag v,String n){if(!v.hasUUID(n))throw new IllegalArgumentException("missing religion uuid "+n);return v.getUUID(n);}
}
