package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.AssistantOutcome;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Assistant progress-state lifecycle: the page must be idle on initial load
 * (no progress panel, no job), a blank submission must not start a job, and
 * the poll endpoint must reflect the job state (running / done / error).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AssistantProgressLifecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository chunkRepository;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void initialPageIsIdle_noProgressPanel_noJob() throws Exception {
        int before = progressService.size();
        mockMvc.perform(get("/assistant"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stellen Sie eine Frage")))
                .andExpect(content().string(not(containsString("class=\"progress-panel\""))))
                .andExpect(content().string(containsString("hx-history=\"false\"")))
                .andExpect(content().string(containsString("pageshow")));
        assertEquals(before, progressService.size(), "page load must not create a progress job");
    }

    @Test
    void blankQuestion_returnsError_andCreatesNoJob() throws Exception {
        int before = progressService.size();
        mockMvc.perform(post("/assistant/ask").param("question", "   ").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte geben Sie eine Frage ein.")))
                .andExpect(content().string(not(containsString("class=\"progress-panel\""))));
        assertEquals(before, progressService.size(), "blank submission must not create a job");
    }

    @Test
    void questionSubmission_showsProgressPanel_andCreatesJob() throws Exception {
        int before = progressService.size();
        mockMvc.perform(post("/assistant/ask").param("question", "Wie hoch ist das Tagegeld bei 12 Stunden?").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("/assistant/progress/")));
        assertEquals(before + 1, progressService.size());
    }

    @Test
    void pollRunning_rendersProgressPanelWithPolling() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Testfrage");
        progressService.recordStage(job.jobId, "Ihre Anfrage wird analysiert …");

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("Ihre Anfrage wird analysiert …")))
                .andExpect(content().string(containsString("progress-terminal__line--active")))
                .andExpect(content().string(containsString("progress-terminal__message")))
                .andExpect(content().string(containsString("/assistant/progress/" + job.jobId)));
    }

    @Test
    void pollDone_groundedWithCitations_rendersClickableSourcesDialog() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Testfrage");
        var citation = new reasoning.ai.model.SourceCitation(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                3, "AV zu §55 LHO Berlin", 4, 100, 220,
                "Direktauftrag bis 10.000 € zulässig.", 0.95,
                reasoning.ai.model.SourceCitation.SourceTier.PRIMARY,
                reasoning.ai.model.SourceCitation.SourceType.AUTHORITATIVE,
                List.of("Keyword"));
        var chunk = new reasoning.search.infrastructure.persistence.DocumentChunkEntity(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                3, reasoning.search.model.ChunkType.TEXT,
                "Vollständiger Abschnittstext.", 4, null, 23, null, null,
                "Direktaufträge nach §55 LHO",
                reasoning.common.model.DocumentFileType.PDF, "CONTRACT",
                java.util.Set.of(), "upload", null, java.time.Instant.now(),
                java.util.Set.of(), null, null, null, null, null);
        when(chunkRepository.findByDocumentIdOrderByChunkIndex(
                UUID.fromString("11111111-1111-1111-1111-111111111111")))
                .thenReturn(List.of(chunk));
        progressService.complete(job.jobId, new AssistantOutcome(
                "Direktauftrag zulässig.", true, "Regelbasiert", List.of(citation),
                List.of(), 95, false), "Die Prüfung wurde abgeschlossen.");

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Durch Quellen belegt")))
                .andExpect(content().string(containsString("Quellen anzeigen")))
                .andExpect(content().string(containsString("badge--clickable")))
                .andExpect(content().string(containsString("modal-overlay")))
                .andExpect(content().string(containsString("AV zu §55 LHO Berlin")))
                .andExpect(content().string(containsString("Version 3")))
                .andExpect(content().string(containsString("Chunk 23")))
                .andExpect(content().string(containsString("S. 4")))
                .andExpect(content().string(containsString("0,95")))
                .andExpect(content().string(containsString("Primär")))
                .andExpect(content().string(containsString("PDF")))
                .andExpect(content().string(containsString("Vertrag")))
                .andExpect(content().string(containsString("Vollständiger Abschnittstext.")));
    }

    @Test
    void pollDone_groundedWithoutCitations_doesNotClaimSources() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Testfrage");
        progressService.complete(job.jobId, new AssistantOutcome(
                "Für diese Frage liegen keine ausreichenden Informationen vor.",
                true, "Hybride Suche", List.of(), List.of(), 42, true),
                "Die Informationen in unserem System reichen für diese Frage derzeit nicht aus.");

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Durch Quellen belegt"))))
                .andExpect(content().string(not(containsString("modal-overlay"))))
                .andExpect(content().string(containsString("Eingeschränkte Quellenlage")));
    }

    @Test
    void pollDone_rendersAnswerAndCitations() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Testfrage");
        progressService.complete(job.jobId, new AssistantOutcome(
                "Tagegeld: 12,00 €", true, "Regelbasiert", List.of(), List.of(), 99, false),
                "Die Prüfung wurde abgeschlossen.");

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tagegeld: 12,00 €")))
                .andExpect(content().string(containsString("Die Prüfung wurde abgeschlossen.")))
                .andExpect(content().string(not(containsString("class=\"progress-panel\""))));
    }

    @Test
    void pollError_rendersErrorMessage() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Testfrage");
        progressService.fail(job.jobId);

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Die Anfrage konnte nicht vollständig verarbeitet werden.")));
    }

    @Test
    void pollUnknownJob_rendersExpiredNotice() throws Exception {
        mockMvc.perform(get("/assistant/progress/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Die Anfrage ist nicht mehr verfügbar. Bitte stellen Sie die Frage erneut.")))
                .andExpect(content().string(not(containsString(
                        "Die Anfrage konnte nicht vollständig verarbeitet werden."))));
    }

    @Test
    void newQuestionGetsFreshJob() throws Exception {
        Job first = progressService.create(JobProgressService.Kind.ASSISTANT, "Frage 1");
        Job second = progressService.create(JobProgressService.Kind.ASSISTANT, "Frage 2");
        assertNotNull(first.jobId);
        assertNotNull(second.jobId);
        org.junit.jupiter.api.Assertions.assertNotEquals(first.jobId, second.jobId);
    }
}
