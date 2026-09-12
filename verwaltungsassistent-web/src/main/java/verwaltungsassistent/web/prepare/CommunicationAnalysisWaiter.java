package verwaltungsassistent.web.prepare;

import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.controller.EmailController.EmailOutcome;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Gemeinsamer Warte-/Prüf-Helfer für die vollständige KI-Kommunikationsanalyse
 * (Phase 2D.15): wartet auf den terminalen Job-Zustand (Job-Schlüssel
 * {@code email:<id>}) und prüft anschließend, ob die KI-Antwort im
 * persistierten Analyse-Ergebnis liegt — dieselbe Semantik wie die
 * Warte-Schleife des Overnight-Prozessors (2D.11), als wiederverwendbare
 * Komponente. Es wird KEINE Analyse ausgelöst und kein zweiter Mechanismus
 * eingeführt.
 */
@Component
public class CommunicationAnalysisWaiter {

    private static final Logger log = LoggerFactory.getLogger(CommunicationAnalysisWaiter.class);

    /** Obergrenze je E-Mail (sicherheitshalber; der normale Lauf ist deutlich schneller). */
    private static final long MAX_WAIT_MILLIS = 10L * 60 * 1000;

    private final JobProgressService progressService;
    private final JpaIncomingEmailRepository emailRepository;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommunicationAnalysisWaiter(JobProgressService progressService,
                                       JpaIncomingEmailRepository emailRepository,
                                       JpaEmailAnalysisRepository analysisRepository) {
        this.progressService = progressService;
        this.emailRepository = emailRepository;
        this.analysisRepository = analysisRepository;
    }

    /**
     * Wartet, bis der Analyse-Job der E-Mail terminiert ist, und liefert
     * {@code true}, wenn eine KI-Antwort persistiert wurde (aiAnswer nicht
     * leer). Liefert {@code false}, wenn kein Ergebnis vorliegt oder die
     * Wartezeit überschritten wird — der Aufrufer entscheidet, ob das ein
     * erwarteter Fall (REVIEW_REQUIRED) oder ein Fehler ist.
     */
    public boolean awaitFullAnalysis(UUID emailId) {
        String key = "email:" + emailId;
        long waited = 0;
        while (progressService.activeJob(key) != null) {
            if (waited >= MAX_WAIT_MILLIS) {
                log.warn("Analyse-Job {} läuft länger als {} min — Abbruch des Wartens.", key,
                        MAX_WAIT_MILLIS / 60000);
                return false;
            }
            // Heartbeat für lange Einzel-Analysen (Datensatz-Aufbereitung,
            // Overnight): alle 30 s sichtbar machen, dass der Job noch läuft.
            if (waited > 0 && waited % 30_000 == 0) {
                log.info("Kommunikations-Analyse {} noch aktiv ({} s gewartet) …", key,
                        waited / 1000);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Warte-Schleife für {} unterbrochen.", key);
                return false;
            }
            waited += 1000;
        }
        return hasPersistedAiAnswer(emailId);
    }

    /** Erfolgreich = die Kommunikations-Analyse hat eine KI-Antwort im persistierten Ergebnis hinterlegt. */
    public boolean hasPersistedAiAnswer(UUID emailId) {
        try {
            IncomingEmailEntity email = emailRepository.findById(emailId).orElse(null);
            if (email == null || email.getAnalysisId() == null) {
                return false;
            }
            EmailAnalysisEntity analysis = analysisRepository.findById(email.getAnalysisId()).orElse(null);
            if (analysis == null || analysis.getResultJson() == null) {
                return false;
            }
            EmailOutcome outcome = mapper.readValue(analysis.getResultJson(), EmailOutcome.class);
            return outcome.aiAnswer() != null && !outcome.aiAnswer().isBlank();
        } catch (Exception e) {
            log.debug("Analyse-Ergebnis für {} nicht lesbar: {}", emailId, e.getMessage());
            return false;
        }
    }
}
