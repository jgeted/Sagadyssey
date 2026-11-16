package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarKey;

import java.util.*;

/** 启动时验证并冻结。此处声明定义可解释性，不宣称物理执行适配已经存在。 */
public final class WarDefinitions {
    public enum Kind { MODULE, TROOP, ORDER }

    public record Definition(WarKey id, Kind kind, int version, Set<WarKey> dependencies) {
        public Definition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            dependencies = Set.copyOf(dependencies);
            if (version < 1 || dependencies.contains(id)) throw new IllegalArgumentException("invalid definition");
        }
    }

    private final Map<WarKey, Definition> definitions;

    private WarDefinitions(Map<WarKey, Definition> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public Optional<Definition> find(WarKey id) { return Optional.ofNullable(definitions.get(id)); }
    public Map<WarKey, Definition> entries() { return definitions; }
    public boolean supports(WarKey id, Kind kind, int version) {
        Definition value = definitions.get(id);
        return value != null && value.kind() == kind && value.version() == version;
    }

    public static final class Builder {
        private final Map<WarKey, Definition> definitions = new TreeMap<>();
        private boolean frozen;

        public Builder register(Definition definition) {
            if (frozen) throw new IllegalStateException("registry already frozen");
            Objects.requireNonNull(definition, "definition");
            if (definitions.size() >= 4096) throw new IllegalStateException("definition limit exceeded");
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("duplicate war definition: " + definition.id());
            }
            return this;
        }

        public WarDefinitions freeze() {
            if (frozen) throw new IllegalStateException("registry already frozen");
            Map<WarKey, Integer> incoming = new HashMap<>();
            Map<WarKey, List<WarKey>> dependants = new HashMap<>();
            for (Definition value : definitions.values()) {
                incoming.put(value.id(), value.dependencies().size());
                for (WarKey dependency : value.dependencies()) {
                    if (!definitions.containsKey(dependency)) {
                        throw new IllegalArgumentException("missing dependency " + dependency + " for " + value.id());
                    }
                    dependants.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(value.id());
                }
            }
            // Iterative topological validation avoids recursion depth depending on configuration.
            Queue<WarKey> ready = new PriorityQueue<>();
            incoming.forEach((id, count) -> { if (count == 0) ready.add(id); });
            Map<WarKey, Definition> ordered = new LinkedHashMap<>();
            while (!ready.isEmpty()) {
                WarKey id = ready.remove();
                ordered.put(id, definitions.get(id));
                for (WarKey next : dependants.getOrDefault(id, List.of())) {
                    if (incoming.compute(next, (key, count) -> count - 1) == 0) ready.add(next);
                }
            }
            if (ordered.size() != definitions.size()) throw new IllegalArgumentException("cyclic war dependencies");
            frozen = true;
            return new WarDefinitions(ordered);
        }
    }
}
