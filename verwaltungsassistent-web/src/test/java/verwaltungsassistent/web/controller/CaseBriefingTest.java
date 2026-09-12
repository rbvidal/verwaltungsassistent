package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.FindingElement;
import reasoning.ai.model.FindingHierarchy;
import reasoning.ai.model.FindingRole;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.SourceCitation;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fallbriefing feature tests: the tab renders the empty state, generation
 * routes through the municipal AiFacade pipeline (the canned pipeline result
 * determines the briefing content — the "Allgemeine Fragen" direct path could
 * never produce it), supported and unsupported points are marked, and the PDF
 * export carries the briefing sections.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CaseBriefingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private AiFacade aiFacade;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity testEntity;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        testEntity = new WorkspaceEntity("WS-BRIEF-1", "Fall Wohngeld",
                "Antrag auf Wohngeld vom 02.08.2026", "CASE_FILE", testUser.email());
        testEntity.setStatus(WorkspaceStatus.ACTIVE);
        testEntity.setPhase(WorkspacePhase.ANALYSIS);
        testEntity.setGeoAuthority("Amt für Bürgerdienste");
        testEntity.setGeoDistrict("Potsdam");

        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.getWorkspaceDocuments(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void briefingTab_rendersEmptyState_whenNoBriefingExists() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/briefing"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Noch kein Fallbriefing erstellt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fallbriefing erstellen")));
    }

    @Test
    void generate_runsMunicipalPipeline_briefingShowsEvidenceAndUnsupported() throws Exception {
        stubPipelineAnswer();

        mockMvc.perform(post("/cases/" + caseId + "/briefing/generate").with(csrf()))
                .andExpect(status().isOk());

        // The generation must go through the municipal AiFacade pipeline entry.
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade, timeout(5000).atLeastOnce()).answer(captor.capture());
        AiRequest request = captor.getValue();
        assertEquals(caseId, request.workspaceId().toString(), "retrieval must be scoped to the case");
        assertEquals(reasoning.ai.model.RetrievalScope.CURRENT_WORKSPACE,
                request.retrievalScope(), "briefing must not use the global retrieval scope");

        // The pipeline request id doubles as the job id (progress stream).
        String jobId = request.context().requestId();
        Job job = waitForDone(jobId);
        assertEquals("DONE", job.state, "briefing job must complete");

        // Briefing content reflects the real pipeline result: supported points
        // marked as belegt, unsupported points as nicht belegt, citations listed.
        mockMvc.perform(get("/cases/" + caseId + "/briefing"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("KURZANTWORT-inhalt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Belegte Kernfeststellung")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("belegt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mietbescheinigung.pdf")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vermuteter Punkt ohne Beleg")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("nicht belegt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Belegt · 1 Beleg(e)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Amt für Bürgerdienste")));
    }

    @Test
    void briefingPdf_containsStructuredSections() throws Exception {
        stubPipelineAnswer();

        mockMvc.perform(post("/cases/" + caseId + "/briefing/generate").with(csrf()))
                .andExpect(status().isOk());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade, timeout(5000).atLeastOnce()).answer(captor.capture());
        waitForDone(captor.getValue().context().requestId());

        byte[] pdf = mockMvc.perform(get("/cases/" + caseId + "/briefing/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(pdf.length > 1000, "PDF should contain content");
        assertTrue(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("%PDF"),
                "PDF magic header");
        try (PDDocument doc = PDDocument.load(pdf)) {
            String normalized = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").trim();
            // Abschnittstitel werden im Template per CSS in Großbuchstaben
            // gerendert (text-transform: uppercase); der Vergleich erfolgt daher
            // case-insensitiv. Die nummerierten Überschriften ("1. KURZFASSUNG")
            // existieren im Briefing-Template bewusst nicht.
            String upper = normalized.toUpperCase(java.util.Locale.GERMANY);
            assertTrue(normalized.contains("Fallbriefing"), "document title expected");
            assertTrue(normalized.contains("Vorgangsnummer"));
            assertTrue(normalized.contains("WS-BRIEF-1"));
            assertTrue(upper.contains("KURZFASSUNG"), "reference section 1 expected");
            assertTrue(upper.contains("SACHVERHALT"), "reference section 2 expected");
            assertTrue(upper.contains("GESICHERTE ERKENNTNISSE"),
                    "reference section 3 expected");
            assertTrue(normalized.contains("Belegte Kernfeststellung"));
            assertTrue(upper.contains("BELEGE"), "reference section 4 expected");
            assertTrue(normalized.contains("Mietbescheinigung.pdf"));
            assertTrue(upper.contains("ZUSTÄNDIGKEIT"));
            assertTrue(upper.contains("OFFENE PUNKTE"), "reference section 5 expected");
            assertTrue(normalized.contains("Vermuteter Punkt ohne Beleg"));
            assertTrue(upper.contains("NÄCHSTE SCHRITTE"), "reference section 6 expected");
            assertTrue(upper.contains("QUELLENLAGE"), "reference section 7 expected");
            assertTrue(normalized.contains("Verwaltungsassistent v"), "version footer expected");
        }
    }

    @Test
    void briefingPdf_withoutBriefing_returnsNotFound() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/briefing/pdf"))
                .andExpect(status().isNotFound());
    }

    /**
     * Issue 11.10: Die Erzeugungs-Antwort rendert die gemeinsame
     * Pipeline-Visualisierung (echte Job-Stufen) und den selbst-pollenden
     * Fortschrittsblock MIT job-Parameter — derselbe Mechanismus wie die
     * Fall-Analyse, kein separater Fortschritts-Pfad. Während des Laufs gibt
     * es weder window.open noch PDF-Aktionen (kein vorzeitiger Tab).
     */
    @Test
    void briefingProgress_rendersPipelineDiagram_andPollsWithJobId() throws Exception {
        stubPipelineAnswer();

        mockMvc.perform(post("/cases/" + caseId + "/briefing/generate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pipeline-diagram")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/cases/" + caseId + "/briefing?job=")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("window.open"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PDF öffnen"))));
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade, timeout(5000).atLeastOnce()).answer(captor.capture());
        waitForDone(captor.getValue().context().requestId());
    }

    /**
     * Issue 11.2/11.3: Der Abschluss-Poll (?job=...) öffnet die fertige PDF
     * GENAU EINMAL automatisch; das bloße erneute Öffnen des Tabs tut das nie
     * (kein zweiter Tab, keine PDF-Aktion beim Besuch).
     */
    @Test
    void briefingCompletionPoll_autoOpensPdfOnce_onlyOnCompletion() throws Exception {
        stubPipelineAnswer();

        mockMvc.perform(post("/cases/" + caseId + "/briefing/generate").with(csrf()))
                .andExpect(status().isOk());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade, timeout(5000).atLeastOnce()).answer(captor.capture());
        String jobId = captor.getValue().context().requestId();
        waitForDone(jobId);

        // Übergang "Lauf fertig → Anzeige": autoOpenPdf + inline-PDF-URL.
        mockMvc.perform(get("/cases/" + caseId + "/briefing").param("job", jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window.open")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/briefing/pdf?inline=true")));

        // Erneuter Besuch (ohne job): gespeichertes Briefing, KEIN auto-open,
        // explizite Aktionen vorhanden.
        mockMvc.perform(get("/cases/" + caseId + "/briefing"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Status: Gespeichert")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PDF öffnen")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Als PDF exportieren")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/briefing/pdf?inline=true")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("window.open"))));
    }

    private void stubPipelineAnswer() {
        String answerText = "KURZANTWORT KURZANTWORT-inhalt: Das Wohngeldverfahren kann fortgeführt werden. "
                + "ENTSCHEIDUNG Die Antragsunterlagen sind vollständig. "
                + "NÄCHSTER SCHRITT Prüfen Sie die Einkommensnachweise. Setzen Sie den Vorgang fort.";
        FindingHierarchy findings = new FindingHierarchy(
                List.of(new FindingElement("Belegte Kernfeststellung", FindingRole.PRIMARY_FINDING,
                        0.9, List.of(), List.of(), "Durch die Unterlagen gedeckt.")),
                List.of(new FindingElement("Vermuteter Punkt ohne Beleg", FindingRole.SUPPORTING_FINDING,
                        0.4, List.of(), List.of(), "Nicht durch die Unterlagen gedeckt.")),
                List.of(), List.of(), List.of());
        ReasonedAnswer reasoned = new ReasonedAnswer(
                answerText,
                List.of(new SourceCitation(UUID.randomUUID(), UUID.randomUUID(), 1,
                        "Mietbescheinigung.pdf", 1, 0, 200, "Auszug", 0.9,
                        SourceCitation.SourceTier.PRIMARY)),
                List.of(),
                findings,
                null,
                new ConfidenceProfile(0.8, 0.8, 0.8, 0.8, 0.8, ""),
                true, 0.9, false);
        when(aiFacade.answer(any(AiRequest.class)))
                .thenReturn(new AiResponse(reasoned, null));
    }

    private Job waitForDone(String jobId) throws InterruptedException {
        Job job = null;
        for (int i = 0; i < 100; i++) {
            job = progressService.get(jobId);
            if (job != null && !"RUNNING".equals(job.state)) return job;
            Thread.sleep(50);
        }
        return job;
    }
}
