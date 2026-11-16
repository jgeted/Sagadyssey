package com.jgeted.sagadyssey.social.war.domain;
import com.jgeted.sagadyssey.social.war.api.WarIntent;
import java.util.*;

/** Stable bottom-up construction/top-down excavation; preview is bounded before enumerating any cell. */
public final class WarEngineeringGeometry{
    private WarEngineeringGeometry(){}
    public static List<WarIntent.Position> box(WarIntent.Position a,WarIntent.Position b,WarEngineeringJob.Mode mode){
        for(var p:List.of(a,b))if(Math.abs((long)p.x())>30_000_000||Math.abs((long)p.z())>30_000_000||p.y()< -2048||p.y()>2047)throw new IllegalArgumentException("engineering coordinate exceeds world bounds");
        if(!a.dimension().equals(b.dimension()))throw new IllegalArgumentException("engineering crosses dimensions");
        long dx=Math.abs((long)a.x()-b.x())+1,dy=Math.abs((long)a.y()-b.y())+1,dz=Math.abs((long)a.z()-b.z())+1;
        if(dx>64||dy>8||dz>64||dx*dy*dz>64)throw new IllegalArgumentException("first work order is limited to64cells and8height");
        var cells=new ArrayList<WarIntent.Position>();int minY=Math.min(a.y(),b.y()),maxY=Math.max(a.y(),b.y());
        for(int i=0;i<dy;i++){int y=mode==WarEngineeringJob.Mode.BUILD?minY+i:maxY-i;for(int x=Math.min(a.x(),b.x());x<=Math.max(a.x(),b.x());x++)for(int z=Math.min(a.z(),b.z());z<=Math.max(a.z(),b.z());z++)cells.add(new WarIntent.Position(a.dimension(),x,y,z));}
        return List.copyOf(cells);
    }
}
