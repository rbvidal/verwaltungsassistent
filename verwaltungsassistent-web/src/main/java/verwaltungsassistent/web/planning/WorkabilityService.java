package verwaltungsassistent.web.planning;

import org.springframework.stereotype.Service;

/**
 * Explizite Bearbeitbarkeit (Phase 1 der intelligenten Mitarbeiter-
 * Arbeitsplanung): Kann eine Mitarbeiterin an diesem Fall JETZT sinnvoll
 * weiterarbeiten? Blockierungsgründe werden ausschließlich aus persistiertem
 * Zustand abgeleitet — nie vom LLM geraten.
 *
 * <p>Ein hoher Prioritätswert blockiert die Warteschlange bewusst NICHT: Ein
 * nicht bearbeitbarer Fall bleibt hoch priorisiert, wird aber nicht als
 * nächster Arbeitsschritt vorgeschlagen, bis die Blockade wegfällt.</p>
 */
@Service
public class WorkabilityService {

    /** Ergebnis der Bearbeitbarkeits-Prüfung: Flag, deutscher Grund, strukturierter Zustand. */
    public record Workability(boolean workable, String blockedReason, WorkabilityState state) {}

    /** Deterministische Bearbeitbarkeit aus den strukturierten Fall-Fakten. */
    public Workability determine(CaseFacts facts) {
        if (facts.status() == reasoning.common.model.WorkspaceStatus.CLOSED
                || facts.status() == reasoning.common.model.WorkspaceStatus.ARCHIVED) {
            return new Workability(false, "Der Fall ist abgeschlossen bzw. archiviert.",
                    WorkabilityState.COMPLETED);
        }
        if (facts.documentsTotal() > 0 && facts.documentsReady() == 0 && facts.documentsProcessing() > 0) {
            return new Workability(false, "Die Dokumentverarbeitung läuft noch – Ergebnis abwarten.",
                    WorkabilityState.DOCUMENT_PROCESSING);
        }
        if (facts.documentsTotal() > 0 && facts.documentsReady() == 0 && facts.documentsProcessing() == 0
                && facts.documentsFailed() > 0 && !facts.ingestionResolved()) {
            return new Workability(false,
                    "Die Dokumentverarbeitung ist fehlgeschlagen – Dokumente ersetzen oder explizit ohne Unterlagen fortfahren.",
                    WorkabilityState.WAITING_FOR_DOCUMENTS);
        }
        if (facts.documentsTotal() == 0 && !facts.ingestionResolved()) {
            return new Workability(false,
                    "Unterlagen fehlen – Dokumente zuordnen oder explizit ohne Unterlagen fortfahren.",
                    WorkabilityState.WAITING_FOR_DOCUMENTS);
        }
        if ("RUNNING".equals(facts.analysisStatus())) {
            return new Workability(false, "Die Analyse läuft noch – Ergebnis abwarten.",
                    WorkabilityState.ANALYSIS_RUNNING);
        }
        Workability waiting = waitingOnWorkability(facts);
        if (waiting != null) {
            return waiting;
        }
        if (facts.beingWorked()) {
            return new Workability(true, null, WorkabilityState.ALREADY_BEING_WORKED);
        }
        return new Workability(true, null, WorkabilityState.READY_TO_WORK);
    }

    /**
     * Expliziter Wartegrund (waitingOn). Die Fakten-Prüfungen haben Vorrang:
     * ein veralteter DOCUMENTS-Hinweis bei vorhandenen Unterlagen blockiert
     * nicht — "Unterlagen fehlen" wird rein faktisch aus den Dokumenten
     * bestimmt (siehe oben) und der veraltete Hinweis beim Planungs-Neuaufbau
     * entfernt.
     */
    private static Workability waitingOnWorkability(CaseFacts facts) {
        if (facts.waitingOnType() == null || "DOCUMENTS".equals(facts.waitingOnType())) {
            return null;
        }
        return switch (facts.waitingOnType()) {
            case "CITIZEN" -> new Workability(false,
                    "Wartet auf Rückmeldung der Bürgerin bzw. des Bürgers.",
                    WorkabilityState.WAITING_FOR_CITIZEN);
            case "EXTERNAL" -> new Workability(false,
                    "Wartet auf Rückmeldung einer anderen Behörde.",
                    WorkabilityState.WAITING_FOR_EXTERNAL);
            default -> new Workability(false, "Der Vorgang wurde pausiert (Sonstiges).",
                    WorkabilityState.WAITING_FOR_OTHER);
        };
    }
}
