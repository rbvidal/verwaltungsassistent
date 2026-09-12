package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for the processed/unprocessed/running e-mail states and the
 * "Diesem Fall zuordnen" association:
 * <ul>
 *   <li>unprocessed e-mail → primary "E-Mail analysieren";</li>
 *   <li>already analysed e-mail → stored result + secondary "E-Mail erneut
 *       analysieren" (never a "new e-mail" look);</li>
 *   <li>running analysis → disabled "Analyse läuft …";</li>
 *   <li>assign-case links the e-mail to an existing Fall/Vorgang.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailProcessingStateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private JpaIncomingEmailRepository incomingRepo;
    @MockBean
    private JpaEmailAnalysisRepository analysisRepo;
    @MockBean
    private WorkspaceService workspaceService;

    private final AuthenticatedUser admin = new AuthenticatedUser(
            UUID.randomUUID(), "admin@verwaltungsassistent.local", "Admin", Set.of("ADMIN"));
    private final UUID emailId = UUID.randomUUID();
    private final UUID analysisId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(incomingRepo.findByStatusOrderByReceivedAtDesc(any())).thenReturn(List.of());
        when(incomingRepo.findVisibleQueueList(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private IncomingEmailEntity email() {
        IncomingEmailEntity e = new IncomingEmailEntity(
                emailId, "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Erika Müller", "erika.mueller@example.de",
                "Betreff: Wohngeldantrag\n\nSehr geehrte Damen und Herren, ich habe meinen Antrag eingereicht.",
                Instant.now(), AddressedTo.GENERAL, "kontakt@verwaltungs-demo.de");
        return e;
    }

    private EmailAnalysisEntity analysisFor(IncomingEmailEntity email) throws Exception {
        EmailAnalysisEntity analysis = new EmailAnalysisEntity(
                analysisId, admin.email(), email.getText(),
                "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Wohngeld", "test", null, null);
        analysis.setResultJson(new ObjectMapper().writeValueAsString(
                new EmailController.EmailOutcome(
                        "Wohngeldantrag – welche Unterlagen fehlen noch?", "Wohngeld", null, null,
                        List.of(), List.of(), List.of("Mietvertrag / Mietbescheinigung"), List.of(),
                        null, null, null, List.of())));
        return analysis;
    }

    @Test
    void unprocessedEmail_showsPrimaryAnalyseButton() throws Exception {
        // Phase 2C.5: Der Operateur ist eine MITARBEITERIN — das Leitungs-Konto
        // erhält keine Analyse-Aktion (read-only). Employee principal setzen.
        var employee = new AuthenticatedUser(
                UUID.randomUUID(), "demo01@verwaltungsassistent.local", "Anna Bergmann", Set.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        employee, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(incomingRepo.findById(emailId)).thenReturn(Optional.of(email()));

        mockMvc.perform(get("/emails").param("email", emailId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">E-Mail analysieren</button>")))
                // Issue 1: kein "Verwaltungsassistent"-Branding mehr im E-Mail-Panel
                .andExpect(content().string(containsString("Reasoning-Pipeline")))
                .andExpect(content().string(not(containsString("Verwaltungsassistent-Pipeline"))))
                // Issue 5: die Suche ist ein NATIVES GET-Formular (kein htmx) —
                // ein htmx-Swap hätte die komplette Seite verschachtelt.
                .andExpect(content().string(containsString("action=\"/emails\"")))
                .andExpect(content().string(containsString("method=\"get\"")))
                .andExpect(content().string(not(containsString("hx-boost"))));
    }

    @Test
    void queueSearch_usesServerSideSearchAndKeepsQuery() throws Exception {
        mockMvc.perform(get("/emails").param("q", "Wohngeld"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wohngeld")));

        org.mockito.Mockito.verify(incomingRepo).searchNewByStatus(
                org.mockito.ArgumentMatchers.eq(verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status.NEW),
                org.mockito.ArgumentMatchers.eq("Wohngeld"));
    }

    @Test
    void unassignCase_clearsAssociation() throws Exception {
        IncomingEmailEntity email = email();
        email.setAnalysisId(analysisId);
        email.setWorkspaceId(caseId);
        EmailAnalysisEntity analysis = analysisFor(email);
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(analysis));
        when(incomingRepo.findAll()).thenReturn(List.of(email));

        mockMvc.perform(post("/emails/" + analysisId + "/unassign-case").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/emails?email=*"));

        assertEquals(null, email.getWorkspaceId(), "association is removed");
    }

    @Test
    void analysedEmail_showsStoredResultAndSecondaryReanalyseButton() throws Exception {
        IncomingEmailEntity email = email();
        email.setAnalysisId(analysisId);
        email.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
        when(incomingRepo.findById(emailId)).thenReturn(Optional.of(email));
        when(analysisRepo.existsById(analysisId)).thenReturn(true);
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(analysisFor(email)));

        mockMvc.perform(get("/emails").param("email", emailId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("E-Mail erneut analysieren")))
                .andExpect(content().string(containsString("Vorläufige E-Mail-Analyse")))
                .andExpect(content().string(not(containsString("E-Mail analysieren</button>"))));
    }

    @Test
    void associatedEmail_showsCaseInsteadOfCreateAction() throws Exception {
        IncomingEmailEntity email = email();
        email.setAnalysisId(analysisId);
        email.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
        email.setWorkspaceId(caseId);
        WorkspaceEntity workspace = new WorkspaceEntity(
                "WS-TEST", "Ummeldung nach Umzug",
                "Ummeldung nach Umzug – benötigte Unterlagen und Fristen klären.",
                "CASE", admin.email());
        when(incomingRepo.findById(emailId)).thenReturn(Optional.of(email));
        when(analysisRepo.existsById(analysisId)).thenReturn(true);
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(analysisFor(email)));
        when(workspaceService.findById(caseId.toString())).thenReturn(Optional.of(workspace));

        mockMvc.perform(get("/emails").param("email", emailId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zugeordneter Fall")))
                .andExpect(content().string(containsString(">Fall öffnen</a>")))
                .andExpect(content().string(containsString("Zuordnung aufheben")))
                .andExpect(content().string(not(containsString(">Vorgang aus E-Mail anlegen</button>"))))
                .andExpect(content().string(not(containsString(">Diesem Fall zuordnen</button>"))));
    }

    @Test
    void runningAnalysis_showsDisabledRunningButton() throws Exception {
        IncomingEmailEntity email = email();
        when(incomingRepo.findById(emailId)).thenReturn(Optional.of(email));
        JobProgressService.Job job = progressService.create(
                JobProgressService.Kind.ASSISTANT, "Wohngeldantrag – welche Unterlagen fehlen noch?");
        progressService.registerActive("email:" + emailId, job.jobId);
        try {
            mockMvc.perform(get("/emails").param("email", emailId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Analyse läuft")))
                    .andExpect(content().string(containsString("disabled=\"disabled\"")));
        } finally {
            progressService.unregisterActive("email:" + emailId, job.jobId);
        }
    }

    @Test
    void assignCase_linksEmailToWorkspace_andRecordsEvent() throws Exception {
        IncomingEmailEntity email = email();
        email.setAnalysisId(analysisId);
        EmailAnalysisEntity analysis = analysisFor(email);
        WorkspaceEntity workspace = new WorkspaceEntity(
                "WS-TEST", "Ummeldung nach Umzug",
                "Ummeldung nach Umzug – benötigte Unterlagen und Fristen klären.",
                "CASE", admin.email());
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(analysis));
        when(workspaceService.findById(caseId.toString())).thenReturn(Optional.of(workspace));
        when(incomingRepo.findAll()).thenReturn(List.of(email));

        mockMvc.perform(post("/emails/" + analysisId + "/assign-case")
                        .param("caseId", caseId.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/emails?email=*"));

        assertEquals(caseId, email.getWorkspaceId(), "e-mail is associated with the case");
        verify(workspaceService).addTimelineEvent(any(), any(), any(), any(), any(), any(), anyDouble(), anyBoolean());
    }

    @Test
    void assignCase_unknownCase_isRejected() throws Exception {
        IncomingEmailEntity email = email();
        email.setAnalysisId(analysisId);
        EmailAnalysisEntity analysis = analysisFor(email);
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(analysis));
        when(workspaceService.findById(caseId.toString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/emails/" + analysisId + "/assign-case")
                        .param("caseId", caseId.toString())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }
}
