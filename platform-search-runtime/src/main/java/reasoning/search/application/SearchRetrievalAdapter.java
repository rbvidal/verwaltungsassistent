package reasoning.search.application;

import reasoning.ai.api.RetrievalAugmentationService;
import reasoning.ai.api.RetrievalPort;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.RetrievalContext;
import reasoning.search.api.SearchFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the AI domain's {@link RetrievalPort} to the search infrastructure.
 *
 * <p>This adapter lives in platform-search-runtime and delegates to
 * {@link RetrievalAugmentationService} (the AI-domain SPI implemented by
 * platform-ai-runtime). This ensures the AI runtime can depend on
 * {@link RetrievalPort} without importing search implementation types.
 *
 * <p>Governed by ADR-004 (Provider Model) and ADR-006 (AI Abstraction).
 */
@Service
public class SearchRetrievalAdapter implements RetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(SearchRetrievalAdapter.class);

    private final RetrievalAugmentationService retrievalService;
    private final SearchFacade searchFacade;

    public SearchRetrievalAdapter(RetrievalAugmentationService retrievalService,
                                  SearchFacade searchFacade) {
        this.retrievalService = retrievalService;
        this.searchFacade = searchFacade;
    }

    @Override
    public RetrievalContext retrieve(AiRequest request) {
        log.debug("SearchRetrievalAdapter: delegating to RetrievalAugmentationService");
        return retrievalService.retrieve(request);
    }

    @Override
    public List<String> availableProviders() {
        List<String> providers = new ArrayList<>();
        providers.add("keyword-postgres");
        providers.add("vector-qdrant");
        providers.add("graph-neo4j");
        return providers;
    }
}
