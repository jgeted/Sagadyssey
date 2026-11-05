package com.jgeted.sagadyssey.npc.trade;

public record TradeEvaluation(Outcome outcome, String explanation) {
    public enum Outcome { ACCEPT, COUNTER, REFUSE }
}
