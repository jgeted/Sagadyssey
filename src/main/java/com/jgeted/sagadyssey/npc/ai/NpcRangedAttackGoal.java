package com.jgeted.sagadyssey.npc.ai;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * NPC 远程攻击 AI。
 * 参考原版 RangedBowAttackGoal；武器与箭矢均来自实体装备或 36 格随身物品栏。
 */
public class NpcRangedAttackGoal extends Goal {

    private final NpcBase npc;
    private final double speedModifier;
    private final float attackRadius;
    private final float maxAttackDistance;
    private int attackIntervalMin;
    private int attackTime;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;
    private int crossbowChargeTicks;

    private static final float DEFAULT_ATTACK_RADIUS = 15.0F;
    private static final float CROSSBOW_ATTACK_RADIUS = 12.0F;
    private static final int CROSSBOW_CHARGE_TICKS = 25;

    public NpcRangedAttackGoal(NpcBase npc, double speedModifier, int attackInterval, float maxAttackDistance) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.attackIntervalMin = attackInterval;
        this.maxAttackDistance = maxAttackDistance;

        // 根据职业调整攻击距离
        float radius = DEFAULT_ATTACK_RADIUS;
        if (npc.getProfession() != null) {
            radius = switch (npc.getProfession()) {
                case ARCHER -> 16.0F;
                default -> DEFAULT_ATTACK_RADIUS;
            };
        }
        this.attackRadius = radius;

        this.setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) return false;

        // 申请 ATTACK mutex，与 NpcCombatGoal/近战系统互斥
        if (!npc.requestMutex(AiMutex.ATTACK)) return false;

        // 检查是否有远程武器
        ItemStack weapon = getRangedWeapon();
        if (weapon.isEmpty() || !hasAmmo()) {
            npc.releaseMutex(AiMutex.ATTACK);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = npc.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (npc.isOwnedBy(target.getUUID())) return false;
        // 弹药耗尽/武器丢失 → 切回近战，让近战 goal 接管战斗
        // （主手物品未变，NpcWeaponSwapHandler 不会弹回远程模式）
        if (getRangedWeapon().isEmpty() || !hasAmmo()) {
            npc.ensureMeleeMode();
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        npc.setAggressive(true);
        crossbowChargeTicks = 0;
    }

    @Override
    public void stop() {
        npc.setAggressive(false);
        seeTime = 0;
        crossbowChargeTicks = 0;
        npc.stopUsingItem();
        npc.releaseMutex(AiMutex.ATTACK);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = npc.getTarget();
        if (target == null) return;

        double distSqr = npc.distanceToSqr(target);
        boolean canSee = npc.getSensing().hasLineOfSight(target);
        if (canSee) {
            seeTime++;
        } else {
            seeTime = 0;
        }

        double attackRadiusSqr = attackRadius * attackRadius;

        if (distSqr <= attackRadiusSqr && seeTime >= 5) {
            npc.getNavigation().stop();
        } else {
            npc.getNavigation().moveTo(target, speedModifier);
        }

        npc.getLookControl().setLookAt(target, 45.0F, 45.0F);

        // 远程武器蓄力/射击
        ItemStack weapon = getRangedWeapon();
        if (weapon.getItem() instanceof BowItem) {
            crossbowChargeTicks = 0;
            tickBow(weapon, target, distSqr, canSee);
        } else if (weapon.getItem() instanceof CrossbowItem) {
            tickCrossbow(weapon, target, distSqr, canSee);
        }
    }

    private void tickBow(ItemStack bow, LivingEntity target, double distSqr, boolean canSee) {
        // 射后冷却：attackIntervalMin tick 内不重新蓄力
        if (attackTime > 0) {
            attackTime--;
            return;
        }
        boolean usingBow = npc.isUsingItem();
        if (usingBow) {
            int useTicks = npc.getTicksUsingItem();
            if (useTicks >= 20) {
                // 射箭
                performBowAttack(target, bow, useTicks);
            }
        } else if (canSee && distSqr <= maxAttackDistance * maxAttackDistance) {
            // 开始蓄力
            npc.startUsingItem(ProjectileUtil.getWeaponHoldingHand(npc, item -> item instanceof BowItem));
        }
    }

    private void performBowAttack(LivingEntity target, ItemStack bow, int useTicks) {
        float power = BowItem.getPowerForTime(useTicks);
        if (power < 0.1F) return;

        // releaseUsing 只对玩家生效（源码中整个方法体在 instanceof Player 分支内），
        // 因此参照原版 AbstractSkeleton.performRangedAttack 手动生成箭实体
        ItemStack ammo = getAmmoStack();
        if(ammo.isEmpty()){npc.stopUsingItem();return;}
        AbstractArrow arrow = ProjectileUtil.getMobArrow(npc, ammo, power, bow);
        arrow.setBaseDamage(((com.jgeted.sagadyssey.mixin.AbstractArrowDamageAccessor) arrow).sagadyssey$getBaseDamage() * npc.getMoraleDamageMultiplier());

        double d0 = target.getX() - npc.getX();
        double d1 = target.getY(0.3333333333333333) - arrow.getY();
        double d2 = target.getZ() - npc.getZ();
        double d3 = Math.sqrt(d0 * d0 + d2 * d2);
        arrow.shoot(d0, d1 + d3 * 0.2F, d2, 1.6F, (float) (14 - npc.level().getDifficulty().getId() * 4));

        npc.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (npc.getRandom().nextFloat() * 0.4F + 0.8F));
        if(!com.jgeted.sagadyssey.social.combat.NpcAmmunitionService.launch(npc,arrow,ammo)){npc.stopUsingItem();return;}

        npc.swing(npc.getUsedItemHand());
        npc.playAction(NpcAction.BOW_RELEASE, 10);
        npc.stopUsingItem();

        // 消耗箭
        this.attackTime = this.attackIntervalMin; // 进入射后冷却
    }

    private void tickCrossbow(ItemStack crossbow, LivingEntity target, double distSqr, boolean canSee) {
        if (attackTime > 0) {
            attackTime--;
            crossbowChargeTicks = 0;
            return;
        }

        if (!canSee || distSqr > maxAttackDistance * maxAttackDistance) {
            crossbowChargeTicks = 0;
            return;
        }

        crossbowChargeTicks++;
        if (crossbowChargeTicks == 1) {
            npc.playAction(NpcAction.CROSSBOW_LOAD, CROSSBOW_CHARGE_TICKS);
            npc.playSound(SoundEvents.CROSSBOW_LOADING_START.value(), 0.7F, 1.0F);
        } else if (crossbowChargeTicks == CROSSBOW_CHARGE_TICKS / 2) {
            npc.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 0.7F, 1.0F);
        }

        if (crossbowChargeTicks >= CROSSBOW_CHARGE_TICKS) {
            performCrossbowAttack(target, crossbow);
        }
    }

    private void performCrossbowAttack(LivingEntity target, ItemStack crossbow) {
        ItemStack ammo = getAmmoStack();
        if (ammo.isEmpty()) {
            crossbowChargeTicks = 0;
            return;
        }

        AbstractArrow arrow = ProjectileUtil.getMobArrow(npc, ammo, 1.0F, crossbow);
        arrow.setBaseDamage(((com.jgeted.sagadyssey.mixin.AbstractArrowDamageAccessor) arrow).sagadyssey$getBaseDamage() * npc.getMoraleDamageMultiplier());
        double dx = target.getX() - npc.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - npc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.9F,
                (float) (10 - npc.level().getDifficulty().getId() * 2));

        npc.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F,
                1.0F / (npc.getRandom().nextFloat() * 0.3F + 0.85F));
        if(!com.jgeted.sagadyssey.social.combat.NpcAmmunitionService.launch(npc,arrow,ammo)){crossbowChargeTicks=0;return;}
        npc.swing(ProjectileUtil.getWeaponHoldingHand(npc, item -> item instanceof CrossbowItem));
        npc.playAction(NpcAction.BOW_RELEASE, 10);

        crossbowChargeTicks = 0;
        attackTime = attackIntervalMin + 10;
    }

    /** 获取当前远程武器（弓或弩） */
    private ItemStack getRangedWeapon() {
        ItemStack mainHand = npc.getMainHandItem();
        if (mainHand.getItem() instanceof BowItem || mainHand.getItem() instanceof CrossbowItem) {
            return mainHand;
        }
        ItemStack offHand = npc.getOffhandItem();
        if (offHand.getItem() instanceof BowItem || offHand.getItem() instanceof CrossbowItem) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    /** 获取物品栏中的第一组可用弹药。 */
    private ItemStack getAmmoStack() {
        return npc.getPersonalInventory().first(stack -> stack.getItem() instanceof ArrowItem);
    }

    /** 检查是否有弹药 */
    private boolean hasAmmo() {
        return !getAmmoStack().isEmpty();
    }

    /** 动态更新攻击间隔 */
    public void setAttackInterval(int interval) {
        this.attackIntervalMin = interval;
    }
}
