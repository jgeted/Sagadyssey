package com.jgeted.sagadyssey.npc.gui;

import com.jgeted.sagadyssey.npc.handover.HandoverIntent;
import com.jgeted.sagadyssey.npc.network.NpcHandoverIntentPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/** 明确选择递交意图与数量；打开界面本身不会转移任何物品。 */
public final class NpcHandoverIntentScreen extends Screen {
    private final int npcId;
    private final InteractionHand hand;
    private final String npcName;
    private final String itemName;
    private final int availableCount;
    private boolean wholeStack;
    private int left;
    private int top;
    private Button quantityButton;

    public NpcHandoverIntentScreen(int npcId, InteractionHand hand, String npcName,
                                   String itemName, int availableCount) {
        super(Component.literal("递交意图"));
        this.npcId = npcId;
        this.hand = hand;
        this.npcName = npcName;
        this.itemName = itemName;
        this.availableCount = Math.max(1, availableCount);
    }

    @Override
    protected void init() {
        left = (width - 238) / 2;
        top = (height - 154) / 2;
        quantityButton = addRenderableWidget(Button.builder(quantityLabel(), button -> {
            wholeStack = !wholeStack;
            quantityButton.setMessage(quantityLabel());
        }).bounds(left + 16, top + 48, 206, 20).build());
        HandoverIntent[] intents = HandoverIntent.values();
        for (int index = 0; index < intents.length; index++) {
            HandoverIntent intent = intents[index];
            addRenderableWidget(Button.builder(Component.literal(intent.displayName()),
                    button -> submit(intent)).bounds(left + 16 + (index % 3) * 70,
                    top + 78 + (index / 3) * 24, 66, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(left + 84, top + 130, 70, 18).build());
    }

    private Component quantityLabel() {
        return Component.literal(wholeStack ? "数量：整组 × " + availableCount : "数量：1 个");
    }

    private void submit(HandoverIntent intent) {
        PacketDistributor.sendToServer(new NpcHandoverIntentPacket(UUID.randomUUID(), npcId,
                hand, intent, wholeStack));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(left, top, left + 238, top + 154, 0xF0211E1A);
        graphics.fill(left, top, left + 238, top + 2, 0xFFD6A84B);
        graphics.drawCenteredString(font, "递交给 " + npcName, left + 119, top + 12, 0xFFE9E0CF);
        String line = font.width(itemName) <= 200 ? itemName : font.plainSubstrByWidth(itemName, 190) + "…";
        graphics.drawCenteredString(font, line, left + 119, top + 29, 0xFFD6A84B);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
}
