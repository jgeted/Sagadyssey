package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import java.util.*;

/** Declaration and witnessed review, not omniscient hunger/wealth truth. Fixed delivery identity. */
public record AlmsCase(UUID id,UUID temple,UUID recipient,ItemFingerprint item,int quantity,String statement,
                       Stage stage,UUID workerEntity,UUID workerCharacter,UUID reviewer,int attempts,
                       long createdAt,long updatedAt,long retryAt,Pending pending,String detail){
    public enum Stage{REQUESTED,APPROVED,WAITING_STOCK,RESERVED,CARRYING,ISSUED,DENIED,CANCELLED,REVIEW}
    public record Pending(UUID transfer,UUID destination,boolean pickup,long sourceRevision,long targetRevision,long time){
        public Pending{Objects.requireNonNull(transfer);Objects.requireNonNull(destination);if(sourceRevision<0||targetRevision<0||time<0)throw new IllegalArgumentException("invalid alms transaction");}
    }
    public AlmsCase{
        for(Object x:List.of(id,temple,recipient,item,stage))Objects.requireNonNull(x);
        if(quantity<1||quantity>64||statement==null||statement.isBlank()||statement.length()>512||detail==null||detail.length()>2048
                ||attempts<0||createdAt<0||updatedAt<createdAt||retryAt<0||(workerEntity==null)!=(workerCharacter==null)
                ||(stage==Stage.RESERVED||stage==Stage.CARRYING)&&workerEntity==null||pending!=null&&stage!=Stage.RESERVED&&stage!=Stage.CARRYING&&stage!=Stage.REVIEW)
            throw new IllegalArgumentException("invalid alms case");
    }
    public String token(){return "alms:"+id;}
    public boolean closed(){return stage==Stage.ISSUED||stage==Stage.DENIED||stage==Stage.CANCELLED||stage==Stage.REVIEW;}
    public AlmsCase claim(UUID entity,UUID character){if(workerEntity!=null&&!workerEntity.equals(entity))throw new IllegalStateException("alms case already claimed");
        return new AlmsCase(id,temple,recipient,item,quantity,statement,stage,entity,character,reviewer,attempts,createdAt,updatedAt,retryAt,pending,detail);}
    public AlmsCase update(Stage next,UUID review,long now,long retry,Pending transaction,String reason,boolean release){
        if(now<updatedAt)throw new IllegalArgumentException("alms clock moved backwards");
        return new AlmsCase(id,temple,recipient,item,quantity,statement,next,release?null:workerEntity,release?null:workerCharacter,review,attempts,createdAt,now,retry,transaction,reason);}
    public AlmsCase waitFor(Stage next,long now,String reason){
        if(pending!=null)throw new IllegalStateException("uncertain transfer cannot be discarded");
        return new AlmsCase(id,temple,recipient,item,quantity,statement,next,null,null,reviewer,attempts+1,createdAt,now,now+600,null,reason);
    }
}
