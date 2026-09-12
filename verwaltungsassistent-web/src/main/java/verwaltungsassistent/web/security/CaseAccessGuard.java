package verwaltungsassistent.web.security;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralized object-level authorization for cases.
 *
 * <p>Administrators may access every case (existing administrator policy);
 * regular users may access only cases they own (owner id matches their
 * email). Unknown cases return 404, unauthorized access returns 403.
 * Used by all case detail endpoints and sub-actions so that UUID
 * knowledge alone never grants access.</p>
 */
@Component
public class CaseAccessGuard {

    private final WorkspaceService workspaceService;
    private final verwaltungsassistent.web.planning.CaseViewClaimService caseViewClaimService;

    public CaseAccessGuard(WorkspaceService workspaceService,
                           verwaltungsassistent.web.planning.CaseViewClaimService caseViewClaimService) {
        this.workspaceService = workspaceService;
        this.caseViewClaimService = caseViewClaimService;
    }

    /**
     * Arbeitspool-Vorgang beim Zugriff atomar für die Mitarbeiterin
     * reservieren (View-Lease). Fremde Ansprüche werden mit 423 abgelehnt;
     * der eigene Anspruch verlängert das Lease. Owner-/Admin-/GEO-Zugriffe
     * sind nicht claim-bar und laufen unverändert.
     */
    private WorkspaceEntity claimPoolCase(WorkspaceEntity entity, AuthenticatedUser user) {
        if (user == null || user.email() == null || user.roles() != null && user.roles().contains("ADMIN")) {
            return entity;
        }
        if (caseViewClaimService == null
                || !verwaltungsassistent.web.planning.CaseViewClaimService.isClaimable(entity)) {
            return entity;
        }
        try {
            caseViewClaimService.claim(entity.getId().toString(), user.email());
        } catch (verwaltungsassistent.web.planning.CaseViewClaimService.ClaimConflictException e) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Dieser Vorgang wird gerade von einer anderen Mitarbeiterin bearbeitet.");
        }
        return entity;
    }

    /**
     * Loads the case and verifies that the given user may access it.
     *
     * @throws ResponseStatusException 404 when the case does not exist,
     *                                  403 when the user may not access it
     */
    public WorkspaceEntity requireAccess(String caseId, AuthenticatedUser user) {
        WorkspaceEntity entity = workspaceService.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
        if (user != null && user.roles().contains("ADMIN")) {
            return entity;
        }
        if (user != null && user.email() != null
                && "GEO".equalsIgnoreCase(entity.getWorkspaceType())) {
            // Geovorgänge sind das gemeinsame Außendienst-Register der
            // Verwaltung (Straßenkarte, Fotos, Liste): jede Mitarbeiterin darf
            // sie lesen. Schreibaktionen laufen separat über
            // requireWriteAccess (owner/Arbeitspool) — die GEO-Lösch-/Archiv-
            // Sperre requireNotGeo gilt unverändert für alle Rollen.
            return entity;
        }
        if (user != null && user.email() != null
                && (user.email().equalsIgnoreCase(entity.getOwnerId())
                        // Allgemeiner Arbeitspool: unzugewiesene Vorgänge sind
                        // für jede Mitarbeiterin sichtbar (explizite Übernahme).
                        || verwaltungsassistent.web.service.DemoDataService
                                .isGeneralPoolOwner(entity.getOwnerId()))) {
            // Freie Pool-Vorgänge werden beim Öffnen atomar als View-Lease für
            // diese Mitarbeiterin reserviert (andere sehen/öffnen ihn nicht).
            return claimPoolCase(entity, user);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diesen Fall.");
    }

    /** ADM/Leitung-Konto (Aufsicht): voller Lesezugriff, keine operative Arbeit. */
    public static boolean isSupervisory(AuthenticatedUser user) {
        return user != null && user.roles() != null && user.roles().contains("ADMIN");
    }

    /**
     * Lösch-/Archiv-Sperre für Geovorgänge: GEO-Bestände (Geovorgänge und ihre
     * Foto-Dokumente) dürfen im Demo-Betrieb von KEINER Anwendungsrolle
     * archiviert, entfernt oder gelöst werden — auch nicht von SUPERADMIN
     * (der Demo-Reset bleibt der einzige Weg). Ergänzend zu den URL-Regeln;
     * der Aufrufer muss den Vorgang bereits geladen/gelesen haben.
     *
     * @throws ResponseStatusException 403 für Geovorgänge
     */
    public WorkspaceEntity requireNotGeo(WorkspaceEntity entity) {
        if (entity != null && "GEO".equalsIgnoreCase(entity.getWorkspaceType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Geovorgänge und ihre Fotos können nicht archiviert, entfernt oder gelöst werden.");
        }
        return entity;
    }

    /**
     * Schreibzugriff auf einen Vorgang (operative Bearbeitung). Das
     * ADM/Leitung-Konto darf ALLES lesen, aber NICHTS verändern — es ist ein
     * aufsichtlicher Beobachter, kein "Mitarbeiter mit allen Rechten".
     * Mitarbeiterinnen behalten ihren bisherigen Zugriff (eigene Vorgänge +
     * allgemeiner Arbeitspool).
     *
     * @throws ResponseStatusException 403 für Leitungs-Konten bei
     *                                  Schreibversuchen, 403 ohne Zugriff,
     *                                  404 wenn der Fall nicht existiert
     */
    public WorkspaceEntity requireWriteAccess(String caseId, AuthenticatedUser user) {
        WorkspaceEntity entity = workspaceService.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
        if (isSupervisory(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — Vorgänge können nur lesend eingesehen werden.");
        }
        if (user != null && user.email() != null
                && (user.email().equalsIgnoreCase(entity.getOwnerId())
                        || verwaltungsassistent.web.service.DemoDataService
                                .isGeneralPoolOwner(entity.getOwnerId()))) {
            // Schreibaktionen auf freie Pool-Vorgänge ebenfalls nur mit
            // gültigem View-Lease (verhindert paralleles Übernehmen).
            return claimPoolCase(entity, user);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diesen Fall.");
    }
}
