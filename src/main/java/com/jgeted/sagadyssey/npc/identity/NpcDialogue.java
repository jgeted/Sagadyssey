package com.jgeted.sagadyssey.npc.identity;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.life.NpcLifeRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** 统一生成可本地化的身份语气文本，避免把台词硬编码在界面或网络处理器中。 */
public final class NpcDialogue {
    private NpcDialogue() { }

    public static Component line(NpcBase npc, NpcDialogueContext context, ServerPlayer player) {
        String key = "dialogue.sagadyssey.identity."
                + npc.getSocialStatus().id() + "." + context.id();
        MutableComponent speech = Component.literal("<" + npc.getNpcName() + "> ");
        if (context == NpcDialogueContext.GREETING) {
            speech.append(NpcIdentityRelations.addressPrefix(npc, player));
        }
        if (context == NpcDialogueContext.CONVERSATION) {
            return speech.append(Component.literal(conversationText(npc, player)))
                    .withStyle(ChatFormatting.GRAY);
        }
        return speech.append(Component.translatable(key)).withStyle(ChatFormatting.GRAY);
    }

    private static String conversationText(NpcBase npc, ServerPlayer player) {
        List<String> topics = new ArrayList<>();
        addLogisticsTopic(topics, npc);
        if (!npc.getWorkActivity().isBlank() && !"尚未开始".equals(npc.getWorkActivity())
                && !npc.getWorkActivity().startsWith("物流")) {
            topics.add("我现在在处理“" + npc.getWorkActivity() + "”。");
        }

        UUID viewerId = CharacterRegistry.currentId(player);
        if (!player.isAlive() || viewerId == null) return "当前没有可用的在生人物；不能进行个人身份对话。";
        NpcLifeRecord life = NpcLifeData.get(player.server).record(npc.getCharacterId());
        if (life != null) {
            life.memories().stream().filter(memory -> memory.active(player.serverLevel().getGameTime()))
                    .filter(memory -> viewerId.equals(memory.subjectCharacterId()))
                    .findFirst().ifPresent(memory -> topics.add("我记得，" + sentence(memory.summary())));
            if (!life.lastDecision().isBlank() && !"尚未决策".equals(life.lastDecision())) {
                String reason = life.lastDecisionReason().isBlank() ? "" : "，因为" + life.lastDecisionReason();
                topics.add("我眼下决定“" + life.lastDecision() + "”" + reason + "。");
            }
        }
        firstCarriedStack(npc).ifPresent(stack -> topics.add("我随身带着 " + stack.getCount()
                + " 个" + stack.getHoverName().getString() + "；是否交换，要先把条件说清楚。"));

        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(topics));
        if (distinct.isEmpty()) return Component.translatable("dialogue.sagadyssey.identity."
                + npc.getSocialStatus().id() + ".conversation").getString();
        return selectTopic(distinct, npc.nextConversationTurn());
    }

    private static void addLogisticsTopic(List<String> topics, NpcBase npc) {
        if (npc.getLogisticsStage() <= 0 || npc.getLogisticsItem().isBlank()) return;
        String item = itemName(npc.getLogisticsItem());
        String progress = npc.getLogisticsRequested() > 0
                ? "，这批已经送到 " + npc.getLogisticsDelivered() + "/"
                + npc.getLogisticsRequested() : "";
        if (npc.getLogisticsStage() == 1) {
            topics.add("我正去取 " + item + progress + "。货到手后还得送往另一处市场。");
        } else {
            topics.add("我正运送 " + npc.getLogisticsCargo() + " 个" + item + progress
                    + "。路没走完前，这些货不能拿来私下交易。");
        }
    }

    private static java.util.Optional<ItemStack> firstCarriedStack(NpcBase npc) {
        for (int slot = 0; slot < npc.getEquipmentInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getEquipmentInventory().getItem(slot);
            if (!stack.isEmpty()) return java.util.Optional.of(stack.copy());
        }
        return java.util.Optional.empty();
    }

    private static String itemName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return itemId;
        Item item = BuiltInRegistries.ITEM.get(id);
        return new ItemStack(item).getHoverName().getString();
    }

    static String selectTopic(List<String> topics, int turn) {
        if (topics == null || topics.isEmpty()) return "";
        return topics.get(Math.floorMod(turn, topics.size()));
    }

    private static String sentence(String value) {
        if (value == null || value.isBlank()) return "这件事我还不想细说。";
        return value.endsWith("。") || value.endsWith("！") || value.endsWith("？")
                ? value : value + "。";
    }
}
