package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import org.springframework.stereotype.Service;

/**
 * Geschätzter Aufwand eines Falls (Phase 2B) — drei getrennte Konzepte:
 *
 * <ol>
 *   <li><b>Kategorie-Referenz:</b> empirischer Median aus Phase 2A, sonst
 *       YAML-Baseline ({@link EffortLearningService#effectiveEstimateMinutes}).</li>
 *   <li><b>Gesamtaufwand des Falls:</b> die Kategorie-Referenz als Startwert
 *       (Fälle derselben Art dauern typischerweise so lange).</li>
 *   <li><b>Restaufwand:</b> aus Gesamtaufwand MINUS bereits geleisteter
 *       AKTIVER Arbeit; ohne Messung über die Phasen-Progression; bereinigt um
 *       fehlende Unterlagen (erhöht) und eine quellenbelegte Analyse (senkt).</li>
 * </ol>
 *
 * <p>Invariante: Ein Fall mit 25 Minuten gemessener aktiver Arbeit wird NIE
 * wie ein völlig unberührter Fall derselben Kategorie behandelt. Pausierte/
 * wartende Zeit zählt nicht (sie fließt nie in accumulatedActiveMinutes ein).</p>
 */
@Service
public class EffortEstimator {

    /** Phasen-Progression: wie viel des typischen Gesamtaufwands ist erfahrungsgemäß erledigt. */
    static final double PROGRESS_SETUP = 0.10;
    static final double PROGRESS_INGESTION = 0.25;
    static final double PROGRESS_ANALYSIS = 0.50;
    static final double PROGRESS_REVIEW = 0.80;
    static final double PROGRESS_COMPLETE = 0.95;
    /** Fehlende Unterlagen erhöhen den Restaufwand (bounded, benannt). */
    static final double MISSING_DOCS_FACTOR = 1.25;
    /** Quellenbelegte, frische Analyse senkt den Restaufwand (bounded, benannt). */
    static final double PREPARED_FACTOR = 0.80;

    private final EffortLearningService effortLearningService;
    private final ProcessingTimeConfig config;

    public EffortEstimator(EffortLearningService effortLearningService,
                           ProcessingTimeConfig config) {
        this.effortLearningService = effortLearningService;
        this.config = config;
    }

    /** Gesamtaufwand eines Falls: empirischer Kategorie-Median, sonst YAML-Baseline. */
    public int totalEffortMinutes(String category) {
        return effortLearningService.effectiveEstimateMinutes(category);
    }

    /**
     * Restaufwand dieses konkreten Falls. Alle Eingaben sind strukturierte
     * Fakten; nichts wird erfunden.
     */
    public int remainingEffortMinutes(String category, WorkspacePhase phase,
                                      int accumulatedActiveMinutes,
                                      boolean grounded, int evidenceCount,
                                      int missingDocsCount) {
        int base = totalEffortMinutes(category);
        int remaining;
        if (accumulatedActiveMinutes > 0) {
            remaining = Math.max(0, base - accumulatedActiveMinutes);
        } else {
            double progress = switch (phase != null ? phase : WorkspacePhase.SETUP) {
                case SETUP -> PROGRESS_SETUP;
                case INGESTION -> PROGRESS_INGESTION;
                case ANALYSIS -> PROGRESS_ANALYSIS;
                case REVIEW -> PROGRESS_REVIEW;
                case COMPLETE -> PROGRESS_COMPLETE;
            };
            remaining = Math.max(0, (int) Math.round(base * (1.0 - progress)));
        }
        if (missingDocsCount > 0) {
            remaining = (int) Math.round(remaining * MISSING_DOCS_FACTOR);
        }
        if (grounded && evidenceCount > 0) {
            remaining = (int) Math.round(remaining * PREPARED_FACTOR);
        }
        return Math.max(1, remaining);
    }

    /** Nur für Diagnose: die YAML-Baseline einer Kategorie. */
    public int baselineMinutes(String category) {
        return config.baselineMinutes(category);
    }
}
