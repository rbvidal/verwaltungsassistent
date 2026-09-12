package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.JobProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2D.11 — Auswahl-/Orchestrierungslogik des Overnight-Prozessors OHNE
 * Anwendung, GreenMail oder LLM: default (alle), limit, offset, Reihenfolge,
 * Delegation an {@link MailboxIngestionService#processOne}, Ergebnis-Zählung,
 * Nicht-Mutation der Connector-Liste und keine zweite Anwendungs-Boot-Fähigkeit.
 */
class DemoOvernightProcessorSelectionTest {

    private static final int FIXTURE_SIZE = 9;

    private MailboxConnector connector;
    private MailboxIngestionService ingestionService;
    private DemoOvernightEmailProcessor processor;
    private List<IncomingMessage> original;

    @BeforeEach
    void setUp() {
        connector = mock(MailboxConnector.class);
        ingestionService = mock(MailboxIngestionService.class);
        processor = new DemoOvernightEmailProcessor(
                connector, ingestionService,
                mock(JpaIncomingEmailRepository.class),
                mock(JpaEmailAnalysisRepository.class),
                mock(JobProgressService.class));
        original = new ArrayList<>();
        for (int i = 0; i < FIXTURE_SIZE; i++) {
            original.add(message("msg-seed-" + i, "Betreff " + i, i == 5 ? 2 : 0));
        }
        when(connector.fetchNewMessages()).thenReturn(original);
        // Standard-Erfolg: importiert + voranalysiert, ohne Vorgangskontext
        // (kein Abwarten einer Analyse im Test).
        when(ingestionService.processOne(any(IncomingMessage.class)))
                .thenReturn(new MailboxIngestionService.MessageProcessResult(
                        1, 1, 0, 0, 0, false, UUID.randomUUID(), null));
    }

    private static IncomingMessage message(String messageId, String subject, int attachments) {
        List<IncomingMessage.Attachment> attach = new ArrayList<>();
        for (int i = 0; i < attachments; i++) {
            attach.add(new IncomingMessage.Attachment("anhang-" + i + ".pdf",
                    "application/pdf", new byte[]{37, 80, 68, 70}, i));
        }
        return new IncomingMessage(messageId, null, null,
                "Petra Braun", "petra.braun@example.de", List.of("info@verwaltungs-demo.de"),
                subject, "Betreff: " + subject + "\n\nNachrichtentext.",
                attach, Instant.now().minusSeconds(3600L - attachments * 60L));
    }

    private List<String> processedMessageIds() {
        return java.util.Arrays.stream(original.toArray()).map(m -> ((IncomingMessage) m).messageId()).toList();
    }

    /** Prüft die an processOne übergebenen Nachrichten in Aufruf-Reihenfolge. */
    private List<String> capturedIds() {
        var captor = org.mockito.ArgumentCaptor.forClass(IncomingMessage.class);
        verify(ingestionService, org.mockito.Mockito.atLeast(0)).processOne(captor.capture());
        return captor.getAllValues().stream().map(IncomingMessage::messageId).toList();
    }

    @Test
    void defaultConfiguration_selectsAllAvailableMessages_inConnectorOrder() {
        int code = processor.run(0, 0);

        assertEquals(0, code, "alle Nachrichten erfolgreich");
        List<String> captured = capturedIds();
        assertEquals(FIXTURE_SIZE, captured.size(), "alle verfügbaren Nachrichten werden ausgewählt");
        assertEquals(processedMessageIds(), captured,
                "Reihenfolge entspricht der Connector-Reihenfolge");
    }

    @Test
    void limitTwo_selectsExactlyTwoMessages() {
        int code = processor.run(0, 2);

        assertEquals(0, code);
        verify(ingestionService, times(2)).processOne(any(IncomingMessage.class));
        assertEquals(List.of("msg-seed-0", "msg-seed-1"), capturedIds(),
                "limit wählt die ersten zwei Nachrichten");
    }

    @Test
    void offsetFiveLimitTwo_selectsMessagesSixAndSeven_includingAttachment() {
        int code = processor.run(5, 2);

        assertEquals(0, code);
        assertEquals(List.of("msg-seed-5", "msg-seed-6"), capturedIds(),
                "offset=5, limit=2 wählt Nachrichten #6 und #7");
        assertEquals(2, original.get(5).attachments().size(),
                "Nachricht #6 trägt Anhänge (Tiny-Integrations-Szenario)");
    }

    @Test
    void selectedMessages_areProcessed_inConnectorOrder() {
        processor.run(5, 2);

        List<String> captured = capturedIds();
        assertEquals(captured.stream().sorted().toList(), List.of("msg-seed-5", "msg-seed-6"),
                "Auswahl identisch (Reihenfolge irrelevant bei Sortierung)");
        assertEquals(List.of("msg-seed-5", "msg-seed-6"), captured,
                "Verarbeitung erfolgt in Connector-Reihenfolge");
    }

    @Test
    void delegatesToExistingProcessOne_only() {
        processor.run(0, 1);

        verify(ingestionService, times(1)).processOne(any(IncomingMessage.class));
        // Kein zweiter Verarbeitungspfad: nur der bestehende Service-Aufruf.
        org.mockito.Mockito.verifyNoMoreInteractions(ingestionService);
    }

    @Test
    void resultAccounting_failureIsCountedAndReported() {
        when(ingestionService.processOne(any(IncomingMessage.class)))
                .thenReturn(new MailboxIngestionService.MessageProcessResult(
                        1, 1, 0, 0, 0, false, UUID.randomUUID(), null));
        // Die letzte Nachricht schlägt fehl → Exit-Code 1.
        org.mockito.Mockito.doThrow(new RuntimeException("Verarbeitungsfehler"))
                .when(ingestionService).processOne(org.mockito.ArgumentMatchers.argThat(
                        m -> "msg-seed-8".equals(m.messageId())));

        int code = processor.run(0, FIXTURE_SIZE);

        assertEquals(1, code, "Fehler werden gezählt und als Exit-Code 1 gemeldet");
    }

    @Test
    void deduplicatedMessage_countsAsSkipped_notAsFailure() {
        when(ingestionService.processOne(any(IncomingMessage.class)))
                .thenReturn(new MailboxIngestionService.MessageProcessResult(
                        0, 0, 0, 0, 0, false, null, null));

        int code = processor.run(0, 3);

        assertEquals(0, code, "bereits bekannte Nachrichten (imported=0) sind Skip, kein Fehler");
        verify(ingestionService, times(3)).processOne(any(IncomingMessage.class));
    }

    @Test
    void connectorList_isNotMutatedBySelection() {
        processor.run(5, 2);
        processor.run(0, 3);

        assertEquals(FIXTURE_SIZE, original.size(), "Connector-Liste bleibt unverändert");
        assertEquals("msg-seed-0", original.get(0).messageId());
        assertEquals("msg-seed-8", original.get(FIXTURE_SIZE - 1).messageId());
    }

    @Test
    void processor_hasNoSecondSpringBootEntryPoint() {
        boolean hasMain = java.util.Arrays.stream(DemoOvernightEmailProcessor.class.getDeclaredMethods())
                .anyMatch(m -> "main".equals(m.getName()));
        assertFalse(hasMain,
                "Der Prozessor bootet keine zweite Anwendung (kein main-Einstiegspunkt)");
        assertTrue(DemoOvernightEmailProcessor.class.getAnnotation(org.springframework.stereotype.Component.class)
                        != null,
                "Der Prozessor ist ein Kontext-Bean im laufenden Demo-Kontext");
    }
}
