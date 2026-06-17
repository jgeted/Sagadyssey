package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.network.NpcTradePacket;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.trade.NpcTradeOffer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NPC 交易界面。接收已固定数量的交易列表，直接渲染。
 */
public class NpcTradeScreen extends Screen {

    private final int npcId;
    private final String npcName;
    private final NpcProfession profession;
    private int displayLevel;
    private int displayExp;
    private final List<NpcTradeOffer> trades;

    private static final int PANEL_WIDTH = 196;
    private static final int LINE_H = 24;
    private static final int HEADER_H = 66;
    private static final int MAX_VISIBLE_TRADES = 7;

    private int panelLeft;
    private int panelTop;
    private int scrollOffset = 0;

    public NpcTradeScreen(int npcId, String npcName, String professionDisplayName,
                          int displayLevel, int displayExp, List<NpcTradeOffer> trades) {
        super(Component.literal("交易 - " + npcName));
        this.npcId = npcId;
        this.npcName = npcName;
        this.profession = NpcProfession.fromDisplayName(professionDisplayName);
        this.displayLevel = displayLevel;
        this.displayExp = displayExp;
        this.trades = trades;
    }

    /** 服务端回传新经验后调用，刷新进度条 */
    public void onExperienceUpdated(int newExp, int newLevel) {
        this.displayExp = newExp;
        this.displayLevel = newLevel;
    }

    @Override
    protected void init() {
        super.init();
        int extraRows = (profession == NpcProfession.TRADER) ? 2 : 0;
        int visibleTrades = Math.min(trades.size(), MAX_VISIBLE_TRADES);
        int rows = extraRows + visibleTrades;
        int panelHeight = HEADER_H + rows * LINE_H + 16;
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - panelHeight) / 2;

        // 商人货币兑换（固定两项，不计入 activeTrades）
        if (profession == NpcProfession.TRADER) {
            addCurrencyButton(0, "4 绿宝石 → 1 金锭", -1);
            addCurrencyButton(1, "1 金锭 → 3 绿宝石", -2);
        }

        // 职业交易（仅可见范围）
        int end = Math.min(scrollOffset + MAX_VISIBLE_TRADES, trades.size());
        for (int i = scrollOffset; i < end; i++) {
            int displayRow = extraRows + (i - scrollOffset);
            int y = panelTop + HEADER_H + displayRow * LINE_H;
            int btnW = 48;
            int btnX = panelLeft + PANEL_WIDTH - btnW - 12;
            int index = i;
            addRenderableWidget(Button.builder(
                    Component.literal("交易"),
                    btn -> {
                        PacketDistributor.sendToServer(new NpcTradePacket(npcId, index));
                    })
                    .bounds(btnX, y, btnW, 20)
                    .build());
        }

        // × 关闭按钮
        addRenderableWidget(Button.builder(
                Component.literal("✕").withStyle(s -> s.withColor(0xFF_FF5555)),
                btn -> this.onClose())
                .bounds(panelLeft + PANEL_WIDTH - 20, panelTop + 4, 16, 16)
                .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int extraRows = (profession == NpcProfession.TRADER) ? 2 : 0;
        int maxScroll = Math.max(0, trades.size() + extraRows - MAX_VISIBLE_TRADES);
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scrollOffset = Math.clamp(scrollOffset - (int) scrollY, 0, maxScroll);
        this.rebuildWidgets();
        return true;
    }

    private void addCurrencyButton(int row, String label, int tradeIndex) {
        int y = panelTop + HEADER_H + row * LINE_H;
        int btnW = PANEL_WIDTH - 24;
        int btnX = panelLeft + 12;
        addRenderableWidget(Button.builder(
                Component.literal(label),
                btn -> {
                    PacketDistributor.sendToServer(new NpcTradePacket(npcId, tradeIndex));
                })
                .bounds(btnX, y, btnW, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int extraRows = (profession == NpcProfession.TRADER) ? 2 : 0;
        int visibleTrades = Math.min(trades.size(), MAX_VISIBLE_TRADES);
        int rows = extraRows + visibleTrades;
        int panelHeight = HEADER_H + rows * LINE_H + 16;

        graphics.fill(0, 0, this.width, this.height, 0x88_000000);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xCC_1A1A2E);
        // 金色边框
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + PANEL_WIDTH + 1, panelTop, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop + panelHeight, panelLeft + PANEL_WIDTH + 1, panelTop + panelHeight + 1, 0xFF_AA5500);
        graphics.fill(panelLeft - 1, panelTop, panelLeft, panelTop + panelHeight, 0xFF_AA5500);
        graphics.fill(panelLeft + PANEL_WIDTH, panelTop, panelLeft + PANEL_WIDTH + 1, panelTop + panelHeight, 0xFF_AA5500);

        // 标题
        Component title = Component.literal("交易 - " + npcName).withStyle(s -> s.withBold(true));
        int titleX = panelLeft + (PANEL_WIDTH - font.width(title)) / 2;
        graphics.drawString(font, title, titleX, panelTop + 8, 0xFF_D4A017);

        // 职业 + 等级
        String levelTitle = profession.getDisplayName() + "  Lv." + displayLevel + " " + getLevelTitleText();
        Component prof = Component.literal(levelTitle);
        int profX = panelLeft + (PANEL_WIDTH - font.width(prof)) / 2;
        graphics.drawString(font, prof, profX, panelTop + 24, 0xCC_CCCCCC);

        // XP 进度条
        drawXpBar(graphics, panelLeft + 12, panelTop + 38, PANEL_WIDTH - 24, 6);

        // 分割线
        graphics.fill(panelLeft + 8, panelTop + 48, panelLeft + PANEL_WIDTH - 8, panelTop + 49, 0x55_AA5500);

        // 货币兑换标签
        if (profession == NpcProfession.TRADER) {
            Component exch = Component.literal("— 货币兑换 —").withStyle(s -> s.withColor(0xFF_AA5500));
            int exchX = panelLeft + (PANEL_WIDTH - font.width(exch)) / 2;
            graphics.drawString(font, exch, exchX, panelTop + 54, 0xFF_AA5500);
        }

        if (trades.isEmpty() && extraRows == 0) {
            Component empty = Component.literal("该职业当前等级无可用交易");
            int emptyX = panelLeft + (PANEL_WIDTH - font.width(empty)) / 2;
            graphics.drawString(font, empty, emptyX, panelTop + 64, 0xAA_AAAAAA);
        }

        // 交易列表（仅可见范围）
        int costX = panelLeft + 12;
        int arrowX = panelLeft + 100;
        int resultX = panelLeft + 120;
        int end = Math.min(scrollOffset + MAX_VISIBLE_TRADES, trades.size());
        for (int i = scrollOffset; i < end; i++) {
            NpcTradeOffer offer = trades.get(i);
            int displayRow = extraRows + (i - scrollOffset);
            int y = panelTop + HEADER_H + displayRow * LINE_H;

            graphics.drawString(font, offer.costAmount() + "×", costX, y, 0xFF_FFAAAA);
            graphics.renderItem(offer.costItem(), costX + 20, y - 4);
            graphics.drawString(font, "→", arrowX, y, 0xAA_AAAAAA);
            graphics.drawString(font, offer.resultAmount() + "×", resultX, y, 0xFF_AAFFAA);
            graphics.renderItem(offer.resultItem(), resultX + 20, y - 4);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String getLevelTitleText() {
        return switch (displayLevel) {
            case 1 -> "学徒";
            case 2 -> "熟练";
            case 3 -> "专家";
            case 4 -> "大师";
            default -> "";
        };
    }

    private void drawXpBar(GuiGraphics graphics, int x, int y, int width, int height) {
        if (displayLevel >= 4) {
            graphics.fill(x, y, x + width, y + height, 0xFF_D4A017);
            return;
        }
        int levelMinXp = switch (displayLevel) { case 1 -> 0; case 2 -> 200; case 3 -> 600; default -> 0; };
        int levelMaxXp = switch (displayLevel) { case 1 -> 200; case 2 -> 600; case 3 -> 1400; default -> 200; };
        double progress = (double) (displayExp - levelMinXp) / (levelMaxXp - levelMinXp);
        int filled = (int) (width * Math.max(0.0, Math.min(1.0, progress)));
        graphics.fill(x, y, x + width, y + height, 0x44_333333);
        int barColor = (displayLevel == 3) ? 0xFF_D4A017 : 0xFF_55FF55;
        graphics.fill(x, y, x + filled, y + height, barColor);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean isPauseScreen() { return false; }
}
