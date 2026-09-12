package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2D.11 — One-Shot-Demo-Vorbereitung („Overnight"): verarbeitet die
 * vorbereiteten Demo-E-Mails aus der GreenMail-Demo-Mailbox so, als wären sie
 * über Nacht eingegangen und von der normalen Anwendungsverarbeitung
 * (Postfach-Abruf) behandelt worden: persistieren → Klassifikation/Matching →
 * deterministische Vor-Analyse → Vorgangs-Intake → Anhang-Übernahme →
 * vollständige Kommunikations-Analyse über die bestehende KI-Pipeline (wird
 * pro E-Mail abgewartet).
 *
 * <p>KEIN Scheduler, KEINE Hintergrund-Wiederholung, KEIN eigener
 * Anwendungs-Boot: bewusst ein Einmal-Lauf IM bereits laufenden Demo-Kontext.
 * Ausgelöst wird er vom {@code DemoCaseSeeder} am Ende des Demo-Starts
 * (NACH dem Demo-Reset/-Reseed), gesteuert über die Start-Eigenschaften
 * {@code demo.overnight=true}, {@code demo.overnight.limit=N} (erste N
 * Nachrichten) und {@code demo.overnight.offset=N} (Nachrichten ab Position N
 * überspringen; nur für Auswahl/Tests). Es wird ausschließlich der BESTEHENDE
 * Backend-Pfad verwendet ({@link MailboxIngestionService#processOne} + der
 * bestehende asynchrone Analyse-Job) — keine zweite Analyse-Implementierung,
 * kein Fake-Ergebnis, kein UI-Klick, kein Outbound.</p>
 */
@Component
@Profile({"demo", "playwright"})
public class DemoOvernightEmailProcessor {

    private static final Logger log = LoggerFactory.getLogger(DemoOvernightEmailProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final MailboxConnector connector;
    private final MailboxIngestionService ingestionService;
    private final JpaIncomingEmailRepository emailRepository;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final JobProgressService progressService;

    public DemoOvernightEmailProcessor(MailboxConnector connector,
                                       MailboxIngestionService ingestionService,
                                       JpaIncomingEmailRepository emailRepository,
                                       JpaEmailAnalysisRepository analysisRepository,
                                       JobProgressService progressService) {
        this.connector = connector;
        this.ingestionService = ingestionService;
        this.emailRepository = emailRepository;
        this.analysisRepository = analysisRepository;
        this.progressService = progressService;
    }

    /**
     * Führt den Einmal-Lauf im laufenden Anwendungskontext aus; liefert
     * 0 = alle ausgewählten Nachrichten erfolgreich, 1 = mit Fehlern.
     *
     * @param offset Anzahl der zu überspringenden Nachrichten (Mailbox-Reihenfolge)
     * @param limit  maximale Anzahl zu verarbeitender Nachrichten; 0 = alle
     */
    public int run(int offset, int limit) {
        log.info("============================================================");
        log.info("DEMO OVERNIGHT EMAIL PROCESSOR");
        log.info("============================================================");
        log.info("Mailbox: GreenMail-Demo-Mailbox (nur eingehende Nachrichten)");
        log.info("Fetche eingehende Nachrichten …");

        List<IncomingMessage> all = connector.fetchNewMessages();
        int available = all.size();
        log.info("Gefunden: {} Nachricht(en) in der Demo-Mailbox.", available);
        if (available == 0) {
            log.info("Keine Nachrichten — Mailbox ist leer oder bereits verarbeitet.");
            log.info("DEMO OVERNIGHT PROCESSING COMPLETE (nichts zu tun)");
            return 0;
        }
        int from = Math.min(Math.max(offset, 0), available);
        List<IncomingMessage> messages = from >= available
                ? List.of()
                : new ArrayList<>(all.subList(from, all.size()));
        if (limit > 0 && messages.size() > limit) {
            messages = new ArrayList<>(messages.subList(0, limit));
        }
        int total = messages.size();
        if (total == 0) {
            log.info("Auswahl leer (offset {} übersteigt die Mailbox-Größe {}).", offset, available);
            return 0;
        }
        if (offset > 0 || (limit > 0 && limit < available - from)) {
            log.info("Auswahl: Nachrichten {}-{} von {} (offset={}, limit={}).",
                    from + 1, from + total, available, offset, limit == 0 ? "alle" : limit);
        }

        Instant started = Instant.now();
        List<String> failedMessages = new ArrayList<>();
        int successful = 0;
        int failed = 0;
        int skipped = 0;

        for (int i = 0; i < messages.size(); i++) {
            IncomingMessage message = messages.get(i);
            int seq = i + 1;
            log.info("");
            log.info("[{}/{}] START", seq, total);
            log.info("  Message-ID: {}", safe(message.messageId()));
            log.info("  Betreff:    {}", safe(message.subject()));
            log.info("  Von:        {}", safe(message.senderName() != null ? message.senderName() : message.senderEmail()));
            log.info("  Anhänge:    {}", message.attachments() == null ? 0 : message.attachments().size());
            log.info("  Verarbeitung …");
            Instant messageStart = Instant.now();

            try {
                MailboxIngestionService.MessageProcessResult result =
                        ingestionService.processOne(message);
                if (result.imported() == 0) {
                    skipped++;
                    log.info("[{}/{}] SKIPPED (bereits importiert — Idempotenz)", seq, total);
                    continue;
                }
                log.info("  Vor-Analyse:    {}", result.analysed() > 0 ? "OK" : "FEHLGESCHLAGEN");
                log.info("  Vorgang:        {}", result.workspaceId() != null
                        ? "zugeordnet (workspace " + result.workspaceId() + ")" : "kein Vorgang (Prüfung erforderlich)");
                log.info("  Anhänge:        {} übernommen, {} fehlgeschlagen",
                        result.attachmentsImported(), result.attachmentsFailed());

                if (result.workspaceId() != null && result.emailId() != null) {
                    log.info("  Analyse:        STARTED (Kommunikations-Analyse, Job email:{})", result.emailId());
                    boolean completed = awaitAnalysis(result.emailId());
                    if (completed) {
                        log.info("  Analyse:        COMPLETED");
                    } else {
                        log.info("  Analyse:        FEHLGESCHLAGEN (Vor-Analyse bleibt erhalten)");
                        failed++;
                        failedMessages.add("[" + seq + "] " + safe(message.subject()));
                        logDuration(messageStart, seq, total, "FAILED");
                        continue;
                    }
                } else {
                    log.info("  Analyse:        übersprungen (kein Vorgangskontext)");
                }
                successful++;
                logDuration(messageStart, seq, total, "SUCCESS");
            } catch (Exception e) {
                failed++;
                failedMessages.add("[" + seq + "] " + safe(message.subject()));
                log.error("[{}/{}] FEHLGESCHLAGEN: {} (E-Mail/Vorgang bleiben erhalten, Verarbeitung läuft weiter)",
                        seq, total, e.getMessage());
                log.debug("Details für Nachricht {}: ", seq, e);
                logDuration(messageStart, seq, total, "FAILED");
            }
        }

        Duration elapsed = Duration.between(started, Instant.now());
        log.info("");
        log.info("============================================================");
        log.info("DEMO OVERNIGHT PROCESSING COMPLETE");
        log.info("============================================================");
        log.info("Total:      {}", total);
        log.info("Successful: {}", successful);
        log.info("Failed:     {}", failed);
        log.info("Skipped:    {}", skipped);
        log.info("Total elapsed: {}", format(elapsed));
        if (!failedMessages.isEmpty()) {
            log.info("Fehlgeschlagene Nachrichten:");
            failedMessages.forEach(m -> log.info("  {}", m));
        }
        if (failed == 0 && skipped == 0) {
            log.info("Alle Demo-E-Mails wurden erfolgreich verarbeitet.");
        } else if (failed == 0) {
            log.info("Alle neuen Demo-E-Mails wurden erfolgreich verarbeitet ({} bereits bekannt).", skipped);
        }
        log.info("Die Demo-Datenbank befindet sich nun im vorbereiteten Morgen-Zustand.");
        return failed > 0 ? 1 : 0;
    }

    /**
     * Wartet auf den Abschluss der asynchronen Kommunikations-Analyse einer
     * E-Mail (Job-Schlüssel {@code email:<id>}, derselbe Mechanismus wie die
     * E-Mail-Ansicht). Liefert {@code true}, wenn die Analyse erfolgreich
     * abgeschlossen wurde (KI-Antwort im persistierten Ergebnis), sonst false.
     */
    private boolean awaitAnalysis(UUID emailId) {
        String key = "email:" + emailId;
        Instant waitStart = Instant.now();
        Instant lastHeartbeat = Instant.now();
        while (progressService.activeJob(key) != null) {
            Instant now = Instant.now();
            if (Duration.between(lastHeartbeat, now).compareTo(HEARTBEAT) >= 0) {
                lastHeartbeat = now;
                log.info("  Analyse läuft noch ({} s) …", Duration.between(waitStart, now).toSeconds());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Warte-Schleife unterbrochen — Analyse-Ergebnis wird nicht abgewartet.");
                return false;
            }
        }
        return analysisSucceeded(emailId);
    }

    /** Erfolgreich = die Kommunikations-Analyse hat eine KI-Antwort im persistierten Ergebnis hinterlegt. */
    private boolean analysisSucceeded(UUID emailId) {
        try {
            IncomingEmailEntity email = emailRepository.findById(emailId).orElse(null);
            if (email == null || email.getAnalysisId() == null) {
                return false;
            }
            EmailAnalysisEntity analysis = analysisRepository.findById(email.getAnalysisId()).orElse(null);
            if (analysis == null || analysis.getResultJson() == null) {
                return false;
            }
            verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                    MAPPER.readValue(analysis.getResultJson(),
                            verwaltungsassistent.web.controller.EmailController.EmailOutcome.class);
            return outcome.aiAnswer() != null && !outcome.aiAnswer().isBlank();
        } catch (Exception e) {
            log.debug("Analyse-Ergebnis für {} nicht lesbar: {}", emailId, e.getMessage());
            return false;
        }
    }

    private void logDuration(Instant messageStart, int seq, int total, String state) {
        log.info("[{}/{}] {} ({})", seq, total, state,
                format(Duration.between(messageStart, Instant.now())));
    }

    private static String safe(String value) {
        return value != null && !value.isBlank() ? value : "—";
    }

    private static String format(Duration d) {
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
