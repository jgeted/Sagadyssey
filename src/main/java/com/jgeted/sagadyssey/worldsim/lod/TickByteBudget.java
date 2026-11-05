package com.jgeted.sagadyssey.worldsim.lod;

import java.util.*;

/** Hard per-viewer and whole-server byte caps, reset on a monotonic simulation tick. */
public final class TickByteBudget {
    private final int viewerLimit,serverLimit;
    private final Map<UUID,Integer> viewers=new HashMap<>();
    private long tick=-1,denied;
    private int used,peak;
    public TickByteBudget(int viewerLimit,int serverLimit){if(viewerLimit<=0||serverLimit<viewerLimit)throw new IllegalArgumentException("invalid byte limits");this.viewerLimit=viewerLimit;this.serverLimit=serverLimit;}
    public boolean admit(UUID viewer,long now,int bytes){
        Objects.requireNonNull(viewer);if(now<0||now<tick||bytes<=0)throw new IllegalArgumentException("invalid byte admission");
        if(now!=tick){tick=now;used=0;viewers.clear();}
        int own=viewers.getOrDefault(viewer,0);
        if(bytes>viewerLimit-own||bytes>serverLimit-used){denied++;return false;}
        used+=bytes;viewers.put(viewer,own+bytes);peak=Math.max(peak,used);return true;
    }
    public String summary(){return "NPC context bytes/tick="+used+", peak="+peak+", denied="+denied+", viewerLimit="+viewerLimit+", serverLimit="+serverLimit;}
}
