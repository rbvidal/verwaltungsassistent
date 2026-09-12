package reasoning.ai.api;

import reasoning.ai.model.QueryIntent;

/**
 * Classifies a query string into a {@link QueryIntent}.
 */
public interface QueryIntentClassifier {
    /**
     * Classifies the intent of the given query.
     */
    QueryIntent classify(String query);
}
