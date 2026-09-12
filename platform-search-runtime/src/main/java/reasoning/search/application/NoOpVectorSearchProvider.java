package reasoning.search.application;

import reasoning.search.api.VectorSearchProvider;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** No-op vector search provider that returns empty results; used when no real vector store is configured. */
@Component
@ConditionalOnMissingBean(value = VectorSearchProvider.class, ignored = NoOpVectorSearchProvider.class)
public class NoOpVectorSearchProvider implements VectorSearchProvider {

    @Override
    public List<RetrievalCandidate> search(SearchQuery query) {
        return List.of();
    }

    @Override
    public void index(DocumentChunk chunk, float[] embedding) {
    }

    @Override
    public void deleteByDocument(UUID documentId) {
    }
}
