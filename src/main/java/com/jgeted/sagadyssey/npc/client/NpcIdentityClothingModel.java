package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/** 覆盖完整躯干、袖子和下装的身份服装模型。 */
public final class NpcIdentityClothingModel extends HumanoidModel<NpcRenderState> {
    private final ModelPart collar;
    private final ModelPart malePanel;
    private final ModelPart femaleSkirtUpper;
    private final ModelPart femaleSkirtLower;
    private final ModelPart femaleSkirtLeft;
    private final ModelPart femaleSkirtRight;
    private final ModelPart femaleApronUpper;
    private final ModelPart femaleApronLower;
    private final ModelPart mantle;
    private final ModelPart cloak;

    private final NpcModel parent;

    public NpcIdentityClothingModel(ModelPart root, NpcModel parent) {
        super(root);
        this.parent = parent;
        this.collar = body.getChild("collar");
        this.malePanel = body.getChild("male_panel");
        this.femaleSkirtUpper = body.getChild("female_skirt_upper");
        this.femaleSkirtLower = body.getChild("female_skirt_lower");
        this.femaleSkirtLeft = root.getChild("female_skirt_left");
        this.femaleSkirtRight = root.getChild("female_skirt_right");
        this.femaleApronUpper = body.getChild("female_apron_upper");
        this.femaleApronLower = body.getChild("female_apron_lower");
        this.mantle = body.getChild("mantle");
        this.cloak = body.getChild("cloak");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        CubeDeformation cloth = new CubeDeformation(0.32F);

        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(16, 16)
                        .addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, cloth),
                PartPose.ZERO);
        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(40, 16)
                        .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, cloth),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(32, 48)
                        .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, cloth),
                PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(0, 16)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F,
                                new CubeDeformation(0.29F)),
                PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(16, 48)
                        .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F,
                                new CubeDeformation(0.29F)),
                PartPose.offset(1.9F, 12.0F, 0.0F));

        body.addOrReplaceChild("collar", CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.5F, -0.5F, -2.5F, 9.0F, 2.0F, 5.0F,
                                new CubeDeformation(0.05F)), PartPose.ZERO);
        body.addOrReplaceChild("male_panel", CubeListBuilder.create().texOffs(24, 0)
                        .addBox(-3.5F, 6.0F, -2.42F, 7.0F, 9.0F, 1.0F,
                                new CubeDeformation(0.04F)), PartPose.ZERO);
        body.addOrReplaceChild("female_skirt_upper", CubeListBuilder.create().texOffs(0, 32)
                        .addBox(-4.0F, 8.0F, -2.2F, 8.0F, 7.0F, 4.4F,
                                new CubeDeformation(0.04F)), PartPose.ZERO);
        body.addOrReplaceChild("female_skirt_lower", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("female_skirt_right", CubeListBuilder.create().texOffs(32, 32)
                        .addBox(-2.28F, 0.0F, -2.28F, 4.45F, 12.0F, 4.55F,
                                new CubeDeformation(0.035F)), PartPose.offset(-1.86F, 12.0F, 0.0F));
        root.addOrReplaceChild("female_skirt_left", CubeListBuilder.create().texOffs(0, 32)
                        .addBox(-2.17F, 0.0F, -2.28F, 4.45F, 12.0F, 4.55F,
                                new CubeDeformation(0.035F)), PartPose.offset(1.86F, 12.0F, 0.0F));
        body.addOrReplaceChild("female_apron_upper", CubeListBuilder.create().texOffs(48, 0)
                        .addBox(-3.05F, 3.5F, -2.55F, 6.1F, 8.5F, 0.5F,
                                new CubeDeformation(0.025F)), PartPose.ZERO);
        body.addOrReplaceChild("female_apron_lower", CubeListBuilder.create().texOffs(48, 16)
                        .addBox(-3.45F, 11.5F, -2.62F, 6.9F, 12.0F, 0.5F,
                                new CubeDeformation(0.025F)), PartPose.ZERO);
        body.addOrReplaceChild("mantle", CubeListBuilder.create().texOffs(0, 44)
                        .addBox(-4.6F, -0.7F, -2.55F, 9.2F, 4.0F, 5.1F,
                                new CubeDeformation(0.08F)), PartPose.ZERO);
        body.addOrReplaceChild("cloak", CubeListBuilder.create().texOffs(24, 44)
                        .addBox(-4.1F, 0.0F, 2.28F, 8.2F, 16.0F, 1.0F,
                                new CubeDeformation(0.05F)), PartPose.ZERO);
        // 男女身份服装统一使用 128x128 资源配合 64x64 UV 基准，取得相同的织物/饰边密度。
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(NpcRenderState state) {
        super.setupAnim(state);
        parent.setupAnim(state);
        head.loadPose(parent.head.storePose());
        body.loadPose(parent.body.storePose());
        leftArm.loadPose(parent.leftArm.storePose());
        rightArm.loadPose(parent.rightArm.storePose());
        leftLeg.loadPose(parent.leftLeg.storePose());
        rightLeg.loadPose(parent.rightLeg.storePose());
        NpcSocialStatus status = state.socialStatus;
        boolean female = state.gender == NpcGender.FEMALE;
        int tier = status.ordinal();
        boolean militaryCut = state.profession == NpcProfession.WARRIOR
                || state.profession == NpcProfession.ARCHER
                || state.profession == NpcProfession.HEAVY;
        boolean longDress = female && !militaryCut;

        collar.visible = tier >= NpcSocialStatus.FREEMAN.ordinal();
        malePanel.visible = !female && tier >= NpcSocialStatus.BURGHER.ordinal();
        femaleSkirtUpper.visible = longDress;
        femaleSkirtLower.visible = false;
        femaleSkirtLeft.visible = longDress;
        femaleSkirtRight.visible = longDress;
        boolean workDress = longDress && tier <= NpcSocialStatus.FREEMAN.ordinal();
        femaleApronUpper.visible = workDress;
        // 骑乘时下摆分片随腿展开，长围裙下片则视为束起，避免贯穿马背。
        femaleApronLower.visible = workDress && !state.mounted;
        mantle.visible = status == NpcSocialStatus.LORD
                || status == NpcSocialStatus.DUKE || status == NpcSocialStatus.KING;
        cloak.visible = status == NpcSocialStatus.DUKE || status == NpcSocialStatus.KING;

        collar.xScale = female ? 0.90F : 1.0F;
        collar.zScale = female ? 0.94F : 1.0F;
        mantle.xScale = female ? 0.90F : 1.0F;
        mantle.zScale = female ? 0.92F : 1.0F;
        cloak.xScale = female ? 0.95F : 1.0F;

        if (longDress) {
            copySkirtTransform(femaleSkirtLeft, leftLeg, state.mounted);
            copySkirtTransform(femaleSkirtRight, rightLeg, state.mounted);
        }
    }

    private static void copySkirtTransform(ModelPart skirt, ModelPart leg, boolean riding) {
        skirt.loadPose(leg.storePose());
        float motionScale = riding ? 0.72F : 0.34F;
        skirt.xRot = leg.xRot * motionScale;
        skirt.yRot = leg.yRot * (riding ? 0.82F : 0.35F);
        skirt.zRot = leg.zRot * 0.35F;
        skirt.xScale *= 1.02F;
        skirt.zScale *= 1.04F;
    }
}
