package verwaltungsassistent.web.ai;

import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "demo.mode=true")
@AutoConfigureMockMvc
class DemoReadinessTest {

    @Autowired
    private MockMvc mockMvc;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "demo@verwaltungsassistent.local", "Demo Benutzer",
            Set.of("USER"));

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

    // ── Case Creation ──

    @Test
    void caseCreationFormShouldRender() throws Exception {
        mockMvc.perform(get("/cases/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/create"))
                .andExpect(content().string(containsString("Neuen Fall anlegen")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("name=\"description\"")));
    }

    @Test
    void caseCreationWithValidDataShouldRedirectToCaseDetail() throws Exception {
        mockMvc.perform(post("/cases/new")
                        .with(csrf())
                        .param("name", "Testfall Bauantrag")
                        .param("description", "Prüfung einer Baugenehmigung"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/cases/*"));
    }

    @Test
    void caseCreationWithWhitespaceNameShouldStillCreate() throws Exception {
        // Server-side only trims whitespace; HTML required attribute handles client validation
        mockMvc.perform(post("/cases/new")
                        .with(csrf())
                        .param("name", "  ")
                        .param("description", "Test"))
                .andExpect(status().is3xxRedirection());
    }

    // ── Invalid UUID Handling ──

    @Test
    void malformedCaseIdShouldReturn404Not500() throws Exception {
        mockMvc.perform(get("/cases/not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedCaseIdInDecisionShouldReturn404() throws Exception {
        mockMvc.perform(get("/cases/not-a-uuid/decision"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wellFormedButNonexistentUuidShouldReturn404() throws Exception {
        mockMvc.perform(get("/cases/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── Hidden Navigation in Demo Mode ──

    @Test
    void dashboardShouldNotContainPlaceholderQuickActions() throws Exception {
        String content = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Demo mode is true — placeholder links should be hidden
        assertThat(content).doesNotContain("/documents/upload");
        assertThat(content).doesNotContain("/knowledge");
        assertThat(content).doesNotContain("/assistant");
        // Case creation should still be visible
        assertThat(content).contains("/cases/new");
    }

    @Test
    void headerShouldNotContainPlaceholderNavItems() throws Exception {
        String content = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("href=\"/documents\"");
        assertThat(content).doesNotContain("href=\"/knowledge\"");
        assertThat(content).doesNotContain("href=\"/assistant\"");
        assertThat(content).doesNotContain("href=\"/decisions\"");
        // Core nav items should still be present
        assertThat(content).contains("href=\"/dashboard\"");
        assertThat(content).contains("href=\"/cases\"");
    }

    // ── HTMX Loading Indicator ──

    @Test
    void decisionWorkspaceShouldContainLoadingIndicator() throws Exception {
        // Need a real case for this — create one first
        String caseId = createTestCase();
        String content = mockMvc.perform(get("/cases/" + caseId + "/decision"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains("analysis-spinner");
        assertThat(content).contains("Analyse wird durchgeführt");
        assertThat(content).contains("hx-indicator");
    }

    // ── German Terminology ──

    @Test
    void dashboardShouldNotContainEnglishTechnicalTerms() throws Exception {
        String content = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("Ingestion Jobs");
        assertThat(content).contains("Verarbeitungsaufträge");
        assertThat(content).doesNotContain("Java 21");  // Hidden in demo mode
    }

    @Test
    void caseDetailShouldUseGermanPhaseLabels() throws Exception {
        String caseId = createTestCase();
        String content = mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Phase label should use German term, not English tech term
        assertThat(content).contains("Verarbeitung");
    }

    // ── Helper ──

    private String createTestCase() throws Exception {
        String redirectedUrl = mockMvc.perform(post("/cases/new")
                        .with(csrf())
                        .param("name", "Testfall für Demo")
                        .param("description", "Automatisch erstellter Testfall"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        return redirectedUrl.substring(redirectedUrl.lastIndexOf('/') + 1);
    }
}
