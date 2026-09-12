package verwaltungsassistent.web.controller;

import java.util.Map;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.planning.CaseViewClaimService;
import verwaltungsassistent.web.security.CaseAccessGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Heartbeat + explizite Freigabe für den temporären View-Anspruch
 * (Arbeitspool-Lease) eines geöffneten Vorgangs. Der Heartbeat durchläuft
 * den CaseAccessGuard und verlängert damit das Lease des Anspruchsinhabers;
 * ein fremder Anspruch wird serverseitig mit 423 abgelehnt. Die Freigabe
 * löscht den Anspruch nur, wenn er der anfragenden Mitarbeiterin gehört —
 * nach der Überführung in eine echte Zuweisung (Pipeline-Start) existiert
 * kein Anspruch mehr und die Freigabe ist ein No-op. Lease-Ablauf bleibt das
 * Sicherheitsnetz für Crash/geschlossenen Tab.
 */
@Controller
public class CaseViewClaimController {

    private final CaseAccessGuard caseAccessGuard;
    private final CaseViewClaimService caseViewClaimService;

    public CaseViewClaimController(CaseAccessGuard caseAccessGuard,
                                   CaseViewClaimService caseViewClaimService) {
        this.caseAccessGuard = caseAccessGuard;
        this.caseViewClaimService = caseViewClaimService;
    }

    @PostMapping("/cases/{id}/view-claim/heartbeat")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable String id,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user); // claims bzw. verlängert das Lease
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/cases/{id}/view-claim/release")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> release(@PathVariable String id,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user);
        if (user != null && user.email() != null) {
            caseViewClaimService.release(id, user.email());
        }
        return ResponseEntity.ok(Map.of("released", true));
    }
}
