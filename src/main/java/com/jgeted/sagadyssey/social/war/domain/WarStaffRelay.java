package com.jgeted.sagadyssey.social.war.domain;
import java.util.*;

/** One exact owner-approved rally at an existing fixed banner, never an open-ended command grant. */
public record WarStaffRelay(UUID id,UUID appointment,WarCommandPost post,long generation,long unitRevision,long createdAt,long deadline,
                            Phase phase,long revision,long changedAt,String reason){
    public enum Phase { QUEUED,ISSUING,SENT,CANCELLED,REVIEW }
    public WarStaffRelay {
        Objects.requireNonNull(id);Objects.requireNonNull(appointment);Objects.requireNonNull(post);Objects.requireNonNull(phase);
        if(generation<0||unitRevision<1||createdAt<0||deadline<=createdAt||deadline-createdAt>200||revision<1||changedAt<createdAt||reason==null||reason.length()>256||phase==Phase.QUEUED&&revision!=1)throw new IllegalArgumentException("invalid staff relay");
    }
    public boolean terminal(){return phase==Phase.SENT||phase==Phase.CANCELLED||phase==Phase.REVIEW;}
    public boolean mayIssue(long now,long currentRevision,boolean currentAuthority){return currentAuthority&&now>=createdAt&&now<deadline&&unitRevision==currentRevision;}
    public WarStaffRelay advance(Phase next,long now,String why){
        if(terminal()||now<changedAt||revision==Long.MAX_VALUE||phase==Phase.QUEUED&&next!=Phase.ISSUING&&next!=Phase.CANCELLED&&next!=Phase.REVIEW||phase==Phase.ISSUING&&next==Phase.QUEUED)throw new IllegalArgumentException("invalid relay transition");
        return new WarStaffRelay(id,appointment,post,generation,unitRevision,createdAt,deadline,next,revision+1,now,why);
    }
    public static boolean follows(WarStaffRelay a,WarStaffRelay b){if(a.equals(b))return true;try{return a.advance(b.phase,b.changedAt,b.reason).equals(b);}catch(IllegalArgumentException e){return false;}}
}
