package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.auth.model.Role;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Object-level authorization for cases: an authenticated user may access
 * only their own case (detail page and all sub-actions); administrators
 * may access everything; foreign case IDs are denied with 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CaseOwnershipTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private WorkspaceEntity caseA;
    private WorkspaceEntity caseB;

    private final AuthenticatedUser userA = new AuthenticatedUser(
            UUID.randomUUID(), "a@owner.test", "Besitzer A", Set.of("USER"));
    private final AuthenticatedUser userB = new AuthenticatedUser(
            UUID.randomUUID(), "b@owner.test", "Besitzer B", Set.of("USER"));
    private final AuthenticatedUser admin = new AuthenticatedUser(
            UUID.randomUUID(), "admin@owner.test", "Admin", Set.of("ADMIN"));

    @BeforeEach
    void setUp() {
        caseA = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                "Fall A", "Beschreibung A", "CASE", userA.email()));
        caseB = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                "Fall B", "Beschreibung B", "CASE", userB.email()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(AuthenticatedUser u) {
        var auth = new UsernamePasswordAuthenticationToken(u, null,
                u.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String id(WorkspaceEntity e) {
        return e.getId();
    }

    // ── Detail page ──

    @Test
    void owner_canOpenOwnCase() throws Exception {
        authenticateAs(userA);
        mockMvc.perform(get("/cases/" + id(caseA))).andExpect(status().isOk());
    }

    @Test
    void userA_cannotOpenUserBCase() throws Exception {
        authenticateAs(userA);
        mockMvc.perform(get("/cases/" + id(caseB))).andExpect(status().isForbidden());
    }

    @Test
    void userB_cannotOpenUserACase() throws Exception {
        authenticateAs(userB);
        mockMvc.perform(get("/cases/" + id(caseA))).andExpect(status().isForbidden());
    }

    @Test
    void admin_canOpenAnyCase() throws Exception {
        authenticateAs(admin);
        mockMvc.perform(get("/cases/" + id(caseA))).andExpect(status().isOk());
        mockMvc.perform(get("/cases/" + id(caseB))).andExpect(status().isOk());
    }

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/cases/" + id(caseA))).andExpect(status().is3xxRedirection());
    }

    @Test
    void unknownCase_returnsNotFound() throws Exception {
        authenticateAs(userA);
        mockMvc.perform(get("/cases/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ── Sub-actions (GET) ──

    @Test
    void owner_canOpenOwnSubActions() throws Exception {
        authenticateAs(userA);
        String base = "/cases/" + id(caseA);
        mockMvc.perform(get(base + "/documents")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/timeline")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/notes")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/checklist")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/attach")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/decision")).andExpect(status().isOk());
    }

    @Test
    void user_cannotOpenForeignSubActions() throws Exception {
        authenticateAs(userA);
        String base = "/cases/" + id(caseB);
        mockMvc.perform(get(base + "/documents")).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/timeline")).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/notes")).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/checklist")).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/attach")).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/decision")).andExpect(status().isForbidden());
    }

    @Test
    void admin_canOpenForeignSubActions() throws Exception {
        authenticateAs(admin);
        String base = "/cases/" + id(caseB);
        mockMvc.perform(get(base + "/documents")).andExpect(status().isOk());
        mockMvc.perform(get(base + "/decision")).andExpect(status().isOk());
    }

    // ── Sub-actions (POST/PUT/DELETE) ──

    @Test
    void user_cannotPostToForeignCase() throws Exception {
        authenticateAs(userA);
        String base = "/cases/" + id(caseB);
        mockMvc.perform(post(base + "/notes").with(csrf()).param("text", "Notiz"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/phase").with(csrf()).param("direction", "advance"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/decision/analyze").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void owner_canPostToOwnCase() throws Exception {
        authenticateAs(userA);
        String base = "/cases/" + id(caseA);
        mockMvc.perform(post(base + "/notes").with(csrf()).param("text", "Notiz"))
                .andExpect(status().isOk());
    }

    @Test
    void user_cannotDeleteForeignCase() throws Exception {
        authenticateAs(userA);
        mockMvc.perform(delete("/cases/" + id(caseB)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void user_cannotPollForeignAnalysisProgress() throws Exception {
        authenticateAs(userA);
        mockMvc.perform(get("/cases/" + id(caseB) + "/decision/analyze/progress/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
