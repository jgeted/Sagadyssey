package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** Bounded low-arc aim for this build's native arrow air inertia. The arrow still performs all flight/collision/damage. */
public final class WarArrowAim12111 {
    private static final double INERTIA=(double).99F;
    private WarArrowAim12111(){}
    public static Optional<Vec3> velocity(double dx,double dy,double dz,double speed,double gravity){
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(dz)||!Double.isFinite(speed)||!Double.isFinite(gravity)||speed<=0||gravity<0)return Optional.empty();
        double lo=.001,hi=lo;
        // First crossing is the low arc. No unbounded iteration and no world prediction/scanning.
        for(int i=1;i<=160;i++){
            hi=i*.25;if(at(dx,dy,dz,hi,gravity).lengthSqr()<=speed*speed){
                for(int n=0;n<32;n++){double mid=(lo+hi)/2;if(at(dx,dy,dz,mid,gravity).lengthSqr()>speed*speed)lo=mid;else hi=mid;}
                return Optional.of(at(dx,dy,dz,hi,gravity));
            }lo=hi;
        }return Optional.empty();
    }
    private static Vec3 at(double x,double y,double z,double time,double gravity){
        double sum=(1-Math.pow(INERTIA,time))/(1-INERTIA),fall=gravity/(1-INERTIA)*(time-sum);
        return new Vec3(x/sum,(y+fall)/sum,z/sum);
    }
}
