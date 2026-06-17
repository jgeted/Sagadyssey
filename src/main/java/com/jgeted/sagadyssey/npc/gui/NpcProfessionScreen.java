package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.network.NpcProfessionPacket;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC 职业切换界面。
 * 显示所有可选职业及招募费用，点击切换（固定费用 3 绿宝石）。
 */
public class NpcProfessionScreen extends Screen {

    private final int npcId;
    private final String npcName;
    private final String currentProfessionDisplay;

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 170;

    private int panelLeft;
    private int panelTop;

    public NpcProfessionScreen(int npcId, String npcName, String currentProfessionDisplay) {
        super(Component.literal("职业切换"));
        this.npcId = npcId;
        this.npcName = npcName;
        this.currentProfessionDisplay = currentProfessionDisplay;
    }

    @Override
    protected void init() {
        super.init();
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - PANEL_HEIGHT) / 2;

        NpcProfession[] professions = NpcProfession.values();
        int btnW = 76;
        int btnH = 20;
        int startX = panelLeft + 10;
        int startY = panelTop + 50;

        for (int i = 0; i < professions.length; i++) {
            NpcProfession prof = professions[i];
            int col = i % 2;
            int row = i / 2;
            int x = startX + col * (btnW + 4);
            int y = startY + row * (btnH + 4);

            String label = prof.getDisplayName() + " (" + prof.getRecruitmentCost() + "绿宝石)";
            if (prof.getDisplayName().equals(currentProfessionDisplay)) {
                label += " ✓";
            }

            addRenderableWidget(Button.builder(
                    Component.literal(label),
                    btn -> {
                        PacketDistributor.sendToServer(new NpcProfessionPacket(npcId, prof.name()));
                        this.onClose();
                    })
                    .bounds(x, y, btnW, btnH)
                    .build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88_000000);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xCC_1A1A2E);
        // 金色边框
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + PANEL_WIDTH + 1, panelTop, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop + PANEL_HEIGHT, panelLeft + PANEL_WIDTH + 1, panelTop + PANEL_HEIGHT + 1, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop, panelLeft, panelTop + PANEL_HEIGHT, 0xFF_AA5500);
        graphics.fill(panelLeft + PANEL_WIDTH, panelTop, panelLeft + PANEL_WIDTH + 1, panelTop + PANEL_HEIGHT, 0xFF_AA5500);

        // 标题
        Component title = Component.literal("职业切换 - " + npcName).withStyle(s -> s.withBold(true));
        int titleX = panelLeft + (PANEL_WIDTH - font.width(title)) / 2;
        graphics.drawString(font, title, titleX, panelTop + 8, 0xFF_D4A017);

        // 分割线
        graphics.fill(panelLeft + 8, panelTop + 26, panelLeft + PANEL_WIDTH - 8, panelTop + 27, 0x55_AA5500);

        // 费用说明
        Component costText = Component.literal("切换费用：3 绿宝石");
        int costX = panelLeft + (PANEL_WIDTH - font.width(costText)) / 2;
        graphics.drawString(font, costText, costX, panelTop + 32, 0xFF_55FF55);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不绘制原版背景
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
