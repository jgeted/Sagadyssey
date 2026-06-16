package com.jgeted.sagadyssey.npc.entity;

import com.jgeted.sagadyssey.npc.gui.NpcInteractionScreen;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Sagadyssey NPC 基类。
 * v0.1 — 最小可用：生成、移动、存盘、右键交互、职业系统。
 */
public class NpcBase extends PathfinderMob {

    private String customName = "NPC";
    private NpcProfession profession = NpcProfession.NONE;

    public NpcBase(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    public String getNpcName() { return customName; }
    public void setNpcName(String name) { this.customName = name; }

    public NpcProfession getProfession() { return profession; }

    public void setProfession(NpcProfession profession) {
        this.profession = profession;
        if (profession != NpcProfession.NONE) {
            this.setNpcName(profession.getDisplayName());
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    /** 属性定义（在注册实体时调用） */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.level().isClientSide) {
            openInteractionScreen();
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    /** 客户端打开交互界面 */
    private void openInteractionScreen() {
        if (this.level().isClientSide) {
            try {
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new NpcInteractionScreen(this.getId(), this.customName, this.profession.getDisplayName()));
            } catch (Exception e) {
                // 安全兜底，不崩溃
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("NpcName", this.customName);
        tag.putString("Profession", this.profession.name());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("NpcName")) {
            this.customName = tag.getString("NpcName");
        }
        if (tag.contains("Profession")) {
            try {
                this.profession = NpcProfession.valueOf(tag.getString("Profession"));
            } catch (IllegalArgumentException e) {
                this.profession = NpcProfession.NONE;
            }
        }
    }
}
