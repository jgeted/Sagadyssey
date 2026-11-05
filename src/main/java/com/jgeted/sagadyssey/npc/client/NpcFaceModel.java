package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.EquipmentSlot;

/** 独立于职业贴图的脸、头发和头巾模型。 */
public final class NpcFaceModel extends HumanoidModel<NpcBase> {
    private final ModelPart nose;
    private final ModelPart beard;
    private final ModelPart fringeLeft;
    private final ModelPart fringeRight;
    private final ModelPart sideLockLeft;
    private final ModelPart sideLockRight;
    private final ModelPart braidLeft;
    private final ModelPart braidRight;
    private final ModelPart braidLeftMid;
    private final ModelPart braidRightMid;
    private final ModelPart braidLeftTip;
    private final ModelPart braidRightTip;
    private final ModelPart veilBack;
    private final ModelPart veilLeft;
    private final ModelPart veilRight;

    public NpcFaceModel(ModelPart root) {
        super(root);
        this.nose = head.getChild("nose");
        this.beard = head.getChild("beard");
        this.fringeLeft = head.getChild("fringe_left");
        this.fringeRight = head.getChild("fringe_right");
        this.sideLockLeft = head.getChild("side_lock_left");
        this.sideLockRight = head.getChild("side_lock_right");
        this.braidLeft = head.getChild("braid_left");
        this.braidRight = head.getChild("braid_right");
        this.braidLeftMid = head.getChild("braid_left_mid");
        this.braidRightMid = head.getChild("braid_right_mid");
        this.braidLeftTip = head.getChild("braid_left_tip");
        this.braidRightTip = head.getChild("braid_right_tip");
        this.veilBack = head.getChild("veil_back");
        this.veilLeft = head.getChild("veil_left");
        this.veilRight = head.getChild("veil_right");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                                new CubeDeformation(0.035F)), PartPose.ZERO);
        head.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(0, 16)
                        .addBox(-1.0F, -4.2F, -4.35F, 2.0F, 2.0F, 1.0F,
                                new CubeDeformation(0.02F)), PartPose.ZERO);
        head.addOrReplaceChild("beard", CubeListBuilder.create().texOffs(8, 16)
                        .addBox(-3.0F, -2.45F, -4.26F, 6.0F, 2.65F, 0.34F,
                                new CubeDeformation(0.02F)), PartPose.ZERO);
        head.addOrReplaceChild("fringe_left", CubeListBuilder.create().texOffs(24, 16)
                        .addBox(-3.85F, -7.35F, -4.26F, 3.65F, 1.65F, 0.45F,
                                new CubeDeformation(0.02F)), PartPose.rotation(0.0F, 0.0F, -0.035F));
        head.addOrReplaceChild("fringe_right", CubeListBuilder.create().texOffs(28, 16)
                        .addBox(0.2F, -7.2F, -4.26F, 3.65F, 1.55F, 0.45F,
                                new CubeDeformation(0.02F)), PartPose.rotation(0.0F, 0.0F, 0.035F));
        head.addOrReplaceChild("side_lock_left", CubeListBuilder.create().texOffs(24, 20)
                        .addBox(3.72F, -5.65F, -3.65F, 0.65F, 4.2F, 1.35F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, -0.025F));
        head.addOrReplaceChild("side_lock_right", CubeListBuilder.create().texOffs(29, 20)
                        .addBox(-4.37F, -5.65F, -3.65F, 0.65F, 4.2F, 1.35F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, 0.025F));
        head.addOrReplaceChild("braid_left", CubeListBuilder.create().texOffs(24, 16)
                        .addBox(3.15F, -2.5F, -0.65F, 1.1F, 2.5F, 1.1F,
                                new CubeDeformation(0.03F)), PartPose.ZERO);
        head.addOrReplaceChild("braid_right", CubeListBuilder.create().texOffs(30, 16)
                        .addBox(-4.25F, -2.5F, -0.65F, 1.1F, 2.5F, 1.1F,
                                new CubeDeformation(0.03F)), PartPose.ZERO);
        head.addOrReplaceChild("braid_left_mid", CubeListBuilder.create().texOffs(25, 20)
                        .addBox(3.04F, -0.1F, -0.45F, 1.0F, 2.5F, 1.0F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, 0.045F));
        head.addOrReplaceChild("braid_right_mid", CubeListBuilder.create().texOffs(31, 20)
                        .addBox(-4.04F, -0.1F, -0.45F, 1.0F, 2.5F, 1.0F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, -0.045F));
        head.addOrReplaceChild("braid_left_tip", CubeListBuilder.create().texOffs(26, 24)
                        .addBox(2.95F, 2.25F, -0.15F, 0.85F, 2.35F, 0.85F,
                                new CubeDeformation(0.02F)), PartPose.rotation(0.0F, 0.0F, 0.085F));
        head.addOrReplaceChild("braid_right_tip", CubeListBuilder.create().texOffs(32, 24)
                        .addBox(-3.80F, 2.25F, -0.15F, 0.85F, 2.35F, 0.85F,
                                new CubeDeformation(0.02F)), PartPose.rotation(0.0F, 0.0F, -0.085F));
        head.addOrReplaceChild("veil_back", CubeListBuilder.create().texOffs(36, 16)
                        .addBox(-3.85F, -7.2F, 3.82F, 7.7F, 8.2F, 0.55F,
                                new CubeDeformation(0.035F)), PartPose.ZERO);
        head.addOrReplaceChild("veil_left", CubeListBuilder.create().texOffs(44, 16)
                        .addBox(3.78F, -6.45F, -1.2F, 0.48F, 7.2F, 4.85F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, -0.025F));
        head.addOrReplaceChild("veil_right", CubeListBuilder.create().texOffs(50, 16)
                        .addBox(-4.26F, -6.45F, -1.2F, 0.48F, 7.2F, 4.85F,
                                new CubeDeformation(0.025F)), PartPose.rotation(0.0F, 0.0F, 0.025F));

        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0)
                        .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                                new CubeDeformation(0.18F)), PartPose.ZERO);
        addEmptyHumanoidParts(root);
        // 故意保留 64x32 UV 基准：男女脸资源均为 256x128，因此同一头部面采样 32x32 像素。
        // 若把这里同步改成资源尺寸，正脸会重新退回 8x8 的有效密度。
        return LayerDefinition.create(mesh, 64, 32);
    }

    private static void addEmptyHumanoidParts(PartDefinition root) {
        root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
    }

    public void configure(NpcBase entity) {
        NpcGender gender = entity.getGender();
        NpcFaceVariant face = entity.getFaceVariant();
        boolean female = gender == NpcGender.FEMALE;
        boolean helmeted = !entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        hat.visible = !helmeted;

        // 男女均由同一套 32x32 像素五官表达；禁用旧立体鼻，避免男性退回村民画风。
        nose.visible = false;
        float faceWidth = switch (face) {
            case BROAD -> 1.20F;
            case FAIR -> 0.82F;
            default -> 1.0F;
        };
        float faceLength = face == NpcFaceVariant.LONG ? 1.24F : 1.0F;
        float faceDepth = switch (face) {
            case LONG, WEATHERED -> 1.18F;
            case FAIR -> 0.78F;
            default -> 1.0F;
        };
        nose.xScale = faceWidth;
        nose.yScale = faceLength;
        nose.zScale = faceDepth;
        beard.visible = !female && (face == NpcFaceVariant.BROAD
                || face == NpcFaceVariant.WEATHERED || face == NpcFaceVariant.ELDER);
        beard.xScale = face == NpcFaceVariant.BROAD ? 1.05F : 0.96F;
        beard.yScale = face == NpcFaceVariant.ELDER ? 1.18F : 0.88F;
        beard.zScale = 1.0F;
        boolean braided = female && !helmeted && (face == NpcFaceVariant.YOUNG
                || face == NpcFaceVariant.FAIR || face == NpcFaceVariant.BROAD);
        boolean hairVisible = female && !helmeted;
        fringeLeft.visible = hairVisible;
        fringeRight.visible = hairVisible;
        sideLockLeft.visible = braided;
        sideLockRight.visible = braided;
        braidLeft.visible = braided;
        braidRight.visible = braided && face != NpcFaceVariant.BROAD;
        braidLeftMid.visible = braidLeft.visible;
        braidRightMid.visible = braidRight.visible;
        braidLeftTip.visible = braidLeft.visible;
        braidRightTip.visible = braidRight.visible;
        // 旧头巾由三块宽平面组成，近距离或骑乘插值时会被放大成遮屏的棕色方板。
        // 基础帽层已经包含头巾纹理，先停用额外几何，保留正常头部轮廓。
        veilBack.visible = false;
        veilLeft.visible = false;
        veilRight.visible = false;
    }
}
