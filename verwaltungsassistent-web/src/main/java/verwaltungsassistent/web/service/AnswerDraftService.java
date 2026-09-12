package verwaltungsassistent.web.service;

import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The citizen answer draft for a case. The draft is assembled DETERMINISTICALLY
 * from the persisted decision analysis (decision proposal) — no second
 * reasoning pass, so it can never contradict the already generated decision.
 * It is persisted as part of the workspace phase data (the existing JSON
 * artifact store used for notes/checklists) and therefore survives restarts.
 *
 * <p>Only information present in the case data and the analysis result is
 * used; nothing is invented. A draft based on limited source coverage keeps
 * an explicit coverage note.</p>
 */
@Service
public class AnswerDraftService {

    private static final Logger log = LoggerFactory.getLogger(AnswerDraftService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkspaceService workspaceService;
    private final verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;

    public AnswerDraftService(WorkspaceService workspaceService,
                              verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository) {
        this.workspaceService = workspaceService;
        this.incomingEmailRepository = incomingEmailRepository;
    }

    /** The persisted draft of a case; null when none exists yet. */
    public AnswerDraft load(String caseId) {
        WorkspaceEntity entity = workspaceService.findById(caseId).orElse(null);
        if (entity == null) return null;
        try {
            Map<String, Object> data = parseJson(entity.getPhaseData());
            Object raw = data.get("answerDraft");
            if (!(raw instanceof Map<?, ?> m)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> draft = (Map<String, Object>) m;
            // Legacy-Entwürfe (vor dem Bearbeitungs-/Prüfvermerk, Phase 2D.5)
            // tragen kein "edited"-Flag: eine spätere aktualisierte Zeit als die
            // Erstellung kennzeichnet eine erfolgte manuelle Bearbeitung.
            Object editedRaw = draft.get("edited");
            boolean edited = editedRaw instanceof Boolean b ? b
                    : !java.util.Objects.equals(draft.get("createdAt"), draft.get("updatedAt"));
            return new AnswerDraft(
                    (String) draft.getOrDefault("text", ""),
                    ((Number) draft.getOrDefault("analysisVersion", 0)).intValue(),
                    (String) draft.getOrDefault("createdAt", ""),
                    (String) draft.getOrDefault("updatedAt", ""),
                    (String) draft.getOrDefault("createdBy", ""),
                    (String) draft.getOrDefault("updatedBy", ""),
                    edited,
                    (String) draft.getOrDefault("reviewedAt", ""),
                    (String) draft.getOrDefault("reviewedBy", ""),
                    Boolean.TRUE.equals(draft.get("grounded")),
                    Boolean.TRUE.equals(draft.get("limitedCoverage")),
                    (String) draft.getOrDefault("instruction", ""));
        } catch (Exception e) {
            log.warn("Could not load answer draft for case {}: {}", caseId, e.getMessage());
            return null;
        }
    }

    /** Persists a freshly generated (unedited) draft in the case phase data. */
    public void saveGenerated(String caseId, String text, String actorEmail, int analysisVersion,
                              boolean grounded) {
        saveGenerated(caseId, text, actorEmail, analysisVersion, grounded, null);
    }

    /** Persists a freshly generated draft incl. der optionalen Erstellungs-Hinweise. */
    public void saveGenerated(String caseId, String text, String actorEmail, int analysisVersion,
                              boolean grounded, String instruction) {
        WorkspaceEntity entity = workspaceService.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Fall nicht gefunden: " + caseId));
        Map<String, Object> data = parseJson(entity.getPhaseData());
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("text", text != null ? text : "");
        draft.put("analysisVersion", analysisVersion);
        draft.put("grounded", grounded);
        draft.put("limitedCoverage", !grounded);
        draft.put("instruction", instruction != null ? instruction : "");
        String now = DATE_FMT.format(Instant.now().atZone(ZoneId.systemDefault()));
        if (data.get("answerDraft") instanceof Map<?, ?> existing) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prev = (Map<String, Object>) existing;
            draft.put("createdAt", prev.getOrDefault("createdAt", now));
            draft.put("createdBy", prev.getOrDefault("createdBy", actorEmail));
        } else {
            draft.put("createdAt", now);
            draft.put("createdBy", actorEmail);
        }
        draft.put("updatedAt", now);
        draft.put("updatedBy", actorEmail);
        // Neu erzeugter Text: weder manuell bearbeitet noch geprüft.
        draft.put("edited", false);
        draft.put("reviewedAt", null);
        draft.put("reviewedBy", null);
        data.put("answerDraft", draft);
        try {
            entity.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            entity.setPhaseData("{}");
        }
        workspaceService.save(entity);
    }

    /**
     * Persists the manually edited draft text (Phase 2D.5): die Bearbeitung
     * wird sichtbar (edited), die bearbeitende Person festgehalten und ein
     * bereits gesetzter Prüfvermerk zurückgesetzt — der Vermerk gilt nur für
     * den Stand, der geprüft wurde. Analyse-Bezug und Erstellungs-Hinweise
     * bleiben erhalten.
     */
    public void saveEdited(String caseId, String text, String actorEmail) {
        WorkspaceEntity entity = workspaceService.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Fall nicht gefunden: " + caseId));
        Map<String, Object> data = parseJson(entity.getPhaseData());
        if (!(data.get("answerDraft") instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Kein Antwortentwurf vorhanden: " + caseId);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = new LinkedHashMap<>((Map<String, Object>) m);
        draft.put("text", text != null ? text : "");
        draft.put("updatedAt", DATE_FMT.format(Instant.now().atZone(ZoneId.systemDefault())));
        draft.put("updatedBy", actorEmail);
        draft.put("edited", true);
        if (draft.get("reviewedAt") != null || draft.get("reviewedBy") != null) {
            draft.put("reviewedAt", null);
            draft.put("reviewedBy", null);
        }
        data.put("answerDraft", draft);
        try {
            entity.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            entity.setPhaseData("{}");
        }
        workspaceService.save(entity);
    }

    /**
     * Lokaler Prüfvermerk (Phase 2D.5): die Mitarbeiterin bestätigt, den
     * Entwurf geprüft zu haben. Rein interner Vermerk für die Aktenlage —
     * kein Versand, kein Statuswechsel. Ein bereits gesetzter Vermerk wird
     * nicht überschrieben.
     */
    public void markReviewed(String caseId, String actorEmail) {
        WorkspaceEntity entity = workspaceService.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Fall nicht gefunden: " + caseId));
        Map<String, Object> data = parseJson(entity.getPhaseData());
        if (!(data.get("answerDraft") instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Kein Antwortentwurf vorhanden: " + caseId);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = new LinkedHashMap<>((Map<String, Object>) m);
        if (draft.get("reviewedAt") == null) {
            draft.put("reviewedAt", DATE_FMT.format(Instant.now().atZone(ZoneId.systemDefault())));
            draft.put("reviewedBy", actorEmail);
            data.put("answerDraft", draft);
            try {
                entity.setPhaseData(MAPPER.writeValueAsString(data));
            } catch (JsonProcessingException e) {
                entity.setPhaseData("{}");
            }
            workspaceService.save(entity);
        }
    }

    /**
     * Assembles the letter text from the persisted decision analysis. All
     * sections derive from real fields; empty sources simply produce no
     * section. The answer text is cleaned of the internal pipeline markers
     * (KURZANTWORT/ENTSCHEIDUNG/…) without altering the content.
     */
    public String generate(Map<String, Object> analysis, WorkspaceDto dto) {
        return generate(analysis, dto, null);
    }

    /**
     * Wie {@link #generate(Map, WorkspaceDto)}, zusätzlich mit optionaler
     * Erstellungs-Anweisung der Mitarbeiterin (Phase 2C.1): Die Anweisung
     * steuert den Entwurf deterministisch (z. B. Frist-Satz, wenn Unterlagen
     * angemahnt werden sollen) und wird mit dem Entwurf gespeichert — sie
     * gelangt nie ungeprüft in den Brieftext. Der Entwurf berücksichtigt die
     * Ausgangskommunikation des Falls (Bezug: Ihre E-Mail vom …).
     */
    public String generate(Map<String, Object> analysis, WorkspaceDto dto, String instruction) {
        if (analysis == null) return null;
        StringBuilder sb = new StringBuilder();
        String caseName = dto.name() != null ? dto.name() : (dto.workspaceCode() != null ? dto.workspaceCode() : "Ihr Anliegen");

        // Betreff
        sb.append("Betreff: ").append(caseName);
        if (dto.workspaceCode() != null && !dto.workspaceCode().isBlank()) {
            sb.append(" (Aktenzeichen ").append(dto.workspaceCode()).append(")");
        }
        sb.append("\n\n");

        // Bezug auf die Ausgangskommunikation des Falls (Phase 2C.1)
        String bezugDate = bezugEmailDate(dto.id());
        if (bezugDate != null) {
            sb.append("Bezug: Ihre E-Mail vom ").append(bezugDate).append("\n\n");
        }

        // Anrede (no invented citizen name)
        sb.append("Sehr geehrte Damen und Herren,\n\n");

        // Antwort / Ergebnis
        String answer = cleanedAnswer(analysis.get("decisionAnswer"));
        if (answer != null && !answer.isBlank()) {
            sb.append("zu Ihrem Anliegen teilen wir Ihnen Folgendes mit:\n\n");
            sb.append(answer).append("\n\n");
        }

        // Begründung aus den Kernfeststellungen
        List<Map<String, Object>> findings = list(analysis.get("primaryFindings"));
        if (!findings.isEmpty()) {
            sb.append("Begründung:\n");
            for (Map<String, Object> f : findings) {
                String label = str(f, "label");
                if (!label.isBlank()) sb.append("- ").append(label).append("\n");
                String description = str(f, "description");
                if (!description.isBlank()) sb.append("  ").append(description).append("\n");
            }
            sb.append("\n");
        }

        // Rechtsgrundlage — only when actually present
        List<Map<String, Object>> authorities = list(analysis.get("authorities"));
        if (!authorities.isEmpty()) {
            sb.append("Rechtsgrundlage:\n");
            for (Map<String, Object> a : authorities) {
                String title = str(a, "title");
                String ref = str(a, "reference");
                sb.append("- ").append(title.isBlank() ? ref : title);
                if (!ref.isBlank() && !"—".equals(ref) && !ref.equals(title)) {
                    sb.append(" (").append(ref).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Erforderliche Unterlagen — only when actually missing
        List<String> missingDocs = strings(analysis.get("missingDocs"));
        if (!missingDocs.isEmpty()) {
            sb.append("Für die weitere Bearbeitung benötigen wir noch:\n");
            for (String doc : missingDocs) {
                sb.append("- ").append(doc).append("\n");
            }
            sb.append("\n");
        }

        // Frist-Satz bei Unterlagen-Anweisung (deterministisch, Phase 2C.1):
        // nur wenn die Anweisung Unterlagen/Fristen betrifft UND eine Frist
        // im Vorgang hinterlegt ist — sonst kein erfundener Termin.
        if (wantsDocumentsDeadline(instruction)) {
            String deadline = nextDeadlineDate(dto.id());
            if (deadline != null) {
                sb.append("Bitte reichen Sie die fehlenden Unterlagen bis zum ")
                        .append(deadline).append(" nach.\n\n");
            }
        }

        // Nächste Schritte aus den Verfahrenshinweisen der Analyse
        List<Map<String, Object>> steps = list(analysis.get("proceduralFindings"));
        if (!steps.isEmpty()) {
            sb.append("Weitere Hinweise:\n");
            for (Map<String, Object> s : steps) {
                String label = str(s, "label");
                if (!label.isBlank()) sb.append("- ").append(label).append("\n");
                String description = str(s, "description");
                if (!description.isBlank()) sb.append("  ").append(description).append("\n");
            }
            sb.append("\n");
        }

        // Honest coverage note — limited evidence must not look like certainty
        if (!Boolean.TRUE.equals(analysis.get("grounded"))) {
            sb.append("(Hinweis: Dieser Entwurf basiert auf einer maschinellen Analyse mit eingeschränkter "
                    + "Quellenlage und ist vor dem Versand sorgfältig zu prüfen.)\n\n");
        }

        sb.append("Mit freundlichen Grüßen");

        return sb.toString().trim();
    }

    /** Strips the internal pipeline section markers without changing the content. */
    static String cleanedAnswer(Object raw) {
        if (!(raw instanceof String s)) return null;
        String cleaned = s
                .replaceAll("(?m)^(KURZANTWORT|ENTSCHEIDUNG|RECHTSGRUNDLAGE|VERFAHREN|NÄCHSTER SCHRITT)\\s*:?\\s*", "")
                .replace("**", "")
                .replaceAll("(?m)^\\s*$", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static Map<String, Object> parseJson(String phaseData) {
        if (phaseData == null || phaseData.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> data = MAPPER.readValue(phaseData,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return data != null ? data : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object raw) {
        if (raw instanceof List<?> l) {
            return l.stream().filter(x -> x instanceof Map<?, ?>).map(x -> (Map<String, Object>) x).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        if (raw instanceof List<?> l) {
            return l.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
        }
        return List.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    /**
     * Persisted answer draft of a case. {@code edited} unterscheidet den
     * unveränderten KI-Entwurf von einem manuell bearbeiteten Stand;
     * {@code reviewedAt}/{@code reviewedBy} sind der lokale Prüfvermerk der
     * Sachbearbeitung (gilt nur für den geprüften Stand).
     */
    public record AnswerDraft(String text, int analysisVersion, String createdAt, String updatedAt,
                              String createdBy, String updatedBy, boolean edited,
                              String reviewedAt, String reviewedBy,
                              boolean grounded, boolean limitedCoverage,
                              String instruction) {
        /** Prüfvermerk gesetzt? (Der Vermerk gilt nur für den geprüften Stand.) */
        public boolean reviewed() {
            return reviewedAt != null && !reviewedAt.isEmpty();
        }
    }

    /**
     * Datum der ältesten dem Fall zugeordneten E-Mail (Ausgangskommunikation)
     * als "Bezug"-Zeile — oder null, wenn keine E-Mail zugeordnet ist.
     */
    private String bezugEmailDate(String caseId) {
        try {
            List<verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity> emails =
                    new java.util.ArrayList<>(incomingEmailRepository
                            .findByWorkspaceIdOrderByReceivedAtDesc(java.util.UUID.fromString(caseId)));
            if (emails.isEmpty()) {
                return null;
            }
            verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity oldest =
                    emails.get(emails.size() - 1);
            return oldest.getReceivedAt() != null
                    ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                            .format(oldest.getReceivedAt().atZone(java.time.ZoneId.systemDefault()))
                    : null;
        } catch (Exception e) {
            log.debug("Bezug-E-Mail von {} nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    /** Früheste DEADLINE der Timeline als "dd.MM.yyyy", sonst null. */
    private String nextDeadlineDate(String caseId) {
        try {
            return workspaceService.getTimeline(caseId).stream()
                    .filter(ev -> ev.getEventType() == reasoning.workspace.model.TimelineEventType.DEADLINE)
                    .map(reasoning.workspace.api.TimelineEventEntity::getEventDate)
                    .filter(java.util.Objects::nonNull)
                    .min(java.util.Comparator.naturalOrder())
                    .map(d -> java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(d))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Frist von {} nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    /** Betrifft die Anweisung Unterlagen/fehlende Nachweise mit Frist? (deterministisch) */
    private static boolean wantsDocumentsDeadline(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String lower = instruction.toLowerCase(java.util.Locale.GERMANY);
        return lower.contains("frist") || lower.contains("bis wann")
                || lower.contains("nachreichen") || lower.contains("unterlagen");
    }
}
