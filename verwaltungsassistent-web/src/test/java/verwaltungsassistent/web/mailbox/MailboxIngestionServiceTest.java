package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.EmailCaseMatchingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.2: Mailbox-Import in die bestehende E-Mail-Pipeline.
 * Idempotenz (Message-ID-Deduplizierung), SourceType.MAILBOX und die
 * Thread-Vor-Analyse (References → THREAD_IDENTITY-Vorschlag) müssen stimmen;
 * eine E-Mail wird dabei nie automatisch einem Fall ZUGEWIESEN.
 */
class MailboxIngestionServiceTest {

    private record Harness(MailboxIngestionService service, List<IncomingEmailEntity> saved,
                           List<EmailAnalysisEntity> savedAnalyses,
                           JpaIncomingEmailRepository repo,
                           EmailCaseMatchingService matchingService,
                           MailboxAttachmentDocumentIngestionService attachmentService,
                           MailboxIntakeService intakeService,
                           IncomingCommunicationAnalysisService communicationAnalysisService) {}

    private static MailboxIntakeService.IntakeInfo noIntake() {
        return new MailboxIntakeService.IntakeInfo(MailboxIntakeService.IntakeMode.NEW_CASE,
                "WS-TEST0001", "Testfall", false, null, null,
                new CommunicationClassifier.CommunicationClassification(
                        CommunicationClassifier.CommunicationType.QUESTION, true, true,
                        "Anliegen bearbeiten und antworten.", "ACKNOWLEDGEMENT"));
    }

    private Harness harness(WorkspaceService workspaceService, List<IncomingMessage> messages) {
        JpaIncomingEmailRepository repo = mock(JpaIncomingEmailRepository.class);
        List<IncomingEmailEntity> saved = new ArrayList<>();
        when(repo.findByMessageId(any())).thenReturn(Optional.empty());
        when(repo.save(any(IncomingEmailEntity.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        JpaEmailAnalysisRepository analysisRepo = mock(JpaEmailAnalysisRepository.class);
        List<EmailAnalysisEntity> savedAnalyses = new ArrayList<>();
        when(analysisRepo.save(any(EmailAnalysisEntity.class))).thenAnswer(i -> {
            savedAnalyses.add(i.getArgument(0));
            return i.getArgument(0);
        });
        MailboxConnector connector = () -> messages;
        EmailCaseMatchingService matchingService = mock(EmailCaseMatchingService.class);
        MailboxAttachmentDocumentIngestionService attachmentService =
                mock(MailboxAttachmentDocumentIngestionService.class);
        when(attachmentService.ingestAttachments(any(), any())).thenReturn(
                new MailboxAttachmentDocumentIngestionService.AttachmentIngestionResult(0, 0));
        MailboxIntakeService intakeService = mock(MailboxIntakeService.class);
        when(intakeService.intake(any(), any(), any(), any())).thenReturn(noIntake());
        IncomingCommunicationAnalysisService communicationAnalysisService =
                mock(IncomingCommunicationAnalysisService.class);
        MailboxIngestionService service =
                new MailboxIngestionService(connector, repo, analysisRepo, matchingService,
                        attachmentService, intakeService, communicationAnalysisService);
        return new Harness(service, saved, savedAnalyses, repo, matchingService, attachmentService,
                intakeService, communicationAnalysisService);
    }

    private static IncomingMessage message(String messageId, String inReplyTo, String references) {
        return new IncomingMessage(messageId, inReplyTo, references,
                "Petra Braun", "petra.braun@example.de", List.of("info@verwaltungs-demo.de"),
                "Hundesteuer – Anmeldung eines Hundes",
                "Betreff: Hundesteuer\n\nGuten Tag, wir haben einen Hund aufgenommen.\n\nMit freundlichen Grüßen\nPetra Braun",
                Instant.now());
    }

    // ── Phase 2D.11: öffentlicher Einzel-Nachrichten-Pfad (Overnight-Prozessor) ──

    @Test
    void processOne_importsMessage_throughTheSamePathAsFetch() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-2d11", null, null)));
        // Der echte Intake verknüpft die E-Mail mit dem Vorgang — im Test wird
        // der Intake gemockt und setzt die Verknüpfung stellvertretend.
        when(h.intakeService().intake(any(), any(), any(), any())).thenAnswer(inv -> {
            IncomingEmailEntity e = inv.getArgument(1);
            e.setWorkspaceId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
            return noIntake();
        });

        MailboxIngestionService.MessageProcessResult r =
                h.service().processOne(message("msg-2d11", null, null));

        assertEquals(1, r.imported(), "Nachricht wird über den Einzel-Pfad importiert");
        assertEquals(1, r.analysed(), "deterministische Vor-Analyse wird erzeugt");
        assertEquals(0, r.failed());
        assertTrue(r.emailId() != null, "E-Mail-Id wird für das Abwarten der Analyse geliefert");
        assertTrue(r.workspaceId() != null, "zugeordneter Vorgang wird im Ergebnis mitgeliefert");
        assertTrue(r.newCase(), "Intake-Modus NEW_CASE wird gemeldet");
        assertEquals(1, h.saved().size());
        org.mockito.Mockito.verify(h.communicationAnalysisService())
                .submitAnalysis(any(IncomingEmailEntity.class),
                        org.mockito.ArgumentMatchers.eq("00000000-0000-0000-0000-0000000000d1"));
    }

    @Test
    void processOne_knownMessageId_isSkipped_withoutSideEffects() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-2d11-dedup", null, null)));

        assertEquals(1, h.service().processOne(message("msg-2d11-dedup", null, null)).imported());
        when(h.repo().findByMessageId("msg-2d11-dedup")).thenReturn(Optional.of(h.saved().get(0)));

        MailboxIngestionService.MessageProcessResult r =
                h.service().processOne(message("msg-2d11-dedup", null, null));

        assertEquals(0, r.imported(), "bekannte Message-ID → Skip (Idempotenz)");
        assertEquals(1, h.saved().size(), "kein zweites E-Mail-Objekt");
    }

    @Test
    void fetchAndIngest_importsOnce_andSetsMailboxSource() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-1", null, null)));

        assertEquals(1, h.service().fetchAndIngest().imported());
        // Zweiter Abruf: Message-ID bekannt → kein Duplikat.
        when(h.repo().findByMessageId("msg-1")).thenReturn(Optional.of(h.saved().get(0)));
        assertEquals(0, h.service().fetchAndIngest().imported());

        assertEquals(1, h.saved().size(), "wiederholte Abrufe dürfen keine Duplikate erzeugen");
        IncomingEmailEntity entity = h.saved().get(0);
        assertEquals(IncomingEmailEntity.SourceType.MAILBOX, entity.getSourceType());
        assertEquals(IncomingEmailEntity.Status.NEW, entity.getStatus());
        assertEquals("msg-1", entity.getMessageId());
        assertEquals("info@verwaltungs-demo.de", entity.getAddressedToEmail());
        assertTrue(entity.getAnalysisId() != null, "Import erzeugt die deterministische Vor-Analyse");
    }

    @Test
    void fetchAndIngest_messageWithoutMessageId_usesSyntheticKeyAndStaysIdempotent() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message(null, null, null)));

        assertEquals(1, h.service().fetchAndIngest().imported());
        IncomingEmailEntity entity = h.saved().get(0);
        assertTrue(entity.getMessageId().startsWith("synthetic-"),
                "Fallback-Schlüssel ohne Message-ID statt Absturz");
        // zweiter Abruf → derselbe synthetische Schlüssel → kein Duplikat
        when(h.repo().findByMessageId(entity.getMessageId())).thenReturn(Optional.of(entity));
        assertEquals(0, h.service().fetchAndIngest().imported());
        assertEquals(1, h.saved().size());
    }

    @Test
    void fetchAndIngest_threadReferences_reuseSharedMatchingAndProduceThreadSuggestion() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        JpaIncomingEmailRepository repo = mock(JpaIncomingEmailRepository.class);
        when(repo.findByMessageId(any())).thenReturn(Optional.empty());
        List<IncomingEmailEntity> saved = new ArrayList<>();
        when(repo.save(any(IncomingEmailEntity.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        JpaEmailAnalysisRepository analysisRepo = mock(JpaEmailAnalysisRepository.class);
        List<EmailAnalysisEntity> savedAnalyses = new ArrayList<>();
        when(analysisRepo.save(any(EmailAnalysisEntity.class))).thenAnswer(i -> {
            savedAnalyses.add(i.getArgument(0));
            return i.getArgument(0);
        });
        // Der GEMEINSAME Matching-Service liefert den Thread-Treffer (wie er es
        // für die manuelle Analyse tut) — die Ingestion reicht die Nachricht
        // nur durch und übernimmt das Ergebnis in die Vor-Analyse.
        EmailCaseMatchingService matchingService = mock(EmailCaseMatchingService.class);
        when(matchingService.matchCases(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of(
                new verwaltungsassistent.web.controller.EmailController.CaseRef(
                        "ws-muell", "Müllsäcke abgeholt",
                        "Thread-Zugehörigkeit: Die E-Mail bezieht sich über ihre Header "
                                + "(In-Reply-To/References) auf eine E-Mail in diesem Vorgang.",
                        "THREAD_IDENTITY")));
        MailboxConnector connector = () -> List.of(new IncomingMessage(
                "msg-folge-1", "msg-muell-2026-08-29", "msg-muell-2026-08-29",
                "Familie Nowak", "nowak.familie@example.de", List.of("info@verwaltungs-demo.de"),
                "Müllsäcke – erneute Abholung", "Betreff: Müllsäcke erneut\n\nBitte Abholung veranlassen.",
                Instant.now()));
        MailboxIngestionService service = new MailboxIngestionService(
                connector, repo, analysisRepo, matchingService,
                mock(MailboxAttachmentDocumentIngestionService.class), mock(MailboxIntakeService.class),
                mock(IncomingCommunicationAnalysisService.class));

        assertEquals(1, service.fetchAndIngest().imported());
        assertEquals("msg-folge-1", saved.get(0).getMessageId());
        assertEquals("msg-muell-2026-08-29", saved.get(0).getInReplyTo());
        assertEquals("msg-muell-2026-08-29", saved.get(0).getReferences());
        // Vor-Analyse enthält den THREAD_IDENTITY-Vorschlag (Vorschlag ≠ Zuordnung)
        EmailAnalysisEntity analysis = savedAnalyses.get(0);
        assertTrue(analysis.getResultJson().contains("THREAD_IDENTITY"),
                "Thread-Vor-Analyse muss den Vorschlag enthalten");
        assertTrue(analysis.getResultJson().contains("Müllsäcke abgeholt"));
        assertTrue(saved.get(0).getWorkspaceId() == null,
                "Import darf die E-Mail niemals automatisch einem Fall zuweisen");
    }

    @Test
    void fetchAndIngest_returnsSummaryAndCountsFailures() {
        JpaIncomingEmailRepository repo = mock(JpaIncomingEmailRepository.class);
        when(repo.findByMessageId(any())).thenReturn(Optional.empty());
        List<IncomingEmailEntity> saved = new ArrayList<>();
        when(repo.save(any(IncomingEmailEntity.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        JpaEmailAnalysisRepository analysisRepo = mock(JpaEmailAnalysisRepository.class);
        EmailCaseMatchingService matchingService = mock(EmailCaseMatchingService.class);
        // Analyse schlägt fehl → Fehlerzähler, aber die E-Mail bleibt erhalten.
        when(matchingService.matchCases(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Matching kaputt"));
        MailboxConnector connector = () -> List.of(message("msg-x", null, null));
        MailboxIngestionService service = new MailboxIngestionService(
                connector, repo, analysisRepo, matchingService,
                mock(MailboxAttachmentDocumentIngestionService.class), mock(MailboxIntakeService.class),
                mock(IncomingCommunicationAnalysisService.class));

        MailboxIngestionService.MailboxFetchResult result = service.fetchAndIngest();

        assertEquals(1, result.imported(), "E-Mail wird auch bei Analysefehler importiert/erhalten");
        assertEquals(0, result.analysed());
        assertEquals(1, result.failed(), "Analysefehler muss im Abruf-Ergebnis sichtbar sein");
        assertEquals(1, saved.size());
        assertEquals(null, saved.get(0).getAnalysisId(),
                "ohne Analyse bleibt die E-Mail erneut analysierbar (bestehender Retry-Weg)");
    }

    // ── Phase 2C.3a: Anhänge ──

    private static IncomingMessage withAttachments(String messageId, List<IncomingMessage.Attachment> attachments) {
        return new IncomingMessage(messageId, null, null,
                "Erika Schulze", "erika.schulze@example.de", List.of("info@verwaltungs-demo.de"),
                "Wohngeldantrag – Unterlagen",
                "Betreff: Wohngeldantrag\n\nGuten Tag, die Unterlagen sind anbei.\n\nMit freundlichen Grüßen\nErika Schulze",
                attachments, Instant.now());
    }

    @Test
    void fetchAndIngest_delegatesAttachments_withoutWorkspaceAssignment() {
        Harness h = harness(mock(WorkspaceService.class), List.of(withAttachments("msg-att-1", List.of(
                new IncomingMessage.Attachment("Mietvertrag.pdf", "application/pdf", new byte[]{37, 80, 68, 70}, 0),
                new IncomingMessage.Attachment("Einkommensnachweis.pdf", "application/pdf", new byte[]{37, 80, 68, 70}, 1)))));
        when(h.attachmentService().ingestAttachments(any(), any())).thenReturn(
                new MailboxAttachmentDocumentIngestionService.AttachmentIngestionResult(2, 0));

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.imported());
        assertEquals(2, result.attachmentsImported(), "Anhänge erscheinen im Abruf-Ergebnis");
        assertEquals(0, result.attachmentsFailed());
        org.mockito.ArgumentCaptor<String> workspaceCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(h.attachmentService())
                .ingestAttachments(any(IncomingMessage.class), workspaceCaptor.capture());
        assertTrue(workspaceCaptor.getValue() == null,
                "Anhänge werden ohne Fall-Zuordnung übernommen (keine automatische Zuweisung)");
    }

    @Test
    void fetchAndIngest_attachmentFailureIsolatesEmailProcessing() {
        Harness h = harness(mock(WorkspaceService.class), List.of(withAttachments("msg-att-2", List.of(
                new IncomingMessage.Attachment("kaputt.pdf", "application/pdf", new byte[]{1}, 0)))));
        when(h.attachmentService().ingestAttachments(any(), any())).thenReturn(
                new MailboxAttachmentDocumentIngestionService.AttachmentIngestionResult(0, 1));

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.imported(), "E-Mail-Import ist vom Anhang-Fehler isoliert");
        assertEquals(1, result.analysed(), "Vor-Analyse bleibt erfolgreich");
        assertEquals(0, result.failed(), "Anhang-Fehler zählt nicht als E-Mail-Fehler");
        assertEquals(1, result.attachmentsFailed(), "Anhang-Fehler ist im Abruf-Ergebnis sichtbar");
    }

    // ── Phase 2C.3b: Vorgangs-Intake ──

    @Test
    void fetchAndIngest_intakeDelegatedAndNewCasesCounted() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-att-1", null, null)));
        when(h.intakeService().intake(any(), any(), any(), any())).thenReturn(noIntake());

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.imported());
        assertEquals(1, result.newCases(), "neue Konversation zählt als neuer Vorgang");
        org.mockito.Mockito.verify(h.intakeService()).intake(any(), any(), any(), any());
    }

    @Test
    void fetchAndIngest_intakeCrashStillImportsEmail() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-intake-x", null, null)));
        when(h.intakeService().intake(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Intake kaputt"));

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.imported(), "E-Mail bleibt auch bei Intake-Fehler erhalten");
        assertEquals(1, result.analysed(), "Vor-Analyse war erfolgreich");
        assertEquals(1, result.failed(), "Intake-Fehler ist im Abruf-Ergebnis sichtbar");
        assertEquals(1, h.saved().size());
    }

    @Test
    void fetchAndIngest_attachmentsGetIntakeWorkspaceId() {
        Harness h = harness(mock(WorkspaceService.class),
                List.of(withAttachments("msg-att-4", List.of(
                        new IncomingMessage.Attachment("Mietvertrag.pdf", "application/pdf", new byte[]{37, 80, 68, 70}, 0)))));
        // Intake ordnet der E-Mail einen Vorgang zu (bestehender Fall) — wie
        // der echte Intake setzt der Stub die workspaceId auf der E-Mail.
        String intakeWsId = "11111111-1111-1111-1111-111111111111";
        when(h.intakeService().intake(any(), any(), any(), any())).thenAnswer(i -> {
            i.getArgument(1, IncomingEmailEntity.class).setWorkspaceId(UUID.fromString(intakeWsId));
            return new MailboxIntakeService.IntakeInfo(
                    MailboxIntakeService.IntakeMode.EXISTING_CASE, "WS-1A2B3C4D", "Fall",
                    false, null, "demo02@verwaltungsassistent.local",
                    new CommunicationClassifier.CommunicationClassification(
                            CommunicationClassifier.CommunicationType.FOLLOW_UP, true, true,
                            "Antworten.", "EMPLOYEE_RESPONSE"));
        });
        when(h.attachmentService().ingestAttachments(any(), any())).thenReturn(
                new MailboxAttachmentDocumentIngestionService.AttachmentIngestionResult(1, 0));

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.attachmentsImported());
        org.mockito.ArgumentCaptor<String> workspaceCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(h.attachmentService())
                .ingestAttachments(any(IncomingMessage.class), workspaceCaptor.capture());
        assertEquals(intakeWsId, workspaceCaptor.getValue(),
                "Anhänge werden mit der Vorgangs-ID des Intake übernommen (bestehender Vorgang)");
    }

    // ── Phase 2C.3c: vollständige Kommunikations-Analyse ──

    @Test
    void fetchAndIngest_triggersFullAnalysisForIntakeEmails() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-an-1", null, null)));
        when(h.intakeService().intake(any(), any(), any(), any())).thenAnswer(i -> {
            i.getArgument(1, IncomingEmailEntity.class)
                    .setWorkspaceId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            return noIntake();
        });

        h.service().fetchAndIngest();

        org.mockito.Mockito.verify(h.communicationAnalysisService()).submitAnalysis(
                any(IncomingEmailEntity.class),
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void fetchAndIngest_reviewRequiredEmail_getsNoFullAnalysis() {
        Harness h = harness(mock(WorkspaceService.class), List.of(message("msg-rev-1", null, null)));
        when(h.intakeService().intake(any(), any(), any(), any())).thenReturn(
                new MailboxIntakeService.IntakeInfo(MailboxIntakeService.IntakeMode.REVIEW_REQUIRED,
                        null, null, true, "WS-AB12CD34", null,
                        new CommunicationClassifier.CommunicationClassification(
                                CommunicationClassifier.CommunicationType.QUESTION, true, true,
                                "Anliegen prüfen.", "EMPLOYEE_RESPONSE")));

        h.service().fetchAndIngest();

        org.mockito.Mockito.verify(h.communicationAnalysisService(), org.mockito.Mockito.never())
                .submitAnalysis(any(), any());
    }

    @Test
    void fetchAndIngest_attachmentServiceCrashStillImportsEmail() {
        Harness h = harness(mock(WorkspaceService.class), List.of(withAttachments("msg-att-3", List.of(
                new IncomingMessage.Attachment("Mietvertrag.pdf", "application/pdf", new byte[]{37, 80, 68, 70}, 0)))));
        when(h.attachmentService().ingestAttachments(any(), any()))
                .thenThrow(new RuntimeException("Anhang-Service kaputt"));

        MailboxIngestionService.MailboxFetchResult result = h.service().fetchAndIngest();

        assertEquals(1, result.imported(), "E-Mail bleibt auch bei Anhang-Service-Absturz erhalten");
        assertEquals(1, result.analysed());
        assertEquals(1, result.attachmentsFailed(), "Absturz wird als Anhang-Fehler gezählt");
        assertEquals(1, h.saved().size());
    }
}
