package com.jgeted.sagadyssey.social.religion.life;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import com.jgeted.sagadyssey.worldsim.NbtSavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Separate strict schema: an unreadable existing ledger must never become an empty one. */
public final class ChurchLifeData extends NbtSavedData {
    public static final String NAME="sagadyssey_church_life_v3";
    public static final Factory<ChurchLifeData> FACTORY=new Factory<>(ChurchLifeData::new,ChurchLifeData::load);
    private final Map<UUID,ChurchActivity> activities=new LinkedHashMap<>();
    private final Map<UUID,ChurchRecord> records=new LinkedHashMap<>();
    private final Map<UUID,Calendar> calendars=new LinkedHashMap<>();
    private final Map<UUID,Map<UUID,Integer>> exposure=new LinkedHashMap<>();
    private final Map<UUID,ChurchCompact> compacts=new LinkedHashMap<>();
    public record Calendar(UUID id,UUID temple,net.minecraft.core.BlockPos venue,List<UUID> invited,int minimum,long interval,long first,int count,int limit){
        public Calendar{Objects.requireNonNull(id);Objects.requireNonNull(temple);Objects.requireNonNull(venue);venue=venue.immutable();invited=List.copyOf(invited);
            if(invited.size()>16||new HashSet<>(invited).size()!=invited.size()||minimum<1||minimum>invited.size()||interval<200||interval>168000||first<0||count<0||limit<1||limit>14||count>limit)throw new IllegalArgumentException("invalid church calendar");}
        public long next(){return Math.addExact(first,Math.multiplyExact(interval,count));}
    }
    public static ChurchLifeData get(MinecraftServer s){var storage=s.overworld().getDataStorage();var d=storage.get(FACTORY.type(NAME));if(d!=null)return d;
        if(Files.exists(s.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("existing church-life data unreadable; restore a consistent backup");
        d=new ChurchLifeData();storage.set(FACTORY.type(NAME),d);return d;}
    public List<ChurchActivity> activities(){return List.copyOf(activities.values());}
    public List<ChurchRecord> records(){return List.copyOf(records.values());}
    public List<Calendar> calendars(){return List.copyOf(calendars.values());}
    public ChurchActivity activity(UUID id){var a=activities.get(id);if(a==null)throw new IllegalArgumentException("unknown activity");return a;}
    public ChurchRecord record(UUID id){var a=records.get(id);if(a==null)throw new IllegalArgumentException("unknown document");return a;}
    public ChurchRecord findRecord(UUID id){return records.get(id);}
    public List<ChurchCompact> compacts(){return List.copyOf(compacts.values());}
    public ChurchCompact compact(UUID id){var c=compacts.get(id);if(c==null)throw new IllegalArgumentException("unknown compact");return c;}
    public void put(ChurchCompact c){var old=compacts.get(c.id());if(old!=null&&(!old.temple().equals(c.temple())||!old.partner().equals(c.partner())||!old.terms().equals(c.terms())||old.created()!=c.created()||old.expires()!=c.expires()||!old.proposer().equals(c.proposer())||old.templeSigned()&&!old.templeSigner().equals(c.templeSigner())||old.partnerSigned()&&!old.partnerSigner().equals(c.partnerSigner())))throw new IllegalStateException("compact consent/identity cannot change");capacity(compacts,c.id());compacts.put(c.id(),c);setDirty();}
    public void put(ChurchActivity a){var old=activities.get(a.id());if(old!=null&&(!old.sameIntent(a)||old.stage().closed()&&!old.equals(a)||old.host()!=null&&!old.host().equals(a.host())||a.work()<old.work()||a.updated()<old.updated()||a.attempts()<old.attempts()))throw new IllegalStateException("activity identity/terminal settlement cannot change");
        capacity(activities,a.id());activities.put(a.id(),a);setDirty();}
    public void put(ChurchRecord r){var old=records.get(r.id());if(old!=null&&!old.equals(r))throw new IllegalStateException("document identity/content cannot change");capacity(records,r.id());records.put(r.id(),r);setDirty();}
    public void put(Calendar c){var old=calendars.get(c.id());if(old!=null&&(!old.temple().equals(c.temple())||!old.venue().equals(c.venue())||!old.invited().equals(c.invited())||old.minimum()!=c.minimum()||old.first()!=c.first()||old.interval()!=c.interval()||old.limit()!=c.limit()||c.count()<old.count()))throw new IllegalStateException("calendar identity cannot change");capacity(calendars,c.id());calendars.put(c.id(),c);setDirty();}
    public boolean venueFree(ChurchActivity a){return activities.values().stream().noneMatch(b->!b.id().equals(a.id())&&!b.stage().closed()&&b.stage()!=ChurchActivity.Stage.PLANNED&&b.temple().equals(a.temple())&&b.venue().distSqr(a.venue())<36);}
    private static void capacity(Map<?,?> m,Object id){if(!m.containsKey(id)&&m.size()>=4096)throw new IllegalStateException("church-life ledger full; explicit archival required");}
    public void expose(ChurchActivity a,List<UUID> actual){var per=exposure.computeIfAbsent(a.id(),id->new HashMap<>());for(var id:actual){if(!a.consent().contains(id))throw new IllegalArgumentException("unconsenting exposure");per.merge(id,1,Integer::sum);}setDirty();}
    public int exposure(UUID activity,UUID person){return exposure.getOrDefault(activity,Map.of()).getOrDefault(person,0);}
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider lookup){t.putInt("Schema",3);var a=new ListTag();activities.values().forEach(v->a.add(v.save()));t.put("Activities",a);var r=new ListTag();records.values().forEach(v->r.add(v.save()));t.put("Records",r);var c=new ListTag();for(var v:calendars.values()){var x=new CompoundTag();x.store("Id", UUIDUtil.CODEC,v.id());x.store("Temple", UUIDUtil.CODEC,v.temple());x.putLong("Venue",v.venue().asLong());x.put("Invited",ChurchActivity.people(v.invited()));x.putInt("Minimum",v.minimum());x.putLong("Interval",v.interval());x.putLong("First",v.first());x.putInt("Count",v.count());x.putInt("Limit",v.limit());c.add(x);}t.put("Calendars",c);var p=new ListTag();for(var per:exposure.entrySet())for(var person:per.getValue().entrySet()){var x=new CompoundTag();x.store("Activity", UUIDUtil.CODEC,per.getKey());x.store("Person", UUIDUtil.CODEC,person.getKey());x.putInt("Ticks",person.getValue());p.add(x);}t.put("Attendance",p);var co=new ListTag();compacts.values().forEach(v->co.add(v.save()));t.put("Compacts",co);return t;}
    public static ChurchLifeData load(CompoundTag t,HolderLookup.Provider lookup){require(t,"Schema",Tag.TAG_INT);int schema=t.getIntOr("Schema", 0);if(schema<1||schema>3)throw new IllegalArgumentException("unsupported church-life schema");var d=new ChurchLifeData();
        for(var e:list(t,"Activities",4096)){var a=ChurchActivity.load((CompoundTag)e);if(d.activities.putIfAbsent(a.id(),a)!=null)throw new IllegalArgumentException("duplicate activity");}
        for(var e:list(t,"Records",4096)){var r=ChurchRecord.load((CompoundTag)e);if(d.records.putIfAbsent(r.id(),r)!=null)throw new IllegalArgumentException("duplicate document");}
        for(var e:list(t,"Calendars",4096)){var v=(CompoundTag)e;for(var n:List.of("Venue","Interval","First"))require(v,n,Tag.TAG_LONG);for(var n:List.of("Minimum","Count","Limit"))require(v,n,Tag.TAG_INT);var c=new Calendar(uuid(v,"Id"),uuid(v,"Temple"),net.minecraft.core.BlockPos.of(v.getLongOr("Venue", 0L)),ChurchActivity.people(v,"Invited"),v.getIntOr("Minimum", 0),v.getLongOr("Interval", 0L),v.getLongOr("First", 0L),v.getIntOr("Count", 0),v.getIntOr("Limit", 0));if(d.calendars.putIfAbsent(c.id(),c)!=null)throw new IllegalArgumentException("duplicate calendar");}
        for(var a:d.activities.values()){if(a.source()!=null&&!d.records.containsKey(a.source()))throw new IllegalArgumentException("orphan source");if((a.stage()==ChurchActivity.Stage.SETTLING||a.stage()==ChurchActivity.Stage.COMPLETED)&&!d.records.containsKey(a.id()))throw new IllegalArgumentException("unproven activity completion");}
        for(var r:d.records.values()){if(r.activity()!=null&&!d.activities.containsKey(r.activity())||r.source()!=null&&!d.records.containsKey(r.source()))throw new IllegalArgumentException("orphan document");}
        for(var e:schema>=2||t.contains("Attendance")?list(t,"Attendance",65536):new ListTag()){var x=(CompoundTag)e;UUID id=uuid(x,"Activity"),person=uuid(x,"Person");require(x,"Ticks",Tag.TAG_INT);var a=d.activity(id);int ticks=x.getIntOr("Ticks", 0);if(!a.invited().contains(person)||ticks<1||ticks>a.work()||d.exposure.computeIfAbsent(id,i->new HashMap<>()).putIfAbsent(person,ticks)!=null)throw new IllegalArgumentException("invalid attendance exposure");}
        for(var e:schema>=3||t.contains("Compacts")?list(t,"Compacts",4096):new ListTag()){var c=ChurchCompact.load((CompoundTag)e);if(d.compacts.putIfAbsent(c.id(),c)!=null)throw new IllegalArgumentException("duplicate compact");}
        for(var a:d.activities.values())if(a.stage()==ChurchActivity.Stage.SETTLING||a.stage()==ChurchActivity.Stage.COMPLETED){var r=d.record(a.id());boolean report=a.kind()==ChurchActivity.Kind.FUNERAL&&r.source()!=null&&d.record(r.source()).predicate().equals("church.death_report");if(!r.temple().equals(a.temple())||!r.author().equals(a.host())||!Objects.equals(r.source(),a.source())&&!report||a.kind().manuscript()&&r.physical()==null)throw new IllegalArgumentException("record not matching completed activity");}
        return d;}
    static UUID uuid(CompoundTag t,String key){if(!t.read(key, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing UUID "+key);return t.read(key, UUIDUtil.CODEC).orElseThrow();}
    static void require(CompoundTag t,String key,int type){if(!MinecraftApi.containsTag(t, key,type))throw new IllegalArgumentException("missing field "+key);}
    static ListTag list(CompoundTag t,String key,int max){if(!(t.get(key) instanceof ListTag l)||l.size()>max||!l.isEmpty()&&MinecraftApi.elementType(l)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid list "+key);return l;}
}
