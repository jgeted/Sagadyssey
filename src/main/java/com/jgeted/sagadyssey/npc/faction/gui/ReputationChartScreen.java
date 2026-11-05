package com.jgeted.sagadyssey.npc.faction.gui;

import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.faction.InterFactionRelation;
import com.jgeted.sagadyssey.npc.faction.StandingLevel;
import com.jgeted.sagadyssey.npc.faction.network.ClientFactionCache;
import com.jgeted.sagadyssey.npc.faction.network.RequestStandingsRefreshPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Full diplomatic overview opened by R: faction roster, reputation and known relations. */
public final class ReputationChartScreen extends Screen {
    private static final int DESIRED_WIDTH = 620;
    private static final int DESIRED_HEIGHT = 360;
    private static final int ROW_HEIGHT = 43;
    private static final int BACKDROP = 0xFF0A0F14;
    private static final int SURFACE = 0xFF151B20;
    private static final int HEADER = 0xFF1D2832;
    private static final int PANEL = 0xFF20282E;
    private static final int PANEL_DEEP = 0xFF11171C;
    private static final int GOLD = 0xFFD7AE62;
    private static final int GOLD_DARK = 0xFF705A34;
    private static final int INK = 0xFFF1E7D1;
    private static final int MUTED = 0xFF9EA5A7;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int rosterWidth;
    private int rosterTop;
    private int rosterBottom;
    private int visibleRows;
    private int scrollRow;
    private Faction selectedFaction;
    private List<Faction> factions = List.of();

    public ReputationChartScreen() {
        super(Component.literal("势力与声望"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(DESIRED_WIDTH, width - 12);
        panelHeight = Math.min(DESIRED_HEIGHT, height - 12);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        rosterWidth = panelWidth < 400 ? 126
                : Math.max(150, Math.min(206, panelWidth * 35 / 100));
        rosterTop = panelTop + 67;
        rosterBottom = panelTop + panelHeight - 17;
        visibleRows = Math.max(1, (rosterBottom - rosterTop) / ROW_HEIGHT);
        refreshFactionList();
        if (selectedFaction == null || factions.stream()
                .noneMatch(value -> value.id().equals(selectedFaction.id()))) {
            selectedFaction = factions.stream().filter(value -> "sagadyssey:player".equals(value.id()))
                    .findFirst().orElse(factions.isEmpty() ? null : factions.getFirst());
        }
        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow()));

        PacketDistributor.sendToServer(new RequestStandingsRefreshPacket());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
                .bounds(panelLeft + panelWidth - 24, panelTop + 8, 16, 16).build());
    }

    private void refreshFactionList() {
        List<Faction> values = new ArrayList<>();
        for (Faction faction : FactionRegistry.getAllFactions()) {
            if (ClientFactionCache.isKnown(faction.id())) values.add(faction);
        }
        values.sort(Comparator.comparing((Faction value) -> !"sagadyssey:player".equals(value.id()))
                .thenComparing(this::displayName));
        factions = List.copyOf(values);
    }

    @Override
    public void tick() {
        refreshFactionList();
        if (selectedFaction != null && factions.stream()
                .noneMatch(value -> value.id().equals(selectedFaction.id()))) {
            selectedFaction = factions.stream().filter(value -> "sagadyssey:player".equals(value.id()))
                    .findFirst().orElse(factions.isEmpty() ? null : factions.getFirst());
        }
        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(0, 0, width, Math.max(34, panelTop), 0xFF111B25);
        graphics.fill(panelLeft + 6, panelTop + 7, panelLeft + panelWidth + 6,
                panelTop + panelHeight + 7, 0x88000000);
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1,
                panelTop + panelHeight + 1, GOLD_DARK);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, SURFACE);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 48, HEADER);
        graphics.fill(panelLeft, panelTop + 46, panelLeft + panelWidth, panelTop + 48, GOLD);

        graphics.drawString(font, Component.literal("势力与声望")
                        .withStyle(style -> style.withBold(true)),
                panelLeft + 16, panelTop + 10, INK);
        graphics.drawString(font, panelWidth < 400
                        ? "仅显示你目前知道的政治信息"
                        : "这是你目前知道的政治世界，而不是后台全知地图",
                panelLeft + 16, panelTop + 28, MUTED);

        renderRoster(graphics, mouseX, mouseY);
        renderDetails(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRoster(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelLeft + 12;
        int width = rosterWidth - 18;
        graphics.drawString(font, Component.literal("已知势力  " + factions.size())
                .withStyle(style -> style.withBold(true)), x, panelTop + 54, GOLD);
        graphics.fill(x, rosterTop - 4, x + width, rosterBottom, PANEL_DEEP);

        int end = Math.min(factions.size(), scrollRow + visibleRows);
        for (int index = scrollRow; index < end; index++) {
            Faction faction = factions.get(index);
            int y = rosterTop + (index - scrollRow) * ROW_HEIGHT;
            renderRosterRow(graphics, faction, x + 3, y, width - 6,
                    mouseX >= x + 3 && mouseX < x + width - 3
                            && mouseY >= y && mouseY < y + ROW_HEIGHT - 3);
        }
        if (factions.isEmpty()) {
            graphics.drawCenteredString(font, "尚未获知任何势力",
                    x + width / 2, rosterTop + 18, MUTED);
        }
        renderScrollBar(graphics, x + width - 3);
    }

    private void renderRosterRow(GuiGraphics graphics, Faction faction, int x, int y,
                                 int width, boolean hovered) {
        boolean selected = selectedFaction != null && selectedFaction.id().equals(faction.id());
        int standing = ClientFactionCache.getStanding(faction.id());
        StandingLevel level = ClientFactionCache.getLevel(faction.id());
        int color = levelColor(level);
        graphics.fill(x, y, x + width, y + ROW_HEIGHT - 3,
                selected ? 0xFF30383D : hovered ? 0xFF272F34 : PANEL);
        graphics.fill(x, y, x + (selected ? 4 : 2), y + ROW_HEIGHT - 3,
                selected ? GOLD : color);
        drawShield(graphics, x + 9, y + 7, faction.color() | 0xFF000000, 18);
        graphics.drawString(font, fit(displayName(faction), width - 40), x + 34, y + 6, INK);
        graphics.drawString(font, levelName(level), x + 34, y + 20, color);
        String value = signed(standing);
        graphics.drawString(font, value, x + width - font.width(value) - 6, y + 20, MUTED);
        drawReputationBar(graphics, x + 34, y + 33, width - 42, 4, standing, color);
    }

    private void renderDetails(GuiGraphics graphics) {
        int x = panelLeft + rosterWidth;
        int y = panelTop + 56;
        int detailWidth = panelLeft + panelWidth - 14 - x;
        graphics.fill(x - 8, panelTop + 55, x - 7, panelTop + panelHeight - 14, 0x55705A34);
        graphics.fill(x, y, x + detailWidth, panelTop + panelHeight - 16, PANEL_DEEP);
        if (selectedFaction == null) {
            graphics.drawCenteredString(font, "从左侧选择一个势力",
                    x + detailWidth / 2, y + 42, MUTED);
            return;
        }

        Faction faction = selectedFaction;
        int standing = ClientFactionCache.getStanding(faction.id());
        StandingLevel level = ClientFactionCache.getLevel(faction.id());
        int color = levelColor(level);
        drawShield(graphics, x + 14, y + 12, faction.color() | 0xFF000000, 32);
        graphics.drawString(font, Component.literal(fit(displayName(faction), detailWidth - 70))
                        .withStyle(style -> style.withBold(true)),
                x + 56, y + 12, INK);
        graphics.drawString(font, levelName(level) + " · 声望 " + signed(standing),
                x + 56, y + 29, color);

        int barY = y + 55;
        drawReputationBar(graphics, x + 14, barY, detailWidth - 28, 8, standing, color);
        graphics.drawString(font, "敌对", x + 14, barY + 12, 0xFFE7776A);
        graphics.drawCenteredString(font, "中立", x + detailWidth / 2, barY + 12, MUTED);
        graphics.drawString(font, "尊崇", x + detailWidth - 14 - font.width("尊崇"),
                barY + 12, GOLD);

        int cardY = barY + 34;
        int tradeBasisPoints = "sagadyssey:player".equals(faction.id())
                ? 10_000 : level.tradePriceBasisPoints();
        renderPermissionCard(graphics, x + 14, cardY, (detailWidth - 34) / 2,
                "交易", level.canTrade(), level.canTrade()
                        ? "报价门槛为正常条件的 " + tradeBasisPoints / 100 + "%"
                        : "对方拒绝与你贸易");
        renderPermissionCard(graphics, x + 20 + (detailWidth - 34) / 2, cardY,
                (detailWidth - 34) / 2, "招募",
                faction.canRecruit() && level == StandingLevel.REVERED,
                faction.canRecruit() ? "需要达到崇拜声望" : "该势力不接受个人招募");

        if (panelHeight < 300) {
            graphics.drawString(font, "点击左侧势力切换详情",
                    x + 14, panelTop + panelHeight - 29, MUTED);
            return;
        }

        int relationY = cardY + 51;
        graphics.drawString(font, Component.literal("已知外交关系")
                        .withStyle(style -> style.withBold(true)),
                x + 14, relationY, GOLD);
        relationY += 17;
        List<RelationLine> relations = knownRelations(faction);
        if (relations.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.literal(
                    "你还没有获知这个势力与其他组织之间的明确关系。"), detailWidth - 34);
            for (int index = 0; index < Math.min(3, lines.size()); index++) {
                graphics.drawString(font, lines.get(index), x + 14,
                        relationY + index * 12, MUTED);
            }
        } else {
            int maximum = Math.max(1, (panelTop + panelHeight - 29 - relationY) / 24);
            for (int index = 0; index < Math.min(maximum, relations.size()); index++) {
                renderRelationLine(graphics, relations.get(index), x + 14,
                        relationY + index * 24, detailWidth - 28);
            }
            if (relations.size() > maximum) {
                graphics.drawString(font, "另有 " + (relations.size() - maximum) + " 条已知关系",
                        x + 14, relationY + maximum * 24, MUTED);
            }
        }
        graphics.drawString(font, "点击左侧势力切换详情 · 滚轮浏览名册",
                x + 14, panelTop + panelHeight - 29, MUTED);
    }

    private void renderPermissionCard(GuiGraphics graphics, int x, int y, int width,
                                      String title, boolean allowed, String explanation) {
        int color = allowed ? 0xFF77C49A : 0xFFE08072;
        graphics.fill(x, y, x + width, y + 43, PANEL);
        graphics.fill(x, y, x + 3, y + 43, color);
        graphics.drawString(font, title + " · " + (allowed ? "可行" : "受限"), x + 8, y + 6, color);
        List<FormattedCharSequence> lines = font.split(Component.literal(explanation), width - 16);
        for (int index = 0; index < Math.min(2, lines.size()); index++) {
            graphics.drawString(font, lines.get(index), x + 8, y + 20 + index * 11, MUTED);
        }
    }

    private void renderRelationLine(GuiGraphics graphics, RelationLine relation,
                                    int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 20, PANEL);
        graphics.fill(x, y, x + 3, y + 20, relation.color());
        drawShield(graphics, x + 8, y + 3, relation.faction().color() | 0xFF000000, 14);
        String relationText = relation.label() + "  " + relation.effect();
        graphics.drawString(font, fit(displayName(relation.faction()),
                        width - 42 - font.width(relationText)),
                x + 28, y + 6, INK);
        graphics.drawString(font, relationText, x + width - font.width(relationText) - 7,
                y + 6, relation.color());
    }

    private List<RelationLine> knownRelations(Faction focus) {
        List<RelationLine> result = new ArrayList<>();
        for (Faction other : factions) {
            if (other.id().equals(focus.id())) continue;
            InterFactionRelation relation;
            if ("sagadyssey:player".equals(focus.id())) {
                relation = standingRelation(ClientFactionCache.getLevel(other.id()));
            } else if ("sagadyssey:player".equals(other.id())) {
                relation = standingRelation(ClientFactionCache.getLevel(focus.id()));
            } else {
                String raw = ClientFactionCache.getRelationSummary()
                        .get(focus.id() + "→" + other.id());
                if (raw == null) continue;
                try {
                    relation = InterFactionRelation.valueOf(raw);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
            if (relation == InterFactionRelation.NEUTRAL && !"sagadyssey:player".equals(focus.id())) {
                continue;
            }
            result.add(new RelationLine(other, relationName(relation), relationEffect(relation),
                    relationColor(relation)));
        }
        result.sort(Comparator.comparing((RelationLine value) -> relationPriority(value.label()))
                .thenComparing(value -> displayName(value.faction())));
        return result;
    }

    private void renderScrollBar(GuiGraphics graphics, int x) {
        if (factions.size() <= visibleRows) return;
        int trackTop = rosterTop;
        int trackHeight = Math.max(8, visibleRows * ROW_HEIGHT - 3);
        graphics.fill(x, trackTop, x + 2, trackTop + trackHeight, 0x443F474B);
        int thumbHeight = Math.max(12, trackHeight * visibleRows / factions.size());
        int travel = trackHeight - thumbHeight;
        int thumbY = trackTop + travel * scrollRow / Math.max(1, maxScrollRow());
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, GOLD);
    }

    private static void drawReputationBar(GuiGraphics graphics, int x, int y,
                                          int width, int height, int standing, int color) {
        graphics.fill(x, y, x + width, y + height, 0xFF343A3D);
        int center = x + width / 2;
        graphics.fill(center, y - 1, center + 1, y + height + 1, 0xFF8A8F90);
        int magnitude = Math.round(width / 2.0F * Math.min(100, Math.abs(standing)) / 100.0F);
        if (standing >= 0) graphics.fill(center, y, center + magnitude, y + height, color);
        else graphics.fill(center - magnitude, y, center, y + height, color);
    }

    private static void drawShield(GuiGraphics graphics, int x, int y, int color, int size) {
        graphics.fill(x + 2, y, x + size - 2, y + size - 3, GOLD_DARK);
        graphics.fill(x, y + 2, x + size, y + size - 6, GOLD_DARK);
        graphics.fill(x + 3, y + 3, x + size - 3, y + size - 6, color);
        graphics.fill(x + 5, y + size - 6, x + size - 5, y + size - 3, color);
        graphics.fill(x + size / 2 - 1, y + size - 3, x + size / 2 + 1, y + size - 1, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int x = panelLeft + 15;
        int width = rosterWidth - 24;
        if (mouseX < x || mouseX >= x + width || mouseY < rosterTop || mouseY >= rosterBottom) {
            return false;
        }
        int row = (int) ((mouseY - rosterTop) / ROW_HEIGHT);
        int index = scrollRow + row;
        if (index >= 0 && index < factions.size()) {
            selectedFaction = factions.get(index);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX < panelLeft || mouseX >= panelLeft + rosterWidth
                || mouseY < rosterTop || mouseY >= rosterBottom || maxScrollRow() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        }
        scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - (int) Math.signum(deltaY)));
        return true;
    }

    private int maxScrollRow() { return Math.max(0, factions.size() - visibleRows); }

    private String displayName(Faction faction) {
        if ("sagadyssey:player".equals(faction.id())) {
            return ClientFactionCache.getPlayerFactionDisplayName();
        }
        return Component.translatable(faction.displayName()).getString();
    }

    private static String levelName(StandingLevel level) {
        return Component.translatable(level.getTranslationKey()).getString();
    }

    private static int levelColor(StandingLevel level) {
        return switch (level) {
            case REVERED -> 0xFFFFD76A;
            case HONORED -> 0xFF72C98F;
            case NEUTRAL -> 0xFFAAB0B1;
            case COLD -> 0xFFE09A62;
            case HATED -> 0xFFE26F65;
        };
    }

    private static InterFactionRelation standingRelation(StandingLevel level) {
        return switch (level) {
            case REVERED -> InterFactionRelation.ALLY;
            case HONORED -> InterFactionRelation.FRIENDLY;
            case NEUTRAL -> InterFactionRelation.NEUTRAL;
            case COLD -> InterFactionRelation.DISTRUSTFUL;
            case HATED -> InterFactionRelation.ENEMY;
        };
    }

    private static String relationName(InterFactionRelation relation) {
        return switch (relation) {
            case ALLY -> "盟友";
            case FRIENDLY -> "友好";
            case NEUTRAL -> "中立";
            case DISTRUSTFUL -> "猜忌";
            case ENEMY -> "敌对";
        };
    }

    private static String relationEffect(InterFactionRelation relation) {
        int value = Math.round(relation.getTransferRate() * 100.0F);
        return value == 0 ? "" : (value > 0 ? "+" : "") + value + "%";
    }

    private static int relationColor(InterFactionRelation relation) {
        return switch (relation) {
            case ALLY -> 0xFFFFD76A;
            case FRIENDLY -> 0xFF72C98F;
            case NEUTRAL -> 0xFFAAB0B1;
            case DISTRUSTFUL -> 0xFFE09A62;
            case ENEMY -> 0xFFE26F65;
        };
    }

    private static int relationPriority(String label) {
        return switch (label) {
            case "敌对" -> 0;
            case "盟友" -> 1;
            case "友好" -> 2;
            case "猜忌" -> 3;
            default -> 4;
        };
    }

    private static String signed(int value) { return value > 0 ? "+" + value : Integer.toString(value); }

    private String fit(String value, int maximumWidth) {
        if (maximumWidth <= font.width("…")) return "";
        return font.width(value) <= maximumWidth ? value
                : font.plainSubstrByWidth(value, maximumWidth - font.width("…")) + "…";
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY,
                                           float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }

    private record RelationLine(Faction faction, String label, String effect, int color) { }
}
