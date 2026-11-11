package com.jgeted.sagadyssey.social.combat;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import java.util.Set;

/** Exact ammo components, physical launch and rights updates are one server-thread transaction. */
public final class NpcAmmunitionService {
    private NpcAmmunitionService(){}
    public static boolean launch(NpcBase npc,AbstractArrow arrow,ItemStack ammo){
        if(npc.level().isClientSide()||ammo==null||ammo.isEmpty())return false;
        var rights=RightsStateData.get(MinecraftApi.server(npc));var actor=SubjectRef.character(npc.getCharacterId());
        var container=ContainerIds.npcPersonal(npc.getCharacterId());var inventory=npc.getPersonalInventory();
        rights.ensureContext(container,"npc:"+npc.getCharacterId()+":personal",actor,actor,AccessPolicy.PRIVATE);
        var snapshot=new ItemStack[inventory.getContainerSize()];for(int i=0;i<snapshot.length;i++)snapshot[i]=inventory.getItem(i).copy();
        var claims=rights.snapshotFor(Set.of(container));
        var result=new ConsumptionService(rights).consume(container,inventory,actor,ItemFingerprint.of(ammo),1,"shot:"+arrow.getUUID(),npc.level().getGameTime());
        if(!result.successful())return false;
        try{
            // A mob shot must not create a second recoverable arrow after consuming its original ammo.
            arrow.pickup=AbstractArrow.Pickup.DISALLOWED;
            if(npc.level().addFreshEntity(arrow)){NpcCombatRuntime.shot(npc,arrow.getUUID());return true;}
        }catch(RuntimeException exception){arrow.discard();}
        for(int i=0;i<snapshot.length;i++)inventory.setItem(i,snapshot[i]);rights.restore(claims);inventory.setChanged();return false;
    }
}
