package reasoning.search.application.qdrant;

import reasoning.search.model.SearchFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case-scoped retrieval regression tests: the Qdrant vector search must apply
 * the document scope as a payload filter BEFORE its top-K limit, otherwise a
 * case document ranking below the global cutoff would be lost. Without a
 * document scope the request must stay byte-identical to the global search.
 */
class QdrantVectorSearchProviderTest {

    @Test
    void scopedSearch_addsDocumentIdFilter() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        SearchFilter filter = new SearchFilter(Set.of(docA, docB), null, null, null,
                null, null, null, null, List.of());

        Map<String, Object> request = QdrantVectorSearchProvider.buildSearchRequest(
                new float[]{0.1f, 0.2f}, 20, filter);

        assertTrue(request.containsKey("filter"), "scoped search must carry a Qdrant filter");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> must = (List<Map<String, Object>>)
                ((Map<String, Object>) request.get("filter")).get("must");
        assertEquals(1, must.size());
        assertEquals("documentId", must.get(0).get("key"));
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) must.get(0).get("match");
        assertTrue(((List<String>) match.get("any")).containsAll(List.of(docA.toString(), docB.toString())),
                "both case document ids must be in the match filter");
    }

    @Test
    void globalSearch_hasNoFilter() {
        Map<String, Object> request = QdrantVectorSearchProvider.buildSearchRequest(
                new float[]{0.1f}, 20, null);

        assertFalse(request.containsKey("filter"), "global search must not be filtered");
        assertEquals(20, request.get("limit"));
        assertEquals(true, request.get("with_payload"));
    }

    @Test
    void emptyDocumentScope_isTreatedAsGlobalSearch() {
        Map<String, Object> request = QdrantVectorSearchProvider.buildSearchRequest(
                new float[]{0.1f}, 20, new SearchFilter(Set.of(), null, null, null,
                        null, null, null, null, List.of()));

        assertFalse(request.containsKey("filter"), "empty document scope must not filter");
    }

    // ── Candidate depth (pre-merge overscan) ────────────────────────────────

    /**
     * The vector channel must fetch DEEPER than the requested page size: the
     * fusion, reranking and the evidence gates of the AI layer (diversity,
     * lexical anchor, semantic floor, temporal filter) consume candidates
     * before the final window. A passage deep inside a large law document
     * (z. B. die Fristen-Passage im BMG) ranks far below the collection top-K
     * even when it is the only passage that answers the question; a fetch
     * limited to the page size starves it before any downstream decision.
     */
    @Test
    void candidateFetchLimit_providesDeepPreMergeWindow() {
        assertEquals(150, QdrantVectorSearchProvider.candidateFetchLimit(25),
                "the assistant candidate window (25) must fetch ~6x deeper so law-document "
                        + "answer passages can enter the merge");
        assertEquals(60, QdrantVectorSearchProvider.candidateFetchLimit(10),
                "the 10-point floor is overscanned to 60");
        assertEquals(500, QdrantVectorSearchProvider.candidateFetchLimit(100),
                "the overscan must stay bounded for large UI pages");
    }
}
