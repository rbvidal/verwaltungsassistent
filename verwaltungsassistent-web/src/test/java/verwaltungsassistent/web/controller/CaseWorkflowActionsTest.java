package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fälle workflow actions: archive, restore, delegation (authorization,
 * existing active users only) and the Bearbeitungshistorie page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CaseWorkflowActionsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private UserAccountRepository userAccountRepository;

    private final AuthenticatedUser admin = new AuthenticatedUser(
            UUID.randomUUID(), "admin@example.com", "Admin", Set.of("ADMIN"));

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity testEntity;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        testEntity = new WorkspaceEntity("WS-009", "Testfall", "Beschreibung", "CASE",
                "admin@example.com");
        testEntity.setStatus(WorkspaceStatus.ACTIVE);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(new WorkspaceDto(
                caseId, "WS-009", "Testfall", "Beschreibung", "CASE",
                WorkspaceStatus.ACTIVE, null, "admin@example.com", Map.of(),
                List.of(), List.of(), Instant.now().minusSeconds(60), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void archive_setsArchivedStatus_andRecordsHistoryEvent() throws Exception {
        mockMvc.perform(post("/cases/" + caseId + "/archive").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId));

        assertEquals(WorkspaceStatus.ARCHIVED, testEntity.getStatus());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).addTimelineEvent(any(), any(), title.capture(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals("Fall archiviert", title.getValue());
    }

    @Test
    void restore_afterArchive_setsActiveStatus() throws Exception {
        testEntity.setStatus(WorkspaceStatus.ARCHIVED);
        mockMvc.perform(post("/cases/" + caseId + "/restore").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertEquals(WorkspaceStatus.ACTIVE, testEntity.getStatus());
    }

    @Test
    void assign_asAdmin_changesOwnerAndRecordsHistory() throws Exception {
        UserAccountEntity assignee = new UserAccountEntity(
                "kollege@example.com", "$2a$10$abc", "Kollege", Set.of(
                        reasoning.auth.model.Role.USER));
        when(userAccountRepository.findByEmail("kollege@example.com")).thenReturn(Optional.of(assignee));

        mockMvc.perform(post("/cases/" + caseId + "/assign")
                        .param("assignee", "kollege@example.com").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cases/" + caseId));

        assertEquals("kollege@example.com", testEntity.getOwnerId());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).addTimelineEvent(any(), any(), title.capture(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals("Fall übergeben", title.getValue());
    }

    @Test
    void assign_unknownUser_isRejected() throws Exception {
        when(userAccountRepository.findByEmail("niemand@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/cases/" + caseId + "/assign")
                        .param("assignee", "niemand@example.com").with(csrf()))
                .andExpect(status().isBadRequest());
        assertEquals("admin@example.com", testEntity.getOwnerId());
    }

    @Test
    void assign_inactiveUser_isRejected() throws Exception {
        UserAccountEntity locked = new UserAccountEntity(
                "locked@example.com", "$2a$10$abc", "Gesperrt", Set.of(
                        reasoning.auth.model.Role.USER));
        locked.setLocked(true);
        when(userAccountRepository.findByEmail("locked@example.com")).thenReturn(Optional.of(locked));

        mockMvc.perform(post("/cases/" + caseId + "/assign")
                        .param("assignee", "locked@example.com").with(csrf()))
                .andExpect(status().isBadRequest());
        assertEquals("admin@example.com", testEntity.getOwnerId());
    }

    @Test
    void assign_foreignNonAdmin_isForbidden() {
        AuthenticatedUser foreign = new AuthenticatedUser(
                UUID.randomUUID(), "fremd@example.com", "Fremd", Set.of("USER"));
        var auth = new UsernamePasswordAuthenticationToken(
                foreign, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            mockMvc.perform(post("/cases/" + caseId + "/assign")
                            .param("assignee", "kollege@example.com").with(csrf()))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("admin@example.com", testEntity.getOwnerId());
    }

    @Test
    void historyPage_rendersCaseHistory() throws Exception {
        mockMvc.perform(get("/cases/" + caseId + "/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bearbeitungshistorie")))
                .andExpect(content().string(containsString("Vorgang angelegt")));
    }
}
