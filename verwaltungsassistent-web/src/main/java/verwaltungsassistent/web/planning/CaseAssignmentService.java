package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explizite Zuordnung eines Falls (Phase 2B.6-Härtung): ownerId-Änderung und
 * Arbeitszustands-Übergang erfolgen in EINER Transaktion — zwei gleichzeitig
 * eintreffende Zuordnungen können keinen Split-Brain-Zustand erzeugen
 * (z. B. ownerId=Bob bei ACTIVE/Carol). Abgeschlossene/archivierte Fälle sind
 * von der Übergabe ausgeschlossen.
 *
 * <p>Semantik: Die Übergabe aktiviert die neue Mitarbeiterin NICHT stillschweigend —
 * sie beginnt erst mit expliziter Arbeitsaufnahme. Nur der Dashboard-Aufruf
 * [Vorgang übernehmen] ({@code startWork=true}) verbindet Zuordnung und
 * Arbeitsaufnahme in einem expliziten Schritt.</p>
 */
@Service
public class CaseAssignmentService {

    private final WorkspaceService workspaceService;
    private final CaseWorkStateService caseWorkStateService;

    public CaseAssignmentService(WorkspaceService workspaceService,
                                 CaseWorkStateService caseWorkStateService) {
        this.workspaceService = workspaceService;
        this.caseWorkStateService = caseWorkStateService;
    }

    /**
     * Übergibt den Fall atomar an die neue Zuständige. Liefert den Fall in
     * seinem Endzustand zurück.
     *
     * @param startWork {@code true} nur für die explizite [Vorgang übernehmen]-
     *                  Aktion (Zuordnung + Arbeitsaufnahme); die Übergabe über
     *                  die Mitarbeiter-Auswahl bleibt ohne automatischen Start.
     */
    @Transactional
    public WorkspaceEntity assign(String caseId, String assigneeEmail, boolean startWork) {
        WorkspaceEntity ws = workspaceService.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Fall nicht gefunden."));
        if (ws.getStatus() == WorkspaceStatus.CLOSED || ws.getStatus() == WorkspaceStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Abgeschlossene oder archivierte Fälle können nicht übergeben werden.");
        }
        if (!startWork && assigneeEmail != null && assigneeEmail.equalsIgnoreCase(ws.getOwnerId())) {
            return ws; // Übergabe an sich selbst: idempotent
        }
        ws.setOwnerId(assigneeEmail);
        workspaceService.save(ws);
        if (startWork) {
            // Explizite Übernahme = Arbeitsaufnahme durch die neue Zuständige.
            caseWorkStateService.markActive(caseId, assigneeEmail);
        } else {
            // Übergabe: bisherige aktive Zeit abschließen, neue Mitarbeiterin
            // beginnt erst mit expliziter Arbeitsaufnahme.
            caseWorkStateService.handoffTo(caseId, assigneeEmail);
        }
        return workspaceService.findById(caseId).orElse(ws);
    }
}
