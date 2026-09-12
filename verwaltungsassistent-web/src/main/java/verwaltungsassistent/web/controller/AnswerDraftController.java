package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.service.AnswerDraftPdfExporter;
import verwaltungsassistent.web.service.AnswerDraftService;
import verwaltungsassistent.web.service.AnswerDraftService.AnswerDraft;
import verwaltungsassistent.web.util.DateTimeFormats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * "Antwortentwurf erstellen" — an EDITABLE draft response to the citizen,
 * assembled deterministically from the persisted decision analysis. It is
 * never sent automatically and stays an artifact of the case (workspace
 * phase data). The draft is machine-generated and always subject to human
 * review; limited source coverage is never hidden.
 */
@Controller
public class AnswerDraftController {

    private static final Logger log = LoggerFactory.getLogger(AnswerDraftController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormats.GERMAN_DATETIME;

    private final WorkspaceService workspaceService;
    private final CaseAccessGuard caseAccessGuard;
    private final AnswerDraftService draftService;
    private final AnswerDraftPdfExporter pdfExporter;

    public AnswerDraftController(WorkspaceService workspaceService,
                                 CaseAccessGuard caseAccessGuard,
                                 AnswerDraftService draftService,
                                 AnswerDraftPdfExporter pdfExporter) {
        this.workspaceService = workspaceService;
        this.caseAccessGuard = caseAccessGuard;
        this.draftService = draftService;
        this.pdfExporter = pdfExporter;
    }

    /** Draft page: shows the existing draft or an empty state with the create action. */
    @GetMapping("/cases/{id}/draft")
    public String draftPage(@PathVariable String id,
                            @RequestParam(value = "saved", required = false) String saved,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            Model model) {
        WorkspaceEntity entity = caseAccessGuard.requireAccess(id, user);
        WorkspaceDto dto = workspaceService.toDto(entity);

        AnswerDraft draft = draftService.load(id);
        // Phase 2D.7: abgeschlossener Vorgang = Endzustand — Entwurf nur noch
        // lesend; Erzeugen/Bearbeiten/Prüfen erst nach expliziter Wiederaufnahme
        // (Serverseite lehnt die Aktionen zusätzlich ab).
        model.addAttribute("caseClosed",
                entity.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED);
        model.addAttribute("draftSaved", "1".equals(saved));
        model.addAttribute("draftInstruction", draft != null ? draft.instruction() : "");
        model.addAttribute("caseId", id);
        model.addAttribute("caseName", dto.name() != null ? dto.name() : dto.workspaceCode());
        model.addAttribute("workspaceCode", dto.workspaceCode() != null ? dto.workspaceCode() : "");
        model.addAttribute("draft", draft);
        model.addAttribute("hasAnalysis", workspaceService.latestCompletedAnalysisRun(id).isPresent());
        // Herkunft (Phase 2D.5): der Entscheidungszustand der Sachbearbeitung
        // aus derselben phaseData-Quelle wie die Entscheidungs-Seite (2D.3) —
        // der Entwurf bleibt dadurch als Schritt NACH der Entscheidung
        // einordbar, ohne die KI-Empfehlung als Entscheidung auszugeben.
        model.addAttribute("decisionInfo",
                DecisionWorkspaceController.decisionFromPhaseData(parsePhaseData(entity.getPhaseData())));
        model.addAttribute("pageTitle", "Antwortentwurf: " + (dto.name() != null ? dto.name() : dto.workspaceCode()));
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb(dto.name() != null ? dto.name() : dto.workspaceCode(), "/cases/" + id),
                new HomeController.Breadcrumb("Antwortentwurf", "/cases/" + id + "/draft")));
        return "cases/draft";
    }

    /**
     * Generates the draft from the latest completed decision analysis. The
     * generation is deterministic (no second reasoning pass), so the draft
     * cannot contradict the decision proposal. Requires an analysis. Die
     * optionale Erstellungs-Anweisung (Phase 2C.1) steuert den Entwurf
     * deterministisch und wird mit dem Entwurf gespeichert.
     */
    @PostMapping("/cases/{id}/draft/generate")
    public String generate(@PathVariable String id,
                           @RequestParam(value = "instruction", required = false) String instruction,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        caseAccessGuard.requireWriteAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Abgeschlossener Vorgang = Endzustand (Phase 2D.7): kein neuer Entwurf
        // ohne explizite Wiederaufnahme.
        if (entity.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED) {
            redirectAttributes.addFlashAttribute("draftError",
                    "Der Vorgang ist abgeschlossen — ein Antwortentwurf kann nur nach einer Wiederaufnahme erstellt werden.");
            return "redirect:/cases/" + id + "/draft";
        }
        WorkspaceDto dto = workspaceService.toDto(entity);

        WorkspaceAnalysisRunEntity run = workspaceService.latestCompletedAnalysisRun(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Für diesen Fall liegt noch keine Entscheidungsvorlage vor. "
                                + "Bitte führen Sie zuerst die Analyse durch."));
        Map<String, Object> analysis = workspaceService.deserializeAnalysisResult(run);
        String instructionClean = instruction != null ? instruction.trim() : "";
        String text = draftService.generate(analysis, dto,
                instructionClean.isEmpty() ? null : instructionClean);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aus der Entscheidungsvorlage konnte kein Antwortentwurf erstellt werden.");
        }
        boolean grounded = Boolean.TRUE.equals(analysis.get("grounded"));
        String actorEmail = user != null ? user.email() : "system";
        draftService.saveGenerated(id, text, actorEmail,
                run.getVersion(), grounded,
                instructionClean.isEmpty() ? null : instructionClean);
        recordDraftTimeline(id, "Antwortentwurf erstellt",
                "Lokaler Antwortentwurf auf Grundlage der Analyse #" + run.getVersion()
                        + " erstellt durch " + actorEmail + " (kein Versand).");
        log.info("Answer draft generated for case {} from analysis version {}", id, run.getVersion());
        return "redirect:/cases/" + id + "/draft";
    }

    /** Persists the edited draft text (Erstellungs-Hinweise bleiben erhalten). */
    @PostMapping("/cases/{id}/draft/save")
    public String save(@PathVariable String id,
                       @RequestParam("draftText") String draftText,
                       @AuthenticationPrincipal AuthenticatedUser user,
                       org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        caseAccessGuard.requireWriteAccess(id, user);
        // Abgeschlossener Vorgang = Endzustand (Phase 2D.7): Bearbeitung nur
        // nach expliziter Wiederaufnahme.
        if (workspaceService.findById(id)
                .map(e -> e.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED)
                .orElse(false)) {
            redirectAttributes.addFlashAttribute("draftError",
                    "Der Vorgang ist abgeschlossen — der Entwurf kann nur nach einer Wiederaufnahme bearbeitet werden.");
            return "redirect:/cases/" + id + "/draft";
        }
        AnswerDraft existing = draftService.load(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Es ist noch kein Antwortentwurf vorhanden. Bitte erstellen Sie zuerst einen Entwurf.");
        }
        String actorEmail = user != null ? user.email() : "system";
        draftService.saveEdited(id, draftText, actorEmail);
        // Nur die ERSTE Bearbeitung erzeugt einen Verlaufseintrag; weitere
        // Speichervorgänge bleiben stille Arbeitsstände (kein Ereignis-Spam).
        if (!existing.edited()) {
            recordDraftTimeline(id, "Antwortentwurf bearbeitet",
                    "Lokaler Antwortentwurf manuell bearbeitet durch " + actorEmail + " (kein Versand).");
        }
        return "redirect:/cases/" + id + "/draft?saved=1";
    }

    /**
     * Lokaler Prüfvermerk (Phase 2D.5): die Sachbearbeiterin bestätigt, den
     * Entwurf geprüft zu haben — rein intern, kein Versand, kein Statuswechsel.
     * Eine Bearbeitung nach dem Vermerk setzt diesen zurück (erneutes Prüfen).
     */
    @PostMapping("/cases/{id}/draft/review")
    public String review(@PathVariable String id,
                         @AuthenticationPrincipal AuthenticatedUser user,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        caseAccessGuard.requireWriteAccess(id, user);
        // Abgeschlossener Vorgang = Endzustand (Phase 2D.7): Prüfvermerk nur
        // nach expliziter Wiederaufnahme.
        if (workspaceService.findById(id)
                .map(e -> e.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED)
                .orElse(false)) {
            redirectAttributes.addFlashAttribute("draftError",
                    "Der Vorgang ist abgeschlossen — der Entwurf kann nur nach einer Wiederaufnahme geprüft werden.");
            return "redirect:/cases/" + id + "/draft";
        }
        AnswerDraft existing = draftService.load(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Es ist noch kein Antwortentwurf vorhanden. Bitte erstellen Sie zuerst einen Entwurf.");
        }
        String actorEmail = user != null ? user.email() : "system";
        draftService.markReviewed(id, actorEmail);
        if (existing.reviewedAt() == null || existing.reviewedAt().isEmpty()) {
            recordDraftTimeline(id, "Antwortentwurf geprüft",
                    "Lokaler Antwortentwurf geprüft durch " + actorEmail + " (kein Versand).");
        }
        return "redirect:/cases/" + id + "/draft";
    }

    /** Exports the SAVED draft as a PDF letter. Never regenerates the text. */
    @GetMapping("/cases/{id}/draft/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user);
        AnswerDraft draft = draftService.load(id);
        if (draft == null || draft.text() == null || draft.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Es ist noch kein Antwortentwurf vorhanden.");
        }
        WorkspaceDto dto = workspaceService.toDto(
                workspaceService.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        String caseName = dto.name() != null ? dto.name() : dto.workspaceCode();
        String reviewNote = draft.limitedCoverage()
                ? "Hinweis: Maschinell erstellter Entwurf auf Grundlage einer Analyse mit eingeschränkter "
                        + "Quellenlage — vor dem Versand durch eine zuständige Person prüfen und freigeben."
                : "Hinweis: Maschinell erstellter Entwurf — vor dem Versand durch eine zuständige Person "
                        + "prüfen und freigeben.";
        try {
            byte[] pdf = pdfExporter.export(caseName, dto.workspaceCode(), draft.text(),
                    DATE_FMT.format(LocalDateTime.now(ZoneId.systemDefault())), reviewNote);
            String fileName = "antwortentwurf-" + (caseName != null
                    ? caseName.replaceAll("[^a-zA-Z0-9äöüÄÖÜß-]", "_") : dto.workspaceCode()) + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/pdf"))
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .body(pdf);
        } catch (IOException e) {
            log.error("Answer draft PDF export failed for case {}: {}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Die PDF-Erstellung ist fehlgeschlagen.");
        }
    }

    private Map<String, Object> parsePhaseData(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return data != null ? data : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Verlaufseintrag zu Entwurfs-Aktivitäten (manuelle Aktion, keine KI). */
    private void recordDraftTimeline(String caseId, String title, String note) {
        try {
            workspaceService.addTimelineEvent(caseId, java.time.LocalDate.now(), title, note,
                    reasoning.workspace.model.TimelineEventType.CHANGE,
                    null, 1.0, false);
        } catch (Exception e) {
            log.warn("Timeline-Vermerk zum Antwortentwurf für {} nicht möglich: {}", caseId, e.getMessage());
        }
    }
}
