package reasoning.search.application;

import reasoning.search.api.RerankingService;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Reranks retrieval candidates by ranking score in descending order. */
@Service
public class DefaultRerankingService implements RerankingService {

    @Override
    public List<RetrievalCandidate> rerank(SearchQuery query, List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rankingScore).reversed())
                .toList();
    }
}
