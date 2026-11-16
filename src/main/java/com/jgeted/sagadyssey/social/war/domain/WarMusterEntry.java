package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Administrative references only. Persons, consent, labor, equipment and money remain in their original systems. */
public sealed interface WarMusterEntry permits WarMusterEntry.Army,WarMusterEntry.Unit,WarMusterEntry.Offer {
    UUID id();
    long revision();
    record Army(UUID id,UUID owner,String name,long createdAt) implements WarMusterEntry {
        public Army { Objects.requireNonNull(id);Objects.requireNonNull(owner);checkName(name);if(createdAt<0||!id.equals(armyId(owner,name)))throw new IllegalArgumentException("invalid army"); }
        public long revision(){return 1;}
    }
    record Unit(UUID id,UUID army,String name,WarKey profile,int definitionVersion,boolean recorded,long revision) implements WarMusterEntry {
        public Unit {Objects.requireNonNull(id);Objects.requireNonNull(army);Objects.requireNonNull(profile);checkName(name);
            if(!id.equals(unitId(army,name))||definitionVersion<1||revision!=(recorded?2:1))throw new IllegalArgumentException("invalid muster unit");}
        public Unit acknowledged(){return recorded?this:new Unit(id,army,name,profile,definitionVersion,true,2);}
    }
    enum Stage { PROPOSED,DECLINED,GRANTING,PREPARING,ACTIVE,RELEASING,ENDED,REVIEW }
    record Offer(WarMilitiaRequest request,UUID body,UUID parent,long parentRevision,String dimension,UUID workplace,long endsAt,
                 double probability,double draw,Stage stage,long revision,long changedAt,String reason) implements WarMusterEntry {
        public Offer {
            Objects.requireNonNull(request);Objects.requireNonNull(body);Objects.requireNonNull(parent);Objects.requireNonNull(dimension);Objects.requireNonNull(stage);Objects.requireNonNull(reason);
            if(parentRevision<0||dimension.isBlank()||dimension.length()>256||endsAt<=request.requestedAtTick()||endsAt-request.requestedAtTick()>30L*24000
                    ||!Double.isFinite(probability)||probability<0||probability>1||!Double.isFinite(draw)||draw<0||draw>=1
                    ||revision<1||changedAt<request.requestedAtTick()||reason.length()>256||stage==Stage.PROPOSED&&revision!=1
                    ||stage==Stage.DECLINED&&draw<probability||Set.of(Stage.GRANTING,Stage.PREPARING,Stage.ACTIVE).contains(stage)&&draw>=probability
                    ||!com.jgeted.sagadyssey.social.war.domain.WarServiceRoles.supported(request))throw new IllegalArgumentException("invalid service offer");
        }
        public UUID id(){return request.id();}
        public boolean accepted(){return draw<probability;}
        public boolean terminal(){return stage==Stage.DECLINED||stage==Stage.ENDED||stage==Stage.REVIEW;}
        public boolean unresolved(){return !terminal()||stage==Stage.REVIEW;}
        public boolean reserves(long now){return unresolved()||now<changedAt+1200;}
        public Offer advance(Stage next,long now,String why){
            if(!transition(stage,next)||revision==Long.MAX_VALUE||now<changedAt)throw new IllegalArgumentException("invalid muster transition");
            return new Offer(request,body,parent,parentRevision,dimension,workplace,endsAt,probability,draw,next,revision+1,now,why);
        }
        private static boolean transition(Stage from,Stage to){
            if(from==Stage.DECLINED||from==Stage.ENDED||from==Stage.REVIEW)return false;
            if(to==Stage.REVIEW||to==Stage.RELEASING)return true;
            return switch(from){case PROPOSED->to==Stage.DECLINED||to==Stage.GRANTING;case GRANTING->to==Stage.PREPARING;
                case PREPARING->to==Stage.ACTIVE;case RELEASING->to==Stage.ENDED;default->false;};
        }
    }
    static boolean follows(WarMusterEntry before,WarMusterEntry next){
        if(before.equals(next))return true;
        if(before instanceof Unit a && next instanceof Unit b)return !a.recorded&&b.recorded&&a.acknowledged().equals(b);
        if(before instanceof Offer a && next instanceof Offer b){
            try{return a.advance(b.stage,b.changedAt,b.reason).equals(b);}catch(IllegalArgumentException ignored){return false;}
        }
        return false;
    }
    static UUID armyId(UUID owner,String name){checkName(name);return named("army/"+owner+"/"+name);}
    static UUID unitId(UUID army,String name){checkName(name);return named("unit/"+army+"/"+name);}
    static UUID named(String value){return UUID.nameUUIDFromBytes(("sagadyssey/war/muster/v1/"+value).getBytes(StandardCharsets.UTF_8));}
    private static void checkName(String name){if(name==null||!name.matches("[\\p{L}\\p{N}_-]{1,24}"))throw new IllegalArgumentException("名称限1至24个文字、数字、下划线或短横线");}
}
