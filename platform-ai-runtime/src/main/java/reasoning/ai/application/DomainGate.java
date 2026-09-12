package reasoning.ai.application;

import reasoning.ai.model.Domain;
import reasoning.ai.model.DomainKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Domain-first document filter. Uses the weighted DomainClassifier
 * to determine the domain, then filters document titles to only
 * include those matching the domain.
 *
 * <p>A procurement question must never receive travel-expense regulations.
 */
@Component
public class DomainGate {

    private static final Logger log = LoggerFactory.getLogger(DomainGate.class);

    private final DomainClassifier classifier;

    public DomainGate(DomainClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Returns the classified primary domain for a query.
     */
    public Domain classifyDomain(String query) {
        return classifier.classifySimple(query);
    }

    /**
     * Filters document titles to only those matching the detected domain.
     */
    public FilterResult filter(String query, List<String> documentTitles) {
        Domain domain = classifyDomain(query);
        return filterByDomain(domain, documentTitles);
    }

    public FilterResult filterByDomain(Domain domain, List<String> documentTitles) {
        Set<String> accepted = new LinkedHashSet<>();
        Set<String> rejected = new LinkedHashSet<>();

        for (String title : documentTitles) {
            if (accepts(domain, title)) {
                accepted.add(title);
            } else {
                rejected.add(title);
            }
        }

        log.info("DomainGate [{}]: {} accepted, {} rejected", domain, accepted.size(), rejected.size());
        if (!rejected.isEmpty() && rejected.size() <= 5) {
            log.info("DomainGate rejected: {}", String.join(", ", rejected));
        }

        return new FilterResult(domain, List.copyOf(accepted), List.copyOf(rejected));
    }

    private boolean accepts(Domain domain, String title) {
        if (domain.isGeneral() || title == null) return true;
        String lower = title.toLowerCase();
        // Filter terms sourced from DomainKnowledge (externalized YAML config).
        // This replaces the previously hardcoded per-domain keyword lists.
        Map<String, Double> terms = classifier.knowledge().termsFor(domain);
        for (String term : terms.keySet()) {
            if (lower.contains(term)) return true;
        }
        return false;
    }

    /**
     * Returns a domain relevance score multiplier for a document title given
     * the classified domain. This is a SOFT signal — it adjusts scores without
     * hard-filtering. Cross-domain documents are penalized moderately; matching
     * documents are boosted; neutral documents pass through unchanged.
     *
     * @param domain the classified primary domain for the query
     * @param title  the document title
     * @return score multiplier in [0.70, 1.35]; 1.0 = no adjustment
     */
    public double domainScore(Domain domain, String title) {
        if (domain.isGeneral() || title == null) return 1.0;
        String lower = title.toLowerCase();

        // Does the title match the query's own domain?
        if (accepts(domain, title)) return 1.0; // neutral baseline for matching docs

        // Does the title match a DIFFERENT domain instead?
        // If so, it's a cross-domain document → mild penalty.
        for (Domain other : Domain.allClassifiable()) {
            if (other.equals(domain) || other.isGeneral()) continue;
            if (accepts(other, title)) {
                log.debug("DomainGate: '{}' matches {} while query domain is {} → moderate penalty",
                        title, other, domain);
                return 0.80; // cross-domain: 20% penalty
            }
        }

        // Title doesn't match any domain pattern → neutral
        return 1.0;
    }

    private boolean hasAny(String text, String... terms) {
        for (String t : terms) if (text.contains(t)) return true;
        return false;
    }

    public record FilterResult(
            Domain domain,
            List<String> accepted,
            List<String> rejected
    ) {}
}
