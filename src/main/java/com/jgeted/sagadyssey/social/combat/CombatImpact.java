package com.jgeted.sagadyssey.social.combat;

import net.minecraft.nbt.*;
import java.util.*;

/** Domain inbox receipt; not payment, inheritance, destroyed cargo, or knowledge for absent witnesses. */
public record CombatImpact(UUID encounter,UUID character,UUID household,UUID employer,UUID settlement,
                           int wounds,int shots,int droppedStacks,boolean withdrew,long settled) {
    public CombatImpact { if(encounter==null||character==null||wounds<0||shots<0||droppedStacks<0||settled<0)throw new IllegalArgumentException("invalid combat impact"); }
    public UUID id(){return UUID.nameUUIDFromBytes((encounter+":"+character).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public CompoundTag save(){var r=new CompoundTag();r.putUUID("Encounter",encounter);r.putUUID("Character",character);
        if(household!=null)r.putUUID("Household",household);if(employer!=null)r.putUUID("Employer",employer);if(settlement!=null)r.putUUID("Settlement",settlement);
        r.putInt("Wounds",wounds);r.putInt("Shots",shots);r.putInt("DroppedStacks",droppedStacks);r.putBoolean("Withdrew",withdrew);r.putLong("Settled",settled);return r;}
    public static boolean put(Map<UUID,CombatImpact> map,CombatImpact impact){var old=map.get(impact.id());if(old!=null){if(!old.equals(impact))throw new IllegalStateException("combat impact immutable");return false;}
        if(map.size()>=4096)throw new IllegalStateException("combat impact inbox full; explicit archival required");map.put(impact.id(),impact);return true;}
    public static ListTag save(Map<UUID,CombatImpact> map){var l=new ListTag();map.values().forEach(i->l.add(i.save()));return l;}
    public static void load(CompoundTag tag,Map<UUID,CombatImpact> map){if(!tag.contains("CombatImpacts"))return; // explicit old-world migration: no invented outcomes
        if(!(tag.get("CombatImpacts") instanceof ListTag list)||list.size()>4096||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid combat impact inbox");
        for(var raw:list){var r=(CompoundTag)raw;for(var field:List.of("Encounter","Character"))if(!r.hasUUID(field))throw new IllegalArgumentException("missing combat impact identity");
            for(var field:List.of("Household","Employer","Settlement"))if(r.contains(field)&&!r.hasUUID(field))throw new IllegalArgumentException("invalid combat context identity");
            for(var field:List.of("Wounds","Shots","DroppedStacks"))if(!r.contains(field,Tag.TAG_INT))throw new IllegalArgumentException("missing combat impact count");
            if(!r.contains("Settled",Tag.TAG_LONG)||!r.contains("Withdrew",Tag.TAG_BYTE))throw new IllegalArgumentException("missing combat settlement");
            var i=new CombatImpact(r.getUUID("Encounter"),r.getUUID("Character"),r.hasUUID("Household")?r.getUUID("Household"):null,r.hasUUID("Employer")?r.getUUID("Employer"):null,
                    r.hasUUID("Settlement")?r.getUUID("Settlement"):null,r.getInt("Wounds"),r.getInt("Shots"),r.getInt("DroppedStacks"),r.getBoolean("Withdrew"),r.getLong("Settled"));
            if(map.containsKey(i.id()))throw new IllegalArgumentException("duplicate combat settlement");put(map,i);
        }
    }
}
