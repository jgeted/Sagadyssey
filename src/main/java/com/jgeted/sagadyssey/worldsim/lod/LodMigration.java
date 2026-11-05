package com.jgeted.sagadyssey.worldsim.lod;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Explicit loaded-scope CAS migration; simulation and physical assets are never reconstructed. */
public final class LodMigration {
    public static String dryRun(MinecraftServer s,List<NpcBase> targets) {return LodFingerprints.of(snapshot(s,targets));}
    public static CompoundTag counts(MinecraftServer s,List<NpcBase> targets) {
        if(targets.isEmpty()||targets.size()>100)throw new IllegalArgumentException("migration requires 1..100 loaded original NPCs");
        var t=new CompoundTag();int items=0,orders=0,plans=0;
        for(var n:targets){
            for(var raw:LodFingerprints.inventoryTag(n).getList("OrderedSlots",Tag.TAG_COMPOUND))items=Math.addExact(items,((CompoundTag)raw).getInt("count"));
            orders+=com.jgeted.sagadyssey.social.production.ProductionOrderData.get(s).ordersForWorker(n.getCharacterId()).size();
            if(NpcBrainRuntime.data(s).active(n.getUUID()).isPresent())plans++;
        }
        t.putInt("Bodies",targets.size());t.putInt("People",(int)targets.stream().map(NpcBase::getCharacterId).distinct().count());t.putInt("ItemUnits",items);t.putInt("Orders",orders);t.putInt("ActivePlans",plans);return t;
    }
    private static CompoundTag snapshot(MinecraftServer s,List<NpcBase> targets) {
        if(targets.isEmpty()||targets.size()>100)throw new IllegalArgumentException("migration requires 1..100 loaded original NPCs");
        Set<UUID> bodies=new HashSet<>(),persons=new HashSet<>();var list=new ListTag();
        for(var n:targets.stream().sorted(Comparator.comparing(NpcBase::getUUID)).toList()) {
            if(n.getServer()!=s||n.isRemoved()||!n.isAlive()||!bodies.add(n.getUUID())||!persons.add(n.getCharacterId())||CharacterRegistryData.get(s).byId(n.getCharacterId())==null)throw new IllegalStateException("unknown/duplicate/dead migration identity");
            var r=NpcLodRuntime.capture(n,false,"").save();r.remove("CapturedAt");r.remove("SummarizedAt");r.remove("Reconciliation");r.putString("Mode",NpcBrainRuntime.mode(n).name());
            // NoAI/sleep/riding and equipment are not changed by migration.
            r.putBoolean("NoAI",n.isNoAi());list.add(r);
        }
        var tag=new CompoundTag();tag.put("Targets",list);tag.put("Counts",counts(s,targets));return tag;
    }
    public static UUID apply(MinecraftServer s,List<NpcBase> targets,String expected) {
        if(!dryRun(s,targets).equals(expected))throw new IllegalStateException("dry-run revision changed; run dryrun again, no changes applied");
        var data=NpcLodData.get(s);var journal=LodMigrationData.get(s);var tag=new CompoundTag();var list=new ListTag();
        if(data.all().size()+targets.size()>NpcLodData.CAPACITY)throw new IllegalStateException("insufficient checkpoint capacity");
        for(var n:targets) {
            if(NpcBrainRuntime.mode(n)!=NpcBehaviorMode.LEGACY||NpcBrainRuntime.data(s).active(n.getUUID()).isPresent()||n.isPassenger()||n.isSleeping()||NpcLodRuntime.review(data.find(n.getUUID())))throw new IllegalStateException("only idle legacy originals with no active plan may migrate; no forced dismount or cancellation");
            var body=data.bodyForPerson(n.getCharacterId());if(body!=null&&!body.equals(n.getUUID()))throw new IllegalStateException("original person has another checkpoint body");
            var row=new CompoundTag();row.putUUID("Body",n.getUUID());row.putUUID("Person",n.getCharacterId());row.putString("Mode","LEGACY");list.add(row);
        }
        UUID id=UUID.randomUUID();tag.put("Targets",list);tag.putString("State","INTENT");tag.putString("Before",expected);tag.put("BeforeCounts",counts(s,targets));tag.putLong("Time",s.overworld().getGameTime());journal.put(id,tag);save(s);
        for(var n:targets) {NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);if(!NpcLodRuntime.loaded(n))throw new IllegalStateException("migration reconciliation requires review; preserved intent="+id);}
        tag.putString("After",dryRun(s,targets));tag.put("AfterCounts",counts(s,targets));tag.putString("State","APPLIED");journal.put(id,tag);save(s);return id;
    }
    public static void rollback(MinecraftServer s,UUID id) {
        var journal=LodMigrationData.get(s);var row=journal.find(id);if(row==null||!row.getString("State").equals("APPLIED"))throw new IllegalStateException("not an applied migration; uncertain intents require coherent whole-world backup recovery");
        List<NpcBase> actors=new ArrayList<>();for(var raw:row.getList("Targets",Tag.TAG_COMPOUND)){var r=(CompoundTag)raw;NpcBase found=null;for(var l:s.getAllLevels())if(l.getEntity(r.getUUID("Body")) instanceof NpcBase n)found=n;if(found==null||!found.getCharacterId().equals(r.getUUID("Person"))||NpcBrainRuntime.data(s).active(found.getUUID()).isPresent())throw new IllegalStateException("originals must be loaded with no started plan; otherwise restore a coherent whole-world backup");actors.add(found);}
        if(!dryRun(s,actors).equals(row.getString("After")))throw new IllegalStateException("world/asset revisions changed after migration; refusing partial rollback, restore coherent backup");
        for(var n:actors){NpcBrainRuntime.setMode(n,NpcBehaviorMode.LEGACY);NpcLodData.get(s).remove(n.getUUID());}
        row.putString("State","ROLLED_BACK");journal.put(id,row);save(s);
    }
    private static void save(MinecraftServer s){s.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
}
