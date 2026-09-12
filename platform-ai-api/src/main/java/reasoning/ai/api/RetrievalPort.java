package reasoning.ai.api;

import reasoning.ai.model.AiRequest;
import reasoning.ai.model.RetrievalContext;

import java.util.List;

/**
 * AI-owned port for executing retrieval without depending on search infrastructure.
 *
 * <p>This is the AI domain's boundary contract for retrieval. Search modules implement
 * this port via an adapter, keeping the AI module decoupled from search implementation
 * types. The AI runtime depends on this port, never on SearchFacade directly.
 *
 * <p>ADR-004 (Provider Model) and ADR-006 (AI Abstraction) govern this contract.
 */
public interface RetrievalPort {

    /**
     * Executes retrieval for the given AI request and returns the augmented context.
     *
     * @param request the AI request containing the query and retrieval parameters
     * @return the retrieval context with sources, authorities, and structured data
     */
    RetrievalContext retrieve(AiRequest request);

    /**
     * Returns the unique names of all available retrieval providers.
     */
    List<String> availableProviders();
}
