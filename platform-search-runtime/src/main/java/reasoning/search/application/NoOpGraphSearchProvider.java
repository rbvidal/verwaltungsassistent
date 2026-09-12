package reasoning.search.application;

import reasoning.search.api.GraphSearchProvider;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/** No-op fallback when no graph database is configured. */
@Component
@ConditionalOnMissingBean(value = GraphSearchProvider.class, ignored = NoOpGraphSearchProvider.class)
public class NoOpGraphSearchProvider implements GraphSearchProvider {

    @Override public boolean isAvailable() { return false; }

    @Override public List<RetrievalCandidate> search(SearchQuery query) { return List.of(); }

    @Override public List<String> findRelatedDocuments(String documentId, int maxDepth) { return List.of(); }
}
