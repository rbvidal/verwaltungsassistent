package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.EffortEstimateEntity;
import verwaltungsassistent.web.planning.persistence.EffortObservationEntity;
import verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Empirisches Lernmodell der Bearbeitungszeiten (Phase 2A).
 *
 * <p>Drei getrennte Konzepte, bewusst nicht zusammengeführt:</p>
 * <ol>
 *   <li><b>YAML-Referenz</b> ({@link ProcessingTimeConfig}) — initiales
 *       Domänenwissen/Fallback, wird nie umgeschrieben.</li>
 *   <li><b>Empirische typische Bearbeitungszeit</b> — kumulativer Median
 *       (plus P75/P90) über ALLE aktiven Bearbeitungszeit-Beobachtungen der
 *       Fallart, nur im Lern-Intervall (Standard 5) neu berechnet.</li>
 *   <li><b>Restaufwand des konkreten Falls</b> — spätere Schichten
 *       (Phase 2B/2C) leiten ihn aus Fortschritt + Referenz + Analyse ab;
 *       diese Klasse liefert die Referenz dafür.</li>
 * </ol>
 *
 * <p>Automatisch, ohne Administrator: Abschluss → Beobachtung → bei jeder
 * fünften Beobachtung Neuberechnung über ALLE bisherigen Beobachtungen.
 * Robuste Statistik (Nearest-Rank-Perzentile): Ausreißer dominieren den
 * Median nicht. Das Komplexitäts-Feedback (1–10) wird separat gespeichert
 * und fließt NICHT in den Median ein — es ist kontextuelle Information für
 * spätere Modellverbesserungen, kein Leistungsmaß.</p>
 */
@Service
public class EffortLearningService {

    private static final Logger log = LoggerFactory.getLogger(EffortLearningService.class);

    private final JpaEffortObservationRepository observationRepository;
    private final JpaEffortEstimateRepository estimateRepository;
    private final ProcessingTimeConfig config;

    public EffortLearningService(JpaEffortObservationRepository observationRepository,
                                 JpaEffortEstimateRepository estimateRepository,
                                 ProcessingTimeConfig config) {
        this.observationRepository = observationRepository;
        this.estimateRepository = estimateRepository;
        this.config = config;
    }

    /**
     * Erfasst die Beobachtung eines abgeschlossenen Falls (aktive Minuten).
     * Aktivzeiten &lt;= 0 (z. B. gesetzte Demo-Fälle ohne workState) erzeugen
     * bewusst KEINE Beobachtung. Ein Eintrag je Fall (case_id unique) —
     * doppelte Abschlüsse können keine zweite Beobachtung erzeugen. Nach der
     * konfigurierten Anzahl Beobachtungen je Fallart wird die Statistik über
     * ALLE Beobachtungen neu berechnet.
     */
    public void recordObservation(String caseId, String category, long activeMinutes) {
        if (activeMinutes <= 0) {
            return;
        }
        UUID caseUuid = UUID.fromString(caseId);
        EffortObservationEntity observation = observationRepository.findByCaseId(caseUuid)
                .orElseGet(() -> new EffortObservationEntity(caseUuid));
        observation.setCategory(category);
        observation.setObservedActiveMinutes((int) Math.min(activeMinutes, Integer.MAX_VALUE));
        observation.setCompletedAt(Instant.now());
        observationRepository.save(observation);

        long count = observationRepository.countByCategory(category);
        if (count % config.learningInterval() == 0) {
            recalculate(category);
        }
    }

    /**
     * Speichert das optionale Komplexitäts-Feedback (1–10) zur Beobachtung
     * des Falls. Unabhängig vom Median — Feedback ist Kontext, keine
     * Leistungsmessung und kein Eingang in die Zeitstatistik.
     */
    public void setComplexityFeedback(String caseId, Integer value) {
        if (value == null || value < 1 || value > 10) {
            return;
        }
        observationRepository.findByCaseId(UUID.fromString(caseId)).ifPresent(observation -> {
            observation.setComplexityFeedback(value);
            observationRepository.save(observation);
        });
    }

    /**
     * Neuberechnung der kumulativen Statistik einer Fallart über ALLE
     * Beobachtungen (nie nur die letzten fünf). Primärgröße: Median;
     * P75/P90 als Diagnose. Die vorherige Schätzung bleibt als
     * previousMedianMinutes erhalten (Auditierbarkeit).
     */
    public void recalculate(String category) {
        List<Integer> minutes = observationRepository.findByCategoryOrderByCompletedAt(category).stream()
                .map(EffortObservationEntity::getObservedActiveMinutes)
                .sorted()
                .toList();
        if (minutes.isEmpty()) {
            return;
        }
        EffortEstimateEntity estimate = estimateRepository.findByCategory(category)
                .orElseGet(() -> new EffortEstimateEntity(category));
        Integer previous = estimate.getMedianMinutes();
        estimate.setSampleCount(minutes.size());
        estimate.setMedianMinutes((int) Math.round(percentile(minutes, 0.5)));
        estimate.setP75Minutes((int) Math.round(percentile(minutes, 0.75)));
        estimate.setP90Minutes((int) Math.round(percentile(minutes, 0.9)));
        estimate.setPreviousMedianMinutes(previous);
        estimate.setLastUpdated(Instant.now());
        estimate.setSource("EMPIRICAL");
        estimateRepository.save(estimate);
        log.info("Empirische Bearbeitungszeit '{}': n={}, Median {} min (vorher {}), P75 {}, P90 {}",
                category, minutes.size(), estimate.getMedianMinutes(), previous,
                estimate.getP75Minutes(), estimate.getP90Minutes());
    }

    /**
     * Effektive Referenz für eine Fallart: empirischer Median, sobald die
     * Statistik berechnet wurde (erste Neuberechnung nach dem Lern-Intervall),
     * sonst die YAML-Baseline. Quelle intern: EMPIRICAL bzw. INITIAL_CONFIG.
     */
    public int effectiveEstimateMinutes(String category) {
        return estimateRepository.findByCategory(category)
                .map(EffortEstimateEntity::getMedianMinutes)
                .filter(java.util.Objects::nonNull)
                .orElseGet(() -> config.baselineMinutes(category));
    }

    /** Aktuelle Statistik einer Fallart (Audit/Debug; Mitarbeiterin sieht nur "ca. X Min."). */
    public Optional<EffortEstimateEntity> aggregate(String category) {
        return estimateRepository.findByCategory(category);
    }

    public Optional<EffortObservationEntity> observationFor(String caseId) {
        return observationRepository.findByCaseId(UUID.fromString(caseId));
    }

    /**
     * Nearest-Rank-Perzentil (deterministisch): Rang = ceil(p * n), 1-basiert.
     * Bei ungeradem n entspricht p=0,5 dem echten Median; bei geradem n dem
     * unteren Mittelfeld — robust und ausreißerfest, bewusst einfach.
     */
    static double percentile(List<Integer> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.size());
        rank = Math.max(1, Math.min(rank, sorted.size()));
        return sorted.get(rank - 1);
    }
}
