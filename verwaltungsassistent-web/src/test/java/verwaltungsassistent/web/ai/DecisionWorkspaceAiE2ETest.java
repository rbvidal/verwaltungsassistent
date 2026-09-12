package verwaltungsassistent.web.ai;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end test of the Decision Workspace AI workflow.
 *
 * <p>Verifies the full chain from HTMX controller to LLM response:
 * Decision Workspace → Analyse starten → Retrieval → Prompt → LLM →
 * Structured Decision Package → Rendered HTMX fragment.
 *
 * <p>Runs against a real Ollama instance. Retrieval may return empty results
 * when no documents are indexed, but the pipeline still produces a valid
 * AI-generated decision package.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecisionWorkspaceAiE2ETest {

    private static final Logger log = LoggerFactory.getLogger(DecisionWorkspaceAiE2ETest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceService workspaceService;

    private String caseId;
    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "sachbearbeiter@verwaltungsassistent.local", "Sachbearbeiter Test",
            Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        WorkspaceEntity workspace = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(
                        "Baugenehmigung Carport Grundstück Müller",
                        "Prüfung der Genehmigungspflicht für Carport-Bau in Berlin",
                        "CASE", testUser.email()));
        caseId = workspace.getId().toString();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void decisionWorkspacePageShouldRender() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/decision"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/decision"))
                .andExpect(model().attributeExists("caseId"))
                .andExpect(model().attributeExists("caseName"))
                .andExpect(model().attribute("activeSection", "cases"));
    }

    @Test
    void startAnalysisShouldReturnDecisionFragment() throws Exception {
        Instant start = Instant.now();
        String response = mockMvc.perform(post("/cases/" + caseId + "/decision/analyze")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Gesamtkonfidenz")))
                .andReturn().getResponse().getContentAsString();
        Duration latency = Duration.between(start, Instant.now());

        assertThat(response).isNotNull();
        assertThat(response).isNotEmpty();

        log.info("═══════════════════════════════════════════");
        log.info("  E2E Decision Workspace Diagnostics");
        log.info("═══════════════════════════════════════════");
        log.info("  Case ID            : {}", caseId);
        log.info("  Total latency      : {} ms", latency.toMillis());
        log.info("  Response length    : {} chars", response.length());
        log.info("  Response preview   : {}",
                response.length() > 200 ? response.substring(0, 200) + "..." : response);
        log.info("═══════════════════════════════════════════");

        assertThat(latency.toMillis()).isLessThan(120_000);
        assertThat(response).doesNotContain("KI-Dienst ist nicht verfügbar");
    }

    @Test
    void startAnalysisShouldNotReturnErrorWhenAiIsWired() throws Exception {
        String response = mockMvc.perform(post("/cases/" + caseId + "/decision/analyze")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("KI-Dienst ist nicht verfügbar");
        assertThat(response.length()).isGreaterThan(100);
    }
}
