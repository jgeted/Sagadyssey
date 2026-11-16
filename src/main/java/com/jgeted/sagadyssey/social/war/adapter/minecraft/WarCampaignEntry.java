package com.jgeted.sagadyssey.social.war.adapter.minecraft;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import java.util.*;

/** Adapter DTOs contain original endpoint references and effect references, never inventory copies. */
public sealed interface WarCampaignEntry permits WarCampaignEntry.Depot,WarCampaignEntry.Shipment,WarCampaignEntry.Allowance,WarCampaignEntry.Horse {
    UUID id();long revision();
    record Depot(UUID id,UUID owner,ItemLocation location,long revision) implements WarCampaignEntry {
        public Depot{Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(location);if(location.kind()!=ItemLocation.Kind.BLOCK||revision<1)throw new IllegalArgumentException("invalid depot");}
    }
    record Shipment(WarSupplyRun run,ItemLocation source,ItemLocation target,ItemFingerprint item,Optional<PhysicalItemOperation.Intent> pending) implements WarCampaignEntry {
        public Shipment {Objects.requireNonNull(run);Objects.requireNonNull(source);Objects.requireNonNull(target);Objects.requireNonNull(item);Objects.requireNonNull(pending);if(source.kind()!=ItemLocation.Kind.BLOCK||source.id().equals(target.id())||target.kind()==ItemLocation.Kind.EQUIPMENT||run.terminal()&&pending.isPresent())throw new IllegalArgumentException("invalid shipment references");}
        public UUID id(){return run.id();}public long revision(){return run.revision();}
        public Shipment next(WarSupplyRun r){return new Shipment(r,source,target,item,Optional.empty());}
    }
    record Payment(UUID operation,int amount,long at){public Payment {Objects.requireNonNull(operation);if(amount<1||amount>1000||at<0)throw new IllegalArgumentException("invalid stipend receipt");}}
    record Allowance(WarAllowanceTerms terms,ItemLocation treasury,long stop,long revision,long changedAt,List<Payment> payments,Optional<PhysicalItemOperation.Intent> pending,int attempts) implements WarCampaignEntry {
        public Allowance {Objects.requireNonNull(terms);Objects.requireNonNull(treasury);payments=List.copyOf(payments);Objects.requireNonNull(pending);if(stop< -1||stop>=0&&(stop<terms.startsAt()||stop>terms.endsAt())||revision<1||changedAt<terms.startsAt()||payments.size()>512||attempts<0||attempts>1024||new HashSet<>(payments.stream().map(Payment::operation).toList()).size()!=payments.size())throw new IllegalArgumentException("invalid stipend evidence");}
        public UUID id(){return terms.id();}public int paid(){return payments.stream().mapToInt(Payment::amount).sum();}
        public java.math.BigInteger owed(long now){return terms.due(now,stop<0?OptionalLong.empty():OptionalLong.of(stop)).subtract(java.math.BigInteger.valueOf(paid())).max(java.math.BigInteger.ZERO);}
    }
    record Horse(UUID id,UUID service,UUID person,UUID body,UUID owner,long fedAt,long revision,long changedAt,int attempts,Optional<PhysicalItemOperation.Intent> pending) implements WarCampaignEntry {
        public Horse {for(var v:List.of(id,service,person,body,owner))Objects.requireNonNull(v);Objects.requireNonNull(pending);if(fedAt< -1||revision<1||changedAt<0||fedAt>changedAt||attempts<0||attempts>4096)throw new IllegalArgumentException("invalid horse feed record");}
        public boolean fresh(long now){return fedAt>=0&&now>=fedAt&&now-fedAt<WarHorseCare.FEED_PERIOD;}
    }
    static boolean follows(WarCampaignEntry a,WarCampaignEntry b){
        if(a.equals(b))return true;if(!a.id().equals(b.id())||a.revision()==Long.MAX_VALUE||b.revision()!=a.revision()+1)return false;
        if(a instanceof Depot x&&b instanceof Depot y)return x.owner.equals(y.owner);
        if(a instanceof Shipment x&&b instanceof Shipment y)return x.source.equals(y.source)&&x.target.equals(y.target)&&x.item.equals(y.item)&&WarSupplyRun.follows(x.run,y.run);
        if(a instanceof Allowance x&&b instanceof Allowance y)return x.terms.equals(y.terms)&&x.treasury.equals(y.treasury)&&y.changedAt>=x.changedAt&&(x.stop<0||x.stop==y.stop)&&y.payments.size()>=x.payments.size()&&y.payments.subList(0,x.payments.size()).equals(x.payments)&&y.attempts>=x.attempts&&y.attempts<=x.attempts+1;
        if(a instanceof Horse x&&b instanceof Horse y)return x.owner.equals(y.owner)&&y.changedAt>=x.changedAt&&y.fedAt>=x.fedAt&&y.attempts>=x.attempts&&y.attempts<=x.attempts+1;
        return false;
    }
}
