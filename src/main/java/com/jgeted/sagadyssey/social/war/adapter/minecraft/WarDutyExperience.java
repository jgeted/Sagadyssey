package com.jgeted.sagadyssey.social.war.adapter.minecraft;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.nbt.*;
import java.util.UUID;

/** Co-saved acknowledgement only. Career qualification is derived from confirmed original military journals. */
public final class WarDutyExperience {
    private WarDutyExperience(){}
    public static void complete(NpcBase npc,UUID operation){
        var root=npc.getPersistentData();var key="SagadysseyWarCompletedDutiesV1";var old=root.getCompoundOrEmpty(key);
        if(old.contains(operation.toString())||old.size()>=256)return;
        var next=old.copy();next.putLong(operation.toString(),npc.level().getGameTime());root.put(key,next);
    }
}
