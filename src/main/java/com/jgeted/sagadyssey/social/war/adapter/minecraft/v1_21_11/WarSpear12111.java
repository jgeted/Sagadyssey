package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarServerEvents;
import com.jgeted.sagadyssey.social.war.api.WarAttackKind;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import java.util.*;

/** Native piercing/kinetic effects at the military contact boundary. No copied damage formula or synthetic motion. */
final class WarSpear12111 {
    private record Windup(UUID operation,UUID target,UUID targetCharacter,com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint weapon,WarAttackKind kind,long since,long last) {}
    private final Map<NpcBase,Windup> preparing=new WeakHashMap<>();
    void prepare(NpcBase actor,NpcBase target,UUID operation,WarAttackKind kind){
        long now=actor.level().getGameTime();var old=preparing.get(actor);var kinetic=actor.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
        if(kinetic==null){cancel(actor);return;}
        var weapon=com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint.of(actor.getMainHandItem());
        long since=old==null||!old.operation().equals(operation)||!old.target().equals(target.getUUID())||!old.targetCharacter().equals(target.getCharacterId())||!old.weapon().equals(weapon)||old.kind()!=kind||now<old.last()||now-old.last()>40||now-old.since()>kinetic.computeDamageUseDuration()?now:old.since();
        preparing.put(actor,new Windup(operation,target.getUUID(),target.getCharacterId(),weapon,kind,since,now));
    }
    void cancel(NpcBase actor){preparing.remove(actor);}
    AttackResult attack(NpcBase actor,NpcBase target,WarAttackKind kind){
        var stack=actor.getMainHandItem();var piercing=stack.get(DataComponents.PIERCING_WEAPON);var kinetic=stack.get(DataComponents.KINETIC_WEAPON);
        if(piercing==null||kinetic==null||!WarMobility12111.mayAct(actor)||!actor.isAlive()||target==null||!target.isAlive()||actor.level()!=target.level())return blocked("ORIGINAL_NATIVE_SPEAR_REQUIRED");
        actor.lookAt(EntityAnchorArgument.Anchor.EYES,target.getEyePosition());
        var field=WarServerEvents.existing(actor.getServer()).flatMap(v->v.field());
        var windup=preparing.get(actor);UUID prepared=kind==WarAttackKind.SPEAR_JAB||windup==null?null:windup.operation();
        if(field.isEmpty()||!field.get().mayMeleeImpact(actor,target,kind,prepared))return blocked("CURRENT_SPEAR_CONTACT_PERMISSION_REQUIRED");
        var hits=ProjectileUtil.getHitEntitiesAlong(actor,actor.entityAttackRange(),e->PiercingWeapon.canHitEntity(actor,e),ClipContext.Block.COLLIDER).<Collection<EntityHitResult>>map(p->List.of(),p->p);
        var before=new IdentityHashMap<LivingEntity,Float>();
        for(var hit:hits){var entity=hit.getEntity();var person=entity instanceof NpcBase n?n:entity instanceof Horse h&&h.getPassengers().size()==1&&h.getFirstPassenger() instanceof NpcBase n?n:null;
            if(person==null||!field.get().mayMeleeImpact(actor,person,kind,prepared))return blocked("SPEAR_RAY_CONTAINS_UNAUTHORIZED_CONTACT");
            if(entity instanceof LivingEntity living)before.put(living,living.getHealth());
        }
        if(kind==WarAttackKind.SPEAR_JAB){cancel(actor);piercing.attack(actor,EquipmentSlot.MAINHAND);}
        else{
            long now=actor.level().getGameTime();
            if(windup==null||windup.kind()!=kind||!windup.target().equals(target.getUUID())||!windup.targetCharacter().equals(target.getCharacterId())||!windup.weapon().matches(stack)||now<windup.since()||now-windup.last()>40||now-windup.since()<kinetic.delayTicks()||now-windup.since()>kinetic.computeDamageUseDuration())return blocked("REAL_SPEAR_PREPARATION_REQUIRED");
            if((kind==WarAttackKind.MOUNTED_SPEAR_CHARGE)!=actor.isPassenger())return blocked("SPEAR_CHARGE_MOBILITY_CHANGED");
            int elapsed=Math.toIntExact(now-windup.since());cancel(actor);
            // The native routine owns range, speed thresholds, damage, knockback and dismount.
            // Use is instantaneous here so an unfenced automatic item-use tick cannot hit bystanders.
            actor.startUsingItem(InteractionHand.MAIN_HAND);
            try{kinetic.damageEntities(stack,stack.getUseDuration(actor)-elapsed,actor,EquipmentSlot.MAINHAND);actor.swing(InteractionHand.MAIN_HAND);}
            finally{actor.stopUsingItem();}
        }
        boolean damaged=before.entrySet().stream().anyMatch(e->e.getKey().getHealth()<e.getValue());
        return new AttackResult(damaged?AttackStatus.HIT:AttackStatus.NO_HIT,kind==WarAttackKind.SPEAR_JAB?"NATIVE_PIERCING_WEAPON":"NATIVE_KINETIC_WEAPON_ACTUAL_MOTION");
    }
    private static AttackResult blocked(String reason){return new AttackResult(AttackStatus.BLOCKED,reason);}
}
