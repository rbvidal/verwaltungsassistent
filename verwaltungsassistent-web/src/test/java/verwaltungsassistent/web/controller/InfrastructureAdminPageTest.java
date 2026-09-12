package verwaltungsassistent.web.controller;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Administration → Infrastruktur: the Qdrant link points at the actual web
 * UI (/dashboard), and the "Verwaltungsassistent diese Anwendung" self-entry is gone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class InfrastructureAdminPageTest {

    @Autowired
    private MockMvc mockMvc;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "admin@example.com", "Test Admin", Set.of("ADMIN"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void infrastructurePage_qdrantLinkPointsAtDashboardUi() throws Exception {
        String html = mockMvc.perform(get("/admin/infrastructure"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Phase 2D.13/2D.14: Das normale ADM/Leitungs-Konto sieht den
        // Qdrant-Status/Endpunkt, aber KEINEN Durchklick-Link auf das
        // passwortlose Qdrant-Web-Dashboard.
        assertThat(html, containsString("/dashboard"));
        assertThat(html, not(containsString("href=\"http://localhost:6333/dashboard\"")));
        assertThat(html, not(containsString("http://localhost:6333\"")));
    }

    @Test
    void infrastructurePage_superadmin_qdrantDashboardLinkIsClickable() throws Exception {
        var superadmin = new AuthenticatedUser(
                UUID.randomUUID(), "superadmin@verwaltungsassistent.local", "Superadmin",
                Set.of("ADMIN", "SUPERADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(superadmin, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_SUPERADMIN"))));

        String html = mockMvc.perform(get("/admin/infrastructure"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Superadmin darf das Qdrant-Dashboard direkt öffnen.
        assertThat(html, containsString("href=\"http://localhost:6333/dashboard\""));
    }

    @Test
    void infrastructurePage_hasNoSelfEntry() throws Exception {
        String html = mockMvc.perform(get("/admin/infrastructure"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No "Verwaltungsassistent — diese Anwendung" service row (the phrase may still appear
        // in the descriptive sentence about live values).
        assertThat(html, not(containsString(">Verwaltungsassistent<")));
        assertThat(html, not(containsString("<span>diese Anwendung</span>")));
        // The external services remain listed.
        assertThat(html, containsString("PostgreSQL"));
        assertThat(html, containsString("Neo4j"));
        assertThat(html, containsString("Ollama"));
    }
}
