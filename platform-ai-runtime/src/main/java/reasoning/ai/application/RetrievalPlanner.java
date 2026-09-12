package reasoning.ai.application;

import reasoning.ai.config.AiPipelineProperties;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.Domain;
import reasoning.ai.model.RetrievalPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plans retrieval once per question. No recursion, no targeted
 * follow-up searches, no missing-role detection.
 *
 * <p>Pipeline:
 * <pre>
 *   Question → Intent → Domain → RetrievalPlan → Execute (once)
 * </pre>
 */
@Component
public class RetrievalPlanner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPlanner.class);

    private final DomainClassifier domainClassifier;
    private final AiPipelineProperties props;

    public RetrievalPlanner(DomainClassifier domainClassifier, AiPipelineProperties props) {
        this.domainClassifier = domainClassifier;
        this.props = props;
    }

    /**
     * Creates a single retrieval plan for the given question.
     */
    public RetrievalPlan plan(AiRequest request) {
        return plan(request, null);
    }

    /**
     * Creates a retrieval plan using an authoritative domain when supplied
     * (semantic-intent path). Otherwise classifies via {@link DomainClassifier}
     * (legacy fallback).
     */
    public RetrievalPlan plan(AiRequest request, Domain authoritativeDomain) {
        String question = request.question();
        Domain domain;
        Domain secondary = null;
        double confidence;
        if (authoritativeDomain != null) {
            domain = authoritativeDomain;
            confidence = 1.0;
        } else {
            var domainResult = domainClassifier.classify(question);
            domain = domainResult.primary();
            secondary = domainResult.secondary();
            confidence = domainResult.primaryConfidence();
        }

        int maxResults = request.maxRetrievalResults() > 0
                ? Math.min(request.maxRetrievalResults(), 20) : 20;

        // Domain-specific collections/authorities are supplied by the application.
        // The core provides empty defaults; the municipal module may override via config.
        RetrievalPlan plan = new RetrievalPlan(domain, secondary,
                List.of(), List.of(), "HYBRID", maxResults,
                props.getMaxParagraphsPerSource());

        log.info("RetrievalPlan: domain={} (conf={:.2f} {}) | strategy={} | maxResults={} | maxChunksPerDoc={}",
                plan.primaryDomain(), confidence,
                authoritativeDomain != null ? "authoritative" : "classified",
                plan.retrievalStrategy(), plan.maxResults(),
                plan.maxChunksPerDocument());

        return plan;
    }

    /** Returns the classified domain for a question without creating a full plan. */
    public Domain classifyDomain(String question) {
        return domainClassifier.classify(question).primary();
    }
}
