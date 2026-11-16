package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.*;
import java.util.*;

/** Finite mixed work; target states are bound only when an actual worker exposes that face. */
public record WarEngineeringProgram(Kind kind,int version,List<Step> steps,Optional<WarHazardObservation> evidence){
    public enum Kind {
        SPIKE_PIT,WEB_SNARE,TUNNEL,DISARM;
        public WarKey id(){return WarKey.parse("sagadyssey:war/engineering/"+name().toLowerCase(Locale.ROOT));}
    }
    public record Step(WarEngineeringJob.Mode mode,Optional<WarKey> material,WarIntent.Position stand){
        public Step{Objects.requireNonNull(mode);Objects.requireNonNull(material);Objects.requireNonNull(stand);
            if((mode==WarEngineeringJob.Mode.BUILD)!=material.isPresent())throw new IllegalArgumentException("invalid work instruction");}
    }
    public WarEngineeringProgram{
        Objects.requireNonNull(kind);Objects.requireNonNull(evidence);steps=List.copyOf(steps);
        if(version!=1||steps.isEmpty()||steps.size()>64||(kind==Kind.DISARM)!=evidence.isPresent()
                ||kind==Kind.DISARM&&(steps.size()!=1||steps.getFirst().mode()!=WarEngineeringJob.Mode.DIG))
            throw new IllegalArgumentException("unsupported engineering program");
        for(var step:steps)if(step.material().isPresent()){
            var expected=switch(kind){case SPIKE_PIT->"minecraft:pointed_dripstone";case WEB_SNARE->"minecraft:cobweb";case TUNNEL->"minecraft:oak_planks";case DISARM->"";};
            if(!step.material().get().toString().equals(expected))throw new IllegalArgumentException("foreign template material");
        }
    }
    public void validate(List<WarEngineeringJob.Cell> cells,UUID army){
        if(cells.size()!=steps.size())throw new IllegalArgumentException("program geometry differs");
        if(kind==Kind.SPIKE_PIT||kind==Kind.WEB_SNARE){
            int width=kind==Kind.SPIKE_PIT?3:1;if(cells.size()%width!=0||cells.size()/width>8)throw new IllegalArgumentException("invalid trap capacity");
            var a=cells.getFirst().position();var b=cells.get(cells.size()-width).position();int dx=Integer.compare(b.x(),a.x()),dz=Integer.compare(b.z(),a.z());if(dx==0&&dz==0)dx=1;
            var s=steps.getFirst().stand();int side=dx!=0?Integer.compare(s.z(),a.z())*dx:-Integer.compare(s.x(),a.x())*dz;
            var canonical=WarEngineeringLayouts.trap(kind,a,b,side);same(cells,canonical);
        }else if(kind==Kind.TUNNEL){
            if(cells.size()%4!=0)throw new IllegalArgumentException("incomplete tunnel section");
            var entrance=steps.getFirst().stand();var previous=entrance;WarIntent.Position via=null;int turns=0,dx=0,dz=0;
            for(int i=0;i<cells.size();i+=4){var p=cells.get(i+1).position();int x=p.x()-previous.x(),z=p.z()-previous.z();
                if(p.y()!=entrance.y()||Math.abs(x)+Math.abs(z)!=1)throw new IllegalArgumentException("disconnected tunnel");
                if(i>0&&(x!=dx||z!=dz)){turns++;via=previous;}if(turns>1)throw new IllegalArgumentException("too many tunnel bends");dx=x;dz=z;previous=p;
            }
            same(cells,WarEngineeringLayouts.tunnel(entrance,via==null?previous:via,previous));
        }
        for(int i=0;i<steps.size();i++){
            var p=cells.get(i).position();var s=steps.get(i).stand();
            if(!p.dimension().equals(s.dimension())||Math.abs((long)s.x())>30_000_000||Math.abs((long)s.z())>30_000_000
                    ||s.y()< -2048||s.y()>2047||square((long)p.x()-s.x())+square((long)p.y()-s.y())+square((long)p.z()-s.z())>36)throw new IllegalArgumentException("invalid approved work stand");
        }
        if(evidence.isPresent()&&(!evidence.get().army().equals(army)||!evidence.get().position().equals(cells.getFirst().position())))
            throw new IllegalArgumentException("foreign disarm observation");
    }
    private void same(List<WarEngineeringJob.Cell> cells,WarEngineeringLayouts.Layout canonical){
        if(!canonical.program().steps().equals(steps)||!canonical.targets().equals(cells.stream().map(WarEngineeringJob.Cell::position).toList()))throw new IllegalArgumentException("work differs from approved template");
    }
    private static long square(long n){return n*n;}
}
