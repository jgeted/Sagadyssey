package com.jgeted.sagadyssey.social.industry.common;

import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Live endpoints, never copied inventories. Empty dropped entities expire by the original entity tick. */
public final class IndustryContainers {
    private IndustryContainers() {}
    public static Container dropped(ItemEntity entity){return new Container(){
        public int getContainerSize(){return 1;}
        public boolean isEmpty(){return entity.getItem().isEmpty();}
        public ItemStack getItem(int slot){return slot==0?entity.getItem():ItemStack.EMPTY;}
        public ItemStack removeItem(int slot,int amount){if(slot!=0)return ItemStack.EMPTY;var current=entity.getItem().copy();var result=current.split(amount);entity.setItem(current);return result;}
        public ItemStack removeItemNoUpdate(int slot){var result=getItem(slot).copy();setItem(slot,ItemStack.EMPTY);return result;}
        public void setItem(int slot,ItemStack value){if(slot!=0)throw new IllegalArgumentException("drop slot");entity.setItem(value.copy());}
        public void setChanged(){entity.setItem(entity.getItem().copy());}
        public boolean stillValid(Player p){return entity.isAlive();}
        public boolean canPlaceItem(int slot,ItemStack stack){return slot==0;}
        public void clearContent(){entity.setItem(ItemStack.EMPTY);}
    };}
}
