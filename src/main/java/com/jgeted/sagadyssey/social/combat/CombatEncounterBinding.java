package com.jgeted.sagadyssey.social.combat;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** A host's reference to an existing side. It confers neither hostility nor item/person ownership. */
public record CombatEncounterBinding(String ownerKind,UUID owner,UUID principal,UUID encounter,UUID side){
    public CombatEncounterBinding{
        if(ownerKind==null||!ownerKind.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}"))throw new IllegalArgumentException("invalid encounter host kind");
        Objects.requireNonNull(owner);Objects.requireNonNull(principal);Objects.requireNonNull(encounter);Objects.requireNonNull(side);
    }
    CompoundTag save(){var t=new CompoundTag();t.putString("Kind",ownerKind);t.store("Owner",UUIDUtil.CODEC,owner);t.store("Principal",UUIDUtil.CODEC,principal);t.store("Encounter",UUIDUtil.CODEC,encounter);t.store("Side",UUIDUtil.CODEC,side);return t;}
    static CombatEncounterBinding load(CompoundTag t){
        if(!t.keySet().equals(Set.of("Kind","Owner","Principal","Encounter","Side")))throw new IllegalArgumentException("invalid encounter binding fields");
        return new CombatEncounterBinding(t.getStringOr("Kind",""),t.read("Owner",UUIDUtil.CODEC).orElseThrow(),t.read("Principal",UUIDUtil.CODEC).orElseThrow(),t.read("Encounter",UUIDUtil.CODEC).orElseThrow(),t.read("Side",UUIDUtil.CODEC).orElseThrow());
    }
}
