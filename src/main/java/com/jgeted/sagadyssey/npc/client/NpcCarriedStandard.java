package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.MaterialSet;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/** A carried standard: real banner patterns, a visible shaft and a shorter hanging cloth. */
final class NpcCarriedStandard {
    static final float GRIP_HEIGHT=.9F;
    private final MaterialSet materials;
    private final BannerModel pole;
    private final BannerFlagModel flag;

    NpcCarriedStandard(EntityRendererProvider.Context context){
        materials=context.getMaterials();
        // Bake private copies of the native layers. No world banner model or texture is changed.
        pole=new CarriedPole(context.bakeLayer(ModelLayers.STANDING_BANNER));
        flag=new CarriedCloth(context.bakeLayer(ModelLayers.STANDING_BANNER_FLAG));
    }

    void submit(ItemStack item,PoseStack poses,SubmitNodeCollector collector,int light,int outline){
        if(!(item.getItem() instanceof BannerItem banner))return;
        var material=ModelBakery.BANNER_BASE;
        poses.pushPose();
        ItemTransform.NO_TRANSFORM.apply(false,poses.last());
        poses.translate(.5F,0,.5F);
        poses.scale(2F/3,-2F/3,-2F/3);
        collector.submitModel(pole,Unit.INSTANCE,poses,material.renderType(RenderTypes::entitySolid),light,
                OverlayTexture.NO_OVERLAY,-1,materials.get(material),outline,null);
        BannerRenderer.submitPatterns(materials,poses,collector,light,OverlayTexture.NO_OVERLAY,flag,0F,material,true,
                banner.getColor(),item.getOrDefault(DataComponents.BANNER_PATTERNS,BannerPatternLayers.EMPTY),false,null,outline);
        poses.popPose();
    }

    private static final class CarriedPole extends BannerModel {
        private CarriedPole(ModelPart root){super(root);}
        @Override public void setupAnim(Unit state){
            super.setupAnim(state);
            // Stretch the original wood UV rather than sampling outside its native material patch.
            root().getChild("pole").yScale=60F/42;
            root().getChild("bar").y=-18;
        }
    }
    private static final class CarriedCloth extends BannerFlagModel {
        private CarriedCloth(ModelPart root){super(root);}
        @Override public void setupAnim(Float phase){
            super.setupAnim(phase);
            var cloth=root().getChild("flag");cloth.y=-62;cloth.yScale=.55F;
        }
    }
}
