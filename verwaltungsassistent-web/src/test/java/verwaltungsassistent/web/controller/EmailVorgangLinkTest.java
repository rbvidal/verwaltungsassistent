package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: Die Zeile „Vorgang: Gewerbeanmeldung (WS-73A0DC4F)" in der
 * E-Mail-Liste muss auf den BESTEHENDEN Fall navigieren (/cases/&lt;id&gt;,
 * gleicher Weg wie „Fall öffnen" inkl. Zugriffskontrolle) — nicht die
 * E-Mail-Seite neu laden (/emails?email=…). Der Link darf daher kein
 * verschachtelter Link innerhalb des E-Mail-Karten-Links sein.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVorgangLinkTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JpaIncomingEmailRepository incomingRepo;
    @MockBean
    private JpaEmailAnalysisRepository analysisRepo;
    @MockBean
    private WorkspaceService workspaceService;

    private final AuthenticatedUser employee = new AuthenticatedUser(
            UUID.randomUUID(), "demo01@verwaltungsassistent.local", "Anna Bergmann", Set.of("USER"));
    private final UUID emailId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        employee, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private IncomingEmailEntity emailWithWorkspace() {
        IncomingEmailEntity e = new IncomingEmailEntity(
                emailId, "Gewerbeanmeldung zum 1. September",
                "Claudia Fischer", "claudia.fischer@example.de",
                "Betreff: Gewerbeanmeldung\n\nSehr geehrte Damen und Herren, ich möchte ein Gewerbe anmelden.",
                Instant.now(), AddressedTo.GENERAL, "kontakt@verwaltungs-demo.de");
        e.setStatus(Status.NEW);
        e.setWorkspaceId(workspaceId);
        return e;
    }

    @Test
    void vorgangLabel_isALinkToTheCase_notToTheEmailPage() throws Exception {
        IncomingEmailEntity email = emailWithWorkspace();
        WorkspaceEntity workspace = new WorkspaceEntity(
                "WS-73A0DC4F", "Gewerbeanmeldung",
                "Gewerbeanmeldung zum 1. September – benötigte Unterlagen und Online-Termin.",
                "CASE", employee.email());
        when(incomingRepo.findVisibleQueueList(eq(employee.email()), eq(Status.NEW), eq(AddressedTo.GENERAL)))
                .thenReturn(List.of(email));
        when(workspaceService.findById(workspaceId.toString())).thenReturn(Optional.of(workspace));

        String html = mockMvc.perform(get("/emails"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        html = html.replaceAll("\\s+", " ");

        // 1. Die Vorgang-Zeile ist ein ECHTER Link auf den Fall (/cases/<id>).
        Pattern vorgangLink = Pattern.compile(
                "<a href=\"/cases/" + workspaceId + "\"[^>]*>Vorgang: Gewerbeanmeldung \\(WS-73A0DC4F\\)</a>");
        Matcher linkMatcher = vorgangLink.matcher(html);
        assertTrue(linkMatcher.find(),
                "the Vorgang line must link to the existing case route /cases/<workspaceId>");

        // 2. Der Karten-Link öffnet weiterhin die E-Mail im mittleren Panel.
        String emailHref = "href=\"/emails?email=" + emailId + "\"";
        assertTrue(html.contains(emailHref), "the card itself still opens the e-mail panel");

        // 3. Die Vorgang-Zeile liegt NICHT innerhalb des E-Mail-Karten-Links:
        //    zwischen dem href des Karten-Links und dem Vorgang-Link muss der
        //    Karten-Link bereits geschlossen sein. (Vorher lud der Klick auf
        //    „Vorgang: …" die E-Mail-Seite neu — /emails?email=…)
        int emailHrefIdx = html.indexOf(emailHref);
        int emailLinkClose = html.indexOf("</a>", emailHrefIdx);
        assertTrue(emailLinkClose >= 0 && emailLinkClose < linkMatcher.start(),
                "the Vorgang line must not be nested inside the e-mail card link");
    }
}
