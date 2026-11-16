package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;

/** 第一批只表达位置军令；接敌与人物目标在相应能力接入时扩展。 */
public record WarIntent(WarKey kind, int definitionVersion, Position target) {
    public WarIntent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        if (definitionVersion < 1) throw new IllegalArgumentException("invalid intent version");
    }

    public record Position(WarKey dimension, int x, int y, int z) {
        public Position { Objects.requireNonNull(dimension, "dimension"); }
    }
}
