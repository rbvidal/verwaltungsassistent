package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.workspace.api.TimelineEventDto;
import reasoning.workspace.api.WorkspaceDocumentDto;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceDocumentLinkRepository;
import reasoning.workspace.model.TimelineEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CaseDetailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private reasoning.document.application.DocumentService documentService;

    @MockBean
    private verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;

    @Autowired
    private verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository effortObservationRepository;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity testEntity;
    private WorkspaceDto testDto;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        testEntity = new WorkspaceEntity("WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL", testUser.email());
        testEntity.setStatus(WorkspaceStatus.ACTIVE);
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        // Fallalter erzeugt einen echten Prioritäts-Grund ("Fall liegt seit … vor"),
        // damit die Planungs-Meldung ihre Gründe-Zeile anzeigt.
        testEntity.setCreatedAt(Instant.now().minus(java.time.Duration.ofDays(5)));

        testDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.ANALYSIS,
                testUser.id().toString(), Map.of(),
                List.of(), // empty documents
                List.of(new TimelineEventDto("evt-1", caseId, LocalDate.now(),
                        "Dokumentanalyse gestartet", "Automatische Analyse aller angehängten Dokumente",
                        TimelineEventType.EVENT, "doc-1", 0.95, true)),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());

        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.findById("nonexistent")).thenReturn(Optional.empty());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(testDto);
        when(workspaceService.getCompletedSteps(anyString())).thenReturn(List.of());
        when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Full page ---

    @Test
    void caseDetail_authenticated_returnsFullPage() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail"))
                .andExpect(model().attributeExists("case"))
                .andExpect(model().attributeExists("detailTabs"))
                .andExpect(model().attribute("activeSection", "cases"));
    }

    @Test
    void caseDetail_displaysCaseName() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Testfall Bauvorhaben")))
                .andExpect(content().string(containsString("WS-TEST")));
    }

    /**
     * Phase 1.5 Arbeitsplanung: die Fallseite zeigt die nicht-modale
     * Planungs-Meldung mit erklärbarer Priorität ("Priorität: X · Platz N in
     * Ihrer Arbeitsliste") — bewusst OHNE "heute"-Tagesplanungs-Behauptung.
     * Abgeschlossene Fälle erhalten keine Planungs-Meldung.
     */
    @Test
    void caseDetail_showsPlanningAlert_withExplainedPriority() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priorität: ")))
                .andExpect(content().string(not(containsString("Priorität heute"))))
                .andExpect(content().string(containsString("Gründe:")));
    }

    @Test
    void caseDetail_closedCase_showsNoPlanningAlert() throws Exception {
        testEntity.setStatus(WorkspaceStatus.CLOSED);
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Priorität: "))))
                .andExpect(content().string(not(containsString("Gründe:"))));
    }

    /**
     * Abgeschlossener Vorgang (Status GESCHLOSSEN, Phase ABSCHLUSS): die
     * Fallseite kommuniziert "Vorgang abgeschlossen" — keine
     * "Nächster Schritt"-Sprache, keine "Entscheidung vorbereiten"-Aktion als
     * offene Arbeit; nur die EXPLIZITE Wiederaufnahme ist Aktion.
     */
    @Test
    void caseDetail_closedCase_completePhase_showsAbgeschlossenSemantics() throws Exception {
        testEntity.setStatus(WorkspaceStatus.CLOSED);
        testEntity.setPhase(WorkspacePhase.COMPLETE);
        testDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.CLOSED, WorkspacePhase.COMPLETE,
                testUser.id().toString(), Map.of(),
                List.of(),
                List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        // Mockito erfasst den Rückgabewert beim Stubben — nach dem Austausch
        // des DTO muss der Stub neu gesetzt werden.
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(testDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vorgang abgeschlossen")))
                .andExpect(content().string(not(containsString("Entscheidung vorbereiten"))))
                .andExpect(content().string(not(containsString("Nächster Schritt"))))
                .andExpect(content().string(containsString("Vorgang wiederaufnehmen")));
    }

    /**
     * Phase 2D.7: Abgeschlossener Vorgang ist ein Endzustand — keine
     * Phasen-Navigation ("← Überprüfung") auf der Seite, und der Phase-Endpoint
     * weist die Änderung serverseitig ab (erst die EXPLIZITE Wiederaufnahme
     * öffnet den Vorgang wieder).
     */
    @Test
    void caseDetail_closedCase_offersNoPhaseNavigation_andServerRefusesPhaseChange() throws Exception {
        testEntity.setStatus(WorkspaceStatus.CLOSED);
        testEntity.setPhase(WorkspacePhase.COMPLETE);
        testDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.CLOSED, WorkspacePhase.COMPLETE,
                testUser.id().toString(), Map.of(),
                List.of(),
                List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(testDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("← Überprüfung"))));

        mockMvc.perform(post("/cases/" + caseId + "/phase")
                        .param("direction", "previous").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nur nach einer Wiederaufnahme")))
                .andExpect(content().string(containsString("Vorgang abgeschlossen")));
    }

    /**
     * Phase 2D.8: Ist die Entscheidung der Sachbearbeitung bereits dokumentiert,
     * lautet die Fallseiten-Aktion "Entscheidung ansehen" statt der
     * widersprüchlichen "vorbereiten"-Sprache (gleiche Wortwahl wie im
     * Abschluss-Abschnitt).
     */
    @Test
    void caseDetail_openCase_withDocumentedDecision_showsAnsehenInsteadOfVorbereiten() throws Exception {
        testEntity.setPhase(WorkspacePhase.COMPLETE);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("decision", java.util.Map.of(
                "confirmedBy", "user@example.com",
                "confirmedByName", "Test User",
                "confirmedAt", "2026-09-02T10:00:00Z",
                "analysisVersion", 1));
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        testDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.COMPLETE,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(testDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entscheidung ansehen")))
                .andExpect(content().string(not(containsString("Entscheidung vorbereiten"))));
    }

    /**
     * Post-2B-Review §5: Ohne bestätigte fehlende Unterlagen (keine Analyse)
     * zeigt der Wartehinweis die thematisch typischen Unterlagen ehrlich als
     * "Noch zu prüfen – typischerweise erforderlich" — niemals als
     * festgestelltes Fehlen ("Fehlende Unterlagen").
     */
    @Test
    void caseDetail_waitingForDocuments_typicalDocsAreMarkedAsToCheck() throws Exception {
        testEntity.setPhaseData("{\"workState\":{\"state\":\"PAUSED\",\"employee\":\"user@example.com\","
                + "\"since\":\"" + Instant.now().minus(java.time.Duration.ofHours(2)) + "\"},"
                + "\"waitingOn\":{\"type\":\"DOCUMENTS\",\"since\":\""
                + Instant.now().minus(java.time.Duration.ofHours(2)) + "\","
                + "\"note\":\"Einkommensnachweise erwartet\"}}");

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wartet seit")))
                .andExpect(content().string(containsString("Noch zu prüfen – typischerweise erforderlich")))
                .andExpect(content().string(containsString("Dokumente ansehen")))
                .andExpect(content().string(not(containsString("Fehlende Unterlagen"))));
    }

    /**
     * Post-2B-Review §5: Liegen aus der Analyse BESTÄTIGTE fehlende
     * Unterlagen vor, werden sie als "Fehlende Unterlagen" gezeigt — die
     * typischen Unterlagen treten dann nicht zusätzlich auf.
     */
    @Test
    void caseDetail_waitingForDocuments_confirmedMissingDocsAreShownAsMissing() throws Exception {
        testEntity.setPhaseData("{\"workState\":{\"state\":\"PAUSED\",\"employee\":\"user@example.com\","
                + "\"since\":\"" + Instant.now().minus(java.time.Duration.ofHours(2)) + "\"},"
                + "\"waitingOn\":{\"type\":\"DOCUMENTS\",\"since\":\""
                + Instant.now().minus(java.time.Duration.ofHours(2)) + "\"}}");
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                org.mockito.Mockito.mock(reasoning.workspace.api.WorkspaceAnalysisRunEntity.class);
        // factsFor läuft über die ENTITY-id (random UUID), die Header-Prüfung
        // über die Pfad-id — beide müssen denselben Lauf sehen.
        when(workspaceService.findById(anyString())).thenReturn(Optional.of(testEntity));
        when(workspaceService.latestCompletedAnalysisRun(anyString())).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run))
                .thenReturn(Map.of("missingDocs", List.of("Mietvertrag / Mietbescheinigung")));

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fehlende Unterlagen")))
                .andExpect(content().string(containsString("Mietvertrag / Mietbescheinigung")))
                .andExpect(content().string(not(containsString("Noch zu prüfen"))));
    }

    /**
     * Phase 2A: "Fall abschließen" mit gemessenem workState erzeugt GENAU EINE
     * empirische Beobachtung der aktiven Bearbeitungszeit (akkumulierte +
     * letztes Intervall); ein zweiter Abschluss erzeugt keine zweite.
     */
    @Test
    void closeCase_withActiveWork_createsExactlyOneObservation() throws Exception {
        java.time.Instant since = java.time.Instant.now()
                .minus(java.time.Duration.ofMinutes(25)).minusSeconds(30);
        // 2D.16: Abschluss erfordert eine dokumentierte Entscheidung der
        // Sachbearbeitung (Abschluss-Invariante) — die Fixture-Entscheidung
        // macht den Abschluss gültig.
        testEntity.setPhaseData("{\"workState\":{\"state\":\"ACTIVE\",\"employee\":\"user@example.com\","
                + "\"since\":\"" + since + "\",\"accumulatedMinutes\":10},"
                + "\"decision\":{\"confirmedBy\":\"user@example.com\",\"confirmedByName\":\"Test User\","
                + "\"confirmedAt\":\"2026-09-05T08:00:00Z\",\"analysisVersion\":1}}");

        mockMvc.perform(post("/cases/" + caseId + "/close").with(csrf()))
                .andExpect(status().is3xxRedirection());
        var observation = effortObservationRepository.findByCaseId(UUID.fromString(caseId));
        assertTrue(observation.isPresent(), "completion records the active-time observation");
        assertEquals(35, observation.get().getObservedActiveMinutes(),
                "10 accumulated + 25 final active interval");

        // Zweiter Abschluss: Status ist bereits CLOSED → keine zweite Beobachtung.
        mockMvc.perform(post("/cases/" + caseId + "/close").with(csrf()))
                .andExpect(status().is3xxRedirection());
        var again = effortObservationRepository.findByCaseId(UUID.fromString(caseId));
        assertEquals(35, again.orElseThrow().getObservedActiveMinutes(),
                "duplicate completion cannot create a duplicate observation");

        effortObservationRepository.deleteByCaseId(UUID.fromString(caseId));
    }

    /**
     * Phase 2A: Fälle ohne workState (z. B. gesetzte Demo-Fälle) erzeugen beim
     * Abschluss bewusst KEINE Null-Beobachtung.
     */
    @Test
    void closeCase_withoutActiveWork_createsNoObservation() throws Exception {
        // 2D.16: Abschluss-Invariante — ohne dokumentierte Entscheidung wird
        // der Abschluss abgelehnt. Hier liegt eine Entscheidung vor, damit die
        // Beobachtungs-Semantik (kein workState → keine Beobachtung) testbar
        // bleibt.
        testEntity.setPhaseData("{\"decision\":{\"confirmedBy\":\"user@example.com\","
                + "\"confirmedByName\":\"Test User\",\"confirmedAt\":\"2026-09-05T08:00:00Z\","
                + "\"analysisVersion\":1}}");
        mockMvc.perform(post("/cases/" + caseId + "/close").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertTrue(effortObservationRepository.findByCaseId(UUID.fromString(caseId)).isEmpty(),
                "no active work → no observation");

    }

    /** 2D.16: Ein Abschluss ohne dokumentierte Entscheidung ist serverseitig
     *  gesperrt — der Vorgang bleibt offen, keine Beobachtung, keine
     *  Statusänderung (die Abschluss-Invariante wird nicht geschwächt). */
    @Test
    void closeCase_withoutDocumentedDecision_isRejected() throws Exception {
        testEntity.setPhaseData("{}");

        mockMvc.perform(post("/cases/" + caseId + "/close").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertEquals(WorkspaceStatus.ACTIVE, testEntity.getStatus(),
                "closing without a documented decision must not change the status");
        assertTrue(effortObservationRepository.findByCaseId(UUID.fromString(caseId)).isEmpty(),
                "no observation may be recorded for a rejected close");
    }

    @Test
    void caseDetail_hasPhaseProgress() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"progressbar\"")))
                .andExpect(content().string(containsString("Einrichtung")))
                .andExpect(content().string(containsString("Analyse")));
    }

    @Test
    void caseDetail_hasStatusBadge() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("badge--success")))
                .andExpect(content().string(containsString("Aktiv")));
    }

    @Test
    void caseDetail_hasTabBar() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"tablist\"")))
                .andExpect(content().string(containsString("Übersicht")))
                .andExpect(content().string(containsString("Dokumente")))
                .andExpect(content().string(containsString("Timeline")))
                .andExpect(content().string(containsString("Checkliste")))
                .andExpect(content().string(containsString("Notizen")));
    }

    @Test
    void caseDetail_hasPhaseAdvanceButton() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Überprüfung →")));
    }

    @Test
    void caseDetail_hasBackButton() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("← Ingestion")));
    }

    @Test
    void caseDetail_showsOverviewStats() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dokumente")))
                .andExpect(content().string(containsString("Ereignisse")));
    }

    @Test
    void caseDetail_showsTimelineInOverview() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dokumentanalyse gestartet")));
    }

    @Test
    void caseDetail_notFound_returns404() throws Exception {
        mockMvc.perform(get("/cases/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void caseDetail_unauthenticated_redirectsToLogin() throws Exception {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    // --- HTMX fragments ---

    @Test
    void caseDetail_htmxRequest_returnsOverviewFragment() throws Exception {
        // Re-setup with data that validates HTMX path check
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(testDto);
        when(workspaceService.getCompletedSteps(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/cases/" + caseId)
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: overview"));
    }

    @Test
    void documentsTab_returnsFragment() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/documents"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: documentsTab"));
    }

    @Test
    void documentsTab_empty_showsAppropriateMessage() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/documents"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: documentsTab"))
                .andExpect(content().string(containsString("Keine Dokumente")));
    }

    @Test
    void timelineTab_returnsFragment() throws Exception {
        when(workspaceService.getTimeline(caseId)).thenReturn(List.of(
                new reasoning.workspace.api.TimelineEventEntity(
                        UUID.randomUUID().toString(), caseId, LocalDate.now(), "Dokumentanalyse gestartet",
                        "Automatische Analyse aller angehängten Dokumente",
                        TimelineEventType.EVENT, null, 0.95, true)));

        mockMvc.perform(get("/cases/" + caseId + "/timeline")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: timelineTab"))
                .andExpect(content().string(containsString("Dokumentanalyse gestartet")));
    }

    @Test
    void notesTab_returnsFragment() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/notes")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: notesTab"));
    }

    @Test
    void checklistTab_returnsFragment() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/checklist")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: checklistTab"));
    }

    // --- Phase advance ---

    @Test
    void advancePhase_returnsPhaseState() throws Exception {
        WorkspaceEntity advanced = new WorkspaceEntity("WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall", "GENERAL", testUser.id().toString());
        advanced.setStatus(WorkspaceStatus.ACTIVE);
        advanced.setPhase(WorkspacePhase.REVIEW);

        WorkspaceDto advancedDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben", "Ein Testfall", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.REVIEW,
                testUser.id().toString(), Map.of(), List.of(), List.of(),
                Instant.now(), Instant.now());

        when(workspaceService.advancePhase(caseId)).thenReturn(advanced);
        when(workspaceService.toDto(advanced)).thenReturn(advancedDto);
        when(workspaceService.getCompletedSteps(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/cases/" + caseId + "/phase")
                        .param("direction", "advance")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: phaseState"))
                .andExpect(content().string(containsString("Überprüfung")));
    }

    @Test
    void previousPhase_returnsPhaseState() throws Exception {
        WorkspaceEntity previous = new WorkspaceEntity("WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall", "GENERAL", testUser.id().toString());
        previous.setStatus(WorkspaceStatus.ACTIVE);
        previous.setPhase(WorkspacePhase.INGESTION);

        WorkspaceDto prevDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben", "Ein Testfall", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION,
                testUser.id().toString(), Map.of(), List.of(), List.of(),
                Instant.now(), Instant.now());

        when(workspaceService.previousPhase(caseId)).thenReturn(previous);
        when(workspaceService.toDto(previous)).thenReturn(prevDto);
        when(workspaceService.getCompletedSteps(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/cases/" + caseId + "/phase")
                        .param("direction", "previous")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail-fragments :: phaseState"))
                .andExpect(content().string(containsString("Ingestion")));
    }

    // --- Empty states ---

    @Test
    void caseDetail_noDocuments_showsEmptyState() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Dokumente")));
    }

    @Test
    void documentsTab_empty_showsEmptyState() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Dokumente")));
    }

    // --- Phase content (was wurde gemacht / Ergebnis / nächster Schritt) ---

    @Test
    void caseDetail_setupPhase_showsSetupContent() throws Exception {
        WorkspaceDto setupDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.SETUP,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(setupDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Einrichtung abgeschlossen")))
                .andExpect(content().string(containsString("Fall umbenennen")))
                .andExpect(content().string(containsString("Noch keine Dokumente zugeordnet")))
                .andExpect(content().string(containsString("Unterlagen und vorhandene Dokumente verarbeiten – Phase Ingestion")));
    }

    @Test
    void caseDetail_analysisPhase_showsAnalysisContent() throws Exception {
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Erkannte Ereignisse")))
                .andExpect(content().string(containsString("Noch nicht durchgeführt")))
                .andExpect(content().string(containsString("Analyse starten")));
    }

    @Test
    void caseDetail_reviewPhase_showsReviewChecklistContent() throws Exception {
        testEntity.setPhase(WorkspacePhase.REVIEW);
        WorkspaceDto reviewDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.REVIEW,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(reviewDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Überprüfung läuft")))
                .andExpect(content().string(containsString("Angaben zur Person geprüft")))
                .andExpect(content().string(containsString("Relevante Rechtsgrundlagen geprüft")))
                .andExpect(content().string(containsString("0 von 4 abgeschlossen")));
    }

    /** Issue 8: alle Dokumente fehlgeschlagen → ehrlicher Fehlerzustand mit Erholungspfad. */
    @Test
    void ingestionAllFailed_showsErrorStateWithRecoveryAndResolveAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.INGESTION);
        WorkspaceDocumentDto doc = new WorkspaceDocumentDto(
                "link-1", caseId, UUID.randomUUID().toString(), "Foto muell.jpg",
                reasoning.common.model.DocumentCategory.OTHER,
                "OTHER", Map.of(), Instant.now());
        WorkspaceDto ingestionDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION,
                testUser.id().toString(), Map.of(),
                List.of(doc), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(ingestionDto);
        Document failedDoc = new Document(
                UUID.randomUUID(), "tenant",
                new DocumentMetadata("Foto muell.jpg", reasoning.common.model.DocumentFileType.JPG,
                        "OTHER", Set.of(), "INTERNAL"),
                DocumentStatus.FAILED, 1, "system", "system", Instant.now(), Instant.now(), List.of());
        when(documentService.getDocument(any(UUID.class), anyString())).thenReturn(failedDoc);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dokumentverarbeitung fehlgeschlagen")))
                .andExpect(content().string(containsString("Ohne Dokumente fortfahren")))
                .andExpect(content().string(containsString("Fehler bei: Foto muell.jpg")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">Analyse starten</button>"))));
    }

    /** Issue 3b: In der Überprüfungs-Phase wird der Analyse-Zustand ehrlich gezeigt. */
    @Test
    void reviewPhase_withUsableAnalysis_offersAnalysisResult() throws Exception {
        testEntity.setPhase(WorkspacePhase.REVIEW);
        testEntity.setPhaseData("{\"analysis\":{\"status\":\"COMPLETED\"},\"checklist\":[]}");
        WorkspaceDto reviewDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.REVIEW,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(reviewDto);
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 1, "COMPLETED",
                        "user@example.com", Instant.now());
        run.setResultJson("{}");
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(new java.util.LinkedHashMap<>());

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("KI-Analyse")))
                .andExpect(content().string(containsString("Analyseergebnis öffnen")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Zum Fall"))));
    }

    // ── Einzelne "Analyse starten"-Aktion nach abgeschlossener Ingestion ──

    @Test
    void ingestionComplete_exposesSingleAnalyseStartAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.INGESTION);
        WorkspaceDocumentDto doc = new WorkspaceDocumentDto(
                "link-1", caseId, UUID.randomUUID().toString(), "BauO Bln",
                reasoning.common.model.DocumentCategory.POLICY_DOCUMENT,
                "LEGAL_REGULATION", Map.of(), Instant.now());
        WorkspaceDto ingestionDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION,
                testUser.id().toString(), Map.of(),
                List.of(doc), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(ingestionDto);
        Document readyDoc = new Document(
                UUID.randomUUID(), "tenant",
                new DocumentMetadata("BauO Bln", reasoning.common.model.DocumentFileType.PDF,
                        "POLICY_DOCUMENT", Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "system", "system", Instant.now(), Instant.now(), List.of());
        when(documentService.getDocument(any(UUID.class), anyString())).thenReturn(readyDoc);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                // Der Phasen-Balken IST der "Analyse starten"-Button (advance=true);
                // GENAU EIN solcher Button — der Ingestion-Abschnitt trägt keinen
                // zweiten Start (die Zählung prüft ingestionComplete_exposesExactlyOne…).
                .andExpect(content().string(containsString(">Analyse starten</button>")))
                .andExpect(content().string(containsString("hx-post=\"/cases/" + caseId + "/decision/analyze?redirect=true&amp;advance=true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Ohne Dokumente fortfahren"))));
    }

    /** Issue 1: GENAU EINE "Analyse starten"-Aktion bei offenem Gate. */
    @Test
    void ingestionComplete_exposesExactlyOneAnalyseStartAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.INGESTION);
        WorkspaceDocumentDto doc = new WorkspaceDocumentDto(
                "link-1", caseId, UUID.randomUUID().toString(), "BauO Bln",
                reasoning.common.model.DocumentCategory.POLICY_DOCUMENT,
                "POLICY_DOCUMENT", Map.of(), Instant.now());
        WorkspaceDto ingestionDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION,
                testUser.id().toString(), Map.of(),
                List.of(doc), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(ingestionDto);
        Document readyDoc = new Document(
                UUID.randomUUID(), "tenant",
                new DocumentMetadata("BauO Bln", reasoning.common.model.DocumentFileType.PDF,
                        "POLICY_DOCUMENT", Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "system", "system", Instant.now(), Instant.now(), List.of());
        when(documentService.getDocument(any(UUID.class), anyString())).thenReturn(readyDoc);

        MvcResult result = mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertEquals(1, countOccurrences(html, ">Analyse starten</button>"),
                "exactly one visible Analyse starten action when the gate is open");
    }

    /** Issue 1: Abgeschlossene Analyse → KEIN Start-Button (kein versehentlicher Doppelstart). */
    @Test
    void completedAnalysis_showsNoStartAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        testEntity.setPhaseData("{\"analysis\":{\"status\":\"COMPLETED\",\"sourceCount\":2}}");
        WorkspaceDto analysisDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.ANALYSIS,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(analysisDto);
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 1, "COMPLETED",
                        "user@example.com", Instant.now());
        run.setResultJson("{\"confidenceScore\":\"76%\"}");
        when(workspaceService.latestCompletedAnalysisRun(caseId))
                .thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(
                new java.util.LinkedHashMap<>(Map.of("confidenceScore", "76%")));

        MvcResult result = mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertEquals(0, countOccurrences(html, ">Analyse starten</button>"),
                "a completed analysis offers no start action (no duplicate launch)");
        assertTrue(html.contains("Analyse abgeschlossen"));
        // Issue 6: explizite PDF-Aktionen — "PDF öffnen" (inline, neuer Tab)
        // und "PDF herunterladen" (Attachment) statt einer einzigen
        // "Entscheidungsvorlage als PDF"-Erzeugung.
        assertTrue(html.contains("PDF öffnen"),
                "completed analysis offers the inline PDF action explicitly");
        assertTrue(html.contains("PDF herunterladen"),
                "completed analysis offers the download action explicitly");
        assertTrue(html.contains("/decision/export-pdf?inline=true"),
                "the open action targets the inline (viewer) endpoint");
        assertTrue(html.contains("/decision/export-pdf\""),
                "the download action targets the plain attachment endpoint (no inline param)");
        assertFalse(html.contains("Entscheidungsvorlage als PDF"),
                "the old single create-and-generate action is gone");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void ingestionWithoutDocuments_offersResolveInsteadOfAnalyseStart() throws Exception {
        testEntity.setPhase(WorkspacePhase.INGESTION);
        WorkspaceDto ingestionDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(ingestionDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ohne Dokumente fortfahren")))
                // Gate nicht offen → der Phasen-Balken bleibt Navigation
                // ("Analyse →"), KEIN "Analyse starten"-Button.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">Analyse starten</button>"))))
                .andExpect(content().string(containsString("Analyse →")));
    }

    // ── E-Mails des Falls (Issue 3) ──

    @Test
    void emailsTab_listsAssociatedEmailsAndOffersUnassign() throws Exception {
        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity email =
                new verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity(
                        UUID.randomUUID(), "Wohngeldantrag – welche Unterlagen fehlen noch?",
                        "Erika Müller", "erika.mueller@example.de",
                        "Betreff: Wohngeldantrag\n\nSehr geehrte Damen und Herren.",
                        Instant.now(),
                        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo.GENERAL,
                        "kontakt@verwaltungs-demo.de");
        email.setWorkspaceId(UUID.fromString(caseId));
        email.setAnalysisId(UUID.randomUUID());
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(email));

        mockMvc.perform(get("/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wohngeldantrag – welche Unterlagen fehlen noch?")))
                .andExpect(content().string(containsString("Erika Müller")))
                .andExpect(content().string(containsString("Zuordnung aufheben")))
                .andExpect(content().string(containsString("/emails/" + email.getId())));
    }

    @Test
    void emailsTab_emptyState_whenNoEmailsAssociated() throws Exception {
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of());

        mockMvc.perform(get("/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine E-Mails zugeordnet")));
    }

    // ── PDF-Verfügbarkeit (Issue 4): laufende Analyse → kein PDF ──

    @Test
    void runningAnalysis_showsNoPdfAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        testEntity.setPhaseData("{\"analysis\":{\"status\":\"RUNNING\",\"updatedAt\":\"2026-08-29T10:00:00Z\"}}");
        WorkspaceDto analysisDto = new WorkspaceDto(
                caseId, "WS-TEST", "Testfall Bauvorhaben",
                "Ein Testfall für Bauvorhaben Müller", "GENERAL",
                WorkspaceStatus.ACTIVE, WorkspacePhase.ANALYSIS,
                testUser.id().toString(), Map.of(),
                List.of(), List.of(),
                Instant.now().minus(java.time.Duration.ofDays(5)), Instant.now());
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(analysisDto);

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Analyse läuft")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Entscheidungsvorlage als PDF"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("export-pdf"))))
                // Issue 1: während die Analyse LÄUFT, gibt es keinen zweiten
                // startbaren "Analyse starten"-Button.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">Analyse starten</button>"))));
    }

    /**
     * Phase 2B.7 (§3): Ein ANALYSIS-Fall OHNE Analyse (defensiver Zustand,
     * über Seeder/Import erreichbar) muss eine erreichbare Start-Aktion haben.
     */
    @Test
    void analysisPhase_withoutAnalysis_offersStartAction() throws Exception {
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        testEntity.setPhaseData("{\"ingestionResolved\":true}");

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Analyse starten")));
    }

    @Test
    void analysisPhase_withCompletedMarkerButNoRun_offersRetryNotFreshStart() throws Exception {
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        testEntity.setPhaseData("{\"ingestionResolved\":true,\"analysis\":{\"status\":\"COMPLETED\",\"sourceCount\":2}}");

        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Analyseergebnis nicht verfügbar")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">Analyse starten</button>"))));
    }
}
