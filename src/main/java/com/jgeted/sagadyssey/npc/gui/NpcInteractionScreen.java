package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC 右键交互界面。
 * 显示 NPC 名字、职业和可用操作按钮。
 */
public class NpcInteractionScreen extends Screen {
    private final int npcId;
    private final String npcName;
    private final String professionName;

    public NpcInteractionScreen(int npcId, String npcName, String professionName) {
        super(Component.literal(npcName));
        this.npcId = npcId;
        this.npcName = npcName;
        this.professionName = professionName;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 10;

        addRenderableWidget(Button.builder(
                Component.literal("招募"),
                btn -> {
                    PacketDistributor.sendToServer(new NpcInteractionPacket(npcId, "recruit"));
                    this.onClose();
                })
                .bounds(centerX - 50, buttonY, 100, 20)
                .build());

        addRenderableWidget(Button.builder(
                Component.literal("转职（开发中）"),
                btn -> {
                    PacketDistributor.sendToServer(new NpcInteractionPacket(npcId, "change_profession"));
                    this.onClose();
                })
                .bounds(centerX - 60, buttonY + 25, 120, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染背景遮罩，保持游戏画面清晰可见
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int panelW = 150;
        int panelLeft = centerX - panelW / 2;
        int panelTop = this.height / 2 - 60;
        int panelH = 40;

        // 信息面板（深色背景 + 金色下划线）
        graphics.fill(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, 0xCC000000);
        graphics.fill(panelLeft, panelTop + panelH - 1, panelLeft + panelW, panelTop + panelH, 0xFFAA5500);

        // 标题
        Component title = Component.literal(npcName).withStyle(style -> style.withBold(true));
        int titleX = panelLeft + (panelW - font.width(title)) / 2;
        graphics.drawString(font, title, titleX, panelTop + 5, 0xFFFFFF);

        // 职业
        Component prof = Component.literal("职业：" + professionName);
        int profX = panelLeft + (panelW - font.width(prof)) / 2;
        graphics.drawString(font, prof, profX, panelTop + 21, 0xCCCCCC);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
