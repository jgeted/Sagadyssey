package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import com.jgeted.sagadyssey.npc.network.NpcStatsPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC 招募界面。
 * 显示 NPC 属性面板、背包预览和招募按钮。
 * 玩家点击 Hire 后发送 recruit 数据包到服务端。
 */
public class NpcRecruitScreen extends Screen {

    private final int npcId;
    private final String npcName;
    private final String professionName;

    // 属性数据
    private final float currentHp;
    private final float maxHp;
    private final float attackDamage;
    private final float speed;
    private final float armor;
    private final int npcLevel;
    private final int experience;
    private final int kills;
    private final int moral;
    private final int recruitmentCost;

    // 布局常量
    private static final int PANEL_WIDTH = 176;

    // 动态计算的坐标
    private int panelLeft;
    private int panelTop;

    public NpcRecruitScreen(NpcStatsPayload data) {
        super(Component.literal(data.npcName()));
        this.npcId = data.npcId();
        this.npcName = data.npcName();
        this.professionName = data.professionName();
        this.currentHp = data.currentHp();
        this.maxHp = data.maxHp();
        this.attackDamage = data.attackDamage();
        this.speed = data.speed();
        this.armor = data.armor();
        this.npcLevel = data.npcLevel();
        this.experience = data.experience();
        this.kills = data.kills();
        this.moral = data.moral();
        this.recruitmentCost = data.recruitmentCost();
    }

    @Override
    protected void init() {
        super.init();
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - 150) / 2;

        // Hire 按钮（放在属性区域下方）
        int btnW = 120;
        int btnX = panelLeft + (PANEL_WIDTH - btnW) / 2;
        int btnY = panelTop + 118;

        addRenderableWidget(Button.builder(
                Component.literal("招募（花费 " + recruitmentCost + " 绿宝石）"),
                btn -> {
                    PacketDistributor.sendToServer(new NpcInteractionPacket(npcId, "recruit"));
                    this.onClose();
                })
                .bounds(btnX, btnY, btnW, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 自定义背景（比原版浅，不完全遮住游戏画面）
        graphics.fill(0, 0, this.width, this.height, 0x88_000000);

        // === 主面板背景 ===
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 150, 0xCC_1A1A2E);
        // 金色边框
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + PANEL_WIDTH + 1, panelTop, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop + 150, panelLeft + PANEL_WIDTH + 1, panelTop + 151, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop, panelLeft, panelTop + 150, 0xFF_AA5500);
        graphics.fill(panelLeft + PANEL_WIDTH, panelTop, panelLeft + PANEL_WIDTH + 1, panelTop + 150, 0xFF_AA5500);

        // === 标题 ===
        Component title = Component.literal(npcName).withStyle(style -> style.withBold(true));
        int titleX = panelLeft + (PANEL_WIDTH - font.width(title)) / 2;
        graphics.drawString(font, title, titleX, panelTop + 8, 0xFF_D4A017);

        // === 职业 ===
        Component prof = Component.literal("职业：" + professionName);
        int profX = panelLeft + (PANEL_WIDTH - font.width(prof)) / 2;
        graphics.drawString(font, prof, profX, panelTop + 22, 0xCC_CCCCCC);

        // === 分割线 ===
        graphics.fill(panelLeft + 8, panelTop + 36, panelLeft + PANEL_WIDTH - 8, panelTop + 37, 0x55_AA5500);

        // === 属性面板（两列布局） ===
        int col1X = panelLeft + 12;
        int col2X = panelLeft + 92;
        int rowY = panelTop + 42;
        int rowH = 14;

        drawStat(graphics, col1X, rowY, "生命", String.format("%.0f / %.0f", currentHp, maxHp), 0xFF_55FF55);
        drawStat(graphics, col2X, rowY, "等级", String.valueOf(npcLevel), 0xFF_FFFF55);
        rowY += rowH;

        drawStat(graphics, col1X, rowY, "攻击", String.format("%.1f", attackDamage), 0xFF_FF5555);
        drawStat(graphics, col2X, rowY, "经验", String.valueOf(experience), 0xFF_55FFFF);
        rowY += rowH;

        drawStat(graphics, col1X, rowY, "速度", String.format("%.1f", speed), 0xFF_FFFFFF);
        drawStat(graphics, col2X, rowY, "击杀", String.valueOf(kills), 0xFF_FFAA00);
        rowY += rowH;

        drawStat(graphics, col1X, rowY, "护甲", String.format("%.1f", armor), 0xFF_AAAAFF);
        drawStat(graphics, col2X, rowY, "士气", moral + " / 100", 0xFF_FF88FF);
        rowY += rowH;

        // 费用（单独一行，金色）
        Component costText = Component.literal("招募费用：" + recruitmentCost + " 绿宝石")
                .withStyle(style -> style.withColor(0xFF55FF55));
        int costX = panelLeft + (PANEL_WIDTH - font.width(costText)) / 2;
        graphics.drawString(font, costText, costX, panelTop + 105, 0xFF_55FF55);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 绘制单个属性标签：名字（灰色）+ 值（指定颜色） */
    private void drawStat(GuiGraphics graphics, int x, int y, String label, String value, int valueColor) {
        Component labelComp = Component.literal(label + ": ");
        graphics.drawString(font, labelComp, x, y, 0xAA_AAAAAA);
        graphics.drawString(font, Component.literal(value), x + font.width(labelComp), y, valueColor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 覆盖原版背景渲染为空操作。
     * 我们在 render() 中手动绘制半透明遮罩，避免 super.render() 二次绘制时盖掉面板。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不绘制原版背景，面板自身提供深色背景
    }
}
