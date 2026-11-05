package com.jgeted.sagadyssey.social.industry.common;

import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No new ledger. Fixed business roots follow only confirmed no-effect failures in the original ledger. */
public final class IndustryTransfers {
    public enum State { SUCCESS, WAIT, REVIEW }
    public record Outcome(State state,UUID actualId,TransferReceipt receipt,String detail) {}
    private IndustryTransfers() {}
    public static UUID id(String key){return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));}
    public static boolean sameEffect(CompoundTag original,CompoundTag candidate){
        if(original==null || candidate==null)return false;
        var a=original.copy();var b=candidate.copy();a.remove("ExpectedRevisions");b.remove("ExpectedRevisions");
        return a.equals(b);
    }
    public static Outcome confirmed(MinecraftServer server,UUID root,SubjectRef actor,TransferIntent intent,
                                    List<TransferLeg> legs,String token){
        var ledger=TransferLedgerData.get(server);UUID tx=root;TransferReceipt lastFailure=null;
        for(int i=0;i<32;i++){
            var old=ledger.receipt(tx);if(old==null)return new Outcome(State.WAIT,tx,lastFailure,"no committed result beyond confirmed failure");
            var proposed=new TransferRequest(tx,actor,intent,legs,List.of(),token,0);
            if(!sameEffect(ledger.requestIdentity(tx),proposed.identity()))return new Outcome(State.REVIEW,tx,old,"missing/conflicting original request");
            if(old.successful())return new Outcome(State.SUCCESS,tx,old,"original effect confirmed");
            if(old.status()==TransferReceipt.Status.INTERNAL_FAILURE || old.status()==TransferReceipt.Status.RIGHTS_CONTESTED)
                return new Outcome(State.REVIEW,tx,old,"uncertain or contested result");
            lastFailure=old;tx=id(tx+":confirmed-no-effect-retry");
        }
        return new Outcome(State.REVIEW,tx,null,"retry chain limit");
    }
    public static Outcome once(MinecraftServer server,UUID root,SubjectRef actor,TransferIntent intent,
                               List<TransferLeg> legs,Map<UUID,TransferEndpoint> endpoints,String token,long now){
        var rights=RightsStateData.get(server);var ledger=TransferLedgerData.get(server);UUID tx=root;
        var ids=new LinkedHashSet<UUID>();for(var l:legs){ids.add(l.sourceContainerId());ids.add(l.destinationContainerId());}
        for(int attempt=0;attempt<32;attempt++){
            var revs=new ArrayList<ExpectedRevision>();
            for(var endpoint:ids){var c=rights.context(endpoint);if(c==null)return new Outcome(State.REVIEW,tx,null,"missing authoritative endpoint");revs.add(new ExpectedRevision(endpoint,c.revision()));}
            var request=new TransferRequest(tx,actor,intent,legs,revs,token,now);var old=ledger.receipt(tx);
            if(old!=null){
                if(!sameEffect(ledger.requestIdentity(tx),request.identity()))return new Outcome(State.REVIEW,tx,old,"missing/conflicting request evidence");
                if(old.successful())return new Outcome(State.SUCCESS,tx,old,"original request confirmed");
                if(!Set.of(TransferReceipt.Status.STALE_REVISION,TransferReceipt.Status.INSUFFICIENT_ITEMS,
                        TransferReceipt.Status.CAPACITY_EXCEEDED,TransferReceipt.Status.RIGHTS_DENIED,
                        TransferReceipt.Status.ENDPOINT_MISSING).contains(old.status()))
                    return new Outcome(State.REVIEW,tx,old,"not a confirmed retryable no-effect failure");
                if(now-old.completedAt()<1200)return new Outcome(State.WAIT,tx,old,"original 1200 tick backoff");
                tx=id(tx+":confirmed-no-effect-retry");continue;
            }
            var receipt=new TransferService(rights,ledger).submit(request,endpoints,now);
            return receipt.successful()?new Outcome(State.SUCCESS,tx,receipt,"physical transfer committed")
                    :new Outcome(receipt.status()==TransferReceipt.Status.INTERNAL_FAILURE?State.REVIEW:State.WAIT,tx,receipt,receipt.reason());
        }
        return new Outcome(State.REVIEW,tx,null,"retry chain requires review");
    }
}
