package com.jgeted.sagadyssey.social.religion.life;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.nbt.*;
import java.util.*;

/** Source-bearing statement, not automatically objective truth or a family/death mutation. */
public record ChurchRecord(UUID id,UUID temple,UUID activity,UUID source,UUID author,UUID custodian,
        long authoredAt,String title,String text,Access access,Set<UUID> parties,double confidence,
        ItemFingerprint physical,String predicate) {
    public enum Access {PUBLIC_TEXT,PUBLIC_INDEX,PARTY_ONLY,OFFICE_RESTRICTED,CONFIDENTIAL}
    public ChurchRecord {
        Objects.requireNonNull(id);Objects.requireNonNull(temple);Objects.requireNonNull(author);Objects.requireNonNull(custodian);Objects.requireNonNull(access);
        parties=Set.copyOf(parties);
        if(authoredAt<0||title==null||title.isBlank()||title.length()>32||text==null||text.isBlank()||text.length()>4096||parties.size()>16
                ||!Double.isFinite(confidence)||confidence<0||confidence>1||predicate==null||predicate.isBlank()||predicate.length()>64)
            throw new IllegalArgumentException("invalid church record");
    }
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putUUID("Temple",temple);t.putUUID("Author",author);t.putUUID("Custodian",custodian);t.putLong("Time",authoredAt);t.putString("Title",title);t.putString("Text",text);t.putString("Access",access.name());t.putDouble("Confidence",confidence);t.putString("Predicate",predicate);t.put("Parties",ChurchActivity.people(parties));if(activity!=null)t.putUUID("Activity",activity);if(source!=null)t.putUUID("Source",source);if(physical!=null)t.put("Physical",physical.save());return t;}
    public static ChurchRecord load(CompoundTag t){for(var n:List.of("Title","Text","Access","Predicate"))ChurchLifeData.require(t,n,Tag.TAG_STRING);ChurchLifeData.require(t,"Time",Tag.TAG_LONG);ChurchLifeData.require(t,"Confidence",Tag.TAG_DOUBLE);
        ItemFingerprint f=null;if(t.contains("Physical")){ChurchLifeData.require(t,"Physical",Tag.TAG_COMPOUND);var p=t.getCompound("Physical");ChurchLifeData.require(p,"Item",Tag.TAG_STRING);ChurchLifeData.require(p,"Components",Tag.TAG_INT);f=ItemFingerprint.load(p);}
        return new ChurchRecord(ChurchLifeData.uuid(t,"Id"),ChurchLifeData.uuid(t,"Temple"),ChurchActivity.optional(t,"Activity"),ChurchActivity.optional(t,"Source"),ChurchLifeData.uuid(t,"Author"),ChurchLifeData.uuid(t,"Custodian"),t.getLong("Time"),t.getString("Title"),t.getString("Text"),Access.valueOf(t.getString("Access")),Set.copyOf(ChurchActivity.people(t,"Parties")),t.getDouble("Confidence"),f,t.getString("Predicate"));}
}
