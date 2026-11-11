package com.jgeted.sagadyssey.worldsim.lod;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.nbt.CompoundTag;
import java.util.Arrays;

/** Version 1 bounded biological clock. Inventory and work authority belong to the supplied port. */
public final class RemoteCropClock {
    public static final int STEP=200, STAGE_TICKS=6000, OUTPUT=4, SEED=1;
    public interface Port {
        boolean growing(int plot);
        boolean harvestAndReplant(int plot,long time);
        boolean plant(int plot,long time);
        void finishStep(long from,long until);
    }
    private long settledAt,steps,produced,planted;
    private final int[] ages,progress;
    public RemoteCropClock(long time,int[] ages){
        if(time<0||ages.length!=12)throw new IllegalArgumentException("Twelve registered crop positions required");
        this.settledAt=time;this.ages=ages.clone();this.progress=new int[12];validate();
    }
    public long settledAt(){return settledAt;}
    public long produced(){return produced;}
    public long planted(){return planted;}
    public int age(int i){return ages[i];}
    public long backlog(long now){return Math.max(0,now-settledAt)/STEP;}
    public int advance(long until,int budget,Port port){
        if(budget<0||budget>64)throw new IllegalArgumentException("Invalid bounded catch-up budget");
        int done=0;
        while(done<budget&&until-settledAt>=STEP){
            long next=settledAt+STEP;
            for(int i=0;i<12;i++){
                if(!port.growing(i))continue;
                if(ages[i]<0){if(port.plant(i,next)){ages[i]=0;progress[i]=0;planted++;}continue;}
                if(ages[i]<7){progress[i]+=STEP;if(progress[i]>=STAGE_TICKS){progress[i]-=STAGE_TICKS;ages[i]++;}}
                if(ages[i]==7&&port.harvestAndReplant(i,next)){ages[i]=0;progress[i]=0;produced+=OUTPUT;planted+=SEED;}
            }
            port.finishStep(settledAt,next);settledAt=next;steps++;done++;
        }
        return done;
    }
    public CompoundTag save(){var t=new CompoundTag();t.putInt("Schema",1);t.putLong("At",settledAt);t.putLong("Steps",steps);t.putLong("Produced",produced);t.putLong("Planted",planted);t.putIntArray("Ages",ages);t.putIntArray("Progress",progress);return t;}
    public static RemoteCropClock load(CompoundTag t){
        for(String key:new String[]{"At","Steps","Produced","Planted"})if(!MinecraftApi.containsTag(t, key,net.minecraft.nbt.Tag.TAG_LONG))throw new IllegalArgumentException("Missing or mistyped remote time/account: "+key);
        if(!MinecraftApi.containsTag(t, "Schema",net.minecraft.nbt.Tag.TAG_INT)||!MinecraftApi.containsTag(t, "Ages",net.minecraft.nbt.Tag.TAG_INT_ARRAY)||!MinecraftApi.containsTag(t, "Progress",net.minecraft.nbt.Tag.TAG_INT_ARRAY))throw new IllegalArgumentException("Missing remote clock fields");
        if(t.getIntOr("Schema", 0)!=1)throw new IllegalArgumentException("Unknown remote crop clock");
        var c=new RemoteCropClock(t.getLongOr("At", 0L),t.getIntArray("Ages").orElseGet(() -> new int[0]));var p=t.getIntArray("Progress").orElseGet(() -> new int[0]);
        if(p.length!=12)throw new IllegalArgumentException("Invalid progress length");System.arraycopy(p,0,c.progress,0,12);
        c.steps=t.getLongOr("Steps", 0L);c.produced=t.getLongOr("Produced", 0L);c.planted=t.getLongOr("Planted", 0L);c.validate();return c;
    }
    private void validate(){
        if(settledAt<0||steps<0||produced<0||planted<0||Arrays.stream(ages).anyMatch(a->a< -1||a>7)||Arrays.stream(progress).anyMatch(p->p<0||p>=STAGE_TICKS))throw new IllegalArgumentException("Invalid remote crop state");
    }
}
