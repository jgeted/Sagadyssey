package com.jgeted.sagadyssey.social.war.application;

/** Temporary execution availability is separate from persistent office tenure and social identity. */
public final class WarStaffAvailability {
    private WarStaffAvailability(){}
    public record Facts(boolean alive,boolean enabled,boolean sleeping,boolean incapacitated,boolean mortalDanger,
                        double health,double maximumHealth,int hunger,long needsUpdatedAt){}
    public static boolean available(Facts f,long now){
        return now>=0&&f.alive()&&f.enabled()&&!f.sleeping()&&!f.incapacitated()&&!f.mortalDanger()
                &&Double.isFinite(f.health())&&Double.isFinite(f.maximumHealth())&&f.maximumHealth()>0&&f.health()>f.maximumHealth()*.25
                &&f.hunger()>=0&&f.hunger()<900&&f.needsUpdatedAt()>=0&&f.needsUpdatedAt()<=now;
    }
}
