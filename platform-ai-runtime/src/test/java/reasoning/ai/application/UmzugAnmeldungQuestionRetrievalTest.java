package reasoning.ai.application;

import reasoning.ai.api.AuthorityGroundingService;
import reasoning.ai.api.SourceOrchestrationService;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.Domain;
import reasoning.ai.model.RetrievalPlan;
import reasoning.ai.model.SourceDossier;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.CitationReference;
import reasoning.search.model.SearchResult;
import reasoning.search.model.SearchResultPage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test für die Frage „Wie schnell muss ich mich nach meinem Umzug
 * anmelden?" auf dem Retrieval-Pfad des normalen Assistenten.
 *
 * <p>Beobachteter Fehler: Die Antwortpassage „… innerhalb von zwei Wochen nach
 * dem Einzug bei der Meldebehörde anzumelden" (BMG § 17 Abs. 1) erreichte die
 * Evidenz nicht. Die semantisch starken, aber lexikalisch unverankerten
 * Geschwister-Chunks des BMG-Dokuments wurden vom Evidenz-Anker verworfen,
 * die verankerte §17-Passage selbst lag außerhalb der Top-K-Fenster der
 * Suchkanäle (Vektor-Top-25 / Keyword-Kappung). Ergebnis: 0 Quellen →
 * Fail-Closed „keine ausreichenden Informationen".</p>
 *
 * <p>Dieser Test pinnt den Vertrag der AI-Ebene für die korrigierte
 * Kanal-Tiefe: Liefert der Suchindex die §17-Passage mit BEIDEN Signalen
 * (keywordScore &gt; 0 UND vectorScore ≥ Vektor-Schwelle), muss sie die
 * Anker- und Semantik-Gates passieren und als einzige Quelle überleben —
 * unverankerte Vektor-Treffer (auch sehr starke) und schwache lexikalische
 * Zufallstreffer dürfen nicht als Beleg erscheinen.</p>
 */
class UmzugAnmeldungQuestionRetrievalTest {

    private static final String QUESTION = "Wie schnell muss ich mich nach meinem Umzug anmelden?";
    private static final String BMG_TITLE = "Bundesmeldegesetz (BMG)";
    private static final String BMG_17_EXCERPT =
            "§ 17 Anmeldung, Abmeldung (1) Wer eine Wohnung bezieht, hat sich "
            + "innerhalb von zwei Wochen nach dem Einzug bei der Meldebehörde anzumelden.";

    private final UUID bmgDoc = UUID.randomUUID();
    private final UUID junkDoc = UUID.randomUUID();
    private final UUID geoDoc = UUID.randomUUID();

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
        when(chunkStore.findChunks(any(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(List.of());
        return new DefaultRetrievalAugmentationService(
                searchFacade, chunkStore, grounding, planner, domainGate, null, orchestration, "", 0);
    }

    /** Kandidat mit keywordScore (lexikalische Abdeckung) und vectorScore (semantische Ähnlichkeit). */
    private SearchResult result(UUID docId, String title, String excerpt,
                                double keywordScore, double vectorScore) {
        ChunkReference ref = new ChunkReference(UUID.randomUUID(), docId, 1, title, null, null);
        CitationReference citation = new CitationReference(docId, UUID.randomUUID(), 1, title,
                null, null, null, excerpt, null, null, null, null);
        double fused = keywordScore * 0.2 + vectorScore * 0.6 + vectorScore * 0.2;
        return new SearchResult(ref, excerpt, fused, vectorScore, "hybrid", citation,
                keywordScore, vectorScore, 0.0, "GENERAL", "HYBRID_RETRIEVAL");
    }

    @Test
    void umzugQuestion_bmgPassageSurvivesAnchorAndSemanticGate_asOnlySource() {
        // Reale Trefferlage NACH der Kanal-Korrektur: Der Suchindex liefert die
        // beantwortende §17-Passage mit beiden Signalen (kw > 0 über
        // „anzumelden", vec ≈ 0.59) UND die semantisch stärkeren, aber
        // lexikalisch unverankerten Geschwister-Chunks desselben BMG-Dokuments
        // (vec 0.61-0.64) sowie Rausch-Kandidaten (E2E-Boilerplate vec 0.61,
        // GEO-Dokument mit schwachem Vektor).
        List<SearchResult> page = List.of(
                result(bmgDoc, BMG_TITLE, BMG_17_EXCERPT, 0.5, 0.59),
                result(bmgDoc, BMG_TITLE, "im Inland gemeldet ist. Wer nicht für eine Wohnung im Inland gemeldet ist…", 0.0, 0.635),
                result(bmgDoc, BMG_TITLE, "…überlassenen Plätzen übernachten, unterliegen nicht der Meldepflicht…", 0.0, 0.615),
                result(junkDoc, "E2E-Dokument Gewerbeanmeldung", "Ein Zweit-Wohnsitz (Nebenwohnsitz) in Berlin reicht aus…", 0.0, 0.606),
                // Lexikalischer Zufallstreffer („schnell"/„melden" im Text) mit
                // nur schwachem EIGENEM Vektor — real unter der Vektor-Schwelle.
                result(junkDoc, "E2E-Dokument Gewerbeanmeldung", "Die Gewerbeanmeldung ist schnell erledigt…", 0.25, 0.19),
                result(geoDoc, "Straßenbeleuchtung – Zuständigkeit und Meldung defekter Beleuchtung",
                        "Eine defekte Straßenlaterne können Bürger melden…", 0.25, 0.22));
        when(searchFacade.search(any())).thenReturn(
                new SearchResultPage(page, 0, 25, page.size(), 1, "HYBRID"));

        var context = service().retrieve(new AiRequest(QUESTION, null, null,
                new AiConversationContext(List.of(), "user", null, null, "umzug-1"), 15));

        // Kern-Regression: Die Antwort darf NICHT in „keine ausreichenden
        // Informationen" enden — die BMG-Quelle muss die Evidenz erreichen.
        assertFalse(context.sources().isEmpty(),
                "the BMG source must reach the evidence for the Umzug question");
        assertEquals(1, context.sources().size(),
                "only the lexically anchored AND semantically strong BMG passage may qualify");
        var source = context.sources().getFirst();
        assertEquals(bmgDoc, source.documentId());
        assertEquals(BMG_TITLE, source.title());
        assertTrue(source.excerpt().contains("zwei Wochen"),
                "the surviving passage must be the §17 Fristen-Passage that answers the question");
        assertTrue(source.retrievalSources().stream().anyMatch("KEYWORD"::equalsIgnoreCase)
                        && source.retrievalSources().stream().anyMatch("VECTOR"::equalsIgnoreCase),
                "the BMG passage is anchored by keyword AND semantic retrieval");
    }

    @Test
    void umzugQuestion_vectorOnlyJunk_neverBecomesEvidence_whenAnchorEngages() {
        // Sobald der Anker greift (≥ 2 lexikalisch verankerte Kandidaten),
        // dürfen unverankerte Vektor-Treffer — selbst die stärksten — nicht
        // als Beleg erscheinen: Der E2E-Boilerplate-Treffer (vec 0.635) und der
        // unverankerte BMG-Geschwister-Chunk müssen verworfen werden.
        List<SearchResult> page = List.of(
                result(bmgDoc, BMG_TITLE, BMG_17_EXCERPT, 0.5, 0.59),
                result(bmgDoc, BMG_TITLE, "im Inland gemeldet ist…", 0.0, 0.635),
                result(junkDoc, "E2E-Dokument Gewerbeanmeldung", "Ein Zweit-Wohnsitz (Nebenwohnsitz) in Berlin reicht aus…", 0.0, 0.635),
                result(geoDoc, "Straßenbeleuchtung – Zuständigkeit und Meldung defekter Beleuchtung",
                        "Eine defekte Straßenlaterne können Bürger melden…", 0.25, 0.10));
        when(searchFacade.search(any())).thenReturn(
                new SearchResultPage(page, 0, 25, page.size(), 1, "HYBRID"));

        var context = service().retrieve(new AiRequest(QUESTION, null, null,
                new AiConversationContext(List.of(), "user", null, null, "umzug-2"), 15));

        assertFalse(context.sources().isEmpty());
        assertEquals(1, context.sources().size());
        assertEquals(bmgDoc, context.sources().getFirst().documentId(),
                "the anchored BMG §17 passage wins; unanchored vector hits stay excluded");
    }

    @Test
    void umzugQuestion_chunkLevelSemanticContract_keywordOnlyPassageWithoutOwnVectorIsRejected() {
        // AI-Ebenen-Vertrag: Eine lexikalisch verankerte Passage ohne EIGENEN
        // Vektor-Score (vec = 0 — der Chunk lag außerhalb des Vektor-Fensters)
        // bleibt unter der Vektor-Schwelle und wird verworfen, selbst wenn das
        // Dokument andere starke Vektor-Treffer hat. Genau DIESE Situation
        // verhindert die Kanal-Tiefe der Such-Ebene (die §17-Passage wird mit
        // ihrem Vektor-Score geliefert); ohne dieses Signal gibt es keinen
        // semantischen Beleg und der Fail-Closed bleibt ehrlich.
        List<SearchResult> page = List.of(
                result(bmgDoc, BMG_TITLE, BMG_17_EXCERPT, 0.5, 0.0),
                result(bmgDoc, BMG_TITLE, "im Inland gemeldet ist…", 0.0, 0.635),
                result(geoDoc, "Straßenbeleuchtung – Zuständigkeit und Meldung defekter Beleuchtung",
                        "Eine defekte Straßenlaterne können Bürger melden…", 0.25, 0.10));
        when(searchFacade.search(any())).thenReturn(
                new SearchResultPage(page, 0, 25, page.size(), 1, "HYBRID"));

        var context = service().retrieve(new AiRequest(QUESTION, null, null,
                new AiConversationContext(List.of(), "user", null, null, "umzug-3"), 15));

        assertTrue(context.sources().isEmpty(),
                "without a semantic signal on the passage itself the fail-closed stays honest");
    }
}
