package reasoning.ai.application;

import reasoning.ai.model.Domain;
import reasoning.ai.model.DomainKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Weighted domain classifier. Every query receives exactly one primary
 * domain. Optionally one secondary domain. Never GENERAL unless
 * confidence is very low.
 *
 * <p>The classification algorithm is generic. Domain vocabulary,
 * weights, word-boundary rules, and numeric parameters are supplied
 * by {@link DomainKnowledge} — externalized to {@code config/domain-knowledge.yml}.
 */
@Component
public class DomainClassifier {

    private static final Logger log = LoggerFactory.getLogger(DomainClassifier.class);

    private final DomainKnowledge knowledge;

    /**
     * Constructs a classifier with the given domain knowledge.
     * In production, Spring injects the bean from {@code DomainKnowledgeConfig}.
     */
    public DomainClassifier(DomainKnowledge knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "DomainKnowledge must not be null");
    }

    /** Returns the domain knowledge backing this classifier (for DomainGate). */
    DomainKnowledge knowledge() { return knowledge; }

    /** Result of domain classification. */
    public record DomainResult(
            Domain primary,
            double primaryConfidence,
            Domain secondary,
            double secondaryConfidence,
            Map<Domain, Double> allScores
    ) {
        public boolean isStrong() { return primaryConfidence >= 0.7; }
        public boolean isConfident() { return primaryConfidence >= 0.4; }
    }

    /**
     * Classifies a query into exactly one primary domain.
     * Never returns GENERAL unless confidence is below threshold.
     */
    public DomainResult classify(String query) {
        String lower = query.toLowerCase().trim();

        Map<Domain, Double> raw = new LinkedHashMap<>();
        for (Domain d : Domain.allClassifiable()) {
            if (d.isGeneral()) continue;
            raw.put(d, score(lower, knowledge.termsFor(d)));
        }

        // Normalize scores to 0–1 range
        double max = raw.values().stream().max(Double::compareTo).orElse(0.0);
        Map<Domain, Double> normalized = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            normalized.put(entry.getKey(), max > 0 ? entry.getValue() / max : 0.0);
        }

        // Find primary and secondary with tie-breaking
        List<Map.Entry<Domain, Double>> sorted = normalized.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.getValue(), a.getValue()); // descending normalized
                    if (cmp != 0) return cmp;
                    double rawA = raw.getOrDefault(a.getKey(), 0.0);
                    double rawB = raw.getOrDefault(b.getKey(), 0.0);
                    return Double.compare(rawB, rawA);
                })
                .toList();

        Domain primary = Domain.GENERAL;
        double primaryConf = 0.0;
        Domain secondary = null;
        double secondaryConf = 0.0;

        if (!sorted.isEmpty()) {
            var first = sorted.get(0);
            if (first.getValue() >= knowledge.minimumPrimaryConfidence()) {
                primary = first.getKey();
                primaryConf = first.getValue();
            }
            if (sorted.size() > 1 && sorted.get(1).getValue() >= knowledge.minimumSecondaryConfidence()) {
                secondary = sorted.get(1).getKey();
                secondaryConf = sorted.get(1).getValue();
            }
        }

        DomainResult result = new DomainResult(primary, primaryConf, secondary, secondaryConf, normalized);
        log.info("Domain: {} ({:.2f}) | secondary: {} ({:.2f}) | scores: {}",
                result.primary, result.primaryConfidence,
                result.secondary, result.secondaryConfidence,
                result.allScores);
        return result;
    }

    /** Quick domain detection for reranker compatibility. */
    public Domain classifySimple(String query) {
        return classify(query).primary();
    }

    /**
     * Scores a query against a set of weighted domain terms.
     * Uses word-boundary matching for short generic terms and
     * applies a compound-embedding penalty for terms found
     * inside longer compound words.
     */
    private double score(String query, Map<String, Double> terms) {
        double total = 0.0;
        Set<String> wordBoundaryTerms = knowledge.wordBoundaryTerms();
        double penalty = knowledge.compoundEmbeddingPenalty();

        for (var entry : terms.entrySet()) {
            String term = entry.getKey();
            double weight = entry.getValue();

            if (wordBoundaryTerms.contains(term)) {
                // Short generic term — must match at word boundaries
                if (query.matches(".*\\b" + java.util.regex.Pattern.quote(term) + "\\b.*")) {
                    total += weight;
                }
            } else if (query.contains(term)) {
                // Standalone word match gets full weight;
                // compound-embedded gets reduced weight.
                if (query.matches(".*\\b" + java.util.regex.Pattern.quote(term) + "\\b.*")) {
                    total += weight;
                } else {
                    total += weight * penalty;
                }
            }
        }
        return total;
    }
}
