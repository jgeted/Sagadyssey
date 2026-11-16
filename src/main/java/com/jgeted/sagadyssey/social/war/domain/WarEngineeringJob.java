package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** Approved finite work, original people and opaque native block identities. No inventory or civil-plan copies. */
public record WarEngineeringJob(UUID id,UUID army,UUID owner,UUID service,UUID person,UUID body,Mode mode,
                                Optional<WarKey> material,List<Cell> cells,Phase phase,int next,Set<Integer> adopted,
                                long createdAt,long revision,long changedAt,boolean retireAfter,String reason,
                                Optional<WarEngineeringProgram> program){
    public static final long NAMESPACE=0x5341474157415236L;
    public static final String UNOBSERVED="sagadyssey:unobserved_work_face";
    public enum Mode { BUILD,DIG }
    public enum Phase { ACTIVE,PAUSED,ISSUING,REVIEW,DONE,CANCELLED }
    public record Cell(WarIntent.Position position,String before,String after,int workTicks){
        public Cell{Objects.requireNonNull(position);text(before,1024);text(after,1024);if(Math.abs((long)position.x())>30_000_000||Math.abs((long)position.z())>30_000_000||position.y()< -2048||position.y()>2047||before.equals(after)||after.equals(UNOBSERVED)||workTicks<1||workTicks>24000)throw new IllegalArgumentException("invalid engineering cell");}
    }
    public WarEngineeringJob(UUID id,UUID army,UUID owner,UUID service,UUID person,UUID body,Mode mode,
            Optional<WarKey> material,List<Cell> cells,Phase phase,int next,Set<Integer> adopted,long createdAt,
            long revision,long changedAt,boolean retireAfter,String reason){
        this(id,army,owner,service,person,body,mode,material,cells,phase,next,adopted,createdAt,revision,changedAt,retireAfter,reason,Optional.empty());
    }
    public WarEngineeringJob{
        Objects.requireNonNull(program);
        for(var x:List.of(id,army,owner,service,person,body,mode,material,phase))Objects.requireNonNull(x);cells=List.copyOf(cells);adopted=Set.copyOf(adopted);text(reason,256);
        if(id.getMostSignificantBits()!=NAMESPACE||cells.isEmpty()||cells.size()>64||next<0||next>cells.size()||createdAt<0||changedAt<createdAt||revision<1||(mode==Mode.BUILD)!=material.isPresent()||program.isEmpty()&&new HashSet<>(cells.stream().map(Cell::position).toList()).size()!=cells.size()||cells.stream().map(c->c.position().dimension()).distinct().count()!=1||adopted.stream().anyMatch(i->i<0||i>=next)||(phase==Phase.DONE)!=(next==cells.size()))throw new IllegalArgumentException("invalid finite engineering work");
        if(program.isPresent()){
            program.get().validate(cells,army);var first=program.get().steps().getFirst();
            if(mode!=first.mode()||!material.equals(first.material()))throw new IllegalArgumentException("template classification differs");
            for(int i=next+1;i<cells.size();i++)if(!cells.get(i).before().equals(UNOBSERVED))throw new IllegalArgumentException("future work face cannot be observed");
        }
        if(program.isEmpty()&&cells.stream().anyMatch(c->c.before().equals(UNOBSERVED))
                ||phase==Phase.ISSUING&&cells.get(next).before().equals(UNOBSERVED))throw new IllegalArgumentException("physical intent requires an observed face");
        for(int i=0;i<next;i++)if(!adopted.contains(i)&&cells.get(i).before().equals(UNOBSERVED))throw new IllegalArgumentException("unobserved effect cannot be complete");
    }
    public Mode modeAt(int i){return program.map(p->p.steps().get(i).mode()).orElse(mode);}
    public Optional<WarKey> materialAt(int i){return program.isPresent()?program.get().steps().get(i).material():material;}
    public Mode stepMode(){return modeAt(next);}
    public Optional<WarKey> stepMaterial(){return materialAt(next);}
    public boolean unobserved(){return !terminal()&&cells.get(next).before().equals(UNOBSERVED);}
    public WarEngineeringJob observeCell(Cell seen,long now,String detail){
        if(phase!=Phase.ACTIVE||program.isEmpty()||!unobserved()||seen.before().equals(UNOBSERVED)
                ||!seen.position().equals(cells.get(next).position())||!seen.after().equals(cells.get(next).after())
                ||now<changedAt||revision==Long.MAX_VALUE)throw new IllegalArgumentException("invalid exposed work observation");
        var values=new ArrayList<>(cells);values.set(next,seen);
        return new WarEngineeringJob(id,army,owner,service,person,body,mode,material,values,phase,next,adopted,createdAt,revision+1,now,retireAfter,detail,program);
    }
    public boolean terminal(){return phase==Phase.DONE||phase==Phase.CANCELLED;}
    public WarEngineeringJob change(Phase value,long now,String detail){
        boolean allowed=phase==Phase.ACTIVE&&(value==Phase.PAUSED||value==Phase.ISSUING||value==Phase.CANCELLED)
                ||phase==Phase.PAUSED&&(value==Phase.ACTIVE||value==Phase.CANCELLED)||!terminal()&&phase!=Phase.REVIEW&&value==Phase.REVIEW;
        if(!allowed)throw new IllegalArgumentException("invalid engineering phase");return copy(value,next,adopted,now,retireAfter,detail);
    }
    public WarEngineeringJob completeCell(long now,String detail){if(phase!=Phase.ISSUING)throw new IllegalArgumentException("effect intent required");return copy(next+1==cells.size()?Phase.DONE:Phase.ACTIVE,next+1,adopted,now,retireAfter,detail);}
    public WarEngineeringJob adoptCell(long now,String detail){if(phase!=Phase.ACTIVE)throw new IllegalArgumentException("cannot adopt unresolved effect");var set=new HashSet<>(adopted);set.add(next);return copy(next+1==cells.size()?Phase.DONE:Phase.ACTIVE,next+1,set,now,retireAfter,detail);}
    public WarEngineeringJob retiring(long now){if(retireAfter)throw new IllegalArgumentException("retirement already recorded");return copy(phase,next,adopted,now,true,"请求退役：停止新施工，保留已建部分与未明提交");}
    /** Application must prove that this session has not invoked the physical transaction. Never used on cold recovery. */
    public WarEngineeringJob cancelUnissued(long now,String reason){if(phase!=Phase.ISSUING)throw new IllegalArgumentException("no prepared effect");return copy(Phase.CANCELLED,next,adopted,now,retireAfter,reason);}
    public WarEngineeringJob notApplied(long now,String reason){if(phase!=Phase.ISSUING)throw new IllegalArgumentException("no prepared effect");return copy(Phase.ACTIVE,next,adopted,now,retireAfter,reason);}
    private WarEngineeringJob copy(Phase p,int at,Set<Integer> skipped,long now,boolean retire,String detail){if(now<changedAt||revision==Long.MAX_VALUE)throw new IllegalArgumentException("engineering clock/revision reversed");return new WarEngineeringJob(id,army,owner,service,person,body,mode,material,cells,p,at,skipped,createdAt,revision+1,now,retire,detail,program);}
    public static boolean follows(WarEngineeringJob a,WarEngineeringJob b){
        if(a.equals(b))return true;
        try{if(a.observeCell(b.cells.get(a.next),b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException|IndexOutOfBoundsException ignored){}
        try{if(!a.retireAfter&&b.retireAfter&&a.retiring(b.changedAt).equals(b))return true;}catch(IllegalArgumentException ignored){}
        try{if(a.change(b.phase,b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}
        try{if(a.completeCell(b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}
        try{if(b.phase==Phase.CANCELLED&&a.cancelUnissued(b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}
        try{if(b.phase==Phase.ACTIVE&&a.notApplied(b.changedAt,b.reason).equals(b))return true;}catch(IllegalArgumentException ignored){}
        try{return a.adoptCell(b.changedAt,b.reason).equals(b);}catch(IllegalArgumentException ignored){return false;}
    }
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max)throw new IllegalArgumentException("invalid work evidence text");}
}
