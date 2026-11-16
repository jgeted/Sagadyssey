package com.jgeted.sagadyssey.social.war.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** 存档与协议使用稳定名称；不依赖 Minecraft 注册类型或枚举 ordinal。 */
public record WarKey(String namespace, String path) implements Comparable<WarKey> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,128}");

    public WarKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid war key: " + namespace + ':' + path);
        }
    }

    public static WarKey parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator < 1 || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("war key must be namespace:path");
        }
        return new WarKey(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override public String toString() { return namespace + ':' + path; }
    @Override public int compareTo(WarKey other) { return toString().compareTo(other.toString()); }
}
