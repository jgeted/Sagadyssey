package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarIntent.Position;

/** First formation policy: stable three-block slots, cardinal heading and separate peel-off lanes. */
public final class WarCavalryFormation {
    private WarCavalryFormation(){}
    public static void validate(Position a,Position b){
        long dx=(long)b.x()-a.x(),dz=(long)b.z()-a.z();double range=Math.hypot(dx,dz);
        if(!a.dimension().equals(b.dimension())||range<8||range>48||Math.abs((long)a.y()-b.y())>4)throw new IllegalArgumentException("cavalry objective requires an 8..48 block approach in one dimension");
    }
    public static int headingX(Position a,Position b){return Math.abs((long)b.x()-a.x())>=Math.abs((long)b.z()-a.z())?Integer.signum(b.x()-a.x()):0;}
    public static int headingZ(Position a,Position b){return headingX(a,b)==0?Integer.signum(b.z()-a.z()):0;}
    public static Position assembly(Position a,Position b,int slot,int count){return slot(a,a,b,slot,count,false);}
    public static Position objective(Position a,Position b,int slot,int count){return slot(b,a,b,slot,count,false);}
    public static Position exit(Position a,Position b,int slot,int count){return slot(b,a,b,slot,count,true);}
    private static Position slot(Position center,Position a,Position b,int slot,int count,boolean exit){
        if(slot<0||slot>=count||count>128)throw new IllegalArgumentException("invalid cavalry slot");validate(a,b);
        int width=Math.min(4,(int)Math.ceil(Math.sqrt(count))),side=(slot%width)*3-((width-1)*3)/2,back=(slot/width)*3;
        if(exit){side+=(slot%width<width/2?-1:1)*6;back+=4;}
        int hx=headingX(a,b),hz=headingZ(a,b);
        return new Position(center.dimension(),Math.addExact(center.x(),-hz*side-hx*back),center.y(),Math.addExact(center.z(),hx*side-hz*back));
    }
}
