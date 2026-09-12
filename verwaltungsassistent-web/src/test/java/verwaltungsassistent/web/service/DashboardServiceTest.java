package verwaltungsassistent.web.service;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditEventPage;
import reasoning.audit.api.AuditService;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentPage;
import reasoning.document.infrastructure.persistence.JpaDocumentEntityRepository;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.viewmodel.DashboardViewModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private DocumentFacade documentFacade;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private AuditService auditService;

    @Mock
    private JpaIncomingEmailRepository incomingEmailRepository;

    @Mock
    private JpaDocumentEntityRepository documentEntityRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    private DashboardService dashboardService;

    private final AuthenticatedUser user = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
                documentFacade, workspaceService, auditService, incomingEmailRepository,
                documentEntityRepository, userAccountRepository,
                new verwaltungsassistent.web.planning.PriorityCalculationService());
    }

    @Test
    void build_returnsDashboardViewModelWithCorrectCounts() {
        when(documentFacade.findDocuments(any()))
                .thenReturn(new DocumentPage(List.of(), 0, 1, 42, 1));

        when(workspaceService.findByOwner("user@example.com"))
                .thenReturn(List.of());

        when(auditService.query(any()))
                .thenReturn(new AuditEventPage(List.of(), 0, 10, 0, 0));

        DashboardViewModel result = dashboardService.build(user);

        assertThat(result.userName()).isEqualTo("Test User");
        assertThat(result.roles()).contains("USER");
        assertThat(result.totalDocuments()).isEqualTo(42);
        assertThat(result.activeWorkspaces()).isEqualTo(0);
        assertThat(result.recentActivity()).isEmpty();
    }

    @Test
    void build_includesRecentActivity() {
        when(documentFacade.findDocuments(any()))
                .thenReturn(new DocumentPage(List.of(), 0, 1, 0, 0));

        when(workspaceService.findByOwner("user@example.com"))
                .thenReturn(List.of());

        var event = new AuditEvent(
                UUID.randomUUID(), Instant.now(), "user@example.com", null,
                reasoning.common.audit.AuditEventType.USER_LOGIN,
                "USER", "123", "auth", null, null, "/login", "POST", "127.0.0.1", null);

        when(auditService.query(any()))
                .thenReturn(new AuditEventPage(List.of(event), 0, 10, 1, 1));

        DashboardViewModel result = dashboardService.build(user);

        assertThat(result.recentActivity()).hasSize(1);
        assertThat(result.recentActivity().get(0).description())
                .isEqualTo("Anmeldung");
    }

    @Test
    void build_handlesServiceFailure() {
        when(documentFacade.findDocuments(any()))
                .thenThrow(new RuntimeException("Service unavailable"));

        when(workspaceService.findByOwner("user@example.com"))
                .thenThrow(new RuntimeException("Service unavailable"));

        when(auditService.query(any()))
                .thenReturn(new AuditEventPage(List.of(), 0, 10, 0, 0));

        DashboardViewModel result = dashboardService.build(user);

        assertThat(result.totalDocuments()).isEqualTo(0);
        assertThat(result.activeWorkspaces()).isEqualTo(0);
        assertThat(result.userName()).isEqualTo("Test User");
    }
}
