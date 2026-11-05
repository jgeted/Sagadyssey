package com.jgeted.sagadyssey.social.combat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum TreatmentType {
    BANDAGE(Items.WHITE_WOOL, 1),
    SPLINT(Items.STICK, 2),
    ANTISEPTIC(Items.HONEY_BOTTLE, 1);

    private final Item supply;
    private final int quantity;

    TreatmentType(Item supply, int quantity) {
        this.supply = supply;
        this.quantity = quantity;
    }

    public Item supply() { return supply; }
    public int quantity() { return quantity; }
}
