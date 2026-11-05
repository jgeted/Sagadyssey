package com.jgeted.sagadyssey.npc.handover;

public enum HandoverIntent {
    GIFT("赠礼"),
    PAYMENT("付款"),
    DELIVERY("交付"),
    ISSUE("配发"),
    LOAN("借用"),
    SHOW("展示");

    private final String displayName;
    HandoverIntent(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
