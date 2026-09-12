package reasoning.ai.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A domain/category identifier for Verwaltungsassistent. Domains are registered by the
 * application at startup (from domain-knowledge.yml or equivalent).
 *
 * <p>The core ships with a single built-in domain: {@link #GENERAL},
 * which represents "no specific domain classification." All other
 * domains are supplied by the domain application.
 *
 * <p>Domain equality is based on the {@link #name()} string, which
 * is case-insensitive.
 */
public final class Domain implements Comparable<Domain> {

    private static final Map<String, Domain> REGISTRY = new ConcurrentHashMap<>();

    /** The universal fallback domain — no specific classification. */
    public static final Domain GENERAL = register("GENERAL");

    private final String name;

    private Domain(String name) {
        this.name = name;
    }

    /** Registers a domain by name. Idempotent — returns existing instance if already registered. */
    public static Domain of(String name) {
        return REGISTRY.computeIfAbsent(name.toUpperCase(), Domain::new);
    }

    /** Registers a domain (internal use — does not expose registration to callers). */
    static Domain register(String name) {
        return REGISTRY.computeIfAbsent(name.toUpperCase(), Domain::new);
    }

    /** Returns all currently registered domains (excluding GENERAL). */
    public static Set<Domain> allClassifiable() {
        Set<Domain> set = new LinkedHashSet<>(REGISTRY.values());
        set.remove(GENERAL);
        return Collections.unmodifiableSet(set);
    }

    /** Returns all registered domains including GENERAL. */
    public static Set<Domain> all() {
        return Set.copyOf(REGISTRY.values());
    }

    public String name() { return name; }
    public boolean isGeneral() { return this == GENERAL; }

    @Override public String toString() { return name; }
    @Override public boolean equals(Object o) {
        return o instanceof Domain d && name.equalsIgnoreCase(d.name);
    }
    @Override public int hashCode() { return name.toUpperCase().hashCode(); }
    @Override public int compareTo(Domain o) { return name.compareToIgnoreCase(o.name); }
}
