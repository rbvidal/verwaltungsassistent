package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.auth.api.AuthenticatedUser;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Beispiele surface must present the benchmark groups with user-facing
 * German labels (e.g. "hr" → "Personal / Tarifrecht") instead of exposing
 * the internal identifiers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CorpusLabelTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser auditor = new AuthenticatedUser(
            UUID.randomUUID(), "auditor@example.com", "Auditor", Set.of("AUDITOR"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                auditor, null, List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void corpusList_showsGermanLabels() throws Exception {
        mockMvc.perform(get("/corpus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Personal / Tarifrecht")))
                .andExpect(content().string(containsString("Vergaberecht")))
                .andExpect(content().string(containsString("Dienstreiserecht")));
    }

    @Test
    void corpusDetail_usesGermanTitleAndDomainLabel() throws Exception {
        mockMvc.perform(get("/corpus/hr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Beispiele: Personal / Tarifrecht")))
                .andExpect(content().string(containsString("H01")))
                .andExpect(content().string(containsString("H01 — Personal / Tarifrecht")))
                .andExpect(content().string(not(containsString("Beispiele: hr"))));
    }

    @Test
    void corpusDetail_showsGermanStrategyLabelInsteadOfEngineName() throws Exception {
        mockMvc.perform(get("/corpus/hr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Regelbasiert")))
                .andExpect(content().string(not(containsString("RULE_ENGINE"))));
    }
}
