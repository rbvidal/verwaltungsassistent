package verwaltungsassistent.web.viewmodel;

import java.time.Instant;
import java.util.List;

public record DashboardViewModel(
        String userName,
        List<String> roles,
        int totalDocuments,
        int readyDocuments,
        int processingDocuments,
        int failedDocuments,
        int activeWorkspaces,
        int activeIngestionJobs,
        List<RecentActivity> recentActivity,
        List<WorkspaceRow> workspaces,
        List<EmailWorkRow> emailWork
) {

    /**
     * Eine Zeile der "Letzte Aktivitäten"-Tabelle (Phase 2D.12).
     * description = deutsches Ereignis-Label; entityType = technischer
     * Entitäts-Typ (z. B. DOCUMENT) — davon abgeleitet entityLabel (deutsch,
     * z. B. "Dokument") und entityRef (menschenlesbarer Bezug: Dokumenttitel
     * bzw. Kontoname, Fallback technische Kennung). Die technische ID bleibt
     * in entityId erhalten (sekundäre Darstellung).
     */
    public record RecentActivity(
            Instant timestamp,
            String description,
            String entityType,
            String entityId,
            String entityLabel,
            String entityRef
    ) {}

    /** One case row for the "Laufende Akten & Vorhaben" dashboard table. */
    public record WorkspaceRow(
            String id, String name, String workspaceCode,
            String statusLabel, String statusVariant,
            String phaseLabel, int phaseIndex, String updatedAt
    ) {}

    /** One unprocessed incoming e-mail for the "Eingegangene Nachrichten & KI-Erkennung" feed. */
    public record EmailWorkRow(
            String id, String subject, String addressedToLabel, String assignedTo,
            String statusLabel, String statusVariant, Instant receivedAt,
            String priorityLabel, String priorityVariant,
            boolean reviewRequired
    ) {}
}
