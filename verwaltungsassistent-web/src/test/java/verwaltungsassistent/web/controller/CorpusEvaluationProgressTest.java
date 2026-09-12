package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.auth.api.AuthenticatedUser;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Beispiele "Fall auswerten" progress lifecycle: immediate progress panel,
 * polling states, and duplicate-start prevention per case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CorpusEvaluationProgressTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("ADMIN"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void evaluateStart_returnsProgressPanelImmediately() throws Exception {
        mockMvc.perform(post("/corpus/travel/T01/evaluate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall wird ausgewertet")))
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("/corpus/travel/T01/evaluate/progress/")));
    }

    @Test
    void evaluateStart_reusesRunningJobForSameCase() throws Exception {
        Job running = progressService.create(JobProgressService.Kind.EVALUATION, "Fall wird ausgewertet …");
        progressService.registerActive("evaluation:travel:T01", running.jobId);
        int before = progressService.size();

        mockMvc.perform(post("/corpus/travel/T01/evaluate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(running.jobId)));
        assertEquals(before, progressService.size(), "no second job may be created");
    }

    @Test
    void pollRunning_rendersProgressPanel() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.EVALUATION, "Fall wird ausgewertet …");
        progressService.recordStage(job.jobId, "Relevante Informationen werden gesucht …");

        mockMvc.perform(get("/corpus/travel/T01/evaluate/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall wird ausgewertet")))
                .andExpect(content().string(containsString("Relevante Informationen werden gesucht …")))
                .andExpect(content().string(containsString("progress-terminal__line--active")))
                .andExpect(content().string(containsString("progress-terminal__message")))
                .andExpect(content().string(containsString("/corpus/travel/T01/evaluate/progress/" + job.jobId)));
    }

    @Test
    void pollDone_returnsRenderedEvaluationFragment() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.EVALUATION, "Fall wird ausgewertet …");
        progressService.complete(job.jobId,
                "<div class=\"decision-package__recommendation\">AUSWERTUNGSERGEBNIS-MARKER</div>",
                "Die Auswertung wurde abgeschlossen.");

        mockMvc.perform(get("/corpus/travel/T01/evaluate/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AUSWERTUNGSERGEBNIS-MARKER")))
                .andExpect(content().string(not(containsString("class=\"progress-panel\""))));
    }

    @Test
    void pollError_returnsEvaluationWithErrorAndRetry() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.EVALUATION, "Fall wird ausgewertet …");
        progressService.fail(job.jobId);

        mockMvc.perform(get("/corpus/travel/T01/evaluate/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall konnte nicht ausgewertet werden.")))
                .andExpect(content().string(containsString("Erneut versuchen")));
    }

    @Test
    void pollUnknownJob_returnsExpiredNotice() throws Exception {
        mockMvc.perform(get("/corpus/travel/T01/evaluate/progress/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Die Auswertung ist nicht mehr verfügbar. Bitte starten Sie die Auswertung erneut.")))
                .andExpect(content().string(not(containsString("Fall konnte nicht ausgewertet werden."))));
    }
}
