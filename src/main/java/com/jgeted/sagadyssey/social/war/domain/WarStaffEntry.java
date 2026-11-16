package com.jgeted.sagadyssey.social.war.domain;

import java.util.*;

/** Staffing evidence and links to shared institution offices, never a second office authority. */
public sealed interface WarStaffEntry permits WarStaffEntry.Establishment,WarStaffEntry.Appointment {
    UUID id();
    long revision();
    record Establishment(UUID id,UUID owner,long revision,long reviewedAt,Map<UUID,Integer> combatants) implements WarStaffEntry {
        public Establishment {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);combatants=Map.copyOf(combatants);
            if(revision<1||reviewedAt<0||combatants.size()>128||combatants.values().stream().anyMatch(n->n<0||n>1024)
                    ||combatants.values().stream().mapToLong(Integer::longValue).sum()>1024)throw new IllegalArgumentException("invalid staffing establishment");
        }
        public int strength(){return combatants.values().stream().mapToInt(Integer::intValue).sum();}
    }
    enum Role {
        TEMPORARY_DEPUTY("临时副手",true),DEPUTY("随队副手",true),CAPTAIN("兵团队长",true),
        STAFF_OFFICER("专职军务官",false),STANDARD_BEARER("专职旗手",false),QUARTERMASTER("军需负责人",false),ENGINEERING_LEAD("工程负责人",false);
        private final String title;private final boolean partTime;
        Role(String title,boolean partTime){this.title=title;this.partTime=partTime;}
        public String title(){return title;}
        public boolean partTime(){return partTime;}
    }
    enum Mode { REGULAR,ACTING,EXCEPTIONAL }
    enum Phase { PREPARING,ACTIVE,REVOKING,REVOKED,REVIEW }
    record Terms(UUID id,UUID army,UUID owner,UUID holder,UUID service,Role role,Optional<UUID> unit,
                 long establishmentRevision,int combatants,int unitCombatants,int policyVersion,long startsAt,long endsAt,Mode mode,String reason,
                 String socialClass,String profession,int experience) {
        public Terms {
            Objects.requireNonNull(id);Objects.requireNonNull(army);Objects.requireNonNull(owner);Objects.requireNonNull(holder);Objects.requireNonNull(service);
            Objects.requireNonNull(role);Objects.requireNonNull(unit);Objects.requireNonNull(mode);
            if(establishmentRevision<1||combatants<1||combatants>1024||unitCombatants<0||unitCombatants>combatants||policyVersion!=1||startsAt<0||endsAt<=startsAt||endsAt-startsAt>30L*24000||experience<0
                    ||(role==Role.CAPTAIN)!=unit.isPresent())throw new IllegalArgumentException("invalid appointment terms");
            text(reason,256);text(socialClass,64);text(profession,64);
        }
        public boolean activeAt(long now){return now>=startsAt&&now<endsAt;}
        /** Deputy upgrades share one seat, rather than creating a second assistant. */
        public String seat(){return army+"/"+(role==Role.CAPTAIN?"captain/"+unit.orElseThrow():role==Role.TEMPORARY_DEPUTY||role==Role.DEPUTY?"deputy":role.name());}
    }
    record Appointment(Terms terms,Phase phase,long revision,long changedAt,String detail) implements WarStaffEntry {
        public Appointment {
            Objects.requireNonNull(terms);Objects.requireNonNull(phase);text(detail,256);
            if(revision<1||changedAt<terms.startsAt()||phase==Phase.PREPARING&&revision!=1)throw new IllegalArgumentException("invalid appointment state");
        }
        public UUID id(){return terms.id();}
        public boolean reserves(){return phase!=Phase.REVOKED;}
        public Appointment advance(Phase next,long now,String reason){
            boolean allowed=phase==Phase.PREPARING&&(next==Phase.ACTIVE||next==Phase.REVOKING)||phase==Phase.ACTIVE&&next==Phase.REVOKING
                    ||phase==Phase.REVOKING&&next==Phase.REVOKED||phase!=Phase.REVOKED&&phase!=Phase.REVIEW&&next==Phase.REVIEW;
            if(!allowed||now<changedAt||revision==Long.MAX_VALUE)throw new IllegalArgumentException("invalid appointment transition");
            return new Appointment(terms,next,revision+1,now,reason);
        }
    }
    static boolean follows(WarStaffEntry a,WarStaffEntry b){
        if(a.equals(b))return true;
        if(a instanceof Establishment x&&b instanceof Establishment y)return x.id().equals(y.id())&&x.owner().equals(y.owner())&&x.revision()!=Long.MAX_VALUE&&y.revision()==x.revision()+1&&y.reviewedAt()>=x.reviewedAt();
        if(a instanceof Appointment x&&b instanceof Appointment y)try{return x.advance(y.phase(),y.changedAt(),y.detail()).equals(y);}catch(IllegalArgumentException ignored){}
        return false;
    }
    private static void text(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum)throw new IllegalArgumentException("invalid staffing evidence text");}
}
