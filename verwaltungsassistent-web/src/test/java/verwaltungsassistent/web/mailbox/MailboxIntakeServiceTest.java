package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.controller.EmailController;
import verwaltungsassistent.web.planning.CaseAssignmentService;
import verwaltungsassistent.web.service.CaseIdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.3b: automatischer Vorgangs-Intake — Grundregel der Konversation
 * (ohne Vorgangsnummer → neuer Vorgang; mit gültiger Nummer → bestehender;
 * ungültige Nummer → Prüfung), Matching-Precedence, deterministisches
 * Empfänger-Routing und Owner-null für die allgemeine Mailbox.
 */
class MailboxIntakeServiceTest {

    private WorkspaceService workspaceService;
    private CaseAssignmentService caseAssignmentService;
    private UserAccountRepository userAccountRepository;
    private MailboxIntakeService service;

    private final UUID existingWsId = UUID.randomUUID();
    private WorkspaceEntity existing;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        caseAssignmentService = mock(CaseAssignmentService.class);
        userAccountRepository = mock(UserAccountRepository.class);
        service = new MailboxIntakeService(workspaceService, caseAssignmentService, userAccountRepository);
        existing = new WorkspaceEntity("WS-1A2B3C4D", "Wohngeld – Nachfrage zu meinem Antrag",
                "Beschreibung", "CASE", "demo02@verwaltungsassistent.local");
        existing.setId(existingWsId.toString());
        when(workspaceService.findById(existingWsId.toString())).thenReturn(Optional.of(existing));
        when(workspaceService.createWorkspace(any(), any())).thenAnswer(i -> {
            WorkspaceEntity ws = new WorkspaceEntity(
                    i.getArgument(1), i.getArgument(0, CreateWorkspaceCommand.class).name(),
                    i.getArgument(0, CreateWorkspaceCommand.class).description(),
                    "CASE", i.getArgument(0, CreateWorkspaceCommand.class).createdBy());
            ws.setId(UUID.randomUUID().toString());
            return ws;
        });
    }

    private IncomingMessage message(String subject, String body, List<String> recipients) {
        return new IncomingMessage("msg-1", null, null,
                "Erika Schulze", "erika.schulze@example.de", recipients,
                subject, body, Instant.now());
    }

    private IncomingEmailEntity emailFor(IncomingMessage m) {
        return new IncomingEmailEntity(UUID.randomUUID(), m.subject(), m.senderName(),
                m.senderEmail(), m.body(), Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL,
                m.recipients() == null || m.recipients().isEmpty() ? null : m.recipients().get(0));
    }

    private static EmailController.CaseRef ref(String type, String id) {
        return new EmailController.CaseRef(id, "Fall", "Grund", type);
    }

    @Test
    void newEmailWithoutCaseId_createsNewVorgang_unassignedAndWorkable() {
        IncomingMessage m = message("Frage zum Wohngeld", "Welche Unterlagen brauche ich?", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of());

        var info = service.intake(m, email, UUID.randomUUID(), List.of());

        assertEquals(MailboxIntakeService.IntakeMode.NEW_CASE, info.mode());
        assertNull(info.assignedTo(), "allgemeine Mailbox → bewusst NICHT zugewiesen");
        assertEquals(CaseIdService.deterministicCaseCode("Frage zum Wohngeld"), info.caseCode(),
                "deterministisches Aktenzeichen aus dem Betreff");
        ArgumentCaptor<CreateWorkspaceCommand> cmdCaptor = ArgumentCaptor.forClass(CreateWorkspaceCommand.class);
        verify(workspaceService).createWorkspace(cmdCaptor.capture(), any());
        assertNull(cmdCaptor.getValue().createdBy(), "Owner null — kein Fake-Mitarbeiter");
        assertNotNull(email.getWorkspaceId(), "E-Mail ist dem neuen Vorgang zugeordnet");
        verify(workspaceService).addTimelineEvent(any(), any(), any(), any(), any(), any(),
                anyDouble(), anyBoolean());
    }

    @Test
    void validCaseId_usesExistingVorgang_noSecondCase() {
        IncomingMessage m = message("AW: [WS-1A2B3C4D] Frage zum Wohngeld",
                "Eine Frage habe ich noch.", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of(existing));

        var info = service.intake(m, email, UUID.randomUUID(), List.of());

        assertEquals(MailboxIntakeService.IntakeMode.EXISTING_CASE, info.mode());
        assertEquals("WS-1A2B3C4D", info.caseCode());
        assertEquals(existingWsId, email.getWorkspaceId(), "E-Mail gehört dem bestehenden Vorgang");
        verify(workspaceService, never()).createWorkspace(any(), any());
        assertEquals(existing.getOwnerId(), info.assignedTo(), "Zuständigkeit des Vorgangs bleibt");
    }

    @Test
    void invalidCaseId_marksReview_noNewCase_noFallthrough() {
        IncomingMessage m = message("AW: [WS-AB12CD34] Nachfrage zur Gewerbeanmeldung",
                "Gewerbeanmeldung", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of()); // Nummer existiert NICHT

        var info = service.intake(m, email, UUID.randomUUID(),
                List.of(ref("SIMILAR", existingWsId.toString())));

        assertEquals(MailboxIntakeService.IntakeMode.REVIEW_REQUIRED, info.mode());
        assertTrue(info.reviewRequired());
        assertEquals("WS-AB12CD34", info.invalidCaseId());
        assertTrue(email.isReviewRequired(), "Prüf-Flag auf der E-Mail");
        assertNull(email.getWorkspaceId());
        verify(workspaceService, never()).createWorkspace(any(), any());
        // Kein Fallthrough auf semantische Ähnlichkeit.
        verify(workspaceService, never()).addTimelineEvent(any(), any(), any(), any(), any(),
                any(), anyDouble(), anyBoolean());
    }

    @Test
    void confirmedIdentityMatch_usesExistingVorgang() {
        IncomingMessage m = message("Wohngeld – Nachfrage", "Frage zum Antrag", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of());

        var info = service.intake(m, email, UUID.randomUUID(),
                List.of(ref("EMAIL_IDENTITY", existingWsId.toString()), ref("SIMILAR", UUID.randomUUID().toString())));

        assertEquals(MailboxIntakeService.IntakeMode.EXISTING_CASE, info.mode());
        assertEquals(existingWsId, email.getWorkspaceId());
        verify(workspaceService, never()).createWorkspace(any(), any());
    }

    @Test
    void threadIdentityMatch_usesExistingVorgang() {
        IncomingMessage m = message("Müllsäcke – erneute Abholung", "Bitte Abholung veranlassen.", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of());

        var info = service.intake(m, email, UUID.randomUUID(),
                List.of(ref("THREAD_IDENTITY", existingWsId.toString())));

        assertEquals(MailboxIntakeService.IntakeMode.EXISTING_CASE, info.mode());
    }

    @Test
    void similarMatchOnly_neverAutoAssociates() {
        IncomingMessage m = message("Gewerbeanmeldung – Imbiss", "Anmelden zum Oktober", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of(existing));

        var info = service.intake(m, email, UUID.randomUUID(),
                List.of(ref("SIMILAR", existingWsId.toString())));

        assertEquals(MailboxIntakeService.IntakeMode.NEW_CASE, info.mode(),
                "semantische Ähnlichkeit ist ein Vorschlag, keine Zuordnung");
    }

    @Test
    void employeeRecipient_assignsDeterministically() {
        IncomingMessage m = message("Frage zum Wohngeld", "Welche Unterlagen?", List.of("demo01@verwaltungsassistent.local"));
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of());
        when(userAccountRepository.findByEmail("demo01@verwaltungsassistent.local"))
                .thenReturn(Optional.of(mock(UserAccountEntity.class)));

        var info = service.intake(m, email, UUID.randomUUID(), List.of());

        assertEquals("demo01@verwaltungsassistent.local", info.assignedTo());
        verify(caseAssignmentService).assign(any(), org.mockito.ArgumentMatchers.eq("demo01@verwaltungsassistent.local"),
                org.mockito.ArgumentMatchers.eq(false));
        assertEquals(IncomingEmailEntity.AddressedTo.EMPLOYEE, email.getAddressedTo(),
                "E-Mail-Modell: direkt an Mitarbeiterin adressiert");
    }

    @Test
    void generalMailbox_neverAssigned() {
        IncomingMessage m = message("Frage zum Wohngeld", "Welche Unterlagen?",
                List.of("info@verwaltungs-demo.de"));
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of());
        when(userAccountRepository.findByEmail("info@verwaltungs-demo.de")).thenReturn(Optional.empty());

        var info = service.intake(m, email, UUID.randomUUID(), List.of());

        assertNull(info.assignedTo(), "allgemeine Mailbox → unzugewiesen");
        verify(caseAssignmentService, never()).assign(any(), any(), anyBoolean());
        assertEquals(IncomingEmailEntity.AddressedTo.GENERAL, email.getAddressedTo());
    }

    @Test
    void intakeClassification_isStoredInOutcome() {
        IncomingMessage m = message("AW: [WS-1A2B3C4D] Frage zum Wohngeld",
                "Eine Frage habe ich noch: Muss ich die Unterlagen persönlich einreichen?", List.of());
        IncomingEmailEntity email = emailFor(m);
        when(workspaceService.findAll()).thenReturn(List.of(existing));

        var info = service.intake(m, email, UUID.randomUUID(), List.of());

        assertNotNull(info.classification());
        assertEquals(CommunicationClassifier.CommunicationType.FOLLOW_UP,
                info.classification().communicationType());
        assertTrue(info.classification().requiresResponse(), "Folgefrage braucht eine Antwort");
    }
}
