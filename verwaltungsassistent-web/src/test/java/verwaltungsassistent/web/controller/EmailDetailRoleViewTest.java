package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2D.14 — Rollen-Sicht der E-Mail-Detailseite für UNANALYSIERTE
 * E-Mails: Leitungs-/Superadmin-Konto (read-only) erhält keinen
 * "Zur Analyse öffnen"-Auslöser; die Sachbearbeitung behält ihren
 * operativen Pfad.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailDetailRoleViewTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JpaIncomingEmailRepository incomingRepo;

    @MockBean
    private JpaEmailAnalysisRepository analysisRepo;

    private final UUID emailId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        IncomingEmailEntity email = new IncomingEmailEntity(
                emailId, "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Erika Müller", "erika.mueller@example.de",
                "Betreff: Wohngeldantrag\n\nSehr geehrte Damen und Herren, ich habe meinen Antrag eingereicht.",
                Instant.now(), AddressedTo.GENERAL, null);
        when(incomingRepo.findById(emailId)).thenReturn(Optional.of(email));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(String email, Set<String> roles) {
        var user = new AuthenticatedUser(UUID.randomUUID(), email, "Test", roles);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    @Test
    void unanalysedEmail_adminSeesReadOnlyStateWithoutAction() throws Exception {
        login("admin@verwaltungsassistent.local", Set.of("ADMIN"));

        mockMvc.perform(get("/emails/" + emailId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Noch keine KI-Analyse vorhanden")))
                .andExpect(content().string(containsString(
                        "bisher nicht durch die Sachbearbeitung vollständig analysiert")))
                .andExpect(content().string(not(containsString("Zur Analyse öffnen"))));
    }

    @Test
    void unanalysedEmail_employeeKeepsAnalysisAction() throws Exception {
        login("demo01@verwaltungsassistent.local", Set.of("USER"));

        mockMvc.perform(get("/emails/" + emailId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Noch keine Analyse")))
                .andExpect(content().string(containsString("Zur Analyse öffnen")));
    }
}
