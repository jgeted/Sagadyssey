package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.social.combat.NpcAmmunitionService;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarBodyCheckpoint;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarBodyCheckpointCodec;
import com.jgeted.sagadyssey.social.war.feature.command.WarCapabilityCatalog;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.common.ItemAbilities;
import java.util.*;

/** Current native bindings; spear effects use the original piercing/kinetic components. */
public final class WarMinecraft12111 {
    private static final TagKey<Item> SPEARS = ItemTags.SPEARS;
    private static final WarWeaponProfile.Melee SWORD = new WarWeaponProfile.Melee(WarAttackKind.SWORD_STRIKE, 0, 1.9, 20);
    private static final WarWeaponProfile.Ranged BOW = new WarWeaponProfile.Ranged(WarAttackKind.BOW_SHOT, 4, 16, 20, 20);
    private static final WarWeaponProfile.Ranged CROSSBOW = new WarWeaponProfile.Ranged(WarAttackKind.CROSSBOW_SHOT, 4, 12, 25, 30);
    private WarMinecraft12111() {}
    public static WarNativeBindings bindings() {
        return new WarNativeBindings("1.21.11", WarMinecraft12111::inspect, new NativeActors(), new NativeCheckpoints(),
                new WarMobility12111(), new WarBlockWork12111(), new WarHazards12111(),
                WarMusterCheckpoint12111::new, WarStaffOffices12111::new);
    }
    static WarWeaponProfile inspect(ItemStack stack) {
        var weapons = EnumSet.noneOf(WarLoadout.Weapon.class);
        if (stack.isEmpty()) return new WarWeaponProfile(weapons, false, false, false, Optional.empty());
        boolean sword = MinecraftApi.isSword(stack);
        if (sword) weapons.add(WarLoadout.Weapon.SWORD);
        if (stack.is(SPEARS)) weapons.add(WarLoadout.Weapon.SPEAR);
        if (stack.getItem() instanceof BowItem) weapons.add(WarLoadout.Weapon.BOW);
        if (stack.getItem() instanceof CrossbowItem) weapons.add(WarLoadout.Weapon.CROSSBOW);
        // 首批只开放普通原版弓/未装填弩；不抹掉已有装填，也不假装支持多重射击或快装等附魔。
        Optional<WarWeaponProfile.Ranged> ranged = Optional.empty();
        if (!stack.isEnchanted()) {
            if (stack.is(Items.BOW)) ranged = Optional.of(BOW);
            if (stack.is(Items.CROSSBOW) && !CrossbowItem.isCharged(stack)) ranged = Optional.of(CROSSBOW);
        }
        var melee=sword?Optional.of(SWORD):Optional.<WarWeaponProfile.Melee>empty();
        var range=stack.get(DataComponents.ATTACK_RANGE);
        if(stack.is(SPEARS)&&range!=null&&stack.has(DataComponents.PIERCING_WEAPON)&&stack.has(DataComponents.KINETIC_WEAPON)){
            double speed=stack.getAttributeModifiers().modifiers().stream().filter(e->e.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)).mapToDouble(e->e.modifier().amount()).sum()+4;
            melee=Optional.of(new WarWeaponProfile.Melee(WarAttackKind.SPEAR_JAB,range.minRange()*range.mobFactor(),range.maxRange()*range.mobFactor(),Math.max(1,(long)Math.ceil(20/Math.max(.1,speed)))));
        }
        return new WarWeaponProfile(weapons, sword || stack.is(ItemTags.AXES) || stack.is(SPEARS),
                stack.has(DataComponents.BLOCKS_ATTACKS), MinecraftApi.isArmor(stack), melee, ranged);
    }
    private static final class NativeActors implements Actors<NpcBase> {
        private final WarWeaponSwitchAdapter switches = new WarWeaponSwitchAdapter();
        private final WarSpear12111 spears = new WarSpear12111();
        public void prepareCharge(NpcBase actor,NpcBase target,UUID operation,WarAttackKind kind){thread(actor);spears.prepare(actor,target,operation,kind);}
        public void cancelCharge(NpcBase actor){thread(actor);spears.cancel(actor);}
        public Arms arms(NpcBase actor, UUID contract, WarAttackKind primary) { thread(actor); return switches.arms(actor, contract, primary); }
        public WeaponChange equip(NpcBase actor, UUID contract, WarAttackKind primary, WarAttackKind desired) { thread(actor); actor.stopUsingItem(); return switches.equip(actor, contract, primary, desired); }
        public void settleEquipment(NpcBase actor) { thread(actor); switches.settle(actor); }
        public Support support(WarAttackKind kind) { return WarCapabilityCatalog.attack(kind); }
        public WarLoadout.Mount mount(NpcBase actor) {
            if (!(actor.getVehicle() instanceof LivingEntity vehicle)) return WarLoadout.Mount.FOOT;
            var armor = vehicle.getItemBySlot(EquipmentSlot.BODY).get(DataComponents.EQUIPPABLE);
            return vehicle instanceof AbstractHorse && armor != null && armor.slot() == EquipmentSlot.BODY ? WarLoadout.Mount.HEAVY : WarLoadout.Mount.LIGHT;
        }
        public void stopNavigation(NpcBase actor) { WarMobility12111.stop(actor); }
        public void clearTarget(NpcBase actor) { thread(actor); actor.setTarget(null); }
        public void engage(NpcBase actor, NpcBase target) { thread(actor); actor.setTarget(target); actor.getLookControl().setLookAt(target, 25, 25); }
        public boolean canStand(NpcBase actor, WarIntent.Position destination) {
            return WarMobility12111.canStand(actor,destination);
        }
        public boolean navigate(NpcBase actor, WarIntent.Position destination, double speed) {
            return WarMobility12111.navigate(actor,destination,speed);
        }
        public AttackResult attack(NpcBase actor, NpcBase target, WarAttackKind kind) {
            var support = support(kind);
            // Stub在访问身体、目标或任何世界副作用前返回。没有假命中或备用伤害路线。
            if (!support.implemented()) return new AttackResult(AttackStatus.UNSUPPORTED, support.reason());
            if(kind==WarAttackKind.SPEAR_JAB||kind==WarAttackKind.SPEAR_CHARGE||kind==WarAttackKind.MOUNTED_SPEAR_CHARGE){thread(actor);return spears.attack(actor,target,kind);}
            if (kind != WarAttackKind.SWORD_STRIKE) return new AttackResult(AttackStatus.BLOCKED, "RANGED_MISSION_CONTEXT_REQUIRED");
            thread(actor);
            if (kind != WarAttackKind.SWORD_STRIKE || !MinecraftApi.isSword(actor.getMainHandItem())
                    || !WarMobility12111.mayAct(actor) || !actor.isAlive() || target == null || !target.isAlive() || actor.level() != target.level())
                return new AttackResult(AttackStatus.BLOCKED, "ORIGINAL_NATIVE_SWORD_BODY_REQUIRED");
            actor.swing(InteractionHand.MAIN_HAND);
            return new AttackResult(actor.doHurtTarget((ServerLevel)actor.level(), target) ? AttackStatus.HIT : AttackStatus.NO_HIT, "NATIVE_SINGLE_MELEE_STRIKE");
        }
        public boolean hasAmmunition(NpcBase actor) { thread(actor); return !ammunition(actor).isEmpty(); }
        public boolean isRangedUsing(NpcBase actor, WarAttackKind kind) {
            thread(actor); return actor.isUsingItem() && actor.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && ItemStack.matches(actor.getUseItem(), actor.getMainHandItem()) && inspect(actor.getMainHandItem()).ranged().filter(p -> p.kind() == kind).isPresent();
        }
        public void beginRangedUse(NpcBase actor, WarAttackKind kind) {
            thread(actor); actor.startUsingItem(InteractionHand.MAIN_HAND);
            if (kind == WarAttackKind.CROSSBOW_SHOT) actor.playAction(NpcAction.CROSSBOW_LOAD, 25);
        }
        public void cancelRangedUse(NpcBase actor) { thread(actor); actor.stopUsingItem(); }
        public AttackResult rangedAttack(NpcBase actor, NpcBase target, WarAttackKind kind, WarShotPermit permit) {
            var supported = support(kind); if (!supported.implemented()) return new AttackResult(AttackStatus.UNSUPPORTED, supported.reason());
            if (kind != WarAttackKind.BOW_SHOT && kind != WarAttackKind.CROSSBOW_SHOT) return new AttackResult(AttackStatus.BLOCKED, "RANGED_KIND_REQUIRED");
            thread(actor); var weapon = actor.getMainHandItem(); var profile = inspect(weapon).ranged();
            if (profile.isEmpty() || profile.get().kind() != kind || !WarMobility12111.mayAct(actor) || !actor.isAlive() || target == null || !target.isAlive()
                    || actor.level() != target.level() || permit == null || !permit.body().equals(actor.getUUID()) || !permit.character().equals(actor.getCharacterId())
                    || !permit.intendedTarget().equals(target.getCharacterId()) || permit.firedAt() != actor.level().getGameTime()) return new AttackResult(AttackStatus.BLOCKED, "ORIGINAL_RANGED_BODY_OR_MISSION_REQUIRED");
            var p = profile.get(); double range = actor.distanceToSqr(target);
            if (range < p.preferredMinimumReach() * p.preferredMinimumReach() || range > p.maximumReach() * p.maximumReach() || !actor.getSensing().hasLineOfSight(target))
                return new AttackResult(AttackStatus.BLOCKED, "ACTUAL_RANGE_OR_VISIBILITY_CHANGED");
            var ammo = ammunition(actor); if (ammo.isEmpty()) return new AttackResult(AttackStatus.BLOCKED, "ACTUAL_AMMUNITION_REQUIRED");
            var arrow = ProjectileUtil.getMobArrow(actor, ammo, 1.0F, weapon);
            double dx = target.getX() - actor.getX(), dz = target.getZ() - actor.getZ();
            double dy = target.getY(.5) - arrow.getY();float speed=kind==WarAttackKind.BOW_SHOT?1.6F:1.9F;
            var aim=WarArrowAim12111.velocity(dx,dy,dz,speed,arrow.getGravity());
            if(aim.isEmpty())return new AttackResult(AttackStatus.BLOCKED,"NATIVE_LOW_ARC_UNAVAILABLE");
            arrow.shoot(aim.get().x,aim.get().y,aim.get().z,speed,0);
            WarProjectileSafety.mark(arrow, permit);
            if (!NpcAmmunitionService.launch(actor, arrow, ammo)) return new AttackResult(AttackStatus.BLOCKED, "AMMUNITION_OR_CONSUMPTION_RIGHTS_UNAVAILABLE");
            actor.playSound(kind == WarAttackKind.BOW_SHOT ? SoundEvents.SKELETON_SHOOT : SoundEvents.CROSSBOW_SHOOT, 1, 1);
            actor.swing(InteractionHand.MAIN_HAND); actor.playAction(NpcAction.BOW_RELEASE, 10); actor.stopUsingItem();
            return new AttackResult(AttackStatus.LAUNCHED, "NATIVE_PROJECTILE_WITH_REAL_AMMUNITION");
        }
        private static ItemStack ammunition(NpcBase actor) { return actor.getPersonalInventory().first(stack -> stack.getItem() instanceof ArrowItem); }
    }
    private static final class NativeCheckpoints implements Checkpoints<NpcBase> {
        private final WarBodyCheckpointCodec codec = new WarBodyCheckpointCodec();
        public CheckpointRead read(NpcBase actor) { thread(actor); return codec.read(actor.getPersistentData()); }
        public void store(NpcBase actor, WarBodyCheckpoint value) { thread(actor); codec.store(actor.getPersistentData(), value); }
    }
    private static void thread(NpcBase actor) {
        if (!(actor.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("native war actor requires server thread");
    }
}
