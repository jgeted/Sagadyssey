package com.jgeted.sagadyssey.worldsim.lod;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class RemoteCropClockTest {
    static class Farm implements RemoteCropClock.Port {
        int stock,output,seeds,steps;boolean worker=true,land=true;
        public boolean growing(int p){return land;}
        public boolean harvestAndReplant(int p,long t){if(!worker)return false;stock+=3;output+=4;seeds++;return true;}
        public boolean plant(int p,long t){if(!worker||stock==0)return false;stock--;seeds++;return true;}
        public void finishStep(long f,long t){steps++;}
    }
    @Test void absentWorkerCannotManufactureHarvestButCropsStillGrow(){var f=new Farm();f.worker=false;var c=new RemoteCropClock(0,new int[12]);for(int i=0;i<220;i++)c.advance(44000,1,f);assertEquals(7,c.age(0));assertEquals(0,f.stock);assertEquals(0,c.produced());}
    @Test void fullCycleHasExactInputsAndOutputs(){var f=new Farm();var c=new RemoteCropClock(0,new int[12]);for(int i=0;i<210;i++)c.advance(42000,1,f);assertEquals(48,f.output);assertEquals(12,f.seeds);assertEquals(36,f.stock);assertEquals(48,c.produced());assertEquals(12,c.planted());assertEquals(0,c.age(0));}
    @Test void emptyPlotRequiresRealSeedAndValidLand(){var ages=new int[12];Arrays.fill(ages,-1);var c=new RemoteCropClock(0,ages);var f=new Farm();c.advance(200,1,f);assertEquals(-1,c.age(0));f.stock=1;f.land=false;c.advance(400,1,f);assertEquals(1,f.stock);f.land=true;c.advance(600,1,f);assertEquals(0,f.stock);assertEquals(1,c.planted());assertEquals(0,c.produced());}
    @Test void budgetLeavesBacklogAndRepeatedTimeDoesNotRepeatWork(){var c=new RemoteCropClock(0,new int[12]);var f=new Farm();assertEquals(2,c.advance(10000,2,f));assertEquals(400,c.settledAt());assertEquals(48,c.backlog(10000));while(c.backlog(10000)>0)c.advance(10000,2,f);int steps=f.steps;assertEquals(0,c.advance(10000,2,f));assertEquals(steps,f.steps);assertEquals(0,c.advance(0,2,f));assertEquals(10000,c.settledAt());}
    @Test void reloadAndContinuousRunHaveIdenticalReceiptsClockAndStock(){var a=new RemoteCropClock(0,new int[12]);var b=new RemoteCropClock(0,new int[12]);var f=new Farm();var g=new Farm();for(int i=0;i<1200;i++){a.advance(240000,1,f);b.advance(240000,1,g);if(i==599)b=RemoteCropClock.load(b.save());}assertEquals(a.save(),b.save());assertEquals(f.stock,g.stock);assertEquals(f.output,g.output);assertEquals(f.seeds,g.seeds);}
    @Test void missingCheckpointCannotSilentlySettleAgainFromZero(){var t=new RemoteCropClock(240000,new int[12]).save();t.remove("At");assertThrows(IllegalArgumentException.class,()->RemoteCropClock.load(t));t.putString("At","240000");assertThrows(IllegalArgumentException.class,()->RemoteCropClock.load(t));}

}
