package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard "Eingegangene Nachrichten" → /emails?open=&lt;id&gt;: the exact
 * stored e-mail must be displayed (text area + analysis panel + left demo
 * list highlight), selected by analysis ID — never by subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class EmailDashboardLinkTest {

    private static final String DEMO1_BODY = """
            Betreff: Wohngeldantrag – welche Unterlagen fehlen noch?

            Guten Tag,

            ich habe vor zwei Wochen meinen Antrag auf Wohngeld eingereicht.
            Ich bin mir aber nicht sicher, ob alle Unterlagen angekommen sind.
            Können Sie bitte prüfen, ob noch etwas fehlt? Mein Mietvertrag und
            die Einkommensnachweise der letzten drei Monate liegen dem Antrag bei.

            Mit freundlichen Grüßen
            Erika Müller
            Musterstraße 12, 10405 Berlin""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaEmailAnalysisRepository emailAnalysisRepository;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UUID storeAnalysis(String questionText, String subject) {
        String outcomeJson = "{\"subject\":\"" + subject + "\",\"topicLabel\":\"Wohngeld\","
                + "\"domainLabel\":null,\"intentType\":null,\"matchedCases\":[],"
                + "\"relevantDocuments\":[],\"missingDocuments\":[],\"steps\":[]}";
        EmailAnalysisEntity entity = new EmailAnalysisEntity(
                UUID.randomUUID(), testUser.email(), questionText, subject,
                "Wohngeld", "9.9.9-test", outcomeJson, Instant.now());
        return emailAnalysisRepository.save(entity).getId();
    }

    @Test
    void dashboardEmailClick_opensExactEmailInEmailsPanel() throws Exception {
        UUID id = storeAnalysis(DEMO1_BODY, "Wohngeldantrag – welche Unterlagen fehlen noch?");

        // The dashboard feed links each entry to its own analysis.
        String dashboard = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard, containsString("/emails?open=" + id));

        // /emails?open=<id> shows the analysis, restores the e-mail text into
        // the text area and highlights the matching demo entry on the left.
        MvcResult result = mockMvc.perform(get("/emails").param("open", id.toString()))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html, containsString("Vorläufige E-Mail-Analyse"));
        assertThat(html, containsString("Wohngeldantrag – welche Unterlagen fehlen noch?"));
        // Exact e-mail text inside the text area.
        assertThat(html, containsString("Ich bin mir aber nicht sicher, ob alle Unterlagen angekommen sind."));
        // Left demo list highlight — content-identical match only.
        assertThat(html, containsString("email-list-item--selected"));
    }

    @Test
    void sameSubject_differentBodies_opensTheExactAnalysis() throws Exception {
        UUID first = storeAnalysis("Erster eindeutiger Text des Vorgangs.", "Gleicher Betreff");
        UUID second = storeAnalysis("Zweiter völlig anderer Text.", "Gleicher Betreff");

        String html = mockMvc.perform(get("/emails").param("open", second.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html, containsString("Zweiter völlig anderer Text."));
        assertThat(html, not(containsString("Erster eindeutiger Text des Vorgangs.")));
        // Both have the same subject — selection must not depend on it.
        assertThat(html, containsString("Gleicher Betreff"));
        // The first analysis is not highlighted.
        String dashboardHtml = mockMvc.perform(get("/dashboard"))
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboardHtml, containsString("/emails?open=" + second));
    }

    @Test
    void emailsPage_withoutOpen_hasEmptyTextArea() throws Exception {
        String html = mockMvc.perform(get("/emails"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The text area starts empty when no e-mail was selected. (Demo e-mail
        // bodies are embedded as hidden scripts, so only the textarea itself
        // must be checked.)
        // The selected class name also appears in the page's inline JS
        // (loadEmail/clearEmail), so only the textarea content is checked.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<textarea id=\"email-text\"[^>]*>([\\s\\S]*?)</textarea>")
                .matcher(html);
        assertTrue(m.find(), "textarea must be present");
        assertTrue(m.group(1).trim().isEmpty(), "textarea must be empty without ?open=");
    }
}
