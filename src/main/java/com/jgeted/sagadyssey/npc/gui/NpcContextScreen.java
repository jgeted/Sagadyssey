package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.knowledge.ConflictState;
import com.jgeted.sagadyssey.knowledge.FreshnessBand;
import com.jgeted.sagadyssey.knowledge.PrecisionBand;
import com.jgeted.sagadyssey.knowledge.projection.NpcViewSnapshot;
import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import com.jgeted.sagadyssey.npc.network.OpenNpcTradePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** A compact dossier assembled from what this viewer can actually observe or prove. */
public final class NpcContextScreen extends Screen {
    private static final int DESIRED_WIDTH = 600;
    private static final int DESIRED_HEIGHT = 378;
    private static final int INK = 0xFFF1E9DA;
    private static final int MUTED = 0xFFA79E91;
    private static final int DIM = 0xFF766F66;
    private static final int GOLD = 0xFFE2B55B;
    private static final int SURFACE = 0xFF181715;
    private static final int HEADER = 0xFF24201C;
    private static final int CARD = 0xFF2B2722;
    private static final int CARD_DEEP = 0xFF211E1A;
    private static final int LINE = 0x664F473E;

    private NpcViewSnapshot view;
    private final List<InfoHitbox> hitboxes = new ArrayList<>();
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int dividerX;

    public NpcContextScreen(NpcViewSnapshot view) {
        super(Component.literal(view.identity().name()));
        this.view = view;
    }

    public boolean matchesEntity(int entityId) {
        return view.bodyEntityId() == entityId;
    }

    public void update(NpcViewSnapshot updated) {
        if (updated == null || updated.bodyEntityId() != view.bodyEntityId()) return;
        view = updated;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(DESIRED_WIDTH, width - 12);
        panelHeight = Math.min(DESIRED_HEIGHT, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int desiredLeftColumn = panelWidth * 55 / 100;
        int minimumLeftColumn = panelWidth < 395 ? 140 : 205;
        int minimumRightColumn = panelWidth < 395 ? 150 : 190;
        dividerX = left + Math.min(panelWidth - minimumRightColumn,
                Math.max(minimumLeftColumn, desiredLeftColumn));

        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
                .bounds(left + panelWidth - 23, top + 8, 15, 15).build());

        int areaX = dividerX + 12;
        int available = left + panelWidth - areaX - 12;
        int gap = 6;
        int buttonWidth = Math.max(58, (available - gap) / 2);
        int actionTop = top + panelHeight - 83;
        int visible = Math.min(6, view.actions().size());
        for (int index = 0; index < visible; index++) {
            NpcViewSnapshot.AvailableInteraction action = view.actions().get(index);
            int x = areaX + (index % 2) * (buttonWidth + gap);
            int y = actionTop + (index / 2) * 24;
            Button button = Button.builder(Component.literal(action.label()),
                            clicked -> perform(action.actionId()))
                    .bounds(x, y, buttonWidth, 20).build();
            button.setTooltip(Tooltip.create(Component.literal(
                    action.category() + " · " + action.explanation())));
            addRenderableWidget(button);
        }
    }

    private void perform(String actionId) {
        if ("talk".equals(actionId)) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new NpcInteractionPacket(view.bodyEntityId(), actionId));
            return;
        }
        if ("trade".equals(actionId)) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new OpenNpcTradePacket(view.bodyEntityId()));
            return;
        }
        if ("handover".equals(actionId)) {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            InteractionHand hand = !player.getMainHandItem().isEmpty()
                    ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            var stack = player.getItemInHand(hand);
            Minecraft.getInstance().setScreen(new NpcHandoverIntentScreen(view.bodyEntityId(), hand,
                    view.identity().name(), stack.getHoverName().getString(), stack.getCount()));
            return;
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new NpcInteractionPacket(view.bodyEntityId(), actionId));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The dossier is an information screen, not a translucent world overlay. Keeping the
        // backdrop opaque also prevents a nearby NPC model from cutting through the panel.
        graphics.fill(0, 0, width, height, 0xFF090B0E);
        shadow(graphics, left, top, left + panelWidth, top + panelHeight);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1,
                0xFF675231);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, SURFACE);
        graphics.fill(left, top, left + panelWidth, top + 56, HEADER);
        graphics.fill(left, top, left + 4, top + panelHeight, GOLD);
        graphics.fill(left + 4, top + 55, left + panelWidth, top + 57, 0xFF58462A);
        graphics.fill(dividerX, top + 66, dividerX + 1, top + panelHeight - 12, LINE);

        hitboxes.clear();
        renderHeader(graphics);
        renderDossier(graphics);
        renderActions(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        for (InfoHitbox box : hitboxes) {
            if (!box.contains(mouseX, mouseY)) continue;
            graphics.setTooltipForNextFrame(font, Component.literal(box.tooltip()), mouseX, mouseY);
            break;
        }
    }

    private void renderHeader(GuiGraphics graphics) {
        int avatarX = left + 17;
        int avatarY = top + 10;
        graphics.fill(avatarX, avatarY, avatarX + 38, avatarY + 38, 0xFF6A5636);
        graphics.fill(avatarX + 2, avatarY + 2, avatarX + 36, avatarY + 36, 0xFF131210);
        renderPortrait(graphics, avatarX + 5, avatarY + 4, view.identity().name().hashCode());

        int textX = left + 67;
        graphics.drawString(font,
                Component.literal(view.identity().name()).withStyle(style -> style.withBold(true)),
                textX, top + 10, INK);
        int identityWidth = left + panelWidth - textX - 34;
        graphics.drawString(font, trim(view.identity().publicRole() + " · "
                + view.identity().profession(), identityWidth),
                textX, top + 24, GOLD);
        graphics.drawString(font, trim(view.identity().appearance(), identityWidth),
                textX, top + 38, MUTED);
    }

    private void renderDossier(GuiGraphics graphics) {
        int x = left + 17;
        int columnWidth = dividerX - x - 12;
        int y = top + 68;

        sectionTitle(graphics, "当面观察", x, y);
        y += 15;
        int conditionCount = Math.min(3, view.conditions().size());
        boolean compactConditions = columnWidth < 230;
        if (compactConditions) {
            for (int index = 0; index < conditionCount; index++) {
                NpcViewSnapshot.ObservedCondition condition = view.conditions().get(index);
                int cardY = y + index * 20;
                graphics.fill(x, cardY, x + columnWidth, cardY + 17, CARD_DEEP);
                graphics.fill(x, cardY, x + 2, cardY + 17, severityColor(condition.severity()));
                int labelWidth = Math.min(42, Math.max(28, font.width(condition.label()) + 6));
                graphics.drawString(font, trim(condition.label(), labelWidth - 5),
                        x + 5, cardY + 5, MUTED);
                graphics.drawString(font, trim(condition.description(), columnWidth - labelWidth - 9),
                        x + labelWidth + 4, cardY + 5,
                        condition.severity() > 0 ? 0xFFFFC765 : INK);
            }
            y += conditionCount * 20 + 4;
        } else {
            int conditionGap = 5;
            int conditionWidth = Math.max(54,
                    (columnWidth - Math.max(0, conditionCount - 1) * conditionGap)
                            / Math.max(1, conditionCount));
            for (int index = 0; index < conditionCount; index++) {
                NpcViewSnapshot.ObservedCondition condition = view.conditions().get(index);
                int cardX = x + index * (conditionWidth + conditionGap);
                graphics.fill(cardX, y, cardX + conditionWidth, y + 25, CARD_DEEP);
                graphics.fill(cardX, y, cardX + 2, y + 25, severityColor(condition.severity()));
                graphics.drawString(font, trim(condition.label(), conditionWidth - 8),
                        cardX + 5, y + 3, MUTED);
                graphics.drawString(font, trim(condition.description(), conditionWidth - 8),
                        cardX + 5, y + 14, condition.severity() > 0 ? 0xFFFFC765 : INK);
            }
            y += 34;
        }

        sectionTitle(graphics, "世界中的位置", x, y);
        y += 14;
        int insightLimit = panelHeight < 320 ? 1 : 3;
        for (NpcViewSnapshot.SystemInsight insight : view.systemInsights().stream()
                .limit(insightLimit).toList()) {
            renderInsight(graphics, insight, x, y, columnWidth);
            y += 43;
        }

        y += 2;
        if (top + panelHeight - 10 - y >= 27) {
            sectionTitle(graphics, "你所知道的", x, y);
            y += 14;
            int maxFacts = Math.max(0, Math.min(4, (top + panelHeight - 10 - y) / 13));
            for (NpcViewSnapshot.KnownFact fact : view.knownFacts().stream().limit(maxFacts).toList()) {
                int color = fact.freshness() == FreshnessBand.STALE ? DIM
                        : fact.conflict() == ConflictState.CONTESTED ? 0xFFFFC857 : INK;
                String marker = fact.conflict() == ConflictState.CONTESTED ? "  ≠" : "";
                graphics.drawString(font, trim("• " + fact.label() + "  "
                        + fact.displayValue() + marker, columnWidth), x, y, color);
                hitboxes.add(new InfoHitbox(x, y, columnWidth, 11,
                        "来源：" + fact.sourceLabel() + " · " + freshness(fact.freshness())
                                + " · " + precision(fact.precision())));
                y += 13;
            }
        }
    }

    private void renderInsight(GuiGraphics graphics, NpcViewSnapshot.SystemInsight insight,
                               int x, int y, int width) {
        int accent = categoryColor(insight.category(), insight.urgency());
        graphics.fill(x, y, x + width, y + 38, CARD);
        graphics.fill(x, y, x + 3, y + 38, accent);
        int tagWidth = Math.min(42, font.width(insight.category()) + 10);
        graphics.fill(x + 7, y + 4, x + 7 + tagWidth, y + 15, 0xFF37312B);
        graphics.drawString(font, trim(insight.category(), tagWidth - 6), x + 12, y + 6, accent);
        graphics.drawString(font, trim(insight.title(), width - tagWidth - 22),
                x + tagWidth + 13, y + 6, INK);
        graphics.drawString(font, trim(insight.summary(), width - 16),
                x + 8, y + 23, insight.urgency() >= 2 ? 0xFFFFC765 : MUTED);
        hitboxes.add(new InfoHitbox(x, y, width, 38,
                insight.detail() + "\n来源：" + insight.sourceLabel() + " · "
                        + precision(insight.precision()) + " · " + freshness(insight.freshness())));
    }

    private void renderActions(GuiGraphics graphics) {
        int x = dividerX + 12;
        int columnWidth = left + panelWidth - x - 12;
        int y = top + 68;

        if (panelHeight < 320) {
            sectionTitle(graphics, "你与此人", x, y);
            y += 15;
            graphics.drawString(font, trim(view.relationship(), columnWidth), x, y, INK);
            y += 13;
            graphics.drawString(font, trim(view.identity().affiliation(), columnWidth), x, y, MUTED);
            y += 16;
            String conversation = view.conversation().isBlank()
                    ? "对方正等你开口。" : view.conversation();
            graphics.drawString(font, trim(conversation, columnWidth), x, y, INK);
            renderActionHeading(graphics, x, columnWidth);
            return;
        }

        sectionTitle(graphics, "你与此人", x, y);
        y += 15;
        graphics.drawString(font, trim(view.relationship(), columnWidth), x, y, INK);
        y += 13;
        graphics.drawString(font, trim(view.identity().affiliation(), columnWidth), x, y, MUTED);
        y += 20;

        sectionTitle(graphics, "正在交谈", x, y);
        y += 15;
        graphics.fill(x, y, x + columnWidth, y + 47, CARD);
        graphics.fill(x, y, x + 3, y + 47, 0xFF6EB5A3);
        String conversation = view.conversation().isBlank()
                ? "对方正等你开口。" : view.conversation();
        List<FormattedCharSequence> dialogueLines = font.split(Component.literal(conversation),
                columnWidth - 14);
        for (int index = 0; index < Math.min(3, dialogueLines.size()); index++) {
            graphics.drawString(font, dialogueLines.get(index), x + 8, y + 6 + index * 12, INK);
        }
        y += 57;

        sectionTitle(graphics, "外露携带", x, y);
        y += 15;
        if (view.visibleAssets().isEmpty()) {
            graphics.drawString(font, "未见外露物品", x, y, DIM);
            y += 14;
        } else {
            for (NpcViewSnapshot.VisibleAsset asset : view.visibleAssets().stream().limit(2).toList()) {
                graphics.fill(x, y - 2, x + columnWidth, y + 11, CARD_DEEP);
                graphics.drawString(font, trim(asset.location() + " · "
                        + asset.description(), columnWidth - 8), x + 4, y, INK);
                y += 15;
            }
        }

        y += 4;
        sectionTitle(graphics, "已知约定", x, y);
        y += 15;
        if (view.agreements().isEmpty()) {
            graphics.drawString(font, "没有你能确认的正式约定", x, y, DIM);
        } else {
            for (NpcViewSnapshot.KnownAgreement agreement : view.agreements().stream().limit(1).toList()) {
                graphics.drawString(font, trim(agreement.title() + " · "
                        + agreement.status(), columnWidth), x, y, GOLD);
                graphics.drawString(font, trim(agreement.terms(), columnWidth), x, y + 12, MUTED);
                y += 29;
            }
        }

        renderActionHeading(graphics, x, columnWidth);
    }

    private void renderActionHeading(GuiGraphics graphics, int x, int columnWidth) {
        int actionTitleY = top + panelHeight - 101;
        graphics.fill(x, actionTitleY - 7, x + columnWidth, actionTitleY - 6, LINE);
        sectionTitle(graphics, "此刻可以", x, actionTitleY);
        String count = Math.min(6, view.actions().size()) + " 项可用行动";
        graphics.drawString(font, count, x + columnWidth - font.width(count), actionTitleY, DIM);
    }

    private void sectionTitle(GuiGraphics graphics, String title, int x, int y) {
        graphics.drawString(font, Component.literal(title).withStyle(style -> style.withBold(true)),
                x, y, GOLD);
    }

    /** 小型像素肖像比单字占位更容易让玩家把面板与眼前人物联系起来。 */
    private static void renderPortrait(GuiGraphics graphics, int x, int y, int seed) {
        int[] hair = {0xFF3A2418, 0xFF5A3520, 0xFF2B211E, 0xFF76502E, 0xFF4A3028};
        int hairColor = hair[Math.floorMod(seed, hair.length)];
        int skin = Math.floorMod(seed >>> 4, 3) == 0 ? 0xFFD7A77C
                : Math.floorMod(seed >>> 4, 3) == 1 ? 0xFFB9825A : 0xFFE0B48A;
        graphics.fill(x + 5, y + 4, x + 23, y + 29, skin);
        graphics.fill(x + 3, y + 2, x + 25, y + 10, hairColor);
        graphics.fill(x + 3, y + 8, x + 7, y + 24, hairColor);
        graphics.fill(x + 21, y + 8, x + 25, y + 24, hairColor);
        graphics.fill(x + 8, y + 15, x + 11, y + 18, 0xFF27231F);
        graphics.fill(x + 17, y + 15, x + 20, y + 18, 0xFF27231F);
        graphics.fill(x + 12, y + 24, x + 17, y + 26, 0xFF8C4D43);
        graphics.fill(x + 2, y + 29, x + 26, y + 34, 0xFF4E6270);
    }

    private Component trim(String text, int pixelWidth) {
        if (pixelWidth <= 8) return Component.literal("");
        return Component.literal(font.width(text) <= pixelWidth ? text
                : font.plainSubstrByWidth(text, Math.max(8, pixelWidth - font.width("…"))) + "…");
    }

    private static int severityColor(int severity) {
        return severity >= 2 ? 0xFFFF6B62 : severity == 1 ? 0xFFFFC857 : 0xFF6EB5A3;
    }

    private static int categoryColor(String category, int urgency) {
        if (urgency >= 3) return 0xFFFF6B62;
        if (urgency == 2) return 0xFFFFC857;
        return switch (category) {
            case "社会" -> 0xFF9A82D2;
            case "经济" -> 0xFFD6A84B;
            case "战斗" -> 0xFFD97767;
            default -> 0xFF6EB5A3;
        };
    }

    private static String freshness(FreshnessBand freshness) {
        return switch (freshness) {
            case CURRENT -> "刚刚确认";
            case RECENT -> "近期信息";
            case STALE -> "可能已过时";
        };
    }

    private static String precision(PrecisionBand precision) {
        return switch (precision) {
            case EXACT -> "确切";
            case RANGE -> "范围";
            case CATEGORY -> "类别";
            case IMPRESSION -> "观察印象";
            case UNKNOWN -> "未知";
        };
    }

    private static void shadow(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1 + 7, y1 + 7, x2 + 7, y2 + 7, 0x72000000);
        graphics.fill(x1 + 3, y1 + 3, x2 + 3, y2 + 3, 0x52000000);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }

    private record InfoHitbox(int x, int y, int width, int height, String tooltip) {
        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
