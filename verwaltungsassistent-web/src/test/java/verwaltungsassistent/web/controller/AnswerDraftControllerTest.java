package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end draft flow tests: generate from the persisted decision analysis,
 * save edits, reopen (draft persists in the case artifact store), no draft
 * without an analysis, and PDF export of the saved draft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AnswerDraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity testEntity;
    private WorkspaceAnalysisRunEntity run;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        testEntity = new WorkspaceEntity("WS-007", "Reisepass – Minderjährige",
                "Testfall", "GENERAL", testUser.email());
        testEntity.setPhaseData("{}");

        run = new WorkspaceAnalysisRunEntity(UUID.randomUUID(), UUID.fromString(caseId), 2,
                "COMPLETED", testUser.email(), Instant.now().minusSeconds(60));
        run.setCompletedAt(Instant.now());

        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(new WorkspaceDto(
                caseId, "WS-007", "Reisepass – Minderjährige", "Testfall", "GENERAL",
                null, null, testUser.id().toString(), Map.of(),
                List.of(), List.of(), Instant.now().minusSeconds(60), Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("decisionAnswer", "KURZANTWORT: Die Ausstellung eines Reisepasses erfordert "
                + "die Zustimmung der Sorgeberechtigten.");
        analysis.put("grounded", false);
        analysis.put("primaryFindings", List.of(Map.of(
                "label", "Zustimmung erforderlich",
                "description", "Zustimmung der Sorgeberechtigten nachweisen.")));
        analysis.put("authorities", List.of(Map.of("title", "Passgesetz", "reference", "§ 5 PassG")));
        analysis.put("missingDocs", List.of("Einverständniserklärung"));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(analysis);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void draftPage_withoutDraft_showsCreateAction() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Antwortentwurf erstellen")))
                .andExpect(content().string(not(containsString("draft-text"))));
    }

    /** Phase 2D.14: Ohne Analyse behält die Sachbearbeitung den Hinweis mit
     *  dem operativen "Entscheidung vorbereiten"-Link. */
    @Test
    void draftPage_withoutAnalysis_employeeKeepsPrepareLink() throws Exception {
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("noch keine Entscheidungsvorlage")))
                .andExpect(content().string(containsString("href=\"/cases/" + caseId + "/decision\"")))
                .andExpect(content().string(containsString("Entscheidung vorbereiten")));
    }

    /** Phase 2D.14: Leitungs-/Superadmin-Konto (read-only) erhält im Entwurfs-
     *  Leerzustand NUR einen neutralen Hinweis — kein operativer Link. */
    @Test
    void draftPage_withoutAnalysis_supervisoryShowsNeutralStateWithoutPrepareLink() throws Exception {
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());
        var admin = new AuthenticatedUser(
                UUID.randomUUID(), "leitung@verwaltungsassistent.local", "Leitung", Set.of("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("noch keine Entscheidungsvorlage")))
                .andExpect(content().string(containsString(
                        "noch nicht durch die Sachbearbeitung durchgeführt")))
                .andExpect(content().string(not(containsString("Entscheidung vorbereiten"))));
    }

    @Test
    void generate_createsDraftFromAnalysis_andPersistsIt() throws Exception {
        mockMvc.perform(post("/cases/" + caseId + "/draft/generate").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId + "/draft"));

        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).save(captor.capture());
        String phaseData = captor.getValue().getPhaseData();
        assertTrue(phaseData.contains("\"answerDraft\""), "draft must be persisted");
        assertTrue(phaseData.contains("Zustimmung der Sorgeberechtigten"),
                "draft text must come from the decision proposal");
        assertTrue(phaseData.contains("\"analysisVersion\":2"), "analysis version must be recorded");
        assertTrue(phaseData.contains("\"limitedCoverage\":true"),
                "limited coverage must be persisted with the draft");
    }

    @Test
    void generate_withoutAnalysis_rejectsCleanly() throws Exception {
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/cases/" + caseId + "/draft/generate").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reopen_draftPage_showsPersistedDraft() throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", "Sehr geehrte Damen und Herren,\n\nAntworttext aus dem Entwurf.");
        draft.put("analysisVersion", 2);
        draft.put("grounded", false);
        draft.put("limitedCoverage", true);
        draft.put("createdAt", "21.08.2026 10:00");
        draft.put("updatedAt", "21.08.2026 10:05");
        draft.put("createdBy", "user@example.com");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", draft);
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Antworttext aus dem Entwurf")))
                .andExpect(content().string(containsString("Speichern")))
                .andExpect(content().string(containsString("Entwurf vorhanden")))
                .andExpect(content().string(containsString("Eingeschränkte Quellenlage")));
    }

    @Test
    void save_persistsEditedText() throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", "Alt");
        draft.put("analysisVersion", 2);
        draft.put("grounded", true);
        draft.put("limitedCoverage", false);
        draft.put("createdAt", "21.08.2026 10:00");
        draft.put("updatedAt", "21.08.2026 10:00");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", draft);
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));

        mockMvc.perform(post("/cases/" + caseId + "/draft/save")
                        .param("draftText", "Bearbeiteter Text").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId + "/draft?saved=1"));

        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).save(captor.capture());
        assertTrue(captor.getValue().getPhaseData().contains("Bearbeiteter Text"),
                "edited text must be persisted");
    }

    @Test
    void draftPage_withSavedFlag_showsSuccessMessage() throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", "Entwurfstext");
        draft.put("analysisVersion", 2);
        draft.put("grounded", true);
        draft.put("limitedCoverage", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", draft);
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));

        mockMvc.perform(get("/cases/" + caseId + "/draft").param("saved", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entwurf wurde gespeichert.")));
    }

    @Test
    void exportPdf_withoutDraft_rejectsCleanly() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/draft/export-pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportPdf_withDraft_returnsPdf() throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", "Sehr geehrte Damen und Herren,\n\nAntworttext.");
        draft.put("analysisVersion", 2);
        draft.put("grounded", true);
        draft.put("limitedCoverage", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", draft);
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));

        mockMvc.perform(get("/cases/" + caseId + "/draft/export-pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(result -> assertEquals("%PDF",
                        new String(result.getResponse().getContentAsByteArray(), 0, 4,
                                java.nio.charset.StandardCharsets.US_ASCII)));
    }

    private void seedDraft(String text, String createdAt, String updatedAt) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", text);
        draft.put("analysisVersion", 2);
        draft.put("grounded", true);
        draft.put("limitedCoverage", false);
        draft.put("createdAt", createdAt);
        draft.put("updatedAt", updatedAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", draft);
        try {
            testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Phase 2D.5: sichtbarer Entwurfs-Zustand, Prüfvermerk, Leitung lesend ──

    @Test
    void review_marksDraftAsReviewed_andRedirects() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:00");

        mockMvc.perform(post("/cases/" + caseId + "/draft/review").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId + "/draft"));

        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).save(captor.capture());
        String phaseData = captor.getValue().getPhaseData();
        assertTrue(phaseData.contains("\"reviewedBy\":\"user@example.com\""),
                "the local review verdict must be persisted with the draft");
    }

    @Test
    void review_withoutDraft_rejectsCleanly() throws Exception {
        mockMvc.perform(post("/cases/" + caseId + "/draft/review").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void draftPage_editedDraft_showsManualStateReviewAction_andDecisionProvenance() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:05");

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nur Entwurf · kein Versand")))
                .andExpect(content().string(containsString("Manuell bearbeitet")))
                .andExpect(content().string(not(containsString("KI-Entwurf"))))
                .andExpect(content().string(containsString("/cases/" + caseId + "/draft/review")))
                .andExpect(content().string(containsString("Entscheidung &amp; Belege ansehen")))
                .andExpect(content().string(containsString("Entscheidung der Sachbearbeitung noch nicht dokumentiert")));
    }

    @Test
    void draftPage_generatedDraft_showsKiDraftState() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:00");

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("KI-Entwurf")))
                .andExpect(content().string(not(containsString("Manuell bearbeitet"))));
    }

    @Test
    void draftPage_reviewedDraft_showsVerdict_andHidesReviewAction() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:05");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", Map.of(
                "text", "Entwurfstext",
                "analysisVersion", 2,
                "grounded", true,
                "limitedCoverage", false,
                "createdAt", "21.08.2026 10:00",
                "updatedAt", "21.08.2026 10:05",
                "createdBy", "user@example.com",
                "edited", true,
                "reviewedAt", "21.08.2026 11:00",
                "reviewedBy", "erika@example.com"));
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entwurf geprüft · lokal")))
                .andExpect(content().string(containsString("Geprüft von erika@example.com am 21.08.2026 11:00")))
                .andExpect(content().string(not(containsString("/cases/" + caseId + "/draft/review"))));
    }

    // ── Phase 2D.7: abgeschlossener Vorgang — Entwurf nur noch lesend ──

    @Test
    void generate_onClosedCase_redirectsWithError_andCreatesNothing() throws Exception {
        testEntity.setStatus(reasoning.common.model.WorkspaceStatus.CLOSED);

        mockMvc.perform(post("/cases/" + caseId + "/draft/generate").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId + "/draft"))
                .andExpect(flash().attributeExists("draftError"));
    }

    @Test
    void save_onClosedCase_redirectsWithError() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:00");
        testEntity.setStatus(reasoning.common.model.WorkspaceStatus.CLOSED);

        mockMvc.perform(post("/cases/" + caseId + "/draft/save")
                        .param("draftText", "Geändert").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId + "/draft"))
                .andExpect(flash().attributeExists("draftError"));
    }

    @Test
    void draftPage_onClosedCase_isReadOnly_andExplainsReopenPath() throws Exception {
        seedDraft("Entwurfstext", "21.08.2026 10:00", "21.08.2026 10:00");
        testEntity.setStatus(reasoning.common.model.WorkspaceStatus.CLOSED);

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entwurfstext")))
                .andExpect(content().string(containsString("nur noch lesend angezeigt")))
                .andExpect(content().string(containsString("Wiederaufnahme")))
                .andExpect(content().string(not(containsString(">Speichern<"))))
                .andExpect(content().string(not(containsString("/cases/" + caseId + "/draft/review"))));
    }

    @Test
    void draftPage_supervisory_isReadOnly_andShowsReviewedState() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerDraft", Map.of(
                "text", "Entwurfstext",
                "analysisVersion", 2,
                "grounded", true,
                "limitedCoverage", false,
                "createdAt", "21.08.2026 10:00",
                "updatedAt", "21.08.2026 10:00",
                "createdBy", "user@example.com",
                "edited", false,
                "reviewedAt", "21.08.2026 11:00",
                "reviewedBy", "erika@example.com"));
        testEntity.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(), "leitung@verwaltungsassistent.local", "Leitung",
                        Set.of("ADMIN")),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(get("/cases/" + caseId + "/draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entwurfstext")))
                .andExpect(content().string(containsString("Entwurf geprüft · lokal")))
                .andExpect(content().string(containsString("Leitungs-Konto: Entwürfe werden nur lesend eingesehen.")))
                .andExpect(content().string(not(containsString(">Speichern<"))))
                .andExpect(content().string(not(containsString("/cases/" + caseId + "/draft/review"))));
    }
}
