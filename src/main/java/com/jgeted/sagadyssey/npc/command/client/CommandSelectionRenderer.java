package com.jgeted.sagadyssey.npc.command.client;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.command.ClientCommandGroupState;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.UUID;

/** 手持指挥杖时，在世界中绘制小队颜色框和区域框。 */
@EventBusSubscriber(modid = Sagadyssey.MOD_ID, value = Dist.CLIENT)
public final class CommandSelectionRenderer {
    private CommandSelectionRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.NPC_COMMAND_WAND.get())
                && !mc.player.getOffhandItem().is(ModItems.NPC_COMMAND_WAND.get())) return;

        CommandGroupSyncPayload snapshot = ClientCommandGroupState.snapshot();
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        for (CommandGroupSyncPayload.MemberView member : snapshot.members()) {
            if (member.entityId() < 0) continue;
            Entity entity = mc.level.getEntity(member.entityId());
            if (!(entity instanceof NpcBase) || !entity.getUUID().equals(parseUuid(member.uuid()))) continue;
            float r = ((member.color() >> 16) & 0xFF) / 255.0F;
            float g = ((member.color() >> 8) & 0xFF) / 255.0F;
            float b = (member.color() & 0xFF) / 255.0F;
            AABB box = entity.getBoundingBox().inflate(member.selected() ? 0.13 : 0.05);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, member.selected() ? 1.0F : 0.45F);
            if (member.selected()) {
                LevelRenderer.renderLineBox(pose, lines, box.inflate(0.06), 1.0F, 1.0F, 1.0F, 0.85F);
            }
        }

        if (snapshot.corner1() != null) {
            BlockPos a = snapshot.corner1();
            BlockPos b = snapshot.corner2() != null ? snapshot.corner2() : snapshot.corner1();
            AABB region = new AABB(
                    Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
            int color = snapshot.squads().stream().filter(s -> s.id().equals(snapshot.activeSquadId()))
                    .map(CommandGroupSyncPayload.SquadView::color).findFirst().orElse(0x42A5F5);
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float bl = (color & 0xFF) / 255.0F;
            LevelRenderer.renderLineBox(pose, lines, region, r, g, bl, 1.0F);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return new UUID(0L, 0L); }
    }
}
