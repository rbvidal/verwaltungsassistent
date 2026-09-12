package verwaltungsassistent.web.controller;

import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainGate;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.search.api.SearchFacade;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.analysis.persistence.MailboxRepository;
import verwaltungsassistent.web.controller.EmailController.QueuePage;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thymeleaf.TemplateEngine;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the unprocessed e-mail queue access model: admins see the
 * complete queue across all users and general mailboxes, normal users only
 * their own e-mails, and the queue is server-side paginated.
 */
class EmailQueueAccessTest {

    private JpaIncomingEmailRepository repo;
    private EmailController controller;
    private final AuthenticatedUser admin = new AuthenticatedUser(
            UUID.randomUUID(), "admin@verwaltungsassistent.local", "Admin", Set.of("ADMIN"));
    private final AuthenticatedUser employee = new AuthenticatedUser(
            UUID.randomUUID(), "demo01@verwaltungsassistent.local", "Ben Cordes", Set.of("USER"));

    private IncomingEmailEntity email(AddressedTo to, String toEmail, String assignedTo) {
        IncomingEmailEntity e = new IncomingEmailEntity(
                UUID.randomUUID(), "Betreff " + toEmail, "Bürger/in", "buerger@example.de",
                "Text", Instant.now(), to, toEmail);
        e.setAssignedTo(assignedTo);
        return e;
    }

    @BeforeEach
    void setUp() {
        repo = mock(JpaIncomingEmailRepository.class);
        MailboxRepository mailboxRepository = mock(MailboxRepository.class);
        controller = new EmailController(
                mock(JobProgressService.class),
                mock(DecisionRouter.class),
                mock(DomainGate.class),
                mock(SearchFacade.class),
                mock(WorkspaceService.class),
                mock(JpaDocumentChunkRepository.class),
                mock(TemplateEngine.class),
                mock(JpaEmailAnalysisRepository.class),
                repo,
                mailboxRepository,
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class),
                new ObjectMapper(),
                new verwaltungsassistent.web.planning.PriorityCalculationService(),
                mock(reasoning.ai.api.AiFacade.class),
                mock(verwaltungsassistent.web.security.CaseAccessGuard.class),
                mock(verwaltungsassistent.web.service.EmailCaseMatchingService.class));
    }

    @Test
    void adminSeesCompleteQueueAcrossUsersAndGeneralMailboxes() {
        List<IncomingEmailEntity> all = List.of(
                email(AddressedTo.EMPLOYEE, "demo01@verwaltungsassistent.local", null),
                email(AddressedTo.EMPLOYEE, "demo20@verwaltungsassistent.local", null),
                email(AddressedTo.GENERAL, "kontakt@verwaltungs-demo.de", null),
                email(AddressedTo.GENERAL, "info@verwaltungs-demo.de", "demo05@verwaltungsassistent.local"));
        when(repo.findByStatusOrderByReceivedAtDesc(eq(Status.NEW))).thenReturn(all);

        QueuePage page = controller.loadQueue(admin, 1, "");

        assertEquals(4, page.total());
        assertEquals(4, page.items().size());
        assertEquals(1, page.page());
        assertEquals(1, page.totalPages());
        // admin never filters by a specific user — all recipients are present
        assertTrue(page.items().stream().anyMatch(e -> "demo01@verwaltungsassistent.local".equals(e.getAddressedToEmail())));
        assertTrue(page.items().stream().anyMatch(e -> "demo20@verwaltungsassistent.local".equals(e.getAddressedToEmail())));
        assertTrue(page.items().stream().anyMatch(e -> "kontakt@verwaltungs-demo.de".equals(e.getAddressedToEmail())));
        assertTrue(page.items().stream().anyMatch(e -> "info@verwaltungs-demo.de".equals(e.getAddressedToEmail())));
        verify(repo).findByStatusOrderByReceivedAtDesc(eq(Status.NEW));
    }

    @Test
    void normalUserUsesOnlyAuthorizedEmails() {
        when(repo.findVisibleQueueList(eq("demo01@verwaltungsassistent.local"), eq(Status.NEW), eq(AddressedTo.GENERAL)))
                .thenReturn(List.of(email(AddressedTo.EMPLOYEE, "demo01@verwaltungsassistent.local", null)));

        QueuePage page = controller.loadQueue(employee, 1, "");

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        verify(repo).findVisibleQueueList(eq("demo01@verwaltungsassistent.local"), eq(Status.NEW), eq(AddressedTo.GENERAL));
    }

    @Test
    void paginationUsesTwentyPerPageAndKeepsTotals() {
        List<IncomingEmailEntity> all = new java.util.ArrayList<>();
        for (int i = 0; i < 45; i++) {
            all.add(email(AddressedTo.GENERAL, "kontakt@verwaltungs-demo.de", null));
        }
        when(repo.findByStatusOrderByReceivedAtDesc(eq(Status.NEW))).thenReturn(all);

        QueuePage page = controller.loadQueue(admin, 2, "");

        assertEquals(2, page.page());
        assertEquals(45, page.total());
        assertEquals(3, page.totalPages());
        assertEquals(20, page.items().size());
    }

    @Test
    void waitingPriorityOrdersHighestFirst_thenNewest() {
        // Ältere E-Mail (hohe Wartezeit-Dringlichkeit) MUSS vor der jüngeren
        // (niedrige Dringlichkeit) stehen; bei gleicher Klasse gewinnt die
        // neueste. Dieselbe emailPriorityClass-Logik wie die Badges.
        IncomingEmailEntity oldMail = new IncomingEmailEntity(
                UUID.randomUUID(), "Alt – Wartezeit", "Bürger/in", "buerger@example.de",
                "Text", Instant.now().minus(java.time.Duration.ofDays(20)),
                AddressedTo.GENERAL, "kontakt@verwaltungs-demo.de");
        IncomingEmailEntity recentLow = new IncomingEmailEntity(
                UUID.randomUUID(), "Neu – frisch eingegangen", "Bürger/in", "buerger2@example.de",
                "Text", Instant.now(),
                AddressedTo.GENERAL, "info@verwaltungs-demo.de");
        when(repo.findByStatusOrderByReceivedAtDesc(eq(Status.NEW)))
                .thenReturn(new java.util.ArrayList<>(List.of(recentLow, oldMail)));

        QueuePage page = controller.loadQueue(admin, 1, "");

        assertEquals(2, page.total());
        assertEquals(oldMail.getId(), page.items().get(0).getId(),
                "höchste Wartezeit-Dringlichkeit zuerst");
        assertEquals(recentLow.getId(), page.items().get(1).getId());
    }

    @Test
    void emptyQueueIsReportedWithoutError() {
        when(repo.findByStatusOrderByReceivedAtDesc(eq(Status.NEW))).thenReturn(List.of());

        QueuePage page = controller.loadQueue(admin, 1, "");

        assertNotNull(page);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void pageSizeConstantIsSensible() {
        assertEquals(20, EmailController.QUEUE_PAGE_SIZE);
    }
}
