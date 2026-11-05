package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.google.common.collect.ImmutableList;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

/**
 * NPC 自定义模型 — 细手臂 + 全套 outer layer（hat/jacket/sleeves/pants）。
 * 基于 PlayerModel 的 slim 变体，但泛型适配 NpcBase。
 */
public class NpcModel extends HumanoidModel<NpcBase> {

    private final ModelPart modelRoot;
    private final ModelPart jacket;
    private final ModelPart leftSleeve;
    private final ModelPart rightSleeve;
    private final ModelPart leftPants;
    private final ModelPart rightPants;
    private final ModelPart warriorTabard;
    private final ModelPart archerShoulderCape;
    private final ModelPart heavyPaddedCollar;
    private final ModelPart workerBelt;
    private final ModelPart blacksmithApron;
    private final ModelPart farmerHat;
    private final ModelPart bardMantle;
    private final ModelPart medicSash;
    private final ModelPart traderCoatPanel;
    /** ItemInHandLayer 复用模型；必须在每个实体的 prepare 阶段重新赋值。 */
    private boolean hideHeldItems;

    public NpcModel(ModelPart root) {
        super(root);
        this.modelRoot = root;
        // HumanoidModel(1.21.1) 所有部件都是 root 的直接子部件
        this.jacket = root.getChild("jacket");
        this.leftSleeve = root.getChild("left_sleeve");
        this.rightSleeve = root.getChild("right_sleeve");
        this.leftPants = root.getChild("left_pants");
        this.rightPants = root.getChild("right_pants");
        this.warriorTabard = root.getChild("warrior_tabard");
        this.archerShoulderCape = root.getChild("archer_shoulder_cape");
        this.heavyPaddedCollar = root.getChild("heavy_padded_collar");
        this.workerBelt = root.getChild("worker_belt");
        this.blacksmithApron = root.getChild("blacksmith_apron");
        this.farmerHat = root.getChild("farmer_hat");
        this.bardMantle = root.getChild("bard_mantle");
        this.medicSash = root.getChild("medic_sash");
        this.traderCoatPanel = root.getChild("trader_coat_panel");
    }

    @Override
    protected Iterable<ModelPart> bodyParts() {
        return ImmutableList.of(body, rightArm, leftArm, rightLeg, leftLeg, hat,
                jacket, leftSleeve, rightSleeve, leftPants, rightPants,
                warriorTabard, archerShoulderCape, heavyPaddedCollar, workerBelt,
                blacksmithApron, farmerHat, bardMantle, medicSash, traderCoatPanel);
    }

    /** 细手臂 + 全 outer layer 的 LayerDefinition。1.21.1 所有部件必须是 root 直接子部件。 */
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        CubeDeformation outer = new CubeDeformation(0.25F);
        CubeDeformation hatOuter = new CubeDeformation(0.5F);

        // Head + hat — 原点即可
        root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("hat",
                CubeListBuilder.create().texOffs(32, 0)
                        .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, hatOuter),
                PartPose.ZERO);

        // Body + jacket
        root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(16, 16)
                        .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("jacket",
                CubeListBuilder.create().texOffs(16, 32)
                        .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, outer),
                PartPose.ZERO);

        // Left arm + sleeve (slim: 3px)
        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(32, 48)
                        .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F),
                PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_sleeve",
                CubeListBuilder.create().texOffs(48, 48)
                        .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, outer),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        // Right arm + sleeve (slim: 3px)
        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(40, 16)
                        .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_sleeve",
                CubeListBuilder.create().texOffs(40, 32)
                        .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, outer),
                PartPose.offset(-5.0F, 2.0F, 0.0F));

        // Left leg + pants
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(16, 48)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
                PartPose.offset(1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_pants",
                CubeListBuilder.create().texOffs(0, 48)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, outer),
                PartPose.offset(1.9F, 12.0F, 0.0F));

        // Right leg + pants
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(0, 16)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
                PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("right_pants",
                CubeListBuilder.create().texOffs(0, 32)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, outer),
                PartPose.offset(-1.9F, 12.0F, 0.0F));

        // 以下只保留职业服装轮廓，不包含盔甲、武器、工具或可独立持有的随身物品。
        root.addOrReplaceChild("warrior_tabard",
                CubeListBuilder.create().texOffs(64, 0)
                        .addBox(-3.5F, 8.0F, -2.35F, 7.0F, 7.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        root.addOrReplaceChild("archer_shoulder_cape",
                CubeListBuilder.create().texOffs(80, 0)
                        .addBox(-3.5F, 0.0F, 2.15F, 7.0F, 9.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        root.addOrReplaceChild("heavy_padded_collar",
                CubeListBuilder.create().texOffs(96, 0)
                        .addBox(-4.5F, -0.8F, -2.5F, 9.0F, 3.0F, 5.0F, new CubeDeformation(0.15F)),
                PartPose.ZERO);
        root.addOrReplaceChild("worker_belt",
                CubeListBuilder.create().texOffs(64, 18)
                        .addBox(-4.0F, 7.5F, -2.3F, 8.0F, 2.0F, 5.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        root.addOrReplaceChild("blacksmith_apron",
                CubeListBuilder.create().texOffs(80, 18)
                        .addBox(-3.5F, 2.0F, -2.45F, 7.0F, 13.0F, 1.0F, new CubeDeformation(0.03F)),
                PartPose.ZERO);
        root.addOrReplaceChild("farmer_hat",
                CubeListBuilder.create().texOffs(96, 18)
                        .addBox(-5.0F, -8.8F, -5.0F, 10.0F, 1.0F, 10.0F, new CubeDeformation(0.05F))
                        .texOffs(96, 30)
                        .addBox(-3.8F, -11.5F, -3.8F, 7.6F, 3.0F, 7.6F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        root.addOrReplaceChild("bard_mantle",
                CubeListBuilder.create().texOffs(64, 36)
                        .addBox(-4.5F, -0.5F, -2.5F, 9.0F, 4.0F, 5.0F, new CubeDeformation(0.12F)),
                PartPose.ZERO);
        root.addOrReplaceChild("medic_sash",
                CubeListBuilder.create().texOffs(96, 44)
                        .addBox(-3.8F, 0.0F, -2.4F, 2.0F, 10.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        root.addOrReplaceChild("trader_coat_panel",
                CubeListBuilder.create().texOffs(112, 44)
                        .addBox(-3.5F, 7.5F, -2.4F, 7.0F, 7.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 128, 64);
    }

    // 1.21.1 的 HumanoidMobRenderer 不会设置手臂姿态（getArmPose 已从渲染器移入 Player 等实体类），
    // 需自行在 setupAnim 之前赋值，否则 NPC 拉弓/持弩时手臂不抬起
    @Override
    public void prepareMobModel(NpcBase entity, float limbSwing, float limbSwingAmount, float partialTick) {
        hideHeldItems = entity.isSleeping();
        HumanoidModel.ArmPose mainPose = hideHeldItems
                ? HumanoidModel.ArmPose.EMPTY : armPoseFor(entity, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offPose = hideHeldItems
                ? HumanoidModel.ArmPose.EMPTY : armPoseFor(entity, InteractionHand.OFF_HAND);
        if (mainPose.isTwoHanded()) {
            offPose = entity.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            this.rightArmPose = mainPose;
            this.leftArmPose = offPose;
        } else {
            this.rightArmPose = offPose;
            this.leftArmPose = mainPose;
        }
        super.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
    }

    /**
     * NPC 睡觉时仍保留真实装备和工具，只在客户端隐藏手持物。
     * 不能临时清空装备槽，否则会造成服务端状态、掉落和多人同步问题。
     */
    @Override
    public void translateToHand(HumanoidArm side, PoseStack poseStack) {
        if (hideHeldItems) {
            poseStack.translate(0.0D, -1024.0D, 0.0D);
            return;
        }
        super.translateToHand(side, poseStack);
    }

    private static HumanoidModel.ArmPose armPoseFor(NpcBase entity, InteractionHand hand) {
        ItemStack stack = entity.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
            UseAnim useAnim = stack.getUseAnimation();
            if (useAnim == UseAnim.BOW) {
                return HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            if (useAnim == UseAnim.CROSSBOW) {
                return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            }
        } else if (!entity.swinging && stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public void setupAnim(NpcBase entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // bendUpperBody 会平移原版直连骨骼以模拟腰部支点；模型实例会在多个实体和帧之间复用，
        // 因此必须先恢复烘焙姿势，否则骑乘时的微小前倾会逐帧累加，最终让上半身漂到空中。
        this.modelRoot.getAllParts().forEach(ModelPart::resetPose);
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        applyGenderShape(entity);
        if (entity.isPassenger()) applyMountedPose();
        // 脸和发型由独立 NpcFaceLayer 渲染，避免基础职业贴图的旧头发覆盖新人物层。
        hat.visible = false;
        if (!entity.isSleeping()) applyProfessionAction(entity, ageInTicks);

        // outer layer 跟随 inner layer
        if (jacket != null) jacket.copyFrom(body);
        if (leftSleeve != null) leftSleeve.copyFrom(leftArm);
        if (rightSleeve != null) rightSleeve.copyFrom(rightArm);
        if (leftPants != null) leftPants.copyFrom(leftLeg);
        if (rightPants != null) rightPants.copyFrom(rightLeg);

        updateProfessionParts(entity);
    }

    /**
     * 保留原版人形骨架与护甲槽兼容，只用克制的肩宽、躯干和下肢比例区分体型。
     * 每帧先恢复默认值，避免渲染器复用模型时把上一名 NPC 的比例带给下一名。
     */
    private void applyGenderShape(NpcBase entity) {
        body.xScale = 1.0F;
        body.yScale = 1.0F;
        body.zScale = 1.0F;
        leftArm.xScale = rightArm.xScale = 1.0F;
        leftArm.yScale = rightArm.yScale = 1.0F;
        leftArm.zScale = rightArm.zScale = 1.0F;
        leftLeg.xScale = rightLeg.xScale = 1.0F;
        leftLeg.yScale = rightLeg.yScale = 1.0F;
        leftLeg.zScale = rightLeg.zScale = 1.0F;
        leftArm.x = 5.0F;
        rightArm.x = -5.0F;
        leftLeg.x = 1.9F;
        rightLeg.x = -1.9F;

        if (entity.getGender() != NpcGender.FEMALE) return;

        // 中世纪劳动服/长袍下的自然轮廓：略窄肩与躯干，不做夸张现代化曲线。
        body.xScale = 0.94F;
        body.zScale = 0.96F;
        leftArm.xScale = rightArm.xScale = 0.92F;
        leftArm.zScale = rightArm.zScale = 0.95F;
        leftLeg.xScale = rightLeg.xScale = 0.96F;
        leftArm.x = 4.72F;
        rightArm.x = -4.72F;
        leftLeg.x = 1.82F;
        rightLeg.x = -1.82F;
    }

    /** 稍微收拢原版骑乘腿角，减少腿穿入驴腹和长衣摆僵直横切。 */
    private void applyMountedPose() {
        rightLeg.xRot = -1.08F;
        leftLeg.xRot = -1.08F;
        rightLeg.yRot = 0.30F;
        leftLeg.yRot = -0.30F;
        rightLeg.zRot = 0.06F;
        leftLeg.zRot = -0.06F;
        bendUpperBody(0.04F);
    }

    /** 职业服装层随人物骨骼移动，同时只显示当前职业对应的一组。 */
    private void updateProfessionParts(NpcBase entity) {
        warriorTabard.visible = false;
        archerShoulderCape.visible = false;
        heavyPaddedCollar.visible = false;
        workerBelt.visible = false;
        blacksmithApron.visible = false;
        farmerHat.visible = false;
        bardMantle.visible = false;
        medicSash.visible = false;
        traderCoatPanel.visible = false;

        switch (entity.getProfession()) {
            case NONE -> { }
            case WARRIOR -> warriorTabard.visible = true;
            case ARCHER -> archerShoulderCape.visible = true;
            case HEAVY -> heavyPaddedCollar.visible = true;
            case WORKER -> workerBelt.visible = true;
            case BLACKSMITH -> blacksmithApron.visible = true;
            case FARMER -> farmerHat.visible = entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
            case BARD -> bardMantle.visible = true;
            case MEDIC -> medicSash.visible = true;
            case TRADER -> traderCoatPanel.visible = true;
            case COURIER -> {
                traderCoatPanel.visible = true;
                workerBelt.visible = true;
            }
        }

        warriorTabard.copyFrom(body);
        archerShoulderCape.copyFrom(body);
        heavyPaddedCollar.copyFrom(body);
        workerBelt.copyFrom(body);
        blacksmithApron.copyFrom(body);
        bardMantle.copyFrom(body);
        farmerHat.copyFrom(head);

        copyBodyTransform(medicSash, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -0.28F);
        traderCoatPanel.copyFrom(body);
    }

    private void copyBodyTransform(ModelPart part, float x, float y, float z,
                                   float xRot, float yRot, float zRot) {
        part.x = body.x + x;
        part.y = body.y + y;
        part.z = body.z + z;
        part.xRot = body.xRot + xRot;
        part.yRot = body.yRot + yRot;
        part.zRot = body.zRot + zRot;
        part.xScale = body.xScale;
        part.yScale = body.yScale;
        part.zScale = body.zScale;
    }

    /**
     * 在原版行走、骑乘和持物姿势之上叠加职业动作。
     * 动作仅调整人体骨骼，手上的武器和工具仍由原版 ItemInHandLayer 渲染。
     */
    private void applyProfessionAction(NpcBase entity, float ageInTicks) {
        NpcAction action = entity.getVisualAction();
        if (action == NpcAction.NONE) return;

        float progress = entity.getActionProgress(ageInTicks - entity.tickCount);
        float arc = Mth.sin(progress * Mth.PI);
        float pulse = Mth.sin(progress * Mth.PI * 4.0F);

        switch (action) {
            case GREET -> {
                rightArm.xRot = -1.35F;
                rightArm.yRot = -0.15F;
                rightArm.zRot = 0.35F + pulse * 0.12F;
            }
            case HEAD_NOD -> head.xRot += arc * 0.32F;
            case COURTESY_BOW -> {
                // 克制的腰礼：围绕腰部前倾，头部略作反向补偿。
                head.xRot -= arc * 0.06F;
                rightArm.xRot += arc * 0.12F;
                leftArm.xRot += arc * 0.12F;
                bendUpperBody(arc * 0.22F);
            }
            case CHEST_SALUTE -> {
                rightArm.xRot = -1.12F;
                rightArm.yRot = -0.62F;
                rightArm.zRot = 0.18F;
                head.xRot += arc * 0.10F;
            }
            case NOBLE_ACKNOWLEDGE -> {
                head.xRot += arc * 0.16F;
                rightArm.xRot = -0.55F - arc * 0.22F;
                rightArm.yRot = -0.28F;
            }
            case ROYAL_WAVE -> {
                rightArm.xRot = -1.45F;
                rightArm.yRot = -0.12F;
                rightArm.zRot = 0.22F + pulse * 0.07F;
                leftArm.xRot = 0.05F;
                head.yRot += pulse * 0.025F;
            }
            case MELEE_SLASH -> {
                body.yRot -= arc * 0.32F;
                rightArm.xRot -= arc * 1.0F;
                rightArm.yRot -= arc * 0.45F;
            }
            case HEAVY_STRIKE -> {
                rightArm.xRot = -2.65F + progress * 2.3F;
                leftArm.xRot = rightArm.xRot + 0.18F;
                rightArm.yRot = -0.18F;
                leftArm.yRot = 0.28F;
                bendUpperBody(arc * 0.16F);
            }
            case BOW_RELEASE -> {
                rightArm.xRot -= arc * 0.25F;
                leftArm.xRot -= arc * 0.10F;
                body.yRot += pulse * 0.035F;
            }
            case CROSSBOW_LOAD -> {
                // 弩水平贴近胸口，左手托住弩身，右手向后拉弦。
                rightArm.xRot = -1.05F - progress * 0.28F;
                leftArm.xRot = -1.18F;
                rightArm.yRot = -0.58F + progress * 0.32F;
                leftArm.yRot = 0.72F;
                body.yRot -= progress * 0.06F;
            }
            case MINE, CHOP, TILL, SMITH -> applyTwoHandedToolSwing(progress, action == NpcAction.SMITH);
            case MINE_HIGH, CHOP_HIGH -> applyOverheadToolSwing(progress, arc);
            case PLACE -> {
                rightArm.xRot = -0.65F - arc * 0.75F;
                leftArm.xRot = -0.35F - arc * 0.35F;
                bendUpperBody(arc * 0.28F);
            }
            case PLANT, HARVEST -> {
                head.xRot += arc * 0.24F;
                rightArm.xRot = -0.3F - arc * 0.9F;
                leftArm.xRot = -0.2F - arc * 0.65F;
                bendUpperBody(arc * 0.48F);
            }
            case PERFORM -> {
                body.zRot = pulse * 0.055F;
                rightArm.xRot = -0.85F + pulse * 0.16F;
                rightArm.yRot = -0.35F;
                leftArm.xRot = -1.0F - pulse * 0.12F;
                leftArm.yRot = 0.45F;
            }
            case MEDIC_TEND -> {
                head.xRot += arc * 0.18F;
                rightArm.xRot = -1.05F + pulse * 0.08F;
                leftArm.xRot = -0.85F - pulse * 0.08F;
                rightArm.yRot = -0.32F;
                leftArm.yRot = 0.32F;
                bendUpperBody(arc * 0.22F);
            }
            case TRADE_PRESENT -> {
                rightArm.xRot = -0.95F + pulse * 0.08F;
                leftArm.xRot = -0.95F - pulse * 0.08F;
                rightArm.yRot = -0.28F;
                leftArm.yRot = 0.28F;
            }
            case EAT -> {
                rightArm.xRot = -1.55F + pulse * 0.12F;
                rightArm.yRot = -0.18F;
                head.xRot = -0.08F + pulse * 0.03F;
            }
            case CHARGE -> {
                head.xRot -= 0.12F;
                rightArm.xRot = -1.15F + pulse * 0.08F;
                leftArm.xRot = 0.55F - pulse * 0.08F;
                bendUpperBody(0.24F);
            }
            case BRACE -> {
                rightArm.xRot = -0.92F;
                leftArm.xRot = -0.92F;
                rightArm.yRot = -0.48F;
                leftArm.yRot = 0.48F;
                bendUpperBody(0.08F);
            }
            case NONE -> { }
        }
    }

    private void applyTwoHandedToolSwing(float progress, boolean isSmithing) {
        float strike = progress < 0.45F
                ? Mth.lerp(progress / 0.45F, -0.7F, -2.55F)
                : Mth.lerp((progress - 0.45F) / 0.55F, -2.55F, 0.25F);
        rightArm.xRot = strike;
        leftArm.xRot = strike + 0.18F;
        rightArm.yRot = -0.20F;
        leftArm.yRot = 0.28F;
        bendUpperBody(Mth.sin(progress * Mth.PI) * (isSmithing ? 0.15F : 0.06F));
    }

    /**
     * HumanoidModel 的躯干原点位于肩颈处，直接修改 body.xRot 会让人像从脖子折断。
     * 这里把腰线作为虚拟父骨骼，同时平移躯干、头和双臂，再叠加同一旋转；这样不必
     * 改动原版要求的部件层级，也能让护甲层和手持物继续跟随各自的人体部件。
     */
    private void bendUpperBody(float angle) {
        if (Math.abs(angle) < 1.0E-4F) return;
        float waistY = body.y + 12.0F;
        float waistZ = body.z;
        rotatePartAroundWaist(body, waistY, waistZ, angle);
        rotatePartAroundWaist(head, waistY, waistZ, angle);
        rotatePartAroundWaist(rightArm, waistY, waistZ, angle);
        rotatePartAroundWaist(leftArm, waistY, waistZ, angle);
    }

    private static void rotatePartAroundWaist(ModelPart part, float waistY, float waistZ, float angle) {
        float dy = part.y - waistY;
        float dz = part.z - waistZ;
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        part.y = waistY + dy * cos - dz * sin;
        part.z = waistZ + dy * sin + dz * cos;
        part.xRot += angle;
    }

    /** 高处方块使用举臂动作；不复用地面挖掘的前倾姿态。 */
    private void applyOverheadToolSwing(float progress, float arc) {
        float reach = -2.05F - arc * 0.65F;
        rightArm.xRot = reach;
        leftArm.xRot = reach + 0.16F;
        rightArm.yRot = -0.18F;
        leftArm.yRot = 0.24F;
        head.xRot -= 0.22F + arc * 0.10F;
    }
}
