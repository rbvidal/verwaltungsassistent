package verwaltungsassistent.web.service;

import reasoning.ai.api.AiFacade;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue 11 (Fallbriefing): Fingerabdruck-Vergleich für die Wiederverwendung
 * gespeicherter Briefings, Veraltet-Markierung bei substanziellen
 * Fall-Änderungen und die Ableitung des Briefings aus der persistierten
 * Fall-Analyse (kein zweiter Pipeline-Lauf).
 */
class CaseBriefingServiceTest {

    private WorkspaceService workspaceService;
    private JpaIncomingEmailRepository incomingEmailRepository;
    private AiFacade aiFacade;
    private CaseBriefingService service;

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity ws;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        incomingEmailRepository = mock(JpaIncomingEmailRepository.class);
        aiFacade = mock(AiFacade.class);
        service = new CaseBriefingService(aiFacade, workspaceService,
                incomingEmailRepository);
        ws = new WorkspaceEntity("WS-BRIEF-1", "Fall Wohngeld",
                "Antrag auf Wohngeld vom 02.08.2026", "CASE_FILE", "demo01@verwaltungsassistent.local");
        ws.setStatus(WorkspaceStatus.ACTIVE);
        ws.setPhase(WorkspacePhase.ANALYSIS);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(ws));
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of());
        when(workspaceService.getTimeline(caseId)).thenReturn(List.of());
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of());
    }

    private CaseBriefingService.Briefing briefingWith(String fingerprint) {
        return new CaseBriefingService.Briefing(
                "WS-BRIEF-1", "Fall Wohngeld", "31.08.2026, 09:49 Uhr",
                "Kurzfassung", "Sachverhalt", List.of("Erkenntnis"),
                List.of("Mietbescheinigung.pdf"), "Amt für Bürgerdienste",
                List.of(), List.of(), "Belegt · 1 Beleg(e) · 76% Konfidenz",
                true, "Aktiv", fingerprint, "Fall-Analyse");
    }

    @Test
    void isStale_unchangedInputs_reusesStoredBriefing() {
        CaseBriefingService.Briefing briefing = briefingWith(service.fingerprint(caseId));

        assertFalse(service.isStale(caseId, briefing),
                "identical case inputs must keep the stored briefing current");
    }

    @Test
    void isStale_newDocument_marksBriefingStale() {
        CaseBriefingService.Briefing briefing = briefingWith(service.fingerprint(caseId));
        UUID newDoc = UUID.randomUUID();
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(
                List.of(link(newDoc)));

        assertTrue(service.isStale(caseId, briefing),
                "a new document is a substantive change → briefing is stale");
    }

    @Test
    void isStale_newEmail_marksBriefingStale() {
        CaseBriefingService.Briefing briefing = briefingWith(service.fingerprint(caseId));
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(new IncomingEmailEntity(
                        UUID.randomUUID(), "Neue Anfrage", "Erika Schulze", "erika.schulze@example.de",
                        "Betreff: Neue Anfrage", Instant.now(),
                        IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local")));

        assertTrue(service.isStale(caseId, briefing),
                "a new email is a substantive change → briefing is stale");
    }

    @Test
    void isStale_notesOnlyChange_doesNotMarkStale() {
        // Notizen/Checklisten sind KEIN Teil des Fingerabdrucks — reine
        // Arbeitszustände erzeugen keine inhaltliche Änderung.
        CaseBriefingService.Briefing briefing = briefingWith(service.fingerprint(caseId));
        assertFalse(service.isStale(caseId, briefing),
                "navigation or note-taking must not invalidate the briefing");
    }

    @Test
    void isStale_briefingWithoutFingerprint_isStaleWhenInputsExist() {
        CaseBriefingService.Briefing legacy = briefingWith(null);

        assertTrue(service.isStale(caseId, legacy),
                "a legacy briefing without fingerprint must not be presented as current");
    }

    @Test
    void canReuseAnalysis_matchingDocumentBasis_isTrue() {
        UUID doc = UUID.randomUUID();
        WorkspaceAnalysisRunEntity run = run("COMPLETED", 1, Instant.now());
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(Map.of(
                "decisionAnswer", "KURZANTWORT\nAnalyse-Ergebnis"));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of(doc + "@v1"));
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link(doc)));

        assertTrue(service.canReuseAnalysis(caseId),
                "completed analysis with unchanged document basis is reusable");
    }

    @Test
    void canReuseAnalysis_newDocument_isFalse() {
        UUID doc = UUID.randomUUID();
        WorkspaceAnalysisRunEntity run = run("COMPLETED", 1, Instant.now());
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(Map.of(
                "decisionAnswer", "KURZANTWORT\nAnalyse-Ergebnis"));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of(doc + "@v1"));
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of());

        assertFalse(service.canReuseAnalysis(caseId),
                "new documents after the analysis must force a new run");
    }

    @Test
    void canReuseAnalysis_emailAfterRunCompletion_isFalse() {
        WorkspaceAnalysisRunEntity run = run("COMPLETED", 1, Instant.now());
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(Map.of(
                "decisionAnswer", "KURZANTWORT\nAnalyse-Ergebnis"));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of());
        // E-Mail kam NACH dem Analyse-Abschluss an
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(new IncomingEmailEntity(
                        UUID.randomUUID(), "Späte Anfrage", "Erika Schulze", "erika.schulze@example.de",
                        "Betreff: Späte Anfrage", Instant.now().plusSeconds(3600),
                        IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local")));

        assertFalse(service.canReuseAnalysis(caseId),
                "an email that arrived after the analysis must not be silently ignored");
    }

    @Test
    void canReuseAnalysis_runWithoutCompletedAt_usesCreationTimeAsAnchor() {
        // Beschädigter/Alt-Lauf ohne Abschlusszeitpunkt: der Erstellungszeitpunkt
        // ist der Zeitanker — eine danach eingegangene E-Mail blockiert die
        // Wiederverwendung weiterhin.
        WorkspaceAnalysisRunEntity run = new WorkspaceAnalysisRunEntity(
                UUID.randomUUID(), UUID.randomUUID(), 1, "COMPLETED",
                "demo01@verwaltungsassistent.local", Instant.now().minusSeconds(7200));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(Map.of(
                "decisionAnswer", "KURZANTWORT\nAnalyse-Ergebnis"));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of());
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(new IncomingEmailEntity(
                        UUID.randomUUID(), "Späte Anfrage", "Erika Schulze", "erika.schulze@example.de",
                        "Betreff: Späte Anfrage", Instant.now().minusSeconds(3600),
                        IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local")));

        assertFalse(service.canReuseAnalysis(caseId),
                "without a completion timestamp the creation time anchors the email check");
    }

    @Test
    void generateFromAnalysis_buildsBriefingFromPersistedAnalysis() {
        WorkspaceAnalysisRunEntity run = run("COMPLETED", 1, Instant.now());
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("decisionAnswer", "KURZANTWORT\nDie Ummeldung ist erfolgt.");
        result.put("grounded", true);
        result.put("confidenceScore", "76%");
        result.put("evidenceItems", List.of(
                Map.of("title", "Mietbescheinigung.pdf"),
                Map.of("title", "Mietbescheinigung.pdf")));
        result.put("primaryFindings", List.of(Map.of("label", "Fristgerecht angemeldet")));
        result.put("proceduralFindings", List.of(Map.of("label", "Vorgang dokumentieren")));
        result.put("coverageIssues", List.of("Begrenzte Abdeckung"));
        result.put("missingDocs", List.of("Einkommensnachweise"));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(result);

        CaseBriefingService.Briefing briefing = service.generateFromAnalysis(caseId, "demo01@verwaltungsassistent.local");

        assertEquals("Fall-Analyse", briefing.grundlage(),
                "the briefing states its basis honestly");
        assertEquals(1, briefing.erkenntnisse().size());
        assertTrue(briefing.erkenntnisse().contains("Fristgerecht angemeldet"));
        assertEquals(1, briefing.belege().size(),
                "duplicate evidence titles are merged");
        assertTrue(briefing.offenePunkte().contains("Begrenzte Abdeckung"));
        assertTrue(briefing.offenePunkte().stream().anyMatch(o -> o.contains("Einkommensnachweise")));
        assertTrue(briefing.quellenlage().contains("Belegt"));
        assertEquals(briefing.eingabeFingerprint(), service.fingerprint(caseId),
                "the stored fingerprint reflects the current case state");
        // Kein neuer Pipeline-Lauf: der AiFacade-Mock wurde nie aufgerufen.
        org.mockito.Mockito.verifyNoInteractions(aiFacade);
    }

    private static WorkspaceDocumentLinkEntity link(UUID docId) {
        WorkspaceDocumentLinkEntity l = new WorkspaceDocumentLinkEntity();
        l.setDocumentId(docId.toString());
        return l;
    }

    private static WorkspaceAnalysisRunEntity run(String status, int version, Instant completedAt) {
        WorkspaceAnalysisRunEntity run = new WorkspaceAnalysisRunEntity(
                UUID.randomUUID(), UUID.randomUUID(), version, status,
                "demo01@verwaltungsassistent.local", Instant.now().minusSeconds(3600));
        run.setCompletedAt(completedAt);
        return run;
    }

    // ── Fingerprint (Phase 1.5): planungsrelevante Änderungen invalidieren ──

    @Test
    void fingerprint_sameInputs_isStable() {
        assertEquals(service.fingerprint(caseId), service.fingerprint(caseId));
    }

    @Test
    void fingerprint_phaseChange_changesFingerprint() {
        String before = service.fingerprint(caseId);
        ws.setPhase(WorkspacePhase.REVIEW);
        assertFalse(before.equals(service.fingerprint(caseId)),
                "a phase change must invalidate planning state");
    }

    @Test
    void fingerprint_ingestionResolvedChange_changesFingerprint() {
        String before = service.fingerprint(caseId);
        ws.setPhaseData("{\"ingestionResolved\":true}");
        assertFalse(before.equals(service.fingerprint(caseId)),
                "resolving ingestion without documents must invalidate planning state");
    }

    @Test
    void fingerprint_caseCategoryChange_changesFingerprint() {
        String before = service.fingerprint(caseId);
        ws.setPhaseData("{\"caseCategory\":\"Wohngeld\"}");
        assertFalse(before.equals(service.fingerprint(caseId)),
                "a category change (impact factor) must invalidate planning state");
    }

    @Test
    void fingerprint_geoPriorityChange_changesFingerprint() {
        String before = service.fingerprint(caseId);
        ws.setPhaseData("{\"geoPriority\":\"hoch\"}");
        assertFalse(before.equals(service.fingerprint(caseId)),
                "a stated-urgency change must invalidate planning state");
    }

    @Test
    void fingerprint_newDocument_stillInvalidates() {
        String base = service.fingerprint(caseId);
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link(UUID.randomUUID())));
        assertFalse(base.equals(service.fingerprint(caseId)),
                "existing document invalidation must keep working");
    }

    @Test
    void fingerprint_newEmail_stillInvalidates() {
        String base = service.fingerprint(caseId);
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(new IncomingEmailEntity(
                        UUID.randomUUID(), "Neue Anfrage", "Erika Schulze", "erika.schulze@example.de",
                        "Betreff: Neue Anfrage", Instant.now(),
                        IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local")));
        assertFalse(base.equals(service.fingerprint(caseId)),
                "existing email invalidation must keep working");
    }

    @Test
    void fingerprint_newCompletedAnalysis_stillInvalidates() {
        String base = service.fingerprint(caseId);
        when(workspaceService.latestCompletedAnalysisRun(caseId))
                .thenReturn(Optional.of(run("COMPLETED", 1, Instant.now())));
        assertFalse(base.equals(service.fingerprint(caseId)),
                "existing analysis invalidation must keep working");
    }
}
