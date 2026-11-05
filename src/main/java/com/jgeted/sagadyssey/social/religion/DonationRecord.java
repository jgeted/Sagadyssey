package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import java.util.*;

/** Persisted consent and transfer payload. A named purpose chooses a distinct real store. */
public record DonationRecord(UUID id,UUID temple,UUID donor,UUID source,UUID destination,
                             ItemFingerprint item,int quantity,Purpose purpose,Status status,
                             long sourceRevision,long targetRevision,long createdAt,long updatedAt,String detail){
    public enum Purpose{ALMS,GENERAL}
    public enum Status{PENDING,RECORDED,FAILED,REVIEW}
    public DonationRecord{for(Object x:List.of(id,temple,donor,source,destination,item,purpose,status))Objects.requireNonNull(x);
        if(source.equals(destination)||quantity<1||quantity>1728||sourceRevision<0||targetRevision<0||createdAt<0||updatedAt<createdAt||detail==null||detail.length()>2048)throw new IllegalArgumentException("invalid donation");}
    public DonationRecord result(Status next,long now,String reason){return new DonationRecord(id,temple,donor,source,destination,item,quantity,purpose,next,sourceRevision,targetRevision,createdAt,now,reason);}
}
