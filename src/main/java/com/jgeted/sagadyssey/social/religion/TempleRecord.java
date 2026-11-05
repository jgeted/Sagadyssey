package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import net.minecraft.core.BlockPos;
import java.util.*;

/** One local institution, never a faction-wide inventory. Explicit calibrated world locators. */
public record TempleRecord(UUID id,String building,String name,FieldworkStorage treasury,
                           FieldworkStorage alms,BlockPos counter,int retained,int portion,long createdAt){
    public TempleRecord{
        Objects.requireNonNull(id);Objects.requireNonNull(treasury);Objects.requireNonNull(alms);Objects.requireNonNull(counter);
        if(building==null||building.isBlank()||building.length()>512||name==null||name.isBlank()||name.length()>120
                ||retained<0||retained>1728||portion<1||portion>64||createdAt<0
                ||!treasury.dimension().equals(alms.dimension())||treasury.position().equals(alms.position())
                ||treasury.containerId().equals(alms.containerId())
                ||!treasury.owner().equals(com.jgeted.sagadyssey.worldsim.rights.SubjectRef.institution(id))
                ||!alms.owner().equals(treasury.owner()))throw new IllegalArgumentException("invalid local temple binding");
    }
    public enum Role{CLERGY_OFFICIANT,DEACON_STEWARD,COMMUNITY_RELIGIOUS,SCRIBE_TEACHER,CHAPEL_WATCHMAN,TEMPLE_REPRESENTATIVE}
}
