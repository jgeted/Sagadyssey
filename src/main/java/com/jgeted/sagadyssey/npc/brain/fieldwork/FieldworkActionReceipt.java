package com.jgeted.sagadyssey.npc.brain.fieldwork;

import java.util.List;
import java.util.Objects;

/** 服务端真值回执；不加入普通玩家的数据包或界面。负数量为投入，正数量为产出。 */
public record FieldworkActionReceipt(FieldworkAction action, long committedAt,
                                     String resultingState, List<ItemDelta> inventoryDeltas,
                                     int toolDamage) {
    public FieldworkActionReceipt {
        Objects.requireNonNull(action, "action");
        FieldworkAction.requireText(resultingState, 1_024, "resultingState");
        inventoryDeltas = List.copyOf(Objects.requireNonNull(inventoryDeltas, "inventoryDeltas"));
        if (committedAt < 0L || toolDamage < 0 || inventoryDeltas.size() > 96) {
            throw new IllegalArgumentException("invalid action receipt");
        }
    }

    public record ItemDelta(String fingerprint, int quantity) {
        public ItemDelta {
            FieldworkAction.requireText(fingerprint, 1_024, "fingerprint");
            if (quantity == 0 || quantity == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("invalid inventory delta");
            }
        }
    }
}
