package com.jgeted.sagadyssey.social.growth;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SettlementFoodCapacityPolicyTest {
    static final class Failure {
        final UUID txn=UUID.randomUUID(),src=UUID.randomUUID(),dst=UUID.randomUUID(),id=ContainerIds.named("growth-transfer-attempt:"+txn+":0");
        final SubjectRef owner=SubjectRef.household(UUID.randomUUID()),receiver=SubjectRef.character(UUID.randomUUID());
        final RightsStateData rights=new RightsStateData();final TransferLedgerData ledger=new TransferLedgerData();final CompoundTag attempt=new CompoundTag();
        final List<TransferLeg> legs=List.of(new TransferLeg(src,dst,ItemFingerprint.of(new ItemStack(Items.CARROT)),9));
        Failure(){var a=new SimpleContainer(1);a.setItem(0,new ItemStack(Items.CARROT,30));var b=new SimpleContainer(1);b.setItem(0,new ItemStack(Items.COBBLESTONE));rights.ensureContext(src,"s",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(dst,"d",receiver,receiver,AccessPolicy.PRIVATE);var request=new TransferRequest(id,owner,TransferIntent.GIFT,legs,List.of(new ExpectedRevision(src,0),new ExpectedRevision(dst,0)),"v5合法建设或饲料领取",10);var r=new TransferService(rights,ledger).submit(request,Map.of(src,new TransferEndpoint(src,a),dst,new TransferEndpoint(dst,b)),10);assertEquals(TransferReceipt.Status.CAPACITY_EXCEEDED,r.status());assertEquals(30,a.getItem(0).getCount());assertTrue(b.getItem(0).is(Items.COBBLESTONE));attempt.putInt("Attempt",1);attempt.putUUID("Id",id);attempt.putBoolean("Success",false);}
        int proof(SubjectRef actor,TransferIntent intent,List<TransferLeg> requested,TransferLedgerData l){return SettlementGrowthData.foodCapacityEvidence(attempt,txn,actor,intent,requested,l);}
        TransferLedgerData edited(java.util.function.Consumer<CompoundTag> edit){var t=ledger.save(new CompoundTag(),null);edit.accept(t);return TransferLedgerData.FACTORY.deserializer().apply(t,null);}
    }
    @Test void definitePrecommitCapacityHasEvidence(){var f=new Failure();var before=f.ledger.save(new CompoundTag(),null);assertEquals(1,f.proof(f.owner,TransferIntent.GIFT,f.legs,f.ledger));assertEquals(before,f.ledger.save(new CompoundTag(),null));}
    @Test void failedQuantityCanBeReevaluatedWithoutRewritingOldRequest(){var f=new Failure();assertEquals(1,f.proof(f.owner,TransferIntent.GIFT,List.of(new TransferLeg(f.src,f.dst,ItemFingerprint.of(new ItemStack(Items.CARROT)),8)),f.ledger));assertEquals(9,f.ledger.requestIdentity(f.id).getList("Legs",Tag.TAG_COMPOUND).getCompound(0).getInt("Quantity"));}
    @Test void changedActorIsNotCapacityRecovery(){var f=new Failure();assertEquals(-1,f.proof(f.receiver,TransferIntent.GIFT,f.legs,f.ledger));}
    @Test void changedDestinationIsNotCapacityRecovery(){var f=new Failure();assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,List.of(new TransferLeg(f.src,UUID.randomUUID(),ItemFingerprint.of(new ItemStack(Items.CARROT)),9)),f.ledger));}
    @Test void changedSourceIsNotCapacityRecovery(){var f=new Failure();assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,List.of(new TransferLeg(UUID.randomUUID(),f.dst,ItemFingerprint.of(new ItemStack(Items.CARROT)),9)),f.ledger));}
    @Test void changedItemOrIntentNeedsReview(){var f=new Failure();assertEquals(-1,f.proof(f.owner,TransferIntent.DELIVERY,f.legs,f.ledger));assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,List.of(new TransferLeg(f.src,f.dst,ItemFingerprint.of(new ItemStack(Items.POTATO)),9)),f.ledger));}
    @Test void missingResultCannotBeRetriedWithAnotherId(){var f=new Failure();assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,new TransferLedgerData()));assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,null));}
    @Test void missingIdentityNeedsReview(){var f=new Failure();var old=f.edited(t->t.getList("Receipts",Tag.TAG_COMPOUND).getCompound(0).remove("RequestIdentity"));assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,old));}
    @Test void actualAndAttemptCannotBeTransplanted(){var f=new Failure();f.attempt.putUUID("Id",UUID.randomUUID());assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,f.ledger));}
    @Test void successHiddenBehindFailedWrapperIsNotNoEffect(){var f=new Failure();var old=f.edited(t->t.getList("Receipts",Tag.TAG_COMPOUND).getCompound(0).putString("Status","SUCCESS"));assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,old));}
    @Test void anyFinalRevisionInvalidatesNoEffectEvidence(){var f=new Failure();var old=f.edited(t->{var list=t.getList("Receipts",Tag.TAG_COMPOUND).getCompound(0).getList("Revisions",Tag.TAG_COMPOUND);var x=new CompoundTag();x.putUUID("Container",f.src);x.putLong("Revision",1);list.add(x);});assertEquals(-1,f.proof(f.owner,TransferIntent.GIFT,f.legs,old));}
    @Test void otherKnownFailuresKeepOriginalPolicy(){var f=new Failure();for(var status:List.of("RIGHTS_DENIED","INSUFFICIENT_ITEMS","STALE_REVISION","INTERNAL_FAILURE")){var old=f.edited(t->t.getList("Receipts",Tag.TAG_COMPOUND).getCompound(0).putString("Status",status));assertEquals(0,f.proof(f.owner,TransferIntent.GIFT,f.legs,old));}}
    @Test void giftCapacityMatchesActualServiceBoundaryMatrix(){
        for(int q:new int[]{1,9,64,65})for(int fill:new int[]{0,55,63,64})for(int mode=0;mode<4;mode++){
            var rights=new RightsStateData();var ledger=new TransferLedgerData();UUID src=UUID.randomUUID(),dst=UUID.randomUUID();var owner=SubjectRef.household(UUID.randomUUID());var receiver=SubjectRef.character(UUID.randomUUID());
            rights.ensureContext(src,"s",owner,owner,AccessPolicy.PRIVATE);rights.ensureContext(dst,"d",receiver,receiver,AccessPolicy.PRIVATE);var a=new SimpleContainer(2);a.setItem(0,new ItemStack(Items.CARROT,64));a.setItem(1,new ItemStack(Items.CARROT,64));var b=new SimpleContainer(2);
            b.setItem(1,new ItemStack(Items.COBBLESTONE,64));if(fill>0){b.setItem(0,new ItemStack(Items.CARROT,fill));if(mode>0)rights.putClaim(new AssetClaim(UUID.randomUUID(),dst,0,ItemFingerprint.of(b.getItem(0)),fill,mode==1?owner:receiver,receiver,mode==2?Set.of("loan"):Set.of(),Set.of(),mode==3?"different-provenance":"container_default",0));}
            var before=rights.save(new CompoundTag(),null);int space=SettlementGrowthData.foodGiftSpace(rights,dst,b);assertEquals(before,rights.save(new CompoundTag(),null));
            var request=new TransferRequest(UUID.randomUUID(),owner,TransferIntent.GIFT,List.of(new TransferLeg(src,dst,ItemFingerprint.of(new ItemStack(Items.CARROT)),q)),List.of(new ExpectedRevision(src,0),new ExpectedRevision(dst,0)),"v5合法建设或饲料领取",10);
            var result=new TransferService(rights,ledger).submit(request,Map.of(src,new TransferEndpoint(src,a),dst,new TransferEndpoint(dst,b)),10);assertEquals(space>=q,result.successful(),"q="+q+" fill="+fill+" mode="+mode);
        }
    }
    @Test void corruptDestinationClaimIsNotFreeSpace(){var r=new RightsStateData();UUID d=UUID.randomUUID();var owner=SubjectRef.character(UUID.randomUUID());r.ensureContext(d,"d",owner,owner,AccessPolicy.PRIVATE);var bag=new SimpleContainer(1);bag.setItem(0,new ItemStack(Items.CARROT,1));r.putClaim(new AssetClaim(UUID.randomUUID(),d,0,ItemFingerprint.of(bag.getItem(0)),2,owner,owner,Set.of(),Set.of(),"container_default",0));assertEquals(-1,SettlementGrowthData.foodGiftSpace(r,d,bag));}
}
