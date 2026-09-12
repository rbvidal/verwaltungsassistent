package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decision analysis ("Analyse starten") progress lifecycle: the POST must
 * return a progress panel immediately and the poll endpoint must reflect the
 * job state; a running analysis must not be started twice for the same case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DecisionAnalysisProgressTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final WorkspaceEntity case1 = createCaseEntity();

    private static WorkspaceEntity createCaseEntity() {
        WorkspaceEntity e = new WorkspaceEntity("FALL-001", "Fall Müller", "Testfall", "DECISION", "user@example.com");
        e.setId(UUID.nameUUIDFromBytes("ws-1".getBytes()).toString());
        e.setStatus(WorkspaceStatus.ACTIVE);
        return e;
    }

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(workspaceService.findById(anyString())).thenReturn(Optional.empty());
        when(workspaceService.findById("ws-1")).thenReturn(Optional.of(case1));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenAnswer(inv -> {
            WorkspaceEntity e = inv.getArgument(0);
            return new WorkspaceDto(
                    e.getId(), e.getWorkspaceCode(), e.getName(),
                    e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                    e.getOwnerId(), Map.of(), List.of(), List.of(),
                    e.getCreatedAt(), e.getUpdatedAt());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analyzeStart_returnsProgressPanelImmediately() throws Exception {
        mockMvc.perform(post("/cases/ws-1/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Analyse läuft")))
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("/cases/ws-1/decision/analyze/progress/")));
    }

    @Test
    void analyzeStart_unknownCase_returnsNotFound() throws Exception {
        mockMvc.perform(post("/cases/unknown/decision/analyze").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyzeStart_reusesRunningJobForSameCase() throws Exception {
        Job running = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        progressService.registerActive("analysis:ws-1", running.jobId);
        int before = progressService.size();

        mockMvc.perform(post("/cases/ws-1/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(running.jobId)));
        assertEquals(before, progressService.size(), "no second job may be created");
    }

    @Test
    void pollRunning_rendersProgressPanel() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        progressService.recordStage(job.jobId, "Relevante Dokumente werden gesucht …");

        mockMvc.perform(get("/cases/ws-1/decision/analyze/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Analyse läuft")))
                .andExpect(content().string(containsString("Relevante Dokumente werden gesucht …")))
                .andExpect(content().string(containsString("/decision/analyze/progress/" + job.jobId)));
    }

    @Test
    void pollDone_returnsRenderedResultFragment() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        progressService.complete(job.jobId,
                "<div class=\"decision-package\">ENTSCHEIDUNGSERGEBNIS-MARKER</div>",
                "Die Analyse wurde abgeschlossen.");

        mockMvc.perform(get("/cases/ws-1/decision/analyze/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ENTSCHEIDUNGSERGEBNIS-MARKER")))
                .andExpect(content().string(not(containsString("class=\"progress-panel\""))));
    }

    @Test
    void pollError_returnsDecisionResultWithRetry() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        progressService.fail(job.jobId);

        mockMvc.perform(get("/cases/ws-1/decision/analyze/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Die Analyse konnte nicht abgeschlossen werden.")))
                .andExpect(content().string(containsString("Erneut versuchen")));
    }

    @Test
    void pollUnknownJob_returnsExpiredNoticeWithRetry() throws Exception {
        mockMvc.perform(get("/cases/ws-1/decision/analyze/progress/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Die Analyse ist nicht mehr verfügbar. Bitte starten Sie die Analyse erneut.")))
                .andExpect(content().string(not(containsString(
                        "Die Analyse konnte nicht abgeschlossen werden."))))
                .andExpect(content().string(containsString("Erneut versuchen")));
    }

    /**
     * Regression (Warteseite der Entscheidungsvorlage): der JSON-Zustand für
     * die PDF-Warte-Seite muss die echten Pipeline-KNOTEN liefern (Label +
     * Zustand done/active/pending aus dem Job-Stufenstrom) — erst damit kann
     * die Warte-Seite die orange Knoten-Visualisierung statt der reinen
     * Textliste „✓ … / ▶ …" zeichnen. Keine erfundenen Prozentwerte.
     */
    @Test
    void analysisState_exposesPipelineNodeStatesForPdfWaitScreen() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        progressService.registerActive("analysis:ws-1", job.jobId);
        progressService.recordStageData(job.jobId, "intent", Map.of());
        progressService.recordStageData(job.jobId, "retrieval-done", Map.of());
        try {
            mockMvc.perform(get("/cases/ws-1/decision/analysis-state"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes").isArray())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes.length()").value(7))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[0].label").value("Frage"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[0].state").value("done"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[1].label").value("Intent"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[1].state").value("done"))
                    // retrieval-done ist die zuletzt erreichte Stufe → aktiv
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[2].label").value("Retrieval"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[2].state").value("active"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.nodes[3].state").value("pending"));
        } finally {
            progressService.unregisterActive("analysis:ws-1", job.jobId);
        }
    }
}
