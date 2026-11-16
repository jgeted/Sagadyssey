package com.jgeted.sagadyssey.social.war.adapter.persistence;
import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarCampaignEntry.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import java.util.*;

public final class WarCampaignCodec extends NbtSnapshotCodec<WarCampaignEntry>{
    private static final PhysicalItemOperationCodec ITEMS=new PhysicalItemOperationCodec();
    public WarCampaignCodec(){super("sagadyssey:war_campaign/v1",16*1024*1024,2048);}
    protected UUID identity(WarCampaignEntry e){return e.id();}
    protected void validate(Collection<WarCampaignEntry> records){
        var people=new HashSet<UUID>();var bodies=new HashSet<UUID>();var services=new HashSet<UUID>();
        for(var e:records){
            if(e instanceof Shipment s){
                var r=s.run();if(!r.terminal()&&(!people.add(r.person())||!bodies.add(r.body())||!services.add(r.service())))throw new IllegalArgumentException("duplicate original carrier duty");
                if(s.pending().isPresent()){
                    boolean pick=r.phase()==WarSupplyRun.Phase.LOADING,back=r.phase()==WarSupplyRun.Phase.RETURN_UNLOAD;
                    if(!pick&&!back&&r.phase()!=WarSupplyRun.Phase.UNLOADING)throw new IllegalArgumentException("pending shipment in wrong phase");
                    UUID cargo=ContainerIds.npcCargo(r.person());validateTransfer(s.pending().get(),WarCampaignIdentity.operation("shipment/"+s.id()+"/"+(r.attempt()-1)),r.owner(),pick?TransferIntent.ISSUE:back?TransferIntent.RETURN:TransferIntent.DELIVERY,pick?s.source().id():cargo,pick?cargo:back?s.source().id():s.target().id(),s.item(),r.quantity(),r.createdAt(),r.changedAt(),WarCampaignIdentity.cargo(r.service(),r.id()));
                }
            }else if(e instanceof Allowance a){
                if(java.math.BigInteger.valueOf(a.paid()).compareTo(a.terms().due(a.changedAt(),a.stop()<0?OptionalLong.empty():OptionalLong.of(a.stop())))>0||a.payments().stream().anyMatch(p->p.at()>a.changedAt()||p.at()<a.terms().startsAt()))throw new IllegalArgumentException("stipend exceeds original legal term");
                if(a.pending().isPresent()){
                    int amount=a.pending().get().transfer().orElseThrow().legs().getFirst().quantity();if(java.math.BigInteger.valueOf(amount).compareTo(a.owed(a.changedAt()))>0)throw new IllegalArgumentException("stipend intent exceeds debt");
                    validateTransfer(a.pending().get(),WarCampaignIdentity.operation("allowance/"+a.id()+"/"+(a.attempts()-1)),a.terms().owner(),TransferIntent.PAYMENT,a.treasury().id(),ContainerIds.npcPersonal(a.terms().person()),new ItemFingerprint("minecraft:emerald",0),amount,a.terms().startsAt(),a.changedAt(),"");
                }
            }else if(e instanceof Horse h&&h.pending().isPresent()){
                var p=h.pending().get();var c=p.consumption().orElseThrow();if(!p.id().equals(WarCampaignIdentity.operation("horse/"+h.id()+"/"+(h.attempts()-1)))||!c.container().equals(ContainerIds.npcPersonal(h.person()))||!c.actor().equals(com.jgeted.sagadyssey.worldsim.rights.SubjectRef.character(h.person()))||!c.item().equals(new ItemFingerprint("minecraft:hay_block",0))||c.quantity()!=1||!c.authority().isEmpty()||!c.purpose().equals("WAR_HORSE_FEED:"+h.id())||c.requestedAt()>h.changedAt())throw new IllegalArgumentException("foreign horse feed intent");
            }
        }
    }
    private static void validateTransfer(PhysicalItemOperation.Intent p,UUID op,UUID owner,TransferIntent kind,UUID source,UUID target,ItemFingerprint item,int quantity,long start,long at,String authority){
        var t=p.transfer().orElseThrow();if(!p.id().equals(op)||!t.initiatedBy().equals(com.jgeted.sagadyssey.worldsim.rights.SubjectRef.character(owner))||t.intent()!=kind||t.legs().size()!=1||!t.authority().equals(authority)||!t.sourceAuthorities().isEmpty()||t.requestedAt()<start||t.requestedAt()>at)throw new IllegalArgumentException("foreign campaign transfer");
        var leg=t.legs().getFirst();if(!leg.sourceContainerId().equals(source)||!leg.destinationContainerId().equals(target)||!leg.fingerprint().equals(item)||leg.quantity()!=quantity||leg.sourceSlot()!=-1||leg.destinationSlot()!=-1||t.expectedRevisions().size()!=2||!new HashSet<>(t.expectedRevisions().stream().map(ExpectedRevision::containerId).toList()).equals(Set.of(source,target)))throw new IllegalArgumentException("foreign campaign endpoints");
    }
    private static void id(CompoundTag t,String k,UUID v){t.store(k,UUIDUtil.CODEC,v);}
    protected CompoundTag encode(WarCampaignEntry e){
        var t=new CompoundTag();id(t,"Id",e.id());t.putLong("Revision",e.revision());
        if(e instanceof Depot d){t.putString("Type","depot");id(t,"Owner",d.owner());t.put("Location",d.location().save());}
        else if(e instanceof Shipment s){var r=s.run();t.putString("Type","shipment");id(t,"Army",r.army());id(t,"Owner",r.owner());id(t,"Service",r.service());id(t,"Person",r.person());id(t,"Body",r.body());t.putInt("Quantity",r.quantity());t.putInt("Trips",r.trips());t.putLong("CreatedAt",r.createdAt());t.putLong("Deadline",r.deadline());t.putString("Phase",r.phase().name());t.putInt("Delivered",r.delivered());t.putInt("Attempt",r.attempt());t.putLong("ChangedAt",r.changedAt());t.putBoolean("RetireAfter",r.retireAfter());t.putString("Reason",r.reason());t.put("Source",s.source().save());t.put("Target",s.target().save());t.put("Item",s.item().save());pending(t,s.pending());}
        else if(e instanceof Allowance a){var r=a.terms();t.putString("Type","allowance");id(t,"Army",r.army());id(t,"Owner",r.owner());id(t,"Person",r.person());id(t,"Body",r.body());id(t,"Service",r.service());t.putLong("StartsAt",r.startsAt());t.putLong("EndsAt",r.endsAt());t.putInt("Daily",r.daily());t.put("Treasury",a.treasury().save());t.putLong("Stop",a.stop());t.putLong("ChangedAt",a.changedAt());t.putInt("Attempts",a.attempts());var rows=new ListTag();for(var p:a.payments()){var row=new CompoundTag();id(row,"Operation",p.operation());row.putInt("Amount",p.amount());row.putLong("At",p.at());rows.add(row);}t.put("Payments",rows);pending(t,a.pending());}
        else{var h=(Horse)e;t.putString("Type","horse");id(t,"Service",h.service());id(t,"Person",h.person());id(t,"Body",h.body());id(t,"Owner",h.owner());t.putLong("FedAt",h.fedAt());t.putLong("ChangedAt",h.changedAt());t.putInt("Attempts",h.attempts());pending(t,h.pending());}return t;
    }
    private static void pending(CompoundTag t,Optional<PhysicalItemOperation.Intent> p){if(p.isPresent())try{t.putByteArray("Pending",ITEMS.write(List.of(new PhysicalItemOperation(p.get(),PhysicalItemOperation.Phase.INTENT,Optional.empty()))));}catch(java.io.IOException e){throw new IllegalArgumentException(e);}}
    private static Optional<PhysicalItemOperation.Intent> pending(CompoundTag t){if(!t.contains("Pending"))return Optional.empty();try{var rows=ITEMS.read(t.getByteArray("Pending").orElseThrow());if(rows.size()!=1)throw new IllegalArgumentException("one pending effect required");var row=rows.iterator().next();if(row.phase()!=PhysicalItemOperation.Phase.INTENT||row.proof().isPresent())throw new IllegalArgumentException("embedded campaign reference is only an intent");return Optional.of(row.intent());}catch(java.io.IOException e){throw new IllegalArgumentException(e);}}
    private static void check(CompoundTag t,String fields){var keys=new HashSet<>(List.of(fields.split(",")));if(t.contains("Pending")&&(fields.contains("Attempts")||fields.contains("Phase")))keys.add("Pending");if(!t.keySet().equals(keys))throw new IllegalArgumentException("invalid campaign fields");}
    private static int n(CompoundTag t,String k){if(!(t.get(k) instanceof IntTag))throw new IllegalArgumentException("invalid int");return t.getIntOr(k,-1);}
    protected WarCampaignEntry decode(CompoundTag t){
        return switch(string(t,"Type")){
            case "depot"->{check(t,"Id,Revision,Type,Owner,Location");yield new Depot(uuid(t,"Id"),uuid(t,"Owner"),ItemLocation.load(t.getCompoundOrEmpty("Location")),number(t,"Revision"));}
            case "shipment"->{check(t,"Id,Revision,Type,Army,Owner,Service,Person,Body,Quantity,Trips,CreatedAt,Deadline,Phase,Delivered,Attempt,ChangedAt,RetireAfter,Reason,Source,Target,Item");var r=new WarSupplyRun(uuid(t,"Id"),uuid(t,"Army"),uuid(t,"Owner"),uuid(t,"Service"),uuid(t,"Person"),uuid(t,"Body"),n(t,"Quantity"),n(t,"Trips"),number(t,"CreatedAt"),number(t,"Deadline"),WarSupplyRun.Phase.valueOf(string(t,"Phase")),n(t,"Delivered"),n(t,"Attempt"),number(t,"Revision"),number(t,"ChangedAt"),t.getBoolean("RetireAfter").orElseThrow(),string(t,"Reason"));yield new Shipment(r,ItemLocation.load(t.getCompoundOrEmpty("Source")),ItemLocation.load(t.getCompoundOrEmpty("Target")),ItemFingerprint.load(t.getCompoundOrEmpty("Item")),pending(t));}
            case "allowance"->{check(t,"Id,Revision,Type,Army,Owner,Person,Body,Service,StartsAt,EndsAt,Daily,Treasury,Stop,ChangedAt,Attempts,Payments");var r=new WarAllowanceTerms(uuid(t,"Id"),uuid(t,"Army"),uuid(t,"Owner"),uuid(t,"Person"),uuid(t,"Body"),uuid(t,"Service"),number(t,"StartsAt"),number(t,"EndsAt"),n(t,"Daily"));if(!(t.get("Payments") instanceof ListTag rows)||rows.size()>512)throw new IllegalArgumentException("invalid stipend list");var list=new ArrayList<Payment>();for(var v:rows){var p=(CompoundTag)v;check(p,"Operation,Amount,At");list.add(new Payment(uuid(p,"Operation"),n(p,"Amount"),number(p,"At")));}yield new Allowance(r,ItemLocation.load(t.getCompoundOrEmpty("Treasury")),number(t,"Stop"),number(t,"Revision"),number(t,"ChangedAt"),list,pending(t),n(t,"Attempts"));}
            case "horse"->{check(t,"Id,Revision,Type,Service,Person,Body,Owner,FedAt,ChangedAt,Attempts");yield new Horse(uuid(t,"Id"),uuid(t,"Service"),uuid(t,"Person"),uuid(t,"Body"),uuid(t,"Owner"),number(t,"FedAt"),number(t,"Revision"),number(t,"ChangedAt"),n(t,"Attempts"),pending(t));}
            default->throw new IllegalArgumentException("unknown campaign record");
        };
    }
}
