package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.network.SubmitTradeProposalPacket;
import com.jgeted.sagadyssey.npc.network.TradeSessionPayload;
import com.jgeted.sagadyssey.npc.trade.TradeAsset;
import com.jgeted.sagadyssey.npc.trade.TradeCatalogEntry;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Civilization-style negotiation desk with persistent, multi-line offer baskets. */
public final class NpcNegotiationScreen extends Screen {
    private static final int DESIRED_WIDTH = 620;
    private static final int DESIRED_HEIGHT = 360;
    private static final int MAX_OFFER_LINES = 9;
    private static final int BACKDROP = 0xFF0A0F14;
    private static final int SURFACE = 0xFF151B20;
    private static final int HEADER = 0xFF1D2832;
    private static final int PANEL = 0xFF20282E;
    private static final int PANEL_DEEP = 0xFF11171C;
    private static final int GOLD = 0xFFD7AE62;
    private static final int GOLD_DARK = 0xFF705A34;
    private static final int INK = 0xFFF1E7D1;
    private static final int MUTED = 0xFF9EA5A7;
    private static final int PLAYER_ACCENT = 0xFF77C49A;
    private static final int NPC_ACCENT = 0xFFD7A86A;

    private TradeSessionPayload payload;
    private final LinkedHashMap<ItemFingerprint, TradeAsset> playerOffer = new LinkedHashMap<>();
    private final LinkedHashMap<ItemFingerprint, TradeAsset> npcOffer = new LinkedHashMap<>();
    private ItemFingerprint selectedPlayerOffer;
    private ItemFingerprint selectedNpcOffer;
    private int playerOfferPage;
    private int npcOfferPage;
    private int playerCatalogPage;
    private int npcCatalogPage;
    private boolean awaitingResponse;
    private String localMessage = "";

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int sideWidth;
    private int playerX;
    private int npcX;
    private int offerTop;
    private int catalogTop;
    private int footerTop;
    private int offerRows;
    private int catalogRows;

    public NpcNegotiationScreen(TradeSessionPayload payload) {
        super(Component.literal("与 " + payload.npcName() + " 谈判"));
        this.payload = payload;
        loadServerOffer();
    }

    public UUID sessionId() { return payload.sessionId(); }

    public void update(TradeSessionPayload next) {
        payload = next;
        awaitingResponse = false;
        localMessage = "";
        loadServerOffer();
        rebuildWidgets();
    }

    private void loadServerOffer() {
        playerOffer.clear();
        npcOffer.clear();
        payload.playerOffer().forEach(asset -> playerOffer.put(asset.fingerprint(), asset));
        payload.npcOffer().forEach(asset -> npcOffer.put(asset.fingerprint(), asset));
        selectedPlayerOffer = playerOffer.isEmpty() ? null : playerOffer.keySet().iterator().next();
        selectedNpcOffer = npcOffer.isEmpty() ? null : npcOffer.keySet().iterator().next();
        playerOfferPage = 0;
        npcOfferPage = 0;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(DESIRED_WIDTH, width - 12);
        panelHeight = Math.min(DESIRED_HEIGHT, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        sideWidth = Math.max(126, (panelWidth - 42) / 2);
        playerX = left + 14;
        npcX = left + panelWidth - 14 - sideWidth;
        offerTop = top + 61;
        footerTop = top + panelHeight - 68;
        int bodyHeight = Math.max(84, footerTop - offerTop);
        int offerHeight = Math.max(44, Math.min(102, (bodyHeight - 18) / 2));
        offerRows = Math.max(1, Math.min(5, (offerHeight - 18) / 21));
        catalogTop = offerTop + 18 + offerRows * 21 + 13;
        catalogRows = Math.max(1, Math.min(6, (footerTop - catalogTop - 18) / 21));

        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
                .bounds(left + panelWidth - 24, top + 8, 16, 16).build());
        if (terminal()) {
            addRenderableWidget(Button.builder(Component.literal("完成"), button -> onClose())
                    .bounds(left + panelWidth / 2 - 48, top + panelHeight - 38, 96, 22).build());
            return;
        }

        addOfferPageButtons(true);
        addOfferPageButtons(false);
        addCatalogPageButtons(true);
        addCatalogPageButtons(false);
        addOfferButtons(true);
        addOfferButtons(false);
        addCatalogButtons(true);
        addCatalogButtons(false);
        addAdjustmentButtons(true);
        addAdjustmentButtons(false);

        Button submit = Button.builder(Component.literal(awaitingResponse ? "正在核对…" : "提出交易"),
                        button -> submit())
                .bounds(left + panelWidth / 2 - 54, footerTop + 24, 108, 22).build();
        submit.active = canSubmit();
        addRenderableWidget(submit);
    }

    private void addOfferPageButtons(boolean playerSide) {
        List<TradeAsset> assets = offerList(playerSide);
        if (assets.size() <= offerRows) return;
        int x = sideX(playerSide) + sideWidth - 36;
        int y = offerTop - 1;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> pageOffer(playerSide, -1))
                .bounds(x, y, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("›"), b -> pageOffer(playerSide, 1))
                .bounds(x + 18, y, 16, 16).build());
    }

    private void addCatalogPageButtons(boolean playerSide) {
        List<TradeCatalogEntry> assets = catalog(playerSide);
        if (assets.size() <= catalogRows) return;
        int x = sideX(playerSide) + sideWidth - 36;
        int y = catalogTop - 1;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> pageCatalog(playerSide, -1))
                .bounds(x, y, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("›"), b -> pageCatalog(playerSide, 1))
                .bounds(x + 18, y, 16, 16).build());
    }

    private void addOfferButtons(boolean playerSide) {
        List<TradeAsset> assets = offerList(playerSide);
        int page = clampPage(playerSide ? playerOfferPage : npcOfferPage, assets.size(), offerRows);
        if (playerSide) playerOfferPage = page; else npcOfferPage = page;
        int start = page * offerRows;
        for (int row = 0; row < offerRows && start + row < assets.size(); row++) {
            TradeAsset asset = assets.get(start + row);
            String prefix = asset.fingerprint().equals(selectedOffer(playerSide)) ? "◆ " : "  ";
            String label = fit(prefix + asset.knownName() + "  ×" + asset.quantity(), sideWidth - 12);
            int y = offerTop + 18 + row * 21;
            addRenderableWidget(Button.builder(Component.literal(label),
                            b -> selectOfferAndRefresh(playerSide, asset.fingerprint()))
                    .bounds(sideX(playerSide), y, sideWidth, 19).build());
        }
    }

    private void addCatalogButtons(boolean playerSide) {
        List<TradeCatalogEntry> assets = catalog(playerSide);
        int page = clampPage(playerSide ? playerCatalogPage : npcCatalogPage,
                assets.size(), catalogRows);
        if (playerSide) playerCatalogPage = page; else npcCatalogPage = page;
        int start = page * catalogRows;
        for (int row = 0; row < catalogRows && start + row < assets.size(); row++) {
            TradeCatalogEntry entry = assets.get(start + row);
            String label = fit("＋  " + entry.knownName() + "  ×" + entry.available(), sideWidth - 12);
            int y = catalogTop + 18 + row * 21;
            Button button = Button.builder(Component.literal(label), b -> addToOffer(playerSide, entry))
                    .bounds(sideX(playerSide), y, sideWidth, 19).build();
            button.active = !awaitingResponse && entry.available() > 0;
            addRenderableWidget(button);
        }
    }

    private void addAdjustmentButtons(boolean playerSide) {
        int x = sideX(playerSide);
        int y = footerTop;
        Button minus = Button.builder(Component.literal("−"), b -> adjust(playerSide, -1))
                .bounds(x, y, 20, 20).build();
        Button plus = Button.builder(Component.literal("+"), b -> adjust(playerSide, 1))
                .bounds(x + 24, y, 20, 20).build();
        Button remove = Button.builder(Component.literal("移除"), b -> removeSelected(playerSide))
                .bounds(x + sideWidth - 48, y, 48, 20).build();
        boolean active = !awaitingResponse && selectedOffer(playerSide) != null;
        minus.active = active;
        plus.active = active;
        remove.active = active;
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        addRenderableWidget(remove);
    }

    private void addToOffer(boolean playerSide, TradeCatalogEntry entry) {
        if (awaitingResponse) return;
        Map<ItemFingerprint, TradeAsset> own = offerMap(playerSide);
        Map<ItemFingerprint, TradeAsset> other = offerMap(!playerSide);
        if (other.containsKey(entry.fingerprint())) {
            localMessage = "同一种物品不能同时出现在双方报价中。";
            return;
        }
        TradeAsset existing = own.get(entry.fingerprint());
        if (existing == null && own.size() >= MAX_OFFER_LINES) {
            localMessage = "每一方最多可以提出九种物品。";
            return;
        }
        int next = existing == null ? 1 : Math.min(entry.available(), existing.quantity() + 1);
        own.put(entry.fingerprint(), new TradeAsset(entry.fingerprint(), next, entry.knownName()));
        selectOffer(playerSide, entry.fingerprint());
        int index = new ArrayList<>(own.keySet()).indexOf(entry.fingerprint());
        if (playerSide) playerOfferPage = Math.max(0, index / offerRows);
        else npcOfferPage = Math.max(0, index / offerRows);
        localMessage = "已加入报价；再次点击库存条目可以增加数量。";
        rebuildWidgets();
    }

    private void selectOffer(boolean playerSide, ItemFingerprint fingerprint) {
        if (playerSide) selectedPlayerOffer = fingerprint;
        else selectedNpcOffer = fingerprint;
    }

    private void selectOfferAndRefresh(boolean playerSide, ItemFingerprint fingerprint) {
        selectOffer(playerSide, fingerprint);
        localMessage = "已选中该报价条目；可在下方调整数量或移除。";
        rebuildWidgets();
    }

    private void adjust(boolean playerSide, int delta) {
        ItemFingerprint selected = selectedOffer(playerSide);
        if (selected == null) return;
        Map<ItemFingerprint, TradeAsset> offers = offerMap(playerSide);
        TradeAsset current = offers.get(selected);
        TradeCatalogEntry catalog = findCatalog(playerSide, selected);
        if (current == null || catalog == null) return;
        int quantity = Math.max(1, Math.min(catalog.available(), current.quantity() + delta));
        offers.put(selected, new TradeAsset(selected, quantity, current.knownName()));
        localMessage = "";
        rebuildWidgets();
    }

    private void removeSelected(boolean playerSide) {
        ItemFingerprint selected = selectedOffer(playerSide);
        if (selected == null) return;
        Map<ItemFingerprint, TradeAsset> offers = offerMap(playerSide);
        offers.remove(selected);
        ItemFingerprint next = offers.isEmpty() ? null : offers.keySet().iterator().next();
        if (playerSide) selectedPlayerOffer = next; else selectedNpcOffer = next;
        localMessage = "已从报价中移除。";
        rebuildWidgets();
    }

    private void pageOffer(boolean playerSide, int delta) {
        int pages = pageCount(offerList(playerSide).size(), offerRows);
        if (playerSide) playerOfferPage = Math.floorMod(playerOfferPage + delta, pages);
        else npcOfferPage = Math.floorMod(npcOfferPage + delta, pages);
        rebuildWidgets();
    }

    private void pageCatalog(boolean playerSide, int delta) {
        int pages = pageCount(catalog(playerSide).size(), catalogRows);
        if (playerSide) playerCatalogPage = Math.floorMod(playerCatalogPage + delta, pages);
        else npcCatalogPage = Math.floorMod(npcCatalogPage + delta, pages);
        rebuildWidgets();
    }

    private boolean canSubmit() {
        return !awaitingResponse && !terminal() && !playerOffer.isEmpty() && !npcOffer.isEmpty()
                && playerOffer.keySet().stream().noneMatch(npcOffer::containsKey);
    }

    private void submit() {
        if (!canSubmit()) return;
        awaitingResponse = true;
        localMessage = "正在等待对方核对整份报价……";
        PacketDistributor.sendToServer(new SubmitTradeProposalPacket(payload.npcEntityId(),
                payload.sessionId(), payload.revision(), offerList(true), offerList(false)));
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackdrop(graphics);
        renderFrame(graphics);
        if (terminal()) renderTerminal(graphics);
        else renderNegotiation(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!terminal()) renderItemIcons(graphics);
    }

    private void renderBackdrop(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(0, 0, width, Math.max(34, top), 0xFF111B25);
        graphics.fill(0, height - Math.max(28, top), width, height, 0xFF070A0E);
        graphics.fill(0, Math.max(0, top - 2), width, Math.max(0, top), GOLD_DARK);
    }

    private void renderFrame(GuiGraphics graphics) {
        graphics.fill(left + 6, top + 7, left + panelWidth + 6, top + panelHeight + 7, 0x88000000);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, GOLD_DARK);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, SURFACE);
        graphics.fill(left, top, left + panelWidth, top + 47, HEADER);
        graphics.fill(left, top + 46, left + panelWidth, top + 48, GOLD);
        graphics.drawCenteredString(font, "与 " + payload.npcName() + " 缔结交易",
                left + panelWidth / 2, top + 10, INK);
        String status = localMessage.isBlank() ? payload.explanation() : localMessage;
        graphics.drawCenteredString(font, fit(status, panelWidth - 84),
                left + panelWidth / 2, top + 29, awaitingResponse ? GOLD : MUTED);
    }

    private void renderNegotiation(GuiGraphics graphics) {
        renderSide(graphics, true);
        renderSide(graphics, false);
        graphics.fill(left + panelWidth / 2 - 1, top + 58,
                left + panelWidth / 2 + 1, footerTop - 5, 0x55705A34);
        graphics.drawCenteredString(font, "⇄", left + panelWidth / 2, top + 72, GOLD);
        renderSelectedQuantity(graphics, true);
        renderSelectedQuantity(graphics, false);
        graphics.drawCenteredString(font, "双方确认后，所有物品会作为一次原子交换结算",
                left + panelWidth / 2, top + panelHeight - 16, MUTED);
    }

    private void renderSide(GuiGraphics graphics, boolean playerSide) {
        int x = sideX(playerSide);
        int accent = playerSide ? PLAYER_ACCENT : NPC_ACCENT;
        String owner = playerSide ? "你的提议" : payload.npcName() + " 的提议";
        graphics.drawString(font, Component.literal(owner).withStyle(style -> style.withBold(true)),
                x, top + 50, accent);
        graphics.fill(x, offerTop + 15, x + sideWidth,
                offerTop + 18 + offerRows * 21, PANEL);
        graphics.fill(x, offerTop + 15, x + 3,
                offerTop + 18 + offerRows * 21, accent);
        graphics.drawString(font, "当前报价  " + offerList(playerSide).size() + "/" + MAX_OFFER_LINES,
                x + 5, offerTop + 2, INK);
        drawPageIndicator(graphics, offerList(playerSide).size(), offerRows,
                playerSide ? playerOfferPage : npcOfferPage, x, offerTop);
        if (offerList(playerSide).isEmpty()) {
            graphics.drawCenteredString(font, "从下方库存选择物品",
                    x + sideWidth / 2, offerTop + 27, MUTED);
        }
        graphics.fill(x, catalogTop + 15, x + sideWidth,
                catalogTop + 18 + catalogRows * 21, PANEL_DEEP);
        graphics.fill(x, catalogTop + 15, x + 3,
                catalogTop + 18 + catalogRows * 21, GOLD_DARK);
        graphics.drawString(font, playerSide ? "你的可用物品" : "对方愿意谈的物品",
                x + 5, catalogTop + 2, MUTED);
        drawPageIndicator(graphics, catalog(playerSide).size(), catalogRows,
                playerSide ? playerCatalogPage : npcCatalogPage, x, catalogTop);
    }

    private void renderSelectedQuantity(GuiGraphics graphics, boolean playerSide) {
        TradeAsset selected = selectedAsset(playerSide);
        int x = sideX(playerSide);
        String text = selected == null ? "未选中报价条目"
                : fit(selected.knownName() + " ×" + selected.quantity(), sideWidth - 120);
        graphics.drawCenteredString(font, text, x + sideWidth / 2, footerTop + 6,
                selected == null ? MUTED : INK);
    }

    private void renderTerminal(GuiGraphics graphics) {
        int color = "COMMITTED".equals(payload.status()) ? PLAYER_ACCENT : 0xFFE38D7B;
        graphics.fill(left + 22, top + 70, left + panelWidth - 22, top + panelHeight - 58, PANEL);
        graphics.fill(left + 22, top + 70, left + 26, top + panelHeight - 58, color);
        graphics.drawCenteredString(font, "COMMITTED".equals(payload.status())
                        ? "交易已经履行" : "本次谈判已经结束",
                left + panelWidth / 2, top + 116, color);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.literal(payload.explanation()), panelWidth - 96);
        for (int index = 0; index < Math.min(5, lines.size()); index++) {
            graphics.drawCenteredString(font, lines.get(index), left + panelWidth / 2,
                    top + 142 + index * 13, INK);
        }
    }

    private void renderItemIcons(GuiGraphics graphics) {
        renderSideIcons(graphics, true);
        renderSideIcons(graphics, false);
    }

    private void renderSideIcons(GuiGraphics graphics, boolean playerSide) {
        int x = sideX(playerSide) + 3;
        List<TradeAsset> offers = offerList(playerSide);
        int offerStart = (playerSide ? playerOfferPage : npcOfferPage) * offerRows;
        for (int row = 0; row < offerRows && offerStart + row < offers.size(); row++) {
            renderItemIcon(graphics, offers.get(offerStart + row).fingerprint(), x,
                    offerTop + 19 + row * 21);
        }
        List<TradeCatalogEntry> available = catalog(playerSide);
        int catalogStart = (playerSide ? playerCatalogPage : npcCatalogPage) * catalogRows;
        for (int row = 0; row < catalogRows && catalogStart + row < available.size(); row++) {
            renderItemIcon(graphics, available.get(catalogStart + row).fingerprint(), x,
                    catalogTop + 19 + row * 21);
        }
    }

    private static void renderItemIcon(GuiGraphics graphics, ItemFingerprint fingerprint,
                                       int x, int y) {
        ResourceLocation id = ResourceLocation.tryParse(fingerprint.itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(id)), x, y);
    }

    private void drawPageIndicator(GuiGraphics graphics, int count, int rows, int page,
                                   int x, int y) {
        int pages = pageCount(count, rows);
        if (pages <= 1) return;
        String label = (Math.min(page, pages - 1) + 1) + "/" + pages;
        graphics.drawString(font, label, x + sideWidth - 60, y + 3, MUTED);
    }

    private TradeCatalogEntry findCatalog(boolean playerSide, ItemFingerprint fingerprint) {
        return catalog(playerSide).stream().filter(entry -> entry.fingerprint().equals(fingerprint))
                .findFirst().orElse(null);
    }

    private List<TradeCatalogEntry> catalog(boolean playerSide) {
        return playerSide ? payload.playerAssets() : payload.npcAssets();
    }

    private LinkedHashMap<ItemFingerprint, TradeAsset> offerMap(boolean playerSide) {
        return playerSide ? playerOffer : npcOffer;
    }

    private List<TradeAsset> offerList(boolean playerSide) {
        return List.copyOf(offerMap(playerSide).values());
    }

    private ItemFingerprint selectedOffer(boolean playerSide) {
        return playerSide ? selectedPlayerOffer : selectedNpcOffer;
    }

    private TradeAsset selectedAsset(boolean playerSide) {
        ItemFingerprint selected = selectedOffer(playerSide);
        return selected == null ? null : offerMap(playerSide).get(selected);
    }

    private int sideX(boolean playerSide) { return playerSide ? playerX : npcX; }

    private static int pageCount(int size, int rows) {
        return Math.max(1, (size + Math.max(1, rows) - 1) / Math.max(1, rows));
    }

    private static int clampPage(int page, int size, int rows) {
        return Math.max(0, Math.min(page, pageCount(size, rows) - 1));
    }

    private boolean terminal() {
        return "COMMITTED".equals(payload.status()) || "FAILED".equals(payload.status())
                || "EXPIRED".equals(payload.status()) || "CANCELLED".equals(payload.status());
    }

    private String fit(String value, int maximumWidth) {
        return font.width(value) <= maximumWidth ? value
                : font.plainSubstrByWidth(value,
                Math.max(0, maximumWidth - font.width("…"))) + "…";
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY,
                                           float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
}
