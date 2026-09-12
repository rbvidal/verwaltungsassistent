package verwaltungsassistent.web.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.service.DemoDataService;

/**
 * Gemeinsame Sichtbarkeitsregel für die Listen-Oberflächen (Fälle,
 * Entscheidungen, Dashboard, Geovorgänge): Eine Mitarbeiterin sieht ihre
 * eigenen Vorgänge (owner = eigene E-Mail) plus den allgemeinen Arbeitspool
 * (unzugewiesen, admin-eigen oder allgemeine Mailbox — {@link
 * DemoDataService#isGeneralPoolOwner}). Admin/Leitungs-Konten sehen über
 * {@code findAll()} alle Vorgänge.
 *
 * <p>Diese Regel ist dieselbe, die {@link CaseAccessGuard} für den Zugriff
 * auf Vorgangs-Details anwendet — Listen und Detailzugriff können damit nicht
 * auseinanderlaufen.</p>
 */
public final class WorkspaceVisibility {

    private WorkspaceVisibility() {
    }

    /**
     * Eigene Vorgänge + allgemeiner Arbeitspool, ohne Duplikate, unsortiert.
     * Für ADMIN/Leitungs-Konten {@code findAll()} verwenden (unverändert).
     * Pool-Vorgänge, die von einer ANDEREN Mitarbeiterin temporär geöffnet
     * wurden (View-Lease), fehlen in der Liste vollständig — sie erscheinen
     * erst wieder, wenn der Anspruch freigegeben wurde oder abgelaufen ist.
     */
    public static List<WorkspaceEntity> ownAndPool(WorkspaceService workspaceService, String userEmail) {
        java.util.Set<String> claimedByOthers = java.util.Set.of();
        try {
            var provider = verwaltungsassistent.web.config.SpringContextProvider.context();
            if (provider != null && userEmail != null) {
                var claimService = provider.getBean(
                        verwaltungsassistent.web.planning.CaseViewClaimService.class);
                claimedByOthers = claimService.claimedByOthers(userEmail);
            }
        } catch (Exception ignored) {
            // Kein Spring-Kontext (Unit-Test) oder Bean fehlt: ohne Filter.
        }
        Map<String, WorkspaceEntity> byId = new LinkedHashMap<>();
        for (WorkspaceEntity ws : workspaceService.findByOwner(userEmail)) {
            byId.put(ws.getId(), ws);
        }
        for (WorkspaceEntity ws : workspaceService.findAll()) {
            if (DemoDataService.isGeneralPoolOwner(ws.getOwnerId())
                    && !claimedByOthers.contains(ws.getId())) {
                byId.putIfAbsent(ws.getId(), ws);
            }
        }
        return new ArrayList<>(byId.values());
    }
}
