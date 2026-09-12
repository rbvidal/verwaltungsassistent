package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentCategory;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceDocumentDto;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
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
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @Autowired
    private JpaEmailAnalysisRepository emailAnalysisRepository;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final WorkspaceEntity case1 = createEntity("ws-1", "Fall Müller", WorkspaceStatus.ACTIVE, WorkspacePhase.ANALYSIS, 5);
    private final WorkspaceEntity case2 = createEntity("ws-2", "Fall Schmidt", WorkspaceStatus.ACTIVE, WorkspacePhase.INGESTION, 2);
    private final WorkspaceEntity case3 = createEntity("ws-3", "Fall Weber", WorkspaceStatus.CLOSED, WorkspacePhase.COMPLETE, 12);

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of(case1, case2, case3));

        when(workspaceService.toDto(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> {
                    WorkspaceEntity e = inv.getArgument(0);
                    return new WorkspaceDto(
                            e.getId(), e.getWorkspaceCode(), e.getName() != null ? e.getName() : "Fall",
                            e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                            e.getOwnerId(), Map.of(),
                            List.of(), List.of(),
                            e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now(),
                            e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.now());
                });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Full page ---

    @Test
    void caseList_authenticated_returnsFullPage() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/list"))
                .andExpect(model().attributeExists("cases"))
                .andExpect(model().attributeExists("headers"))
                .andExpect(model().attributeExists("page"))
                .andExpect(model().attributeExists("totalPages"))
                .andExpect(model().attribute("activeSection", "cases"));
    }

    @Test
    void caseList_displaysCaseNames() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall Müller")))
                .andExpect(content().string(containsString("Fall Schmidt")))
                .andExpect(content().string(containsString("Fall Weber")));
    }

    @Test
    void caseList_displaysGermanStatusLabels() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Aktiv")))
                .andExpect(content().string(containsString("Geschlossen")));
    }

    @Test
    void caseList_hasCreateButton() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Neuer Fall")));
    }

    @Test
    void caseList_hasFilterSidebar() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Aktiv")))
                .andExpect(content().string(containsString("Entwurf")))
                .andExpect(content().string(containsString("Geschlossen")))
                .andExpect(content().string(containsString("Archiviert")));
    }

    @Test
    void caseList_hasSearchBox() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("search-box")))
                .andExpect(content().string(containsString("type=\"search\"")));
    }

    // --- HTMX fragment ---

    @Test
    void caseList_htmxRequest_returnsFragment() throws Exception {
        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/fragments :: caseTable"));
    }

    @Test
    void caseList_htmxFragment_containsTableRows() throws Exception {
        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall Müller")))
                .andExpect(content().string(containsString("data-table")));
    }

    @Test
    void caseList_htmxFragment_noPaginationWhenSinglePage() throws Exception {
        // With 3 cases and page size 10, pagination should not appear
        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/fragments :: caseTable"));
    }

    // --- Filtering ---

    @Test
    void caseList_filterByStatus_activeOnly() throws Exception {
        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of(case1, case2)); // only active ones

        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/list :: caseList"))
                .andExpect(content().string(containsString("Fall Müller")))
                .andExpect(content().string(containsString("Fall Schmidt")));
    }

    // --- Search ---

    @Test
    void caseList_searchByName() throws Exception {
        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true")
                        .param("q", "Müller"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall Müller")));
    }

    // --- Empty state ---

    @Test
    void caseList_emptyDatabase_rendersEmptyState() throws Exception {
        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of());

        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Fälle")))
                .andExpect(content().string(containsString("Ersten Fall erstellen")));
    }

    @Test
    void caseList_emptyDatabase_htmxFragment_returnsEmptyState() throws Exception {
        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of());

        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Fälle")));
    }

    // --- Delete ---

    @Test
    void deleteCase_returnsFragment() throws Exception {
        when(workspaceService.findById(anyString()))
                .thenReturn(java.util.Optional.of(case1));

        mockMvc.perform(delete("/cases/" + UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/list :: caseList"));
    }

    @Test
    void deleteCase_notFound_returns404() throws Exception {
        when(workspaceService.findById(anyString()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(delete("/cases/" + UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // --- Access control ---

    @Test
    void caseList_unauthenticated_redirectsToLogin() throws Exception {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();

        mockMvc.perform(get("/cases"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    // --- Accessibility ---

    @Test
    void caseList_hasSortableHeaders() throws Exception {
        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-sort=\"none\"")));
    }

    @Test
    void caseList_hasDeleteConfirmationDialog() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Löschen bestätigen")))
                .andExpect(content().string(containsString("aria-modal=\"true\"")));
    }

    // --- Pagination ---

    @Test
    void caseList_showsPaginationWhenMultiplePages() throws Exception {
        // Create 25 cases to ensure multiple pages (page size 10)
        List<WorkspaceEntity> manyCases = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            manyCases.add(createEntity("ws-" + i, "Fall " + i, WorkspaceStatus.ACTIVE, WorkspacePhase.SETUP, i));
        }
        when(workspaceService.findByOwner(anyString())).thenReturn(manyCases);

        mockMvc.perform(get("/cases")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Pagination\"")));
    }

    // --- Status filtering ---

    @Test
    void caseList_statusFilter_DRAFT_showsOnlyDraftCases() throws Exception {
        WorkspaceEntity draft = createEntity("ws-draft", "Keller umstellen", WorkspaceStatus.DRAFT, WorkspacePhase.SETUP, 0);
        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of(case1, case2, case3, draft));

        mockMvc.perform(get("/cases").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentStatus", "DRAFT"))
                .andExpect(content().string(containsString("Keller umstellen")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Fall Müller"))));
    }

    @Test
    void caseList_statusFilter_emptyStatus_showsEmptyState() throws Exception {
        when(workspaceService.findByOwner(anyString()))
                .thenReturn(List.of(case1, case2, case3));

        mockMvc.perform(get("/cases").param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentStatus", "ARCHIVED"))
                .andExpect(content().string(containsString("Keine Fälle")));
    }

    @Test
    void caseList_hxStatusRequest_returnsCaseListFragment() throws Exception {
        mockMvc.perform(get("/cases").param("status", "DRAFT")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/list :: caseList"))
                .andExpect(model().attribute("currentStatus", "DRAFT"));
    }

    @Test
    void caseList_hxSortRequest_returnsCaseTableFragment() throws Exception {
        mockMvc.perform(get("/cases").param("sort", "name")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/fragments :: caseTable"));
    }

    /**
     * E-Mail → Fall anlegen: the case is created from the analyzed e-mail,
     * keeps an ID-based reference to it (phase data) and records the
     * "E-Mail eingegangen" timeline event.
     */
    @Test
    void createCaseFromEmail_linksCaseToEmailAndRecordsTimelineEvent() throws Exception {
        UUID analysisId = UUID.randomUUID();
        emailAnalysisRepository.save(new EmailAnalysisEntity(
                analysisId, testUser.email(),
                "Ich bin gerade nach Berlin umgezogen. Gibt es Umzugsgeld für mich?",
                "Umzugsgeld nach Umzug", "An- / Ummeldung", "9.9.9-test",
                "{}", Instant.now()));

        WorkspaceEntity created = createEntity("ws-mail", "Umzugsgeld nach Umzug",
                WorkspaceStatus.ACTIVE, WorkspacePhase.SETUP, 0);
        created.setId(UUID.randomUUID().toString());
        when(workspaceService.createWorkspace(any())).thenReturn(created);

        mockMvc.perform(post("/emails/" + analysisId + "/create-case").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + created.getId()));

        // The saved case carries the ID-based reference to the originating e-mail.
        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService, atLeastOnce()).save(captor.capture());
        WorkspaceEntity saved = captor.getValue();
        assertTrue(saved.getPhaseData() != null && saved.getPhaseData().contains(analysisId.toString()),
                "phase data must reference the originating e-mail analysis id");
        assertTrue(saved.getPhaseData().contains("E-Mail vom"),
                "phase data must carry the incoming e-mail date");

        // The timeline records the incoming e-mail event.
        verify(workspaceService).addTimelineEvent(eq(created.getId().toString()), any(java.time.LocalDate.class),
                eq("E-Mail eingegangen"), any(), eq(reasoning.workspace.model.TimelineEventType.COMMUNICATION),
                any(), anyDouble(), anyBoolean());
    }

    @Test
    void createCaseFromEmail_unknownOrForeignAnalysis_returns404() throws Exception {
        mockMvc.perform(post("/emails/" + UUID.randomUUID() + "/create-case").with(csrf()))
                .andExpect(status().isNotFound());

        // Another user's analysis must not be visible.
        UUID foreignId = UUID.randomUUID();
        emailAnalysisRepository.save(new EmailAnalysisEntity(
                foreignId, "other@example.com", "text", "Betreff", "topic", "9.9.9-test",
                "{}", Instant.now()));
        mockMvc.perform(post("/emails/" + foreignId + "/create-case").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // --- Helpers ---

    private static WorkspaceEntity createEntity(String id, String name,
                                                 WorkspaceStatus status, WorkspacePhase phase, int docCount) {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-" + id.toUpperCase(), name, "Description for " + name,
                "GENERAL", "user@example.com");
        try {
            var idField = WorkspaceEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, UUID.nameUUIDFromBytes(id.getBytes()));
        } catch (Exception ignored) {
        }
        entity.setStatus(status);
        entity.setPhase(phase);
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
