package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.planning.NextBestWorkService.Recommendation;
import verwaltungsassistent.web.planning.NextBestWorkService.RecommendationResult;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2B-Dashboard: die Empfehlungskarte zeigt den nächsten empfohlenen
 * Vorgang mit Gründen und explizitem [Vorgang übernehmen]; ohne Kandidaten
 * den erklärbaren Leerzustand. Die Empfehlungs-Engine selbst ist hier
 * gemockt (ihre Logik deckt NextBestWorkServiceTest ab).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardRecommendationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private verwaltungsassistent.web.planning.NextBestWorkService nextBestWorkService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "demo01@verwaltungsassistent.local", "Demo Benutzer", Set.of("USER"));

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

    @Test
    void dashboard_showsRecommendedCase_withReasonsAndTakeover() throws Exception {
        RecommendationResult result = new RecommendationResult("demo01@verwaltungsassistent.local",
                new Recommendation("c-1", "Fall Müller", 1, "Nächster empfohlener Vorgang",
                        "Hoch", List.of("Hohe Priorität", "Bürger wartet seit 3 Tagen"),
                        List.of(), "READY_TO_WORK", 15, true, Instant.now()),
                List.of(), List.of(), List.of());
        when(nextBestWorkService.recommendFor(any())).thenReturn(result);

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("VORGESCHLAGENER NÄCHSTER VORGANG")))
                .andExpect(content().string(containsString("Fall Müller")))
                .andExpect(content().string(containsString("Sehr hohe Empfehlung")))
                .andExpect(content().string(containsString("Vorgang übernehmen")))
                .andExpect(content().string(containsString("Warum dieser Vorgang?")))
                .andExpect(content().string(containsString("Hohe Priorität")))
                .andExpect(content().string(containsString("Geschätzter Restaufwand: ca. 15 Min.")))
                .andExpect(content().string(containsString("Empfehlungsdetails")));
    }

    @Test
    void dashboard_showsEmptyState_whenNoRecommendation() throws Exception {
        when(nextBestWorkService.recommendFor(any()))
                .thenReturn(new RecommendationResult("demo01@verwaltungsassistent.local", null,
                        List.of(), List.of(), List.of()));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("VORGESCHLAGENER NÄCHSTER VORGANG")))
                .andExpect(content().string(containsString("Derzeit keine geeigneten Vorgänge")));
    }
}
