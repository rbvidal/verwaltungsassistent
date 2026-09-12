package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /assistant?caseId=<id> route must establish real case context: the
 * case name/code/documents become visible, invalid or inaccessible cases are
 * reported explicitly, and the route without caseId keeps the existing
 * global assistant behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AssistantCaseContextTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private WorkspaceService workspaceService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity testEntity;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        testEntity = new WorkspaceEntity("WS-042", "Reisepass – Minderjährige",
                "Testfall", "GENERAL", testUser.email());
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(testEntity));
        when(workspaceService.findById("nonexistent")).thenReturn(Optional.empty());
        when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assistant_withoutCaseId_keepsGlobalBehavior() throws Exception {
        mockMvc.perform(get("/assistant"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stellen Sie eine Frage")))
                .andExpect(content().string(not(containsString("case-context"))));
    }

    @Test
    void assistant_withValidCaseId_showsCaseContext() throws Exception {
        var link = new WorkspaceDocumentLinkEntity(
                UUID.randomUUID().toString(), caseId, UUID.randomUUID().toString(), null,
                reasoning.common.model.DocumentCategory.CONTRACT, "general");
        link.setUploadedAt(Instant.now());
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link));

        mockMvc.perform(get("/assistant").param("caseId", caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reisepass – Minderjährige")))
                .andExpect(content().string(containsString("WS-042")))
                .andExpect(content().string(containsString("1 Dokument(e) im Fall")))
                .andExpect(content().string(not(containsString("Der Fall konnte nicht geladen werden"))));
    }

    @Test
    void assistant_withUnknownCaseId_showsExplicitError() throws Exception {
        mockMvc.perform(get("/assistant").param("caseId", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Der Fall konnte nicht geladen werden. Der Assistent arbeitet ohne Fallbezug.")))
                .andExpect(content().string(not(containsString("case-context"))));
    }

    @Test
    void assistant_withInaccessibleCase_showsExplicitError() throws Exception {
        WorkspaceEntity foreign = new WorkspaceEntity("WS-999", "Fremder Fall",
                "nicht erlaubt", "GENERAL", "other@example.com");
        when(workspaceService.findById("foreign")).thenReturn(Optional.of(foreign));

        mockMvc.perform(get("/assistant").param("caseId", "foreign"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Der Fall konnte nicht geladen werden. Der Assistent arbeitet ohne Fallbezug.")));
    }
}
