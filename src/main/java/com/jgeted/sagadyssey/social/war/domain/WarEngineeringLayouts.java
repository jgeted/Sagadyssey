package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;
import static com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob.Mode.*;
import static com.jgeted.sagadyssey.social.war.domain.WarEngineeringProgram.Kind.*;

/** Geometry only: no underground blocks, entities, inventories, or native registries are queried. */
public final class WarEngineeringLayouts{
    public record Layout(WarEngineeringProgram program,List<WarIntent.Position> targets){
        public Layout{targets=List.copyOf(targets);if(targets.size()!=program.steps().size())throw new IllegalArgumentException("geometry mismatch");}
    }
    private WarEngineeringLayouts(){}
    public static Layout trap(WarEngineeringProgram.Kind kind,WarIntent.Position a,WarIntent.Position b,int side){
        if(kind!=SPIKE_PIT&&kind!=WEB_SNARE||side!=1&&side!=-1||a.y()!=b.y())throw new IllegalArgumentException("invalid trap line");
        var points=line(a,b,true,8);int hx=Integer.compare(b.x(),a.x()),hz=Integer.compare(b.z(),a.z());if(hx==0&&hz==0)hx=1;
        var targets=new ArrayList<WarIntent.Position>();var steps=new ArrayList<WarEngineeringProgram.Step>();
        for(var p:points){var stand=offset(p,-hz*side,kind==SPIKE_PIT?1:0,hx*side);
            if(kind==SPIKE_PIT){add(targets,steps,p,DIG,null,stand);add(targets,steps,offset(p,0,-1,0),DIG,null,stand);add(targets,steps,offset(p,0,-1,0),BUILD,"minecraft:pointed_dripstone",stand);}
            else add(targets,steps,p,BUILD,"minecraft:cobweb",stand);
        }
        return layout(kind,targets,steps,Optional.empty());
    }
    public static Layout tunnel(WarIntent.Position entrance,WarIntent.Position via,WarIntent.Position exit){
        if(entrance.y()!=via.y()||entrance.y()!=exit.y())throw new IllegalArgumentException("first tunnel is horizontal with one optional bend");
        var route=new ArrayList<WarIntent.Position>();route.addAll(line(entrance,via,false,16));route.addAll(line(via,exit,false,16));
        if(route.isEmpty()||route.size()>16||new HashSet<>(route).size()!=route.size()||route.contains(entrance))throw new IllegalArgumentException("route must be finite and cannot loop");
        var targets=new ArrayList<WarIntent.Position>();var steps=new ArrayList<WarEngineeringProgram.Step>();var stand=entrance;
        for(var p:route){
            add(targets,steps,offset(p,0,1,0),DIG,null,stand);
            add(targets,steps,p,DIG,null,stand);
            add(targets,steps,offset(p,0,2,0),DIG,null,stand);
            add(targets,steps,offset(p,0,2,0),BUILD,"minecraft:oak_planks",stand);stand=p;
        }
        return layout(TUNNEL,targets,steps,Optional.empty());
    }
    public static Layout disarm(WarHazardObservation seen,WarIntent.Position stand){
        return layout(DISARM,List.of(seen.position()),List.of(new WarEngineeringProgram.Step(DIG,Optional.empty(),stand)),Optional.of(seen));
    }
    private static Layout layout(WarEngineeringProgram.Kind kind,List<WarIntent.Position> targets,List<WarEngineeringProgram.Step> steps,Optional<WarHazardObservation> evidence){
        return new Layout(new WarEngineeringProgram(kind,1,steps,evidence),targets);
    }
    private static void add(List<WarIntent.Position> targets,List<WarEngineeringProgram.Step> steps,WarIntent.Position p,WarEngineeringJob.Mode mode,String item,WarIntent.Position stand){
        targets.add(p);steps.add(new WarEngineeringProgram.Step(mode,Optional.ofNullable(item).map(WarKey::parse),stand));
    }
    public static WarIntent.Position offset(WarIntent.Position p,int x,int y,int z){
        long a=(long)p.x()+x,b=(long)p.y()+y,c=(long)p.z()+z;
        if(Math.abs(a)>30_000_000||Math.abs(c)>30_000_000||b< -2048||b>2047)throw new IllegalArgumentException("work geometry outside supported world");
        return new WarIntent.Position(p.dimension(),(int)a,(int)b,(int)c);
    }
    private static List<WarIntent.Position> line(WarIntent.Position a,WarIntent.Position b,boolean includeStart,int maximum){
        if(!a.dimension().equals(b.dimension())||a.y()!=b.y()||a.x()!=b.x()&&a.z()!=b.z())throw new IllegalArgumentException("route segments must share one dimension and be axis aligned");
        long distance=Math.abs((long)b.x()-a.x())+Math.abs((long)b.z()-a.z());
        if(distance+(includeStart?1:0)>maximum)throw new IllegalArgumentException("work route exceeds capacity");
        int dx=Integer.compare(b.x(),a.x()),dz=Integer.compare(b.z(),a.z());var out=new ArrayList<WarIntent.Position>();
        for(int i=includeStart?0:1;i<=distance;i++)out.add(offset(a,i*dx,0,i*dz));return List.copyOf(out);
    }
}
