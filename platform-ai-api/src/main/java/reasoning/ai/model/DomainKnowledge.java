package reasoning.ai.model;


import java.util.*;

/**
 * Externalized domain knowledge consumed by {@code DomainClassifier}.
 *
 * <p>Encapsulates domain terminology, weights, word-boundary rules,
 * compound-embedding penalty, and classification thresholds — all
 * independent of the classification algorithm.
 *
 * <p>{@link #skeletal()} provides an empty instance for non-Spring
 * contexts (unit tests only — production must load terms from
 * {@code config/domain-knowledge.yml}).
 */
public class DomainKnowledge {

    /** A single weighted domain term. */
    public record TermWeight(String phrase, double weight) {
        public TermWeight {
            if (phrase == null || phrase.isBlank()) {
                throw new IllegalArgumentException("Domain term phrase must not be blank");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("Domain term weight must be positive, got " + weight);
            }
        }
    }

    // ── Instance fields ──

    private final Map<Domain, LinkedHashMap<String, Double>> termMaps;
    private final Set<String> wordBoundaryTerms;
    private final double compoundEmbeddingPenalty;
    private final double minimumPrimaryConfidence;
    private final double minimumSecondaryConfidence;

    public DomainKnowledge(
            Map<Domain, List<TermWeight>> domainTerms,
            Set<String> wordBoundaryTerms,
            double compoundEmbeddingPenalty,
            double minimumPrimaryConfidence,
            double minimumSecondaryConfidence) {

        Map<Domain, LinkedHashMap<String, Double>> maps = new LinkedHashMap<>();
        for (Domain d : Domain.all()) {
            if (d.isGeneral()) continue;
            maps.put(d, new LinkedHashMap<>());
        }
        if (domainTerms != null) {
            for (var entry : domainTerms.entrySet()) {
                Domain d = entry.getKey();
                if (d.isGeneral()) {
                    throw new IllegalArgumentException("GENERAL is reserved and cannot have configured terms");
                }
                LinkedHashMap<String, Double> tm = maps.computeIfAbsent(d, k -> new LinkedHashMap<>());
                for (TermWeight tw : entry.getValue()) {
                    if (tm.containsKey(tw.phrase())) {
                        throw new IllegalArgumentException(
                                "Duplicate term '" + tw.phrase() + "' in domain " + d);
                    }
                    tm.put(tw.phrase(), tw.weight());
                }
            }
        }
        this.termMaps = Collections.unmodifiableMap(maps);
        this.wordBoundaryTerms = wordBoundaryTerms == null ? Set.of() : Set.copyOf(wordBoundaryTerms);
        this.compoundEmbeddingPenalty = compoundEmbeddingPenalty;
        this.minimumPrimaryConfidence = minimumPrimaryConfidence;
        this.minimumSecondaryConfidence = minimumSecondaryConfidence;
    }

    // ── Accessors ──

    /** Returns the ordered term-weight map for the given domain (never null). */
    public Map<String, Double> termsFor(Domain domain) {
        return termMaps.getOrDefault(domain, new LinkedHashMap<>());
    }

    public Set<String> wordBoundaryTerms() { return wordBoundaryTerms; }
    public double compoundEmbeddingPenalty() { return compoundEmbeddingPenalty; }
    public double minimumPrimaryConfidence() { return minimumPrimaryConfidence; }
    public double minimumSecondaryConfidence() { return minimumSecondaryConfidence; }

    // ── Validation ──

    /** Validates this knowledge set, throwing on configuration errors. */
    public void validate() {
        Set<String> seen = new HashSet<>();
        for (var entry : termMaps.entrySet()) {
            for (var tw : entry.getValue().entrySet()) {
                if (!seen.add(tw.getKey())) {
                    throw new IllegalArgumentException(
                            "Duplicate domain term '" + tw.getKey() + "' in domain " + entry.getKey());
                }
            }
        }
        if (compoundEmbeddingPenalty < 0.0 || compoundEmbeddingPenalty > 1.0) {
            throw new IllegalArgumentException(
                    "compoundEmbeddingPenalty must be in [0,1], got " + compoundEmbeddingPenalty);
        }
        if (minimumPrimaryConfidence < 0.0 || minimumPrimaryConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "minimumPrimaryConfidence must be in [0,1], got " + minimumPrimaryConfidence);
        }
    }

    // ── Skeletal instance for non-Spring contexts ──

    /**
     * Returns a skeletal instance with NO domain terminology.
     * Empty term maps, default parameters, empty word-boundary set.
     *
     * <p>Use only in non-Spring unit tests that need a DomainKnowledge
     * instance but do not depend on specific classification terms.
     * Spring-based tests and production must load terms from
     * {@code config/domain-knowledge.yml} via {@code DomainKnowledgeConfig}.
     */
    public static DomainKnowledge skeletal() {
        return new DomainKnowledge(Map.of(), Set.of(), 0.85, 0.15, 0.20);
    }
}
