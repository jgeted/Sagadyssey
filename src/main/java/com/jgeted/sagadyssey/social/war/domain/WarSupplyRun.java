package com.jgeted.sagadyssey.social.war.domain;
import java.util.*;

/** Bounded, explicitly approved trips. Cargo and receipts remain in original physical owners. */
public record WarSupplyRun(UUID id,UUID army,UUID owner,UUID service,UUID person,UUID body,int quantity,int trips,long createdAt,long deadline,
                           Phase phase,int delivered,int attempt,long revision,long changedAt,boolean retireAfter,String reason){
    public enum Phase { TO_SOURCE,LOADING,TO_TARGET,UNLOADING,RETURNING,RETURN_UNLOAD,DONE,CANCELLED,REVIEW }
    public WarSupplyRun {
        for(var v:List.of(id,army,owner,service,person,body))Objects.requireNonNull(v);
        Objects.requireNonNull(phase);Objects.requireNonNull(reason);
        if(quantity<1||quantity>64||trips<1||trips>8||createdAt<0||deadline<=createdAt||deadline-createdAt>7L*24000||delivered<0||delivered>trips||attempt<0||attempt>64||revision<1||changedAt<createdAt||reason.length()>256||phase==Phase.DONE&&delivered!=trips)throw new IllegalArgumentException("invalid supply run");
    }
    public boolean terminal(){return phase==Phase.DONE||phase==Phase.CANCELLED;}
    public boolean custody(){return phase==Phase.TO_TARGET||phase==Phase.UNLOADING||phase==Phase.RETURNING||phase==Phase.RETURN_UNLOAD||phase==Phase.REVIEW;}
    public WarSupplyRun advance(Phase next,long now,String why){
        boolean allowed=next==Phase.REVIEW&&!terminal()||switch(phase){
            case TO_SOURCE->next==Phase.LOADING||next==Phase.CANCELLED;
            case LOADING->next==Phase.TO_TARGET||next==Phase.CANCELLED;
            case TO_TARGET->next==Phase.UNLOADING||next==Phase.RETURNING;
            case UNLOADING->next==Phase.TO_SOURCE||next==Phase.DONE||next==Phase.RETURNING;
            case RETURNING->next==Phase.RETURN_UNLOAD;
            case RETURN_UNLOAD->next==Phase.CANCELLED;
            default->false;
        };
        if(!allowed||now<changedAt||revision==Long.MAX_VALUE)throw new IllegalArgumentException("invalid supply transition");
        int count=delivered+(phase==Phase.UNLOADING&&(next==Phase.TO_SOURCE||next==Phase.DONE)?1:0);
        if(next==Phase.TO_SOURCE&&count>=trips||next==Phase.DONE&&count!=trips)throw new IllegalArgumentException("trip count mismatch");
        return new WarSupplyRun(id,army,owner,service,person,body,quantity,trips,createdAt,deadline,next,count,attempt,revision+1,now,retireAfter,why);
    }
    public WarSupplyRun attempted(long now,String why){if(terminal()||phase==Phase.REVIEW||attempt==64||now<changedAt)throw new IllegalArgumentException("attempt limit");return new WarSupplyRun(id,army,owner,service,person,body,quantity,trips,createdAt,deadline,phase,delivered,attempt+1,revision+1,now,retireAfter,why);}
    public WarSupplyRun retirement(long now){if(terminal()||now<changedAt)throw new IllegalArgumentException("terminal run");return new WarSupplyRun(id,army,owner,service,person,body,quantity,trips,createdAt,deadline,phase,delivered,attempt,revision+1,now,true,"退役请求保留原货物责任，先返回来源");}
    public static boolean follows(WarSupplyRun a,WarSupplyRun b){if(a.equals(b))return true;try{if(a.advance(b.phase,b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}try{if(a.attempted(b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}try{return !a.retireAfter&&a.retirement(b.changedAt).equals(b);}catch(IllegalArgumentException ignored){return false;}}
}
