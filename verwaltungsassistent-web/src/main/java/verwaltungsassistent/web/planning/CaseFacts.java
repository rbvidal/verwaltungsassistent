package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;

import java.util.List;

/**
 * Strukturierte Fall-Fakten für Priorität und Bearbeitbarkeit — die einzige
 * Schnittstelle zwischen {@link CasePlanningService} (Fakten-Sammlung aus der
 * Persistenz) und den reinen Berechnungs-Services. Enthält KEINE LLM-Werte;
 * die Analyse-Felder stammen aus dem persistierten Analyse-Ergebnis.
 */
public record CaseFacts(
        String caseId,
        String name,
        String description,
        /** phaseData.caseCategory bzw. Fallback (workspaceType/Fallname). */
        String category,
        WorkspaceStatus status,
        WorkspacePhase phase,
        String phaseLabel,
        java.time.Instant createdAt,
        int documentsReady,
        int documentsProcessing,
        int documentsFailed,
        int documentsTotal,
        boolean ingestionResolved,
        /** "" (nie gestartet) / RUNNING / COMPLETED / FAILED — phaseData.analysis.status. */
        String analysisStatus,
        /** Version des frischesten abgeschlossenen Analyse-Laufs (null = keiner nutzbar). */
        Integer analysisVersion,
        /** true: die Dokumentengrundlage hat sich seit dem Analyse-Lauf geändert. */
        boolean analysisStale,
        Boolean grounded,
        Integer evidenceCount,
        List<String> missingDocs,
        /** Wartezeit in Tagen (älteste offene Bürger-E-Mail, sonst Fallalter). */
        long waitingDays,
        /** Deutsche Begründung der Wartezeit. */
        String waitingReason,
        /** Tage bis zur nächsten Frist (negativ = überfristet, null = keine Frist). */
        Integer deadlineDays,
        /** Datum der nächsten Frist als Label (dd.MM.yyyy). */
        String deadlineDateLabel,
        /** Vom Mitarbeiter markierte Dringlichkeit (z. B. geoPriority), normalisiert. */
        String statedUrgency,
        /** true: der Vorgang wird gerade aktiv bearbeitet (workState ACTIVE). */
        boolean beingWorked,
        /** Expliziter Wartegrund (waitingOn.type): DOCUMENTS/CITIZEN/EXTERNAL/OTHER oder null. */
        String waitingOnType) {

    /** Offene Fälle (bearbeitbarer Bestand). */
    public boolean open() {
        return status == WorkspaceStatus.DRAFT || status == WorkspaceStatus.ACTIVE;
    }
}
