package verwaltungsassistent.web.viewmodel;

import java.util.List;

/**
 * ViewModel for the Case Detail screen (Screen 7).
 * Contains all data needed to render the case workspace.
 */
public record CaseDetailViewModel(
        String id,
        String name,
        String workspaceCode,
        String description,
        String workspaceTypeLabel,
        String statusLabel,
        String statusVariant,
        String phaseLabel,
        int currentPhaseIndex,
        String ownerName,
        int documentCount,
        /** Dem Vorgang zugeordnete E-Mails (workspace_id) — für die Kopf-Metadaten. */
        int emailCount,
        int timelineEventCount,
        int completedStepsCount,
        boolean canAdvance,
        String advanceBlockReason,
        boolean showAdvanceBlockReason,
        boolean canGoBack,
        String nextPhaseLabel,
        /** hx-post-Ziel des "nächste Phase"-Buttons (Sonderfall: Analyse starten). */
        String nextPhaseHxPost,
        /** true: der nächste Phase-Button IST der "Analyse starten"-Button (startet die Analyse). */
        boolean nextPhaseStartAction,
        String prevPhaseLabel,
        String createdAt,
        String updatedAt,
        /** Auslöser des Vorgangs ("E-Mail vom …" / "Formularantrag …") oder null. */
        String caseOrigin,
        List<PhaseDef> phases,
        List<DocumentItem> documents,
        List<DocumentItem> recentDocuments,
        List<TimelineItem> timeline,
        List<TimelineItem> recentTimeline,
        List<NoteItem> notes,
        List<ChecklistItem> checklist,
        String phaseError,
        PhaseSection phaseSection
) {
    public record PhaseDef(String label) {}

    public record DocumentItem(String id, String name, String type, String uploadedAt) {}

    public record TimelineItem(String id, String date, String title, String detail, String type,
                                boolean aiGenerated, double confidence) {}

    public record NoteItem(String id, String text, String createdBy, String createdAt) {}

    public record ChecklistItem(String id, String label, String phase, boolean completed, boolean notRequired) {}

    /** Phase-specific content shown under the progress bar: what was done, results, open points, next step, actions. */
    public record PhaseSection(String statusLabel, String statusVariant, String headline,
                               List<InfoRow> info, List<String> done, List<String> open,
                               String nextStep, List<ActionLink> actions) {}

    public record InfoRow(String label, String value) {}

    public record ActionLink(String label, String url, String style) {}
}
