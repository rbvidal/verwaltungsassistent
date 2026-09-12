package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Abschluss eines Falls (Phase 2B.6-Härtung): Beobachtung der aktiven Zeit,
 * Aufzeichnung der Lern-Beobachtung und Statuswechsel CLOSED laufen in EINER
 * Transaktion — ein gleichzeitiger Abschluss und eine gleichzeitige Übergabe
 * können keinen unmöglichen Zustand erzeugen (nie CLOSED mit ACTIVE-Arbeiter).
 * Ein zweiter Abschluss ist idempotent (keine zweite Beobachtung).
 */
@Service
public class CaseClosureService {

    private static final DateTimeFormatter CLOSED_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final WorkspaceService workspaceService;
    private final CaseWorkStateService caseWorkStateService;
    private final EffortLearningService effortLearningService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaseClosureService(WorkspaceService workspaceService,
                              CaseWorkStateService caseWorkStateService,
                              EffortLearningService effortLearningService) {
        this.workspaceService = workspaceService;
        this.caseWorkStateService = caseWorkStateService;
        this.effortLearningService = effortLearningService;
    }

    /**
     * Schließt den Fall atomar und liefert die beobachtete aktive
     * Bearbeitungszeit in Minuten zurück (0 bei keinem zweiten Abschluss
     * oder ohne gemessene Aktivzeit).
     */
    @Transactional
    public long close(String caseId, String actorEmail) {
        WorkspaceEntity ws = workspaceService.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Fall nicht gefunden."));
        if (ws.getStatus() != WorkspaceStatus.DRAFT && ws.getStatus() != WorkspaceStatus.ACTIVE) {
            return 0; // idempotent — kein zweiter Abschluss
        }
        long activeMinutes = caseWorkStateService.completeOnClose(caseId);
        if (activeMinutes > 0) {
            WorkspaceEntity fresh = workspaceService.findById(caseId).orElse(ws);
            String category = CasePlanningService.categoryOf(fresh, fresh.getPhaseDataMap());
            effortLearningService.recordObservation(caseId, category, activeMinutes);
        }
        WorkspaceEntity current = workspaceService.findById(caseId).orElse(ws);
        current.setStatus(WorkspaceStatus.CLOSED);
        Map<String, Object> data = current.getPhaseDataMap();
        data.put("closedAt", LocalDateTime.now().format(CLOSED_FMT));
        data.put("closedBy", actorEmail != null ? actorEmail : "—");
        try {
            current.setPhaseData(mapper.writeValueAsString(data));
        } catch (Exception e) {
            current.setPhaseData("{}");
        }
        workspaceService.save(current);
        return activeMinutes;
    }
}
