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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * German UI terminology regression: the navigation/action labels of the
 * German application must say "Assistent" — never the English "Assistant"
 * (technical identifiers and URLs like /assistant stay untouched).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class GermanUiLabelsTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void assistantNavAndActionsUseGermanLabel() throws Exception {
        String html = mockMvc.perform(get("/assistant"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // German label present in the vertical navigation
        assertTrue(html.contains(">Assistent</span>"),
                "the sidebar nav must show 'Assistent'");
        // the English label must not appear as visible text anywhere
        assertFalse(html.contains(">Assistant<"), "no visible 'Assistant' nav label");
        int idx = html.indexOf("Assistant öffnen");
        if (idx >= 0) {
            System.out.println("FOUND 'Assistant öffnen' at " + idx + ": "
                    + html.substring(Math.max(0, idx - 120), idx + 60).replace("\n", " "));
        }
        assertFalse(idx >= 0, "no visible 'Assistant öffnen' action");
        // the technical URL stays untouched
        assertTrue(html.contains("href=\"/assistant\""), "the /assistant route must stay intact");
    }

    @Test
    void dashboardQuickActionUsesGermanLabel() throws Exception {
        String html = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Assistent öffnen"), "quick action must say 'Assistent öffnen'");
        assertFalse(html.contains("AI Assistant öffnen"), "no English quick action label");
    }
}
