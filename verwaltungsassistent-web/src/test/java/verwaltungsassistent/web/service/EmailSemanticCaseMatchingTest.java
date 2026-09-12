package verwaltungsassistent.web.service;

import reasoning.common.model.DocumentFileType;
import reasoning.common.model.WorkspaceStatus;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.SearchResult;
import reasoning.search.model.SearchResultPage;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.7 — semantische (Vektor-)Vorschläge im E-Mail → Vorgang-Abgleich.
 * Deterministische Regeln bleiben vorrangig; die Vektor-Ähnlichkeit liefert
 * NUR konservative Vorschläge (keine Auto-Zuordnung) innerhalb der
 * Zugriffsgrenzen. SearchFacade ist gemockt — kein Qdrant, kein Ollama.
 */
@ExtendWith(MockitoExtension.class)
class EmailSemanticCaseMatchingTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private JpaIncomingEmailRepository incomingEmailRepository;
    @Mock
    private SearchFacade searchFacade;

    private EmailCaseMatchingService service;

    private final String actor = "demo01@verwaltungsassistent.local";
    private final String otherActor = "demo02@verwaltungsassistent.local";

    @BeforeEach
    void setUp() {
        service = new EmailCaseMatchingService(workspaceService, incomingEmailRepository, searchFacade,
                new verwaltungsassistent.web.config.SemanticMatchingProperties());
    }

    private static WorkspaceEntity ws(String id, String name, String owner) {
        WorkspaceEntity e = new WorkspaceEntity("WS-" + id.substring(0, 8).toUpperCase(),
                name, "Beschreibung eines Vorgangs", "CASE", owner);
        e.setId(id);
        e.setStatus(WorkspaceStatus.ACTIVE);
        return e;
    }

    private static WorkspaceDocumentLinkEntity link(UUID docId) {
        WorkspaceDocumentLinkEntity l = new WorkspaceDocumentLinkEntity();
        l.setDocumentId(docId.toString());
        return l;
    }

    private static SearchResult hit(UUID docId, String text, double vectorScore) {
        return new SearchResult(
                new ChunkReference(UUID.randomUUID(), docId, 1, "Titel",
                        new ChunkPosition(1, 0, 0, 0, 0), DocumentFileType.PDF),
                text, vectorScore, vectorScore, "qdrant", null,
                0.0, vectorScore, 0.0, "GENERAL", "SEMANTIC");
    }

    private static void linkDocs(WorkspaceService wsService, String caseId, UUID... docIds) {
        when(wsService.getWorkspaceDocuments(caseId)).thenReturn(
                java.util.Arrays.stream(docIds).map(EmailSemanticCaseMatchingTest::link).toList());
    }

    private void stubSearch(UUID... docIdsToReturn) {
        stubSearchScore(0.87, docIdsToReturn);
    }

    private void stubSearchScore(double score, UUID... docIdsToReturn) {
        SearchResult[] results = java.util.Arrays.stream(docIdsToReturn)
                .map(id -> hit(id, "Ummeldung nach Umzug – neue Wohnung anmelden, Fristen beachten.", score))
                .toArray(SearchResult[]::new);
        when(searchFacade.search(any())).thenReturn(
                new SearchResultPage(List.of(results), 0, 30, results.length, 1, "SEMANTIC"));
    }

    private static String ummeldungEmail() {
        return "Betreff: Frage zur Ummeldung\n\n"
                + "Ich habe noch eine Frage zu meiner Ummeldung nach dem Umzug. "
                + "Die Unterlagen hatte ich letzte Woche geschickt.\n\n"
                + "Mit freundlichen Grüßen\nChristoph Lang";
    }

    @Test
    void strongSemanticCandidate_isSuggested_whenNoDeterministicSignal() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", null);
        UUID doc = UUID.randomUUID();
        when(workspaceService.findAll()).thenReturn(List.of(umzug));
        linkDocs(workspaceService, umzug.getId(), doc);
        stubSearch(doc);

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertEquals(1, matches.size());
        assertEquals("SEMANTIC", matches.get(0).matchType());
        assertTrue(matches.get(0).reason().contains("Semantische Ähnlichkeit"),
                matches.get(0).reason());
        assertTrue(matches.get(0).reason().contains("0,87"), matches.get(0).reason());
    }

    @Test
    void weakSemanticSimilarity_producesNoSuggestion() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", null);
        UUID doc = UUID.randomUUID();
        when(workspaceService.findAll()).thenReturn(List.of(umzug));
        linkDocs(workspaceService, umzug.getId(), doc);
        // Unterhalb der konservativen Schwelle (0.70): kein Vorschlag.
        when(searchFacade.search(any())).thenReturn(new SearchResultPage(
                List.of(hit(doc, "Hinweistext", 0.55)), 0, 30, 1, 1, "SEMANTIC"));

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertTrue(matches.isEmpty(), "schwache Ähnlichkeit darf keinen Vorschlag erzeugen");
    }

    @Test
    void deterministicIdentity_beatsSemanticSuggestion() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", null);
        UUID doc = UUID.randomUUID();
        // Zweiter Vorgang: Absender-Adresse ist dort bereits bekannt (EMAIL_IDENTITY).
        WorkspaceEntity schulze = ws(UUID.randomUUID().toString(), "Gewerbeanmeldung", otherActor);
        when(workspaceService.findAll()).thenReturn(List.of(umzug, schulze));
        linkDocs(workspaceService, umzug.getId(), doc);
        stubSearch(doc);
        IncomingEmailEntity prior = new IncomingEmailEntity(UUID.randomUUID(),
                "Gewerbeanmeldung", "Christoph Lang", "christoph.lang@example.de",
                "Betreff: Gewerbeanmeldung", Instant.now(),
                IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local");
        // Service fragt beide Vorgänge ab — unter Strict-Stubs darf nur der
        // bekannte Vorgang die E-Mail liefern, alle anderen leer (sonst
        // Argument-Mismatch-Exception, die der Service abfängt).
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(any()))
                .thenAnswer(inv -> schulze.getId().equals(inv.getArgument(0).toString())
                        ? List.of(prior) : List.of());

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), "christoph.lang@example.de", actor, true, Set.of());

        assertFalse(matches.isEmpty());
        assertEquals("EMAIL_IDENTITY", matches.get(0).matchType(),
                "bestätigte Identität steht immer vor der semantischen Ähnlichkeit");
    }

    @Test
    void lexicalSimilarHit_isUpgradedToSemanticWhenVectorIsStrong() {
        // Fallname überlappt lexikalisch (Ummeldung + Umzug) — der Treffer
        // entsteht zunächst als SIMILAR und wird bei belastbarer Vektor-
        // Ähnlichkeit durch den ehrlichen SEMANTIC-Treffer ersetzt.
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung nach Umzug – Familie Lang", null);
        UUID doc = UUID.randomUUID();
        when(workspaceService.findAll()).thenReturn(List.of(umzug));
        linkDocs(workspaceService, umzug.getId(), doc);
        stubSearch(doc);

        // E-Mail enthält auch lexikalisch überlappende Begriffe ("ummeldung").
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertEquals(1, matches.size());
        assertEquals("SEMANTIC", matches.get(0).matchType(),
                "belastbare Vektor-Ähnlichkeit ersetzt die lexikalische Wortgleichheit");
        assertFalse(matches.get(0).reason().contains("Gemeinsame Begriffe"),
                matches.get(0).reason());
    }

    @Test
    void semanticResultsOfInvisibleCases_areNeverSuggested() {
        WorkspaceEntity visiblePool = ws(UUID.randomUUID().toString(), "Wohngeld – Familie Lang", null);
        WorkspaceEntity hiddenOther = ws(UUID.randomUUID().toString(), "Reisepass – Familie Weber", otherActor);
        UUID poolDoc = UUID.randomUUID();
        UUID hiddenDoc = UUID.randomUUID();
        // Mitarbeiterin sieht: eigene Vorgänge + Pool (owner null). Der
        // fremde Vorgang (demo02) ist NICHT sichtbar.
        when(workspaceService.findAll()).thenReturn(List.of(visiblePool, hiddenOther));
        linkDocs(workspaceService, visiblePool.getId(), poolDoc);
        // Suchantwort enthält auch einen Treffer des UNSICHTBAREN Vorgangs.
        when(searchFacade.search(any())).thenReturn(new SearchResultPage(List.of(
                hit(poolDoc, "Wohngeldantrag – Unterlagen unvollständig", 0.85),
                hit(hiddenDoc, "Reisepass für Minderjährige – Unterlagen", 0.92)),
                0, 30, 2, 1, "SEMANTIC"));

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertTrue(matches.stream().noneMatch(m -> m.id().equals(hiddenOther.getId())),
                "unsichtbare Vorgänge dürfen nicht als Vorschlag erscheinen");
        assertEquals(1, matches.size());
        assertEquals(visiblePool.getId(), matches.get(0).id());
    }

    /** Phase 2C.12 — kalibrierte Grenzen: 0.74 → kein Vorschlag, 0.75 →
     *  mittel, 0.81 → mittel, 0.82 → hoch (Schwellen KEINE Wahrscheinlichkeiten). */
    @Test
    void calibratedFloors_boundaryBehavior() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", null);
        UUID doc = UUID.randomUUID();
        when(workspaceService.findAll()).thenReturn(List.of(umzug));
        linkDocs(workspaceService, umzug.getId(), doc);

        stubSearchScore(0.74, doc);
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> below =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());
        assertTrue(below.isEmpty(), "0.74 liegt unter der kalibrierten Schwelle (0.75)");

        stubSearchScore(0.75, doc);
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> atFloor =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());
        assertEquals(1, atFloor.size());
        assertEquals("SEMANTIC", atFloor.get(0).matchType());
        assertTrue(atFloor.get(0).reason().contains("mittel"), atFloor.get(0).reason());

        stubSearchScore(0.81, doc);
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> mid =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());
        assertEquals(1, mid.size());
        assertTrue(mid.get(0).reason().contains("mittel"),
                "0.81 bleibt mittel (hoch erst ab 0.82): " + mid.get(0).reason());

        stubSearchScore(0.82, doc);
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> high =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());
        assertEquals(1, high.size());
        assertTrue(high.get(0).reason().contains("hoch"), high.get(0).reason());
    }

    /** Phase 2C.12 — Aggregation: mehrere Chunks EINES Vorgangs ergeben als
     *  Fall-Score das BESTE (Maximum) der Chunk-Ähnlichkeiten (unverändert). */
    @Test
    void caseScore_usesBestChunkOfTheCase() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", null);
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        when(workspaceService.findAll()).thenReturn(List.of(umzug));
        linkDocs(workspaceService, umzug.getId(), docA, docB);
        when(searchFacade.search(any())).thenReturn(new SearchResultPage(
                List.of(hit(docA, "Passage eins", 0.76),
                        hit(docB, "Passage zwei – sehr passend", 0.91)),
                0, 30, 2, 1, "SEMANTIC"));

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertEquals(1, matches.size());
        assertEquals("SEMANTIC", matches.get(0).matchType());
        assertTrue(matches.get(0).reason().contains("0,91"),
                "Fall-Score = Maximum der Chunk-Scores (0.91), nicht der Durchschnitt: "
                        + matches.get(0).reason());
    }

    @Test
    void searchFailure_degradesGracefullyToDeterministicResults() {
        WorkspaceEntity umzug = ws(UUID.randomUUID().toString(), "Ummeldung Wohnsitz – Familie Müller", actor);
        UUID doc = UUID.randomUUID();
        when(workspaceService.findByOwner(actor)).thenReturn(List.of(umzug));
        when(workspaceService.findAll()).thenReturn(List.of());
        linkDocs(workspaceService, umzug.getId(), doc);
        when(searchFacade.search(any()))
                .thenThrow(new RuntimeException("Qdrant nicht erreichbar"));

        List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                service.matchCases(ummeldungEmail(), null, actor, false, Set.of());

        assertTrue(matches.isEmpty(), "Suchfehler darf den Abgleich nicht brechen");
        // Lexikalischer Pfad bleibt zusätzlich intakt: deterministischer Treffer
        // über einen eigenen Vorgang ohne Vektor-Infrastruktur.
        WorkspaceEntity wohngeld = ws(UUID.randomUUID().toString(), "Wohngeld Lang", actor);
        when(workspaceService.findByOwner(actor)).thenReturn(List.of(wohngeld));
        when(workspaceService.getWorkspaceDocuments(wohngeld.getId())).thenReturn(List.of());
        List<verwaltungsassistent.web.controller.EmailController.CaseRef> lexical =
                service.matchCases("Betreff: Wohngeld Lang Antrag prüfen\n\nBitte prüfen Sie den Antrag.",
                        null, actor, false, Set.of());
        assertFalse(lexical.isEmpty(), "lexikalischer Abgleich funktioniert ohne Vektor-Infrastruktur");
    }
}
