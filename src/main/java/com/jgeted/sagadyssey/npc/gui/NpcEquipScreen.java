package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.container.NpcEquipMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * NPC 装备界面。
 * 176×222 面板，显示 NPC 装备槽、背包和玩家背包。
 */
public class NpcEquipScreen extends AbstractContainerScreen<NpcEquipMenu> {

    public NpcEquipScreen(NpcEquipMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 260;
        this.inventoryLabelY = 175;
        this.inventoryLabelY = 145;
    }

    @Override
    protected void init() {
        super.init();
        // × 关闭按钮
        addRenderableWidget(Button.builder(
                Component.literal("✕").withStyle(s -> s.withColor(0xFF_FF5555)),
                btn -> this.onClose())
                .bounds(leftPos + imageWidth - 20, topPos + 4, 16, 16)
                .build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int px = leftPos;
        int py = topPos;
        int pw = imageWidth;
        int ph = imageHeight;

        // 全屏半透明遮罩
        graphics.fill(0, 0, this.width, this.height, 0x88_000000);

        // 面板背景
        graphics.fill(px, py, px + pw, py + ph, 0xCC_1A1A2E);

        // 金色边框
        graphics.fill(px - 1, py - 1, px + pw + 1, py, 0xFF_AA5500);
        graphics.fill(px - 1, py + ph, px + pw + 1, py + ph + 1, 0xFF_AA5500);
        graphics.fill(px - 1, py, px, py + ph, 0xFF_AA5500);
        graphics.fill(px + pw, py, px + pw + 1, py + ph, 0xFF_AA5500);

        // 标题（NPC 名字，金色加粗）
        Component titleComp = title.copy().withStyle(style -> style.withBold(true));
        int titleX = px + (pw - font.width(titleComp)) / 2;
        graphics.drawString(font, titleComp, titleX, py + 6, 0xFF_D4A017);

        // 分割线
        graphics.fill(px + 8, py + 20, px + pw - 8, py + 21, 0x55_AA5500);

        // 区域标签
        graphics.drawString(font, Component.literal("护甲"), px + 8, py + 28, 0xFF_888888);
        graphics.drawString(font, Component.literal("武器"), px + 80, py + 28, 0xFF_888888);
        graphics.drawString(font, Component.literal("背包"), px + 8, py + 100, 0xFF_888888);
        graphics.drawString(font, Component.literal("物品栏"), px + 8, py + 175, 0xFF_888888);

        // 槽位边框
        for (Slot slot : this.menu.slots) {
            int x = px + slot.x;
            int y = py + slot.y;
            graphics.fill(x, y, x + 18, y + 18, 0x55_3A3A3A);
            graphics.fill(x, y, x + 18, y + 1, 0xFF_6B6B6B);
            graphics.fill(x, y, x + 1, y + 18, 0xFF_6B6B6B);
            graphics.fill(x, y + 17, x + 18, y + 18, 0xFF_222222);
            graphics.fill(x + 17, y, x + 18, y + 18, 0xFF_222222);
        }

        // 装备槽标签（槽位左上角，深灰不抢眼）
        int labelColor = 0xFF_555555;
        drawSlotLabel(graphics, px + 8,  py + 38, "头", labelColor);
        drawSlotLabel(graphics, px + 26, py + 38, "胸", labelColor);
        drawSlotLabel(graphics, px + 8,  py + 56, "腿", labelColor);
        drawSlotLabel(graphics, px + 26, py + 56, "脚", labelColor);
        drawSlotLabel(graphics, px + 80, py + 38, "主", labelColor);
        drawSlotLabel(graphics, px + 98, py + 38, "副", labelColor);
        drawSlotLabel(graphics, px + 80, py + 56, "弓", labelColor);
        drawSlotLabel(graphics, px + 98, py + 56, "箭", labelColor);
    }

    private void drawSlotLabel(GuiGraphics graphics, int slotX, int slotY, String label, int color) {
        graphics.drawString(this.font, label, slotX + 2, slotY + 2, color);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBg(graphics, delta, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 文字已在 renderBg 中处理
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
