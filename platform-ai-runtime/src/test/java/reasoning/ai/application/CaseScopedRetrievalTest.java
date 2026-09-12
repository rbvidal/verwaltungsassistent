package reasoning.ai.application;

import reasoning.ai.api.AuthorityGroundingService;
import reasoning.ai.api.SourceOrchestrationService;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.Domain;
import reasoning.ai.model.RetrievalPlan;
import reasoning.ai.model.RetrievalScope;
import reasoning.ai.model.SourceDossier;
import reasoning.ai.model.SourceRole;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.CitationReference;
import reasoning.search.model.SearchFilter;
import reasoning.search.model.SearchQuery;
import reasoning.search.model.SearchResult;
import reasoning.search.model.SearchResultPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Case-scoped retrieval regression tests at the AI layer: a request scoped to
 * a case must (1) pass the case document ids into the search query and
 * (2) keep only case documents in the final evidence — including a case chunk
 * that would rank below the global top-K cutoff. The global path must stay
 * unfiltered.
 */
class CaseScopedRetrievalTest {

    private final UUID caseDoc = UUID.randomUUID();
    private final UUID unrelatedDoc = UUID.randomUUID();

    private final SearchFacade searchFacade = mock(SearchFacade.class);
    private final ChunkManagementService chunkStore = mock(ChunkManagementService.class);
    private final AuthorityGroundingService grounding = mock(AuthorityGroundingService.class);
    private final RetrievalPlanner planner = mock(RetrievalPlanner.class);
    private final DomainGate domainGate = mock(DomainGate.class);
    private final SourceOrchestrationService orchestration = mock(SourceOrchestrationService.class);

    private DefaultRetrievalAugmentationService service() {
        when(planner.plan(any(), any())).thenReturn(
                new RetrievalPlan(Domain.GENERAL, Domain.GENERAL, List.of(), List.of(),
                        "HYBRID", 10, 3));
        when(domainGate.classifyDomain(any())).thenReturn(Domain.GENERAL);
        when(domainGate.filterByDomain(any(), any())).thenReturn(
                new DomainGate.FilterResult(Domain.GENERAL, List.of(), List.of()));
        when(domainGate.domainScore(any(), any())).thenReturn(1.0);
        when(grounding.ground(any())).thenReturn(
                new AuthorityGroundingService.AuthorityGroundingResult(List.of(), List.of()));
        when(orchestration.buildDossier(any(), any())).thenReturn(
                new SourceDossier(Map.of(), List.of(), List.of(), 0.0, "test"));
        return new DefaultRetrievalAugmentationService(
                searchFacade, chunkStore, grounding, planner, domainGate, null, orchestration, "", 0);
    }

    private SearchResult result(UUID docId, double score) {
        ChunkReference ref = new ChunkReference(UUID.randomUUID(), docId, 1, "titel", null, null);
        return new SearchResult(ref, "text", score, score, "hybrid",
                new CitationReference(docId, UUID.randomUUID(), 1,
                        "titel", null, null, null, null),
                0.5, 0.5, 0.0, "GENERAL", "HYBRID_RETRIEVAL");
    }

    @Test
    void scopedRequest_passesCaseDocumentFilter_andKeepsOnlyCaseDocuments() {
        // Global search returns 3 unrelated hits ABOVE the two case hits —
        // the exact scenario where a pre-cutoff filter is required.
        SearchResultPage page = new SearchResultPage(List.of(
                result(unrelatedDoc, 0.9),
                result(unrelatedDoc, 0.85),
                result(unrelatedDoc, 0.8),
                result(caseDoc, 0.45),
                result(caseDoc, 0.4)), 0, 20, 5, 1, "HYBRID");
        when(searchFacade.search(any())).thenReturn(page);

        AiRequest request = new AiRequest("Welche Unterlagen fehlen in diesem Fall?", null,
                new SearchFilter(Set.of(caseDoc), null, null, null, null, null, null, null, List.of()),
                new AiConversationContext(List.of(), "user", null, null, "req-1"), 15,
                RetrievalScope.CURRENT_WORKSPACE, UUID.randomUUID());

        var context = service().retrieve(request);

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        verify(searchFacade).search(captor.capture());
        assertEquals(Set.of(caseDoc), captor.getValue().filter().documentIds(),
                "the case document ids must reach the search query");

        assertEquals(2, context.sources().size(),
                "both case chunks must survive — including the ones below the global top-K");
        assertTrue(context.sources().stream().allMatch(s -> s.documentId().equals(caseDoc)),
                "no unrelated document may enter the case-scoped evidence");
    }

    @Test
    void globalRequest_passesNoDocumentFilter() {
        when(searchFacade.search(any())).thenReturn(
                new SearchResultPage(List.of(result(unrelatedDoc, 0.9)), 0, 20, 1, 1, "HYBRID"));

        AiRequest request = new AiRequest("Allgemeine Frage", null, null,
                new AiConversationContext(List.of(), "user", null, null, "req-2"), 15);

        service().retrieve(request);

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        verify(searchFacade).search(captor.capture());
        assertTrue(captor.getValue().filter().documentIds().isEmpty(),
                "the global path must stay unfiltered");
    }
}
