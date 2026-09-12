package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentCategory;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceAnalysisRunRepository;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decision analyses are persisted as versioned runs per Fall: viewing a case
 * reuses the stored result (no LLM), new evidence is detected, an explicit
 * re-run creates a new version while the previous one stays accessible, and
 * the PDF remains reproducible from the persisted result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DecisionAnalysisLifecycleTest {

    private static final Pattern JOB_URL = Pattern.compile("progress/([a-f0-9-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private JobProgressService progressService;

    @Autowired
    private JpaWorkspaceAnalysisRunRepository analysisRunRepo;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private String caseId;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(decisionRouter.route(anyString())).thenReturn(null);

        WorkspaceEntity ws = workspaceService.createWorkspace(
                new CreateWorkspaceCommand("Testfall Wohngeld", "Wohngeldantrag", "CASE", "user@example.com"));
        caseId = ws.getId();

        var answer = new ReasonedAnswer(
                "Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.",
                List.of(), List.of(), null, null,
                new ConfidenceProfile(0.0, 0.0, 0.0, 0.0, 0.0, "test"),
                false, 0.0, false);
        var metadata = new InferenceMetadata(
                "test", "test-model", Instant.now(), Instant.now(),
                null, null, null, "HYBRID", List.of(), 0.0);
        when(aiFacade.answer(any(AiRequest.class)))
                .thenReturn(new AiResponse(answer, metadata));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String runAnalysis() throws Exception {
        MvcResult start = mockMvc.perform(post("/cases/" + caseId + "/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        Matcher m = JOB_URL.matcher(start.getResponse().getContentAsString());
        assertThat("progress URL must be present", m.find());
        return awaitDone(m.group(1));
    }

    private String awaitDone(String jobId) throws Exception {
        for (int i = 0; i < 50; i++) {
            JobProgressService.Job job = progressService.get(jobId);
            if (job != null && "DONE".equals(job.state)) {
                return mockMvc.perform(get("/cases/" + caseId + "/decision/analyze/progress/" + jobId))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Analysis job did not complete");
    }

    private String decisionPage() throws Exception {
        return mockMvc.perform(get("/cases/" + caseId + "/decision"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void decisionPage_showsCaseContextMetadata() throws Exception {
        runAnalysis();

        String page = decisionPage();
        assertThat(page, containsString("Entscheidung: Testfall Wohngeld"));
        assertThat(page, containsString("Vorgangsnummer"));
        assertThat(page, containsString("Bearbeiter"));
        // The responsible user is the case owner — real data, never invented.
        assertThat(page, containsString("user@example.com"));
    }

    @Test
    void completedAnalysis_isPersistedAndShownWithoutRerun() throws Exception {
        runAnalysis();

        String page = decisionPage();
        assertThat(page, containsString("Analyse vorhanden"));
        assertThat(page, containsString("Analyse #1"));
        assertThat(page, containsString("Letzte Analyse"));
        assertThat(page, containsString("keine ausreichenden Informationen"));

        // Reopening the page must not trigger the AI pipeline again.
        Mockito.clearInvocations(aiFacade);
        decisionPage();
        verify(aiFacade, never()).answer(any(AiRequest.class));

        // The persisted run is readable from the repository (survives restarts).
        var run = workspaceService.latestCompletedAnalysisRun(caseId).orElseThrow();
        assertEquals("COMPLETED", run.getStatus());
        assertEquals("true",
                workspaceService.deserializeAnalysisResult(run).get("analysisComplete").toString());
    }

    @Test
    void newEvidence_isDetectedAndShown() throws Exception {
        runAnalysis();

        workspaceService.attachDocument(new AttachDocumentCommand(
                caseId, UUID.randomUUID().toString(), DocumentCategory.CONTRACT, "general", null));

        String page = decisionPage();
        assertThat(page, containsString("Neue Unterlagen seit der letzten Analyse"));
        assertThat(page, containsString("Analyse aktualisieren"));
    }

    @Test
    void replacedDocument_withUnchangedCount_isDetected() throws Exception {
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        workspaceService.attachDocument(new AttachDocumentCommand(
                caseId, docA, DocumentCategory.CONTRACT, "general", null));
        workspaceService.attachDocument(new AttachDocumentCommand(
                caseId, docB, DocumentCategory.CONTRACT, "general", null));
        runAnalysis(); // basis: [docA, docB]

        // Replace docA with docC — the document count stays 2.
        var links = workspaceService.getWorkspaceDocuments(caseId);
        String linkA = links.stream()
                .filter(l -> l.getDocumentId().equals(docA))
                .map(reasoning.workspace.api.WorkspaceDocumentLinkEntity::getId)
                .findFirst().orElseThrow();
        workspaceService.detachDocument(caseId, linkA);
        workspaceService.attachDocument(new AttachDocumentCommand(
                caseId, UUID.randomUUID().toString(), DocumentCategory.CONTRACT, "general", null));

        String page = decisionPage();
        assertThat(page, containsString("Neue Unterlagen seit der letzten Analyse"));
    }

    @Test
    void rerun_createsVersion2AndKeepsVersion1() throws Exception {
        runAnalysis();
        runAnalysis();

        var runs = workspaceService.listAnalysisRuns(caseId);
        assertEquals(2, runs.size());
        assertEquals(1, runs.get(0).getVersion());
        assertEquals(2, runs.get(1).getVersion());

        String page = decisionPage();
        assertThat(page, containsString("Analyse #2"));
    }

    @Test
    void pdfExport_worksFromPersistedRun() throws Exception {
        runAnalysis();

        MvcResult pdf = mockMvc.perform(get("/cases/" + caseId + "/decision/export-pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andReturn();
        assertTrue(pdf.getResponse().getContentAsByteArray().length > 1000);
    }

    @Test
    void historyVersions_areRenderableViaResultEndpoint() throws Exception {
        runAnalysis();
        runAnalysis();

        mockMvc.perform(get("/cases/" + caseId + "/decision/result/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("keine ausreichenden Informationen")));
    }

    /**
     * Legacy runs persisted by an older pipeline can carry non-zero confidence
     * although no evidence was found (e.g. Gesamtkonfidenz 49 %, Quellenlage
     * 59 %, Vollständigkeit 100 % with zero documents). The decision page must
     * never render those stale values — it must show "nicht bewertbar".
     */
    @Test
    void legacyRun_withStaleConfidenceAndNoEvidence_rendersNotAssessable() throws Exception {
        runAnalysis();

        // Simulate the legacy corruption: no evidence, but non-zero confidence
        // and a grounded flag that contradicts the empty evidence.
        var run = workspaceService.latestCompletedAnalysisRun(caseId).orElseThrow();
        Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
        result.put("evidenceItems", List.of());
        result.put("grounded", true);
        result.put("confidenceScore", "49 %");
        result.put("coverageScore", "59 %");
        result.put("confidence", Map.of(
                "sourceConfidence", 0.59,
                "semanticConfidence", 0.5,
                "structuralConfidence", 0.5,
                "completenessConfidence", 1.0,
                "overallConfidence", 0.49,
                "explanation", "legacy"));
        run.setResultJson(new ObjectMapper().writeValueAsString(result));
        analysisRunRepo.save(run);

        String page = decisionPage();
        assertThat(page, containsString("nicht bewertbar"));
        assertThat(page, not(containsString("49 %")));
        assertThat(page, not(containsString("59 %")));
        // Die Konfidenz-Erläuterung enthält den statischen Hinweis "Ergebnis
        // wird auf 100 % begrenzt" — geprüft wird daher nicht das Fehlen jeder
        // "100 %"-Zeichenfolge, sondern dass die Quellenabdeckung (Vollständig-
        // keit des alten Laufs) nicht mehr als Indikatorwert erscheint: der
        // sanierte Profilwert ist 0 % statt der alten 100 % (">0 %</span>").
        assertThat(page, not(containsString("100 %</span>")));

        // The version endpoint sanitizes the same way.
        String versionFragment = mockMvc.perform(get("/cases/" + caseId + "/decision/result/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(versionFragment, containsString("nicht bewertbar"));
        assertThat(versionFragment, not(containsString("49 %")));
    }
}
