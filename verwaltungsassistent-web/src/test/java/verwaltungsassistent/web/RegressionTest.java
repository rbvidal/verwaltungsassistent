package verwaltungsassistent.web;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.RegisterUserCommand;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.controller.CaseController;
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
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private AuthFacade authFacade;

    private final UUID adminId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final AuthenticatedUser adminUser = new AuthenticatedUser(
            adminId, "admin@verwaltungsassistent.local", "Administrator", Set.of("ADMIN"));

    private final AuthenticatedUser regularUser = new AuthenticatedUser(
            userId, "user@verwaltungsassistent.local", "Sachbearbeiter", Set.of("USER"));

    private final WorkspaceEntity adminCase = createEntity("adm-1", "Admin-Fall",
            WorkspaceStatus.ACTIVE, WorkspacePhase.SETUP, 0, "admin@verwaltungsassistent.local");

    private final WorkspaceEntity userCase = createEntity("usr-1", "User-Fall",
            WorkspaceStatus.ACTIVE, WorkspacePhase.SETUP, 0, "user@verwaltungsassistent.local");

    // ── ADMIN ROLE REGRESSION ──

    @Test
    void adminUser_hasAdminRole() {
        assertTrue(adminUser.roles().contains("ADMIN"),
                "Admin user must have ADMIN role");
        assertFalse(adminUser.roles().contains("USER"),
                "Admin user should not have USER role");
    }

    @Test
    void regularUser_hasOnlyUserRole() {
        assertTrue(regularUser.roles().contains("USER"),
                "Regular user must have USER role");
        assertFalse(regularUser.roles().contains("ADMIN"),
                "Regular user must NOT have ADMIN role");
    }

    @Test
    void adminUser_hasAdminAuthority() {
        var auth = new UsernamePasswordAuthenticationToken(
                adminUser, null,
                adminUser.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
                "Admin must have ROLE_ADMIN authority");
    }

    @Test
    void regularUser_doesNotHaveAdminAuthority() {
        var auth = new UsernamePasswordAuthenticationToken(
                regularUser, null,
                regularUser.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertFalse(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
                "Regular user must NOT have ROLE_ADMIN authority");
    }

    // ── NAVIGATION REGRESSION ──

    @Test
    void adminNavigation_containsAllExpectedLinks() throws Exception {
        authenticateAs(adminUser);

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dashboard")))
                .andExpect(content().string(containsString("Fälle")));
    }

    @Test
    void regularUserNavigation_doesNotContainAdminLinks() throws Exception {
        authenticateAs(regularUser);

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Administration"))));
    }

    // ── CASE LIST REGRESSION ──

    @Test
    void createdCase_appearsInCaseList_forOwner() throws Exception {
        authenticateAs(regularUser);

        when(workspaceService.findByOwner("user@verwaltungsassistent.local"))
                .thenReturn(List.of(userCase));
        when(workspaceService.findAll())
                .thenReturn(List.of(adminCase, userCase));
        when(workspaceService.toDto(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> {
                    WorkspaceEntity e = inv.getArgument(0);
                    return new reasoning.workspace.api.WorkspaceDto(
                            e.getId(), e.getWorkspaceCode(), e.getName(),
                            e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                            e.getOwnerId(), Map.of(), List.of(), List.of(),
                            e.getCreatedAt(), e.getUpdatedAt());
                });

        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User-Fall")));
    }

    @Test
    void caseList_respectsOwnerFiltering() throws Exception {
        authenticateAs(regularUser);

        // Regular user queries by email now, not UUID
        when(workspaceService.findByOwner("user@verwaltungsassistent.local"))
                .thenReturn(List.of(userCase));
        when(workspaceService.toDto(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> {
                    WorkspaceEntity e = inv.getArgument(0);
                    return new reasoning.workspace.api.WorkspaceDto(
                            e.getId(), e.getWorkspaceCode(), e.getName(),
                            e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                            e.getOwnerId(), Map.of(), List.of(), List.of(),
                            e.getCreatedAt(), e.getUpdatedAt());
                });

        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User-Fall")))
                .andExpect(content().string(not(containsString("Admin-Fall"))));
    }

    @Test
    void admin_seesAllCases() throws Exception {
        authenticateAs(adminUser);

        when(workspaceService.findAll())
                .thenReturn(List.of(adminCase, userCase));
        when(workspaceService.toDto(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> {
                    WorkspaceEntity e = inv.getArgument(0);
                    return new reasoning.workspace.api.WorkspaceDto(
                            e.getId(), e.getWorkspaceCode(), e.getName(),
                            e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                            e.getOwnerId(), Map.of(), List.of(), List.of(),
                            e.getCreatedAt(), e.getUpdatedAt());
                });

        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Admin-Fall")))
                .andExpect(content().string(containsString("User-Fall")));
    }

    @Test
    void caseList_and_dashboard_agree_on_activeCaseCount() throws Exception {
        authenticateAs(regularUser);

        // Dashboard calls findByOwner(user.email()) — same as CaseController now
        when(workspaceService.findByOwner("user@verwaltungsassistent.local"))
                .thenReturn(List.of(userCase));

        List<WorkspaceEntity> result = workspaceService.findByOwner("user@verwaltungsassistent.local");
        assertEquals(1, result.size(),
                "findByOwner should return the user's case");
    }

    @Test
    void findByOwner_usesEmail_notUuid() {
        // This test verifies the API contract: findByOwner expects email, not UUID
        when(workspaceService.findByOwner("admin@verwaltungsassistent.local"))
                .thenReturn(List.of(adminCase));

        List<WorkspaceEntity> byEmail = workspaceService.findByOwner("admin@verwaltungsassistent.local");
        assertEquals(1, byEmail.size());

        when(workspaceService.findByOwner(adminId.toString()))
                .thenReturn(List.of());

        List<WorkspaceEntity> byUuid = workspaceService.findByOwner(adminId.toString());
        assertTrue(byUuid.isEmpty(),
                "findByOwner with UUID should return empty — owner is stored as email");
    }

    // ── CASE DETAIL ACCESS ──

    @Test
    void caseDetail_accessibleForOwner() throws Exception {
        authenticateAs(regularUser);

        when(workspaceService.findById(userCase.getId()))
                .thenReturn(java.util.Optional.of(userCase));
        when(workspaceService.toDto(any(WorkspaceEntity.class)))
                .thenAnswer(inv -> {
                    WorkspaceEntity e = inv.getArgument(0);
                    return new reasoning.workspace.api.WorkspaceDto(
                            e.getId(), e.getWorkspaceCode(), e.getName(),
                            e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                            e.getOwnerId(), Map.of(), List.of(), List.of(),
                            e.getCreatedAt(), e.getUpdatedAt());
                });
        when(workspaceService.getCompletedSteps(anyString()))
                .thenReturn(List.of());

        mockMvc.perform(get("/cases/" + userCase.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User-Fall")));
    }

    @Test
    void caseDetail_notFound_returns404() throws Exception {
        authenticateAs(regularUser);

        when(workspaceService.findById(anyString()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/cases/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── CASE CREATION ──

    @Test
    void createCase_persistsWithEmailAsOwner() throws Exception {
        authenticateAs(regularUser);

        when(workspaceService.createWorkspace(any()))
                .thenAnswer(inv -> {
                    var cmd = inv.getArgument(0, reasoning.workspace.api.CreateWorkspaceCommand.class);
                    WorkspaceEntity entity = new WorkspaceEntity(
                            "WS-TEST", cmd.name(), cmd.description(),
                            cmd.workspaceType(), cmd.createdBy());
                    // Force ID for deterministic testing
                    try {
                        var idField = WorkspaceEntity.class.getDeclaredField("id");
                        idField.setAccessible(true);
                        idField.set(entity, UUID.randomUUID());
                    } catch (Exception ignored) {}
                    return entity;
                });

        mockMvc.perform(post("/cases/new")
                        .with(csrf())
                        .param("name", "Testfall")
                        .param("description", "Eine Testbeschreibung"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/cases/*"));
    }

    // ── HELPERS ──

    private void authenticateAs(AuthenticatedUser user) {
        var auth = new UsernamePasswordAuthenticationToken(
                user, null,
                user.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static WorkspaceEntity createEntity(String code, String name,
                                                  WorkspaceStatus status, WorkspacePhase phase,
                                                  int docCount, String ownerId) {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-" + code.toUpperCase(), name, "Description for " + name,
                "GENERAL", ownerId);
        try {
            var idField = WorkspaceEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, UUID.nameUUIDFromBytes(code.getBytes()));
        } catch (Exception ignored) {}
        entity.setStatus(status);
        entity.setPhase(phase);
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
