package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import net.minecraft.nbt.*;
import java.util.*;

/** Domain inbox receipt; not payment, inheritance, destroyed cargo, or knowledge for absent witnesses. */
public record CombatImpact(UUID encounter,UUID character,UUID household,UUID employer,UUID settlement,
                           int wounds,int shots,int droppedStacks,boolean withdrew,long settled) {
    public CombatImpact { if(encounter==null||character==null||wounds<0||shots<0||droppedStacks<0||settled<0)throw new IllegalArgumentException("invalid combat impact"); }
    public UUID id(){return UUID.nameUUIDFromBytes((encounter+":"+character).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public CompoundTag save(){var r=new CompoundTag();r.store("Encounter", UUIDUtil.CODEC,encounter);r.store("Character", UUIDUtil.CODEC,character);
        if(household!=null)r.store("Household", UUIDUtil.CODEC,household);if(employer!=null)r.store("Employer", UUIDUtil.CODEC,employer);if(settlement!=null)r.store("Settlement", UUIDUtil.CODEC,settlement);
        r.putInt("Wounds",wounds);r.putInt("Shots",shots);r.putInt("DroppedStacks",droppedStacks);r.putBoolean("Withdrew",withdrew);r.putLong("Settled",settled);return r;}
    public static boolean put(Map<UUID,CombatImpact> map,CombatImpact impact){var old=map.get(impact.id());if(old!=null){if(!old.equals(impact))throw new IllegalStateException("combat impact immutable");return false;}
        if(map.size()>=4096)throw new IllegalStateException("combat impact inbox full; explicit archival required");map.put(impact.id(),impact);return true;}
    public static ListTag save(Map<UUID,CombatImpact> map){var l=new ListTag();map.values().forEach(i->l.add(i.save()));return l;}
    public static void load(CompoundTag tag,Map<UUID,CombatImpact> map){if(!tag.contains("CombatImpacts"))return; // explicit old-world migration: no invented outcomes
        if(!(tag.get("CombatImpacts") instanceof ListTag list)||list.size()>4096||!list.isEmpty()&&MinecraftApi.elementType(list)!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("invalid combat impact inbox");
        for(var raw:list){var r=(CompoundTag)raw;for(var field:List.of("Encounter","Character"))if(!r.read(field, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing combat impact identity");
            for(var field:List.of("Household","Employer","Settlement"))if(r.contains(field)&&!r.read(field, UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("invalid combat context identity");
            for(var field:List.of("Wounds","Shots","DroppedStacks"))if(!MinecraftApi.containsTag(r, field,Tag.TAG_INT))throw new IllegalArgumentException("missing combat impact count");
            if(!MinecraftApi.containsTag(r, "Settled",Tag.TAG_LONG)||!MinecraftApi.containsTag(r, "Withdrew",Tag.TAG_BYTE))throw new IllegalArgumentException("missing combat settlement");
            var i=new CombatImpact(r.read("Encounter", UUIDUtil.CODEC).orElseThrow(),r.read("Character", UUIDUtil.CODEC).orElseThrow(),r.read("Household", UUIDUtil.CODEC).isPresent()?r.read("Household", UUIDUtil.CODEC).orElseThrow():null,r.read("Employer", UUIDUtil.CODEC).isPresent()?r.read("Employer", UUIDUtil.CODEC).orElseThrow():null,
                    r.read("Settlement", UUIDUtil.CODEC).isPresent()?r.read("Settlement", UUIDUtil.CODEC).orElseThrow():null,r.getIntOr("Wounds", 0),r.getIntOr("Shots", 0),r.getIntOr("DroppedStacks", 0),r.getBooleanOr("Withdrew", false),r.getLongOr("Settled", 0L));
            if(map.containsKey(i.id()))throw new IllegalArgumentException("duplicate combat settlement");put(map,i);
        }
    }
}
