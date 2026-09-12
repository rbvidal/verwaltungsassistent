package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.workspace.api.TimelineEventDto;
import reasoning.workspace.api.WorkspaceDocumentDto;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import verwaltungsassistent.web.service.CaseBriefingService;
import verwaltungsassistent.web.service.FallbriefingPdfExporter;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import verwaltungsassistent.web.util.DateTimeFormats;
import verwaltungsassistent.web.viewmodel.CaseDetailViewModel;
import verwaltungsassistent.web.viewmodel.CaseDetailViewModel.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
public class CaseDetailController {

    private static final Logger log = LoggerFactory.getLogger(CaseDetailController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard;
    private final verwaltungsassistent.web.service.CaseTimelineService caseTimelineService;
    private final verwaltungsassistent.web.service.AnswerDraftService answerDraftService;
    private final UserAccountRepository userAccountRepository;
    private final CaseBriefingService caseBriefingService;
    private final JobProgressService progressService;
    private final FallbriefingPdfExporter briefingPdfExporter;
    private final verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;
    private final verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository emailAnalysisRepository;
    private final verwaltungsassistent.web.planning.CasePlanningService casePlanningService;
    private final verwaltungsassistent.web.planning.CaseWorkStateService caseWorkStateService;
    private final verwaltungsassistent.web.planning.EffortLearningService effortLearningService;
    private final verwaltungsassistent.web.planning.NextBestWorkService nextBestWorkService;
    private final verwaltungsassistent.web.planning.CaseAssignmentService caseAssignmentService;
    private final verwaltungsassistent.web.planning.CaseClosureService caseClosureService;
    private final ExecutorService executor;

    /** Anhang-Dokumente je E-Mail (Provenienz-Sicht; Profil-beans, optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private verwaltungsassistent.web.mailbox.MailboxAttachmentDocumentIngestionService
            mailboxAttachmentService;

    public CaseDetailController(WorkspaceService workspaceService,
                                DocumentFacade documentFacade,
                                verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard,
                                verwaltungsassistent.web.service.CaseTimelineService caseTimelineService,
                                verwaltungsassistent.web.service.AnswerDraftService answerDraftService,
                                UserAccountRepository userAccountRepository,
                                CaseBriefingService caseBriefingService,
                                JobProgressService progressService,
                                FallbriefingPdfExporter briefingPdfExporter,
                                verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository,
                                verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository emailAnalysisRepository,
                                verwaltungsassistent.web.planning.CasePlanningService casePlanningService,
                                verwaltungsassistent.web.planning.CaseWorkStateService caseWorkStateService,
                                verwaltungsassistent.web.planning.EffortLearningService effortLearningService,
                                verwaltungsassistent.web.planning.NextBestWorkService nextBestWorkService,
                                verwaltungsassistent.web.planning.CaseAssignmentService caseAssignmentService,
                                verwaltungsassistent.web.planning.CaseClosureService caseClosureService) {
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.caseAccessGuard = caseAccessGuard;
        this.caseTimelineService = caseTimelineService;
        this.answerDraftService = answerDraftService;
        this.userAccountRepository = userAccountRepository;
        this.caseBriefingService = caseBriefingService;
        this.progressService = progressService;
        this.briefingPdfExporter = briefingPdfExporter;
        this.incomingEmailRepository = incomingEmailRepository;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.casePlanningService = casePlanningService;
        this.caseWorkStateService = caseWorkStateService;
        this.effortLearningService = effortLearningService;
        this.nextBestWorkService = nextBestWorkService;
        this.caseAssignmentService = caseAssignmentService;
        this.caseClosureService = caseClosureService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "briefing-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // --- Full page ---

    @GetMapping("/cases/{id}")
    public String showCase(@PathVariable String id,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                           Model model) {
        CaseController.parseCaseId(id);
        WorkspaceEntity entity = caseAccessGuard.requireAccess(id, user);
        WorkspaceDto dto = workspaceService.toDto(entity);
        CaseDetailViewModel vm = buildViewModel(dto, entity, user);

        model.addAttribute("case", vm);
        model.addAttribute("caseId", id);
        // GEO-Kennzeichen für die UI: Geovorgänge sind unveränderliche
        // Demo-Bestände (kein Archivieren/Lösen — serverseitig gesperrt).
        model.addAttribute("caseIsGeo", entity.getWorkspaceType() != null
                && "GEO".equalsIgnoreCase(entity.getWorkspaceType()));
        model.addAttribute("pageTitle", vm.name());
        model.addAttribute("activeSection", "cases");
        model.addAttribute("hasAnswerDraft", answerDraftService.load(id) != null);
        model.addAttribute("assignableUsers", activeUsers());
        model.addAttribute("currentUserEmail", user != null ? user.email() : "");
        // Planungs-Meldung (Phase 1 der intelligenten Arbeitsplanung): nicht-
        // modaler Hinweis oben auf der Fallseite — "Als Nächstes bearbeiten",
        // blockiert oder persönlicher Rückstand, mit erklärbarer Priorität.
        model.addAttribute("casePlanningAlert",
                casePlanningService.alertFor(id, user != null ? user.email() : null));
        // Arbeitszustand (Phase 2A): Pausieren/Fortsetzen-Steuerung und die
        // Anzeige "Wartet seit … auf …".
        model.addAttribute("caseWorkInfo", workInfoOf(entity));
        // Fehlende Unterlagen (für den Wartehinweis): bestätigte aus der
        // Analyse getrennt von bloß thematisch typischen — die UI darf eine
        // typische Unterlage nicht als festgestelltes Fehlen darstellen.
        List<String> confirmedMissing = confirmedMissingDocumentsOf(entity);
        model.addAttribute("confirmedMissingDocuments", confirmedMissing);
        model.addAttribute("typicalMissingDocuments",
                confirmedMissing.isEmpty() ? typicalMissingDocumentsOf(entity) : List.of());
        // Abgeschlossener Fall: nur wenn eine abrufbare Analyse existiert, ist
        // "Entscheidung ansehen" eine sinnvolle Aktion (gleiche Prüfung wie im
        // Abschluss-Abschnitt).
        model.addAttribute("caseHasUsableAnalysis",
                workspaceService.latestCompletedAnalysisRun(id)
                        .map(workspaceService::deserializeAnalysisResult)
                        .isPresent());
        // Phase 2D.8: Ist die Entscheidung der Sachbearbeitung bereits
        // dokumentiert (phaseData.decision, 2D.3), lautet die Fallseiten-Aktion
        // "Entscheidung ansehen" statt "vorbereiten" — dieselbe Wortwahl wie im
        // Abschluss-Abschnitt, keine widersprüchliche Vorbereitungs-Sprache.
        model.addAttribute("decisionDocumented",
                DecisionWorkspaceController.decisionFromPhaseData(parseJson(entity.getPhaseData())) != null);
        // Phase 2B: Ist DIESER Fall aktuell die Empfehlung der Mitarbeiterin,
        // zeigt die Fallseite "Nächster empfohlener Vorgang". Nur relevant bei
        // bearbeitbarem Zustand (success-Alert).
        boolean caseIsRecommended = false;
        if (model.getAttribute("casePlanningAlert") != null
                && "success".equals(((verwaltungsassistent.web.planning.CasePlanningService.PlanningAlert)
                        model.getAttribute("casePlanningAlert")).variant())
                && user != null) {
            var rec = nextBestWorkService.recommendFor(user);
            caseIsRecommended = rec.recommended() != null && rec.recommended().caseId().equals(id);
            // Mitarbeiterfreundliches Empfehlungs-Level für die Fallseite
            // (gleiche Vokabel wie die Dashboard-Empfehlung).
            model.addAttribute("caseRecommendationLevel", caseIsRecommended
                    ? verwaltungsassistent.web.planning.NextBestWorkService
                            .recommendationLevelLabel(rec.recommended().rank())
                    : null);
        }
        model.addAttribute("caseIsRecommended", caseIsRecommended);
        // Phase 2C.10 — Kommunikations-/Provenienz-Übersicht des Vorgangs.
        model.addAttribute("caseCommunications", communicationRowsOf(id));
        model.addAttribute("detailTabs", List.of(
                tabDef("overview", "Übersicht", null),
                tabDef("emails", "E-Mails", "/cases/" + id + "/emails"),
                tabDef("documents", "Dokumente", "/cases/" + id + "/documents"),
                tabDef("timeline", "Timeline", "/cases/" + id + "/timeline"),
                tabDef("checklist", "Checkliste", "/cases/" + id + "/checklist"),
                tabDef("briefing", "Fallbriefing", "/cases/" + id + "/briefing"),
                tabDef("notes", "Notizen", "/cases/" + id + "/notes")));
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb(vm.name(), "/cases/" + id)));

        if (hxRequest != null) {
            return "cases/detail-fragments :: overview";
        }
        return "cases/detail";
    }

    // ── Archive / restore / delegation / history ─────────────────────────────

    @PostMapping("/cases/{id}/archive")
    public String archiveCase(@PathVariable String id,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        WorkspaceEntity entity = caseAccessGuard.requireNotGeo(
                caseAccessGuard.requireWriteAccess(id, user));
        if (entity.getStatus() != reasoning.common.model.WorkspaceStatus.ARCHIVED) {
            entity.setStatus(reasoning.common.model.WorkspaceStatus.ARCHIVED);
            workspaceService.save(entity);
            recordHistoryEvent(entity.getId(), "Fall archiviert", null);
        }
        return "redirect:/cases/" + id;
    }

    @PostMapping("/cases/{id}/restore")
    public String restoreCase(@PathVariable String id,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        WorkspaceEntity entity = caseAccessGuard.requireNotGeo(
                caseAccessGuard.requireWriteAccess(id, user));
        if (entity.getStatus() == reasoning.common.model.WorkspaceStatus.ARCHIVED) {
            entity.setStatus(reasoning.common.model.WorkspaceStatus.ACTIVE);
            workspaceService.save(entity);
            recordHistoryEvent(entity.getId(), "Fall wiederhergestellt", null);
        }
        return "redirect:/cases/" + id;
    }

    /**
     * Schließen (ABGESCHLOSSEN) is a separate lifecycle step from Archivieren:
     * the case stays in the normal historical record with its documents, and
     * the closing employee + timestamp are recorded in the phase data.
     *
     * <p>2D.16 — Abschluss-Invariante serverseitig abgesichert: Ein Vorgang
     * kann nur geschlossen werden, wenn die Entscheidung der Sachbearbeitung
     * dokumentiert ist (phaseData.decision). Ohne dokumentierte Entscheidung
     * bleibt der Vorgang offen und die UI erhält eine ehrliche Meldung —
     * die Invariante wird nicht geschwächt, sondern erzwungen.</p>
     */
    @PostMapping("/cases/{id}/close")
    public String closeCase(@PathVariable String id,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        WorkspaceEntity entity = caseAccessGuard.requireWriteAccess(id, user);
        DecisionWorkspaceController.DecisionInfo decision =
                DecisionWorkspaceController.decisionFromPhaseData(parseJson(entity.getPhaseData()));
        if (decision == null) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Der Vorgang kann nicht geschlossen werden: Die Entscheidung der Sachbearbeitung "
                            + "ist noch nicht dokumentiert. Dokumentieren Sie zuerst die Entscheidung "
                            + "(Analyseergebnis öffnen → Entscheidung dokumentieren).");
            return "redirect:/cases/" + id;
        }
        // Atomarer Abschluss (Phase 2B.6): Beobachtung + Aufzeichnung +
        // Statuswechsel in EINER Transaktion — nie CLOSED mit ACTIVE-Arbeiter,
        // kein zweiter Abschluss, keine zweite Beobachtung.
        long activeMinutes = caseClosureService.close(id, user != null ? user.email() : null);
        if (activeMinutes > 0) {
            // Kleines, optionales Komplexitäts-Feedback (nicht-blockierend).
            redirectAttributes.addFlashAttribute("complexityFeedbackOpen", true);
        }
        recordHistoryEvent(id, "Fall geschlossen", user != null ? user.email() : null);
        return "redirect:/cases/" + id;
    }

    /** Vorgang pausieren: expliziter Wartegrund (Unterlagen/Bürger/Behörde/Sonstiges). */
    @PostMapping("/cases/{id}/work/pause")
    public String pauseCase(@PathVariable String id,
                            @RequestParam("type") String type,
                            @RequestParam(value = "note", required = false) String note,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireWriteAccess(id, user);
        if (!java.util.Set.of("DOCUMENTS", "CITIZEN", "EXTERNAL", "OTHER").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannter Wartegrund.");
        }
        caseWorkStateService.markPaused(id, user != null ? user.email() : "system", type, note);
        recordHistoryEvent(id, "Vorgang pausiert", waitingOnLabel(type, note));
        return "redirect:/cases/" + id;
    }

    /** Bearbeitung fortsetzen: PAUSED → ACTIVE, Wartehinweis wird entfernt. */
    @PostMapping("/cases/{id}/work/resume")
    public String resumeCase(@PathVariable String id,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireWriteAccess(id, user);
        caseWorkStateService.resume(id, user != null ? user.email() : "system");
        recordHistoryEvent(id, "Vorgang fortgesetzt", null);
        return "redirect:/cases/" + id;
    }

    /** Optionales Komplexitäts-Feedback (1–10); "Überspringen" sendet keinen Wert. */
    @PostMapping("/cases/{id}/close/feedback")
    public String complexityFeedback(@PathVariable String id,
                                     @RequestParam(value = "value", required = false) Integer value,
                                     @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireWriteAccess(id, user);
        if (value != null && (value < 1 || value > 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Komplexität 1–10.");
        }
        effortLearningService.setComplexityFeedback(id, value);
        return "redirect:/cases/" + id;
    }

    private static String waitingOnLabel(String type, String note) {
        String label = switch (type) {
            case "DOCUMENTS" -> "wartet auf Unterlagen";
            case "CITIZEN" -> "wartet auf Bürger/in";
            case "EXTERNAL" -> "wartet auf andere Behörde";
            default -> "pausiert (Sonstiges)";
        };
        return note != null && !note.isBlank() ? label + " — " + note : label;
    }

    /** Anzeige-Daten des Arbeitszustands für die Fallseite (Pause/Fortsetzen, Wartehinweis). */
    @SuppressWarnings("unchecked")
    private CaseWorkInfo workInfoOf(WorkspaceEntity entity) {
        Map<String, Object> data = parseJson(entity.getPhaseData());
        Object rawState = data.get("workState");
        Object rawWaiting = data.get("waitingOn");
        String state = rawState instanceof Map<?, ?> m && m.get("state") != null
                ? String.valueOf(m.get("state")) : null;
        String waitingType = rawWaiting instanceof Map<?, ?> m && m.get("type") != null
                ? String.valueOf(m.get("type")) : null;
        String waitingSince = rawWaiting instanceof Map<?, ?> m && m.get("since") != null
                ? String.valueOf(m.get("since")) : null;
        String waitingNote = rawWaiting instanceof Map<?, ?> m && m.get("note") != null
                ? String.valueOf(m.get("note")) : null;
        return new CaseWorkInfo(state, waitingType, formatWaitingSince(waitingSince), waitingNote);
    }

    private static String formatWaitingSince(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .format(Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()));
        } catch (Exception e) {
            return null;
        }
    }

    /** Arbeitszustand eines Falls für die Anzeige. */
    public record CaseWorkInfo(String state, String waitingType, String waitingSince, String waitingNote) {
        public boolean paused() {
            return "PAUSED".equals(state);
        }
    }

    /**
     * Bestätigte fehlende Unterlagen: die aus der persistierten Analyse
     * spezifisch belegten missingDocs. Nur diese gelten als "fehlend" —
     * eine bloß thematisch typische Unterlage ist kein Nachweis dafür, dass
     * die Bürgerin bzw. der Bürger sie nicht vorgelegt hat.
     */
    private List<String> confirmedMissingDocumentsOf(WorkspaceEntity entity) {
        try {
            var facts = casePlanningService.factsFor(entity.getId());
            if (facts != null && facts.missingDocs() != null && !facts.missingDocs().isEmpty()) {
                return facts.missingDocs();
            }
        } catch (Exception e) {
            log.debug("Belegte fehlende Unterlagen von {} nicht lesbar: {}", entity.getId(), e.getMessage());
        }
        return List.of();
    }

    /**
     * Typischerweise erforderliche Unterlagen der Fallart (deterministische
     * Katalog-Liste, keine KI) — bewusst NUR als Fallback, wenn keine
     * bestätigten fehlenden Unterlagen vorliegen. Die UI kennzeichnet diese
     * als "noch zu prüfen", nie als festgestelltes Fehlen.
     */
    private List<String> typicalMissingDocumentsOf(WorkspaceEntity entity) {
        if (!confirmedMissingDocumentsOf(entity).isEmpty()) {
            return List.of();
        }
        String category = verwaltungsassistent.web.planning.CasePlanningService
                .categoryOf(entity, entity.getPhaseDataMap());
        String name = entity.getName() != null ? entity.getName() : "";
        return verwaltungsassistent.web.controller.EmailController
                .typicalMissingDocuments(topicOfCategory(category + " " + name));
    }

    /** Fallart/Fallname → Thema der typischen Unterlagen (deterministisch). */
    private static String topicOfCategory(String text) {
        if (text == null) {
            return "Allgemeines Anliegen";
        }
        String c = text.toLowerCase(Locale.ROOT);
        if (c.contains("wohngeld")) return "Wohngeld";
        if (c.contains("ummel") || c.contains("melde")) return "An- / Ummeldung";
        if (c.contains("bau")) return "Bau / Baugenehmigung";
        if (c.contains("gewerbe")) return "Gewerbe";
        if (c.contains("reisepass") || c.contains("personalausweis") || c.contains("ausweis"))
            return "Ausweisdokumente";
        if (c.contains("termin")) return "Terminanfrage";
        return "Allgemeines Anliegen";
    }

    /** Reopens a closed case (Abgeschlossen → Aktiv) for continued work. */
    @PostMapping("/cases/{id}/reopen")
    public String reopenCase(@PathVariable String id,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        WorkspaceEntity entity = caseAccessGuard.requireWriteAccess(id, user);
        if (entity.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED) {
            entity.setStatus(reasoning.common.model.WorkspaceStatus.ACTIVE);
            workspaceService.save(entity);
            recordHistoryEvent(entity.getId(), "Fall wieder geöffnet", user != null ? user.email() : null);
        }
        return "redirect:/cases/" + id;
    }

    /**
     * Delegates the case to another ACTIVE system user. Only administrators
     * and the current assignee may delegate; the new assignee must be an
     * existing enabled user (no arbitrary names). The change is recorded in
     * the case history.
     *
     * <p>Semantik (Phase 2B.6): ownerId-Änderung und Arbeitszustand sind atomar
     * ({@link verwaltungsassistent.web.planning.CaseAssignmentService}).
     * Die Übergabe über die Mitarbeiter-Auswahl aktiviert die neue Mitarbeiterin
     * NICHT automatisch — nur die explizite Dashboard-Aktion [Vorgang übernehmen]
     * verbindet Zuordnung und Arbeitsaufnahme (start=true).</p>
     */
    @PostMapping("/cases/{id}/assign")
    public String assignCase(@PathVariable String id,
                             @RequestParam("assignee") String assigneeEmail,
                             @RequestParam(value = "start", required = false) String start,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        WorkspaceEntity entity = caseAccessGuard.requireWriteAccess(id, user);
        // Leitungs-Konto: durch requireWriteAccess bereits abgewiesen (403).
        // Mitarbeiterin darf übergeben, wenn sie den Fall besitzt oder ihn aus
        // dem allgemeinen Arbeitspool explizit übernimmt (write access).
        if (assigneeEmail == null || assigneeEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte einen Benutzer auswählen.");
        }
        UserAccountEntity assignee = userAccountRepository.findByEmail(assigneeEmail.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Der ausgewählte Benutzer existiert nicht."));
        if (!assignee.isEnabled() || assignee.isLocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der ausgewählte Benutzer ist nicht aktiv.");
        }
        // Das Leitungs-Konto ist keine operative Zuständigkeit: Vorgänge
        // können nie an die Leitung übergeben werden.
        if (assignee.getRoles().stream().anyMatch(r -> r.name().equals("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Leitungs-Konto kann keine Vorgänge übernehmen — bitte eine Mitarbeiterin auswählen.");
        }
        String previous = entity.getOwnerId();
        boolean startWork = "true".equals(start);
        WorkspaceEntity updated = caseAssignmentService.assign(id, assignee.getEmail(), startWork);
        String assigneeName = assignee.getDisplayName() != null && !assignee.getDisplayName().isBlank()
                ? assignee.getDisplayName() : assignee.getEmail();
        recordHistoryEvent(updated.getId(), "Fall übergeben",
                "von " + (previous != null ? previous : "—") + " an " + assigneeName
                        + (startWork ? " (übernommen und begonnen)" : ""));
        return "redirect:/cases/" + id;
    }

    /** Bearbeitungshistorie: chronological case history with user attribution. */
    @GetMapping("/cases/{id}/history")
    public String caseHistory(@PathVariable String id,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceDto dto = workspaceService.toDto(
                workspaceService.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        List<verwaltungsassistent.web.service.CaseTimelineService.TimelineEntry> history =
                caseTimelineService.build(id);
        model.addAttribute("history", history);
        model.addAttribute("caseId", id);
        model.addAttribute("caseName", dto.name() != null ? dto.name() : dto.workspaceCode());
        model.addAttribute("pageTitle", "Bearbeitungshistorie: " + (dto.name() != null ? dto.name() : dto.workspaceCode()));
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb(dto.name() != null ? dto.name() : dto.workspaceCode(), "/cases/" + id),
                new HomeController.Breadcrumb("Bearbeitungshistorie", "/cases/" + id + "/history")));
        return "cases/history";
    }

    private void recordHistoryEvent(String workspaceId, String title, String description) {
        try {
            workspaceService.addTimelineEvent(workspaceId, java.time.LocalDate.now(),
                    title, description,
                    reasoning.workspace.model.TimelineEventType.CHANGE,
                    null, 1.0, false);
        } catch (Exception e) {
            log.warn("Could not record history event '{}' for {}: {}", title, workspaceId, e.getMessage());
        }
    }

    /** Active system users for the delegation select (Mitarbeiterinnen; das
     *  Leitungs-Konto ist keine operative Zuständigkeit). */
    private List<UserAccountEntity> activeUsers() {
        try {
            return userAccountRepository.findAll().stream()
                    .filter(u -> u.isEnabled() && !u.isLocked())
                    .filter(u -> u.getRoles().stream().noneMatch(r -> r.name().equals("ADMIN")))
                    .sorted(java.util.Comparator.comparing(
                            u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (Exception e) {
            log.debug("Could not load active users: {}", e.getMessage());
            return List.of();
        }
    }

    // --- Tab endpoints (HTMX) ---

    /**
     * Kommunikationsverlauf des Falls (Phase 2C.1): alle zugeordneten E-Mails
     * (workspace_id) CHRONOLOGISCH (älteste zuerst), damit Verlauf und
     * Auslöser-Nachricht lesbar sind. Jede Zeile öffnet die bestehende
     * E-Mail-Detailseite — kein zweiter E-Mail-Viewer. Die Zuordnung kann
     * hier aufgehoben werden (E-Mail bleibt unverändert erhalten).
     */
    @GetMapping("/cases/{id}/emails")
    public String emailsTab(@PathVariable String id,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            Model model) {
        caseAccessGuard.requireAccess(id, user);
        List<verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity> emails =
                new ArrayList<>(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(
                        verwaltungsassistent.web.controller.CaseController.parseCaseId(id)));
        java.util.Collections.reverse(emails);
        model.addAttribute("caseEmails", emails);
        model.addAttribute("caseId", id);
        // Auslöser-Nachricht des Falls: die E-Mail, deren Analyse den Vorgang
        // angestoßen hat (phaseData.sourceEmailId = Analyse-Id).
        model.addAttribute("caseTriggerAnalysisId", triggerAnalysisIdOf(id));
        // Phase 2D.12: Status-Label der Verlaufszeilen ehrlich nach Analyse-
        // Stufe — "KI-Analyse abgeschlossen" nur bei vollständiger KI-Analyse
        // (aiAnswer im gespeicherten Ergebnis), sonst bleibt es bei
        // "Voranalysiert" für die deterministische Vor-Analyse.
        Map<UUID, Boolean> fullAiByEmail = new HashMap<>();
        for (var e : emails) {
            if (e.getAnalysisId() == null) {
                continue;
            }
            emailAnalysisRepository.findById(e.getAnalysisId()).ifPresent(a -> {
                try {
                    verwaltungsassistent.web.controller.EmailController.EmailOutcome o =
                            mapper.readValue(a.getResultJson(),
                                    new TypeReference<verwaltungsassistent.web.controller.EmailController.EmailOutcome>() {});
                    boolean full = o.aiAnswer() != null && !o.aiAnswer().isBlank();
                    fullAiByEmail.put(e.getId(), full);
                } catch (Exception ex) {
                    fullAiByEmail.put(e.getId(), false);
                }
            });
        }
        model.addAttribute("caseEmailFullAi", fullAiByEmail);
        return "cases/detail-fragments :: emailsTab";
    }

    /** Zahl der dem Vorgang ZUGEORDNETEN E-Mails (workspace_id-Beziehung). */
    private int emailCountOf(String caseId) {
        try {
            return incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(
                    verwaltungsassistent.web.controller.CaseController.parseCaseId(caseId)).size();
        } catch (Exception e) {
            log.debug("E-Mail-Anzahl von Fall {} nicht lesbar: {}", caseId, e.getMessage());
            return 0;
        }
    }

    /** Analyse-Id der auslösenden E-Mail eines Falls (phaseData.sourceEmailId), sonst null. */
    private String triggerAnalysisIdOf(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null) {
                return null;
            }
            Object raw = parseJson(ws.getPhaseData()).get("sourceEmailId");
            return raw != null ? String.valueOf(raw) : null;
        } catch (Exception e) {
            log.debug("Auslöser-Analyse von {} nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    /** Provenienz-Zeile eines Vorgangs: eine zugehoerige E-Mail. */
    public record CaseCommunicationRow(String emailId, String receivedAt, String sender,
                                       String subject, boolean origin, int attachmentCount) {}

    /** Alle dem Vorgang zugeordneten E-Mails in chronologischer Reihenfolge
     *  (aelteste zuerst) inkl. Anhang-Anzahl — reine Lese-/Provenienz-Sicht.
     *  Die E-Mail selbst bleibt die Quelle; nichts wird dupliziert. */
    private List<CaseCommunicationRow> communicationRowsOf(String caseId) {
        List<CaseCommunicationRow> rows = new ArrayList<>();
        try {
            List<verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity> emails =
                    new ArrayList<>(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(
                            verwaltungsassistent.web.controller.CaseController.parseCaseId(caseId)));
            java.util.Collections.reverse(emails);
            String triggerId = triggerAnalysisIdOf(caseId);
            for (var e : emails) {
                String analysisId = e.getAnalysisId() != null ? e.getAnalysisId().toString() : null;
                int attachments = 0;
                if (mailboxAttachmentService != null && e.getMessageId() != null
                        && !e.getMessageId().isBlank()) {
                    attachments = mailboxAttachmentService.attachmentsOf(e.getMessageId()).size();
                }
                rows.add(new CaseCommunicationRow(
                        e.getId().toString(),
                        e.getReceivedAt() != null
                                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                                        .format(e.getReceivedAt().atZone(ZoneId.systemDefault()))
                                : "—",
                        e.getSenderName() != null ? e.getSenderName() : "—",
                        e.getSubject() != null ? e.getSubject() : "—",
                        triggerId != null && analysisId != null && triggerId.equals(analysisId),
                        attachments));
            }
        } catch (Exception e) {
            log.debug("Kommunikations-Übersicht von {} nicht lesbar: {}", caseId, e.getMessage());
        }
        return rows;
    }

    @GetMapping("/cases/{id}/documents")
    public String documentsTab(@PathVariable String id,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String sort,
                               @RequestParam(defaultValue = "0") int page,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("caseIsGeo", entity.getWorkspaceType() != null
                && "GEO".equalsIgnoreCase(entity.getWorkspaceType()));

        // Load document links and enrich with Document data (if DocumentFacade available)
        List<WorkspaceDocumentLinkEntity> links = workspaceService.getWorkspaceDocuments(id);
        List<AttachedDocumentItem> allDocs = new ArrayList<>();
        for (WorkspaceDocumentLinkEntity link : links) {
            try {
                Document doc = documentFacade.getDocument(link.getDocumentUuid(), user.id().toString());
                allDocs.add(new AttachedDocumentItem(
                        link.getId(), link.getDocumentId(),
                        displayTitle(doc, link),
                        doc.metadata().type() != null ? doc.metadata().type().name() : "—",
                        statusLabel(doc.status()), statusVariant(doc.status()),
                        doc.currentVersion(),
                        link.getUploadedAt() != null
                                ? DATE_FMT.format(LocalDate.ofInstant(link.getUploadedAt(), ZoneId.systemDefault()))
                                : "—"));
            } catch (Exception e) {
                log.debug("Could not load document {}: {}", link.getDocumentId(), e.getMessage());
                allDocs.add(new AttachedDocumentItem(
                        link.getId(), link.getDocumentId(),
                        displayTitle(null, link),
                        link.getDocumentType() != null ? link.getDocumentType().name() : "—",
                        "Nicht verfügbar", "neutral", 0,
                        link.getUploadedAt() != null
                                ? DATE_FMT.format(LocalDate.ofInstant(link.getUploadedAt(), ZoneId.systemDefault()))
                                : "—"));
            }
        }

        // In-memory filtering and search
        List<AttachedDocumentItem> filtered = filterDocuments(allDocs, q, status, sort);

        // Pagination
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * pageSize;
        int to = Math.min(from + pageSize, filtered.size());
        List<AttachedDocumentItem> pageItems = filtered.subList(from, to);

        // Filter options
        List<FilterOption> statusOptions = List.of(
                new FilterOption("READY", "Bereit"),
                new FilterOption("INGESTION_PENDING", "Ausstehend"),
                new FilterOption("INGESTING", "In Verarbeitung"),
                new FilterOption("DRAFT", "Entwurf"),
                new FilterOption("FAILED", "Fehlgeschlagen"));

        model.addAttribute("documents", pageItems);
        model.addAttribute("docPage", page);
        model.addAttribute("docTotalPages", totalPages);
        model.addAttribute("docBaseUrl", "/cases/" + id + "/documents");
        model.addAttribute("docQueryParams", buildDocQueryParams(q, status, sort));
        model.addAttribute("docCurrentQ", q);
        model.addAttribute("docCurrentStatus", status);
        model.addAttribute("docStatusOptions", statusOptions);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: documentsTab";
    }

    @DeleteMapping("/cases/{id}/documents/{linkId}")
    public String detachDocument(@PathVariable String id,
                                  @PathVariable String linkId,
                                  @AuthenticationPrincipal AuthenticatedUser user,
                                  Model model) {
        // Geovorgänge: Foto-Dokumente dürfen von KEINER Anwendungsrolle gelöst
        // werden (Lösch-/Archiv-Sperre für GEO-Bestände).
        caseAccessGuard.requireNotGeo(caseAccessGuard.requireWriteAccess(id, user));
        try {
            workspaceService.detachDocument(id, linkId);
            log.info("Detached document link {} from case {}", linkId, id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokumentverknüpfung nicht gefunden");
        }
        // Re-render the document list
        return documentsTab(id, null, null, null, 0, user, model);
    }

    // --- Attach documents ---

    @GetMapping("/cases/{id}/attach")
    public String attachDocumentsForm(@PathVariable String id,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @AuthenticationPrincipal AuthenticatedUser user,
                                      Model model) {
        caseAccessGuard.requireAccess(id, user);

        // Get already-attached document IDs to exclude them
        List<WorkspaceDocumentLinkEntity> existingLinks = workspaceService.getWorkspaceDocuments(id);
        Set<String> attachedDocIds = existingLinks.stream()
                .map(WorkspaceDocumentLinkEntity::getDocumentId)
                .collect(java.util.stream.Collectors.toSet());

        // Search all documents via DocumentFacade
        List<AttachedDocumentItem> candidateDocs = new ArrayList<>();
        try {
            var filter = new reasoning.document.api.DocumentFilter(
                    null, null, null, null, null, null, null, 0, 200);
            var result = documentFacade.findDocuments(filter);
            for (Document doc : result.documents()) {
                if (!attachedDocIds.contains(doc.id().toString())) {
                    candidateDocs.add(new AttachedDocumentItem(
                            "", doc.id().toString(),
                            displayTitle(doc, null),
                            doc.metadata().type() != null ? doc.metadata().type().name() : "—",
                            statusLabel(doc.status()), statusVariant(doc.status()),
                            doc.currentVersion(), "—"));
                }
            }
        } catch (Exception e) {
            log.warn("Could not search documents: {}", e.getMessage());
        }

        // In-memory search
        if (q != null && !q.isEmpty()) {
            String lower = q.toLowerCase();
            candidateDocs = candidateDocs.stream()
                    .filter(d -> d.title().toLowerCase().contains(lower))
                    .collect(java.util.stream.Collectors.toList());
        }

        // Pagination
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil((double) candidateDocs.size() / pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * pageSize;
        int to = Math.min(from + pageSize, candidateDocs.size());

        model.addAttribute("candidateDocs", candidateDocs.subList(from, to));
        model.addAttribute("attachPage", page);
        model.addAttribute("attachTotalPages", totalPages);
        model.addAttribute("attachBaseUrl", "/cases/" + id + "/attach");
        model.addAttribute("attachQuery", q != null ? q : "");
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: attachDocuments";
    }

    @PostMapping("/cases/{id}/attach")
    public String attachDocuments(@PathVariable String id,
                                   @RequestParam List<String> docIds,
                                   @AuthenticationPrincipal AuthenticatedUser user,
                                   Model model) {
        caseAccessGuard.requireWriteAccess(id, user);

        int attached = 0;
        for (String docId : docIds) {
            try {
                Document doc = documentFacade.getDocument(UUID.fromString(docId), user.id().toString());
                // The link type reflects the document's OWN category — never a
                // hardcoded placeholder (e.g. CONTRACT for a passport request).
                var docType = parseDocumentCategory(doc.metadata().category());
                var cmd = new reasoning.workspace.api.AttachDocumentCommand(
                        id, docId, docType,
                        doc.metadata().category() != null ? doc.metadata().category() : "general",
                        null);
                workspaceService.attachDocument(cmd);
                attached++;
            } catch (Exception e) {
                log.warn("Could not attach document {} to case {}: {}", docId, id, e.getMessage());
            }
        }
        log.info("Attached {} documents to case {}", attached, id);

        // Re-render the documents tab
        return documentsTab(id, null, null, null, 0, user, model);
    }

    // --- Document helpers ---

    private List<AttachedDocumentItem> filterDocuments(List<AttachedDocumentItem> docs,
                                                        String q, String status, String sort) {
        // Filter by status
        if (status != null && !status.isEmpty()) {
            docs = docs.stream()
                    .filter(d -> d.statusKey().equals(status))
                    .collect(java.util.stream.Collectors.toList());
        }
        // Search by name
        if (q != null && !q.isEmpty()) {
            String lower = q.toLowerCase();
            docs = docs.stream()
                    .filter(d -> d.title().toLowerCase().contains(lower))
                    .collect(java.util.stream.Collectors.toList());
        }
        // Sort
        if (sort != null && !sort.isEmpty()) {
            java.util.Comparator<AttachedDocumentItem> comp = switch (sort) {
                case "title" -> java.util.Comparator.comparing(d -> d.title().toLowerCase());
                case "status" -> java.util.Comparator.comparing(AttachedDocumentItem::statusLabel);
                case "uploadedAt" -> java.util.Comparator.comparing(AttachedDocumentItem::uploadedAt);
                default -> java.util.Comparator.comparing(AttachedDocumentItem::uploadedAt).reversed();
            };
            docs = docs.stream().sorted(comp).collect(java.util.stream.Collectors.toList());
        }
        return docs;
    }

    private String buildDocQueryParams(String q, String status, String sort) {
        StringBuilder sb = new StringBuilder();
        if (status != null && !status.isEmpty()) sb.append("status=").append(status);
        if (q != null && !q.isEmpty()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append("q=").append(q);
        }
        if (sort != null && !sort.isEmpty()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append("sort=").append(sort);
        }
        return sb.toString();
    }

    /**
     * User-facing document label: metadata title first, then the workspace
     * link name, then a neutral fallback. The raw document UUID must never
     * be the primary label — it stays available as metadata in the document
     * viewer.
     */
    /** The document's own category string as a DocumentCategory enum (fallback OTHER). */
    private static reasoning.common.model.DocumentCategory parseDocumentCategory(String category) {
        if (category == null || category.isBlank()) {
            return reasoning.common.model.DocumentCategory.OTHER;
        }
        try {
            return reasoning.common.model.DocumentCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return reasoning.common.model.DocumentCategory.OTHER;
        }
    }

    /**
     * Real document title for the case overview — the stored link name is
     * often null (falling back to the raw UUID), so the document metadata is
     * the primary source.
     */
    private String resolveDocTitle(WorkspaceDocumentDto d, AuthenticatedUser user) {
        try {
            Document doc = documentFacade.getDocument(UUID.fromString(d.documentId()),
                    user != null ? user.id().toString() : "system");
            if (doc.metadata() != null && doc.metadata().title() != null && !doc.metadata().title().isBlank()) {
                return doc.metadata().title();
            }
        } catch (Exception e) {
            log.debug("Document title lookup failed for {}: {}", d.documentId(), e.getMessage());
        }
        if (d.documentName() != null && !d.documentName().isBlank()) {
            return d.documentName();
        }
        return d.documentId();
    }

    private static String displayTitle(Document doc, WorkspaceDocumentLinkEntity link) {
        if (doc != null && doc.metadata() != null && doc.metadata().title() != null
                && !doc.metadata().title().isBlank()) {
            return doc.metadata().title();
        }
        if (link != null && link.getDocumentName() != null && !link.getDocumentName().isBlank()) {
            return link.getDocumentName();
        }
        return "Dokument ohne Titel";
    }

    static String statusLabel(DocumentStatus s) {
        return switch (s) {
            case READY -> "Bereit";
            case DRAFT -> "Entwurf";
            case INGESTION_PENDING -> "Ausstehend";
            case INGESTING -> "In Verarbeitung";
            case FAILED -> "Fehlgeschlagen";
            case ARCHIVED -> "Archiviert";
            case OBSOLETE -> "Veraltet";
            case DELETED -> "Gelöscht";
        };
    }

    static String statusVariant(DocumentStatus s) {
        return switch (s) {
            case READY -> "success";
            case DRAFT, INGESTION_PENDING -> "warning";
            case INGESTING -> "info";
            case FAILED, DELETED -> "error";
            case ARCHIVED, OBSOLETE -> "neutral";
        };
    }

    public record AttachedDocumentItem(String linkId, String documentId, String title,
                                        String type, String statusLabel, String statusVariant,
                                        int version, String uploadedAt) {
        public String statusKey() {
            return switch (statusLabel) {
                case "Bereit" -> "READY";
                case "Entwurf" -> "DRAFT";
                case "Ausstehend" -> "INGESTION_PENDING";
                case "In Verarbeitung" -> "INGESTING";
                case "Fehlgeschlagen" -> "FAILED";
                default -> "READY";
            };
        }
    }

    public record FilterOption(String value, String label) {}

    @GetMapping("/cases/{id}/timeline")
    public String timelineTab(@PathVariable String id,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        caseAccessGuard.requireAccess(id, user);
        List<CaseDetailViewModel.TimelineItem> timeline = caseTimelineService.build(id).stream()
                .map(e -> new CaseDetailViewModel.TimelineItem(
                        e.id(), e.date(),
                        e.title() != null ? e.title() : "",
                        e.detail(), e.type(),
                        e.aiGenerated(), e.confidence()))
                .toList();
        model.addAttribute("timeline", timeline);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: timelineTab";
    }

    @GetMapping("/cases/{id}/notes")
    public String notesTab(@PathVariable String id,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           Model model) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<NoteItem> notes = parseNotes(entity.getPhaseData());
        model.addAttribute("notes", notes);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: notesTab";
    }

    @PostMapping("/cases/{id}/notes")
    public String addNote(@PathVariable String id,
                          @RequestParam String text,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        caseAccessGuard.requireWriteAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<NoteItem> notes = new ArrayList<>(parseNotes(entity.getPhaseData()));
        notes.add(new NoteItem(UUID.randomUUID().toString(), text,
                user.displayName() != null ? user.displayName() : user.email(),
                Instant.now().toString()));
        saveNotes(entity, notes);
        model.addAttribute("notes", notes);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: notesTab";
    }

    @GetMapping("/cases/{id}/checklist")
    public String checklistTab(@PathVariable String id,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ChecklistItem> items = parseChecklist(entity.getPhaseData(), entity.getPhase());
        model.addAttribute("checklist", items);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: checklistTab";
    }

    @PutMapping("/cases/{id}/checklist/{itemId}")
    public String toggleChecklistItem(@PathVariable String id,
                                      @PathVariable String itemId,
                                      @RequestParam(defaultValue = "toggle") String mode,
                                      @AuthenticationPrincipal AuthenticatedUser user,
                                      Model model,
                                      jakarta.servlet.http.HttpServletResponse response) {
        // The checklist gate is shown in the phase header — ask the client to refresh it after every toggle.
        response.setHeader("HX-Trigger", "{\"refreshPhaseState\": \"/cases/" + id + "/phase-state\"}");
        caseAccessGuard.requireWriteAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ChecklistItem> items = new ArrayList<>(parseChecklist(entity.getPhaseData(), entity.getPhase()));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(itemId)) {
                ChecklistItem old = items.get(i);
                if ("not-required".equals(mode)) {
                    items.set(i, new ChecklistItem(old.id(), old.label(), old.phase(),
                            old.completed(), !old.notRequired()));
                } else {
                    items.set(i, new ChecklistItem(old.id(), old.label(), old.phase(),
                            !old.completed(), old.notRequired()));
                }
                break;
            }
        }
        saveChecklist(entity, items);
        model.addAttribute("checklist", items);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: checklistTab";
    }

    // --- Fallbriefing ---

    /**
     * Fallbriefing tab: shows the stored briefing, the live generation
     * progress (the fragment polls itself while a job is running) or the
     * empty state with the "erstellen" action.
     *
     * <p>Der optionale {@code job}-Parameter wird vom Fortschritts-Fragment
     * beim Polling mitgeschickt: so kann der Endpunkt den Übergang
     * „läuft → fertig" erkennen und GENAU DANN die fertige PDF automatisch
     * öffnen (autoOpenPdf, einmalig beim Abschluss). Beim bloßen erneuten
     * Öffnen des Tabs wird nie automatisch ein Tab geöffnet. Nach dem
     * Verbrauch wird der Job abgemeldet — ein späterer Besuch rendert ohne
     * autoOpenPdf.</p>
     */
    @GetMapping("/cases/{id}/briefing")
    public String briefingTab(@PathVariable String id,
                              @RequestParam(value = "job", required = false) String jobId,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        caseAccessGuard.requireAccess(id, user);
        model.addAttribute("caseId", id);
        CaseBriefingService.Briefing stored = caseBriefingService.load(id);
        if (jobId != null && !jobId.isBlank()) {
            Job job = progressService.get(jobId);
            if (job != null && "DONE".equals(job.state)) {
                // Abschluss des Live-Laufs: Briefing anzeigen und die fertige
                // PDF genau einmal automatisch öffnen; danach Job abmelden.
                model.addAttribute("briefing", stored);
                model.addAttribute("briefingJob", null);
                model.addAttribute("briefingStale", caseBriefingService.isStale(id, stored));
                model.addAttribute("autoOpenPdf", true);
                progressService.unregisterActive("briefing:" + id, jobId);
                return "cases/detail-fragments :: briefingTab";
            }
            if (job != null && "ERROR".equals(job.state)) {
                model.addAttribute("briefing", stored);
                model.addAttribute("briefingJob", null);
                model.addAttribute("briefingError",
                        "Das Fallbriefing konnte nicht erstellt werden. Bitte versuchen Sie es erneut.");
                progressService.unregisterActive("briefing:" + id, jobId);
                return "cases/detail-fragments :: briefingTab";
            }
        }
        model.addAttribute("briefing", stored);
        model.addAttribute("briefingJob", progressService.activeJob("briefing:" + id));
        model.addAttribute("briefingStale", caseBriefingService.isStale(id, stored));
        model.addAttribute("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport
                .pipelineNodes(progressService.activeJob("briefing:" + id)));
        return "cases/detail-fragments :: briefingTab";
    }

    /**
     * Starts the briefing generation. Wenn eine abgeschlossene Fall-Analyse
     * ohne neue Unterlagen vorliegt (canReuseAnalysis), wird das Briefing
     * OHNE neuen Pipeline-Lauf aus der persistierten Analyse abgeleitet
     * (sofort fertig, keine redundante Retrieval-/Reasoning-Arbeit). Sonst
     * läuft die kommunale Analyse-Pipeline (fall-scoped retrieval → evidence
     * → grounding → verifier); die job id ist zugleich die Pipeline-Request-
     * id, sodass der Fortschritts-Listener den Live-Stufenstrom in den Job
     * leitet und der Tab den Fortschritt (inkl. Pipeline-Diagramm) anzeigt.
     */
    @PostMapping("/cases/{id}/briefing/generate")
    public String generateBriefing(@PathVariable String id,
                                   @AuthenticationPrincipal AuthenticatedUser user,
                                   Model model) {
        caseAccessGuard.requireWriteAccess(id, user);
        model.addAttribute("caseId", id);
        // Wiederverwendung der persistierten Analyse statt eines zweiten
        // vollständigen Pipeline-Laufs (keine neue Analyse-Architektur).
        if (caseBriefingService.canReuseAnalysis(id)) {
            try {
                String actorEmail = user != null && user.email() != null ? user.email() : "system";
                CaseBriefingService.Briefing briefing =
                        caseBriefingService.generateFromAnalysis(id, actorEmail);
                caseBriefingService.store(id, briefing);
                model.addAttribute("briefing", briefing);
                model.addAttribute("briefingJob", null);
                model.addAttribute("briefingStale", false);
                model.addAttribute("autoOpenPdf", true);
                return "cases/detail-fragments :: briefingTab";
            } catch (Exception e) {
                log.warn("Briefing aus Fall-Analyse für {} fehlgeschlagen, Pipeline-Lauf: {}",
                        id, e.getMessage());
            }
        }
        String key = "briefing:" + id;
        Job running = progressService.activeJob(key);
        if (running == null) {
            WorkspaceEntity entity = workspaceService.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
            String actorEmail = user != null && user.email() != null ? user.email() : "system";
            Job job = progressService.create(JobProgressService.Kind.ASSISTANT,
                    "Fallbriefing für " + (entity.getName() != null ? entity.getName() : id));
            progressService.registerActive(key, job.jobId);
            progressService.recordStage(job.jobId,
                    "Das Fallbriefing wird über die kommunale Analyse-Pipeline erstellt …");
            executor.submit(() -> runBriefing(job.jobId, id, actorEmail));
            model.addAttribute("briefingJob", job);
            model.addAttribute("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport
                    .pipelineNodes(job));
        } else {
            model.addAttribute("briefingJob", running);
            model.addAttribute("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport
                    .pipelineNodes(running));
        }
        model.addAttribute("briefing", caseBriefingService.load(id));
        model.addAttribute("briefingStale", false);
        return "cases/detail-fragments :: briefingTab";
    }

    private void runBriefing(String jobId, String caseId, String actorEmail) {
        try {
            CaseBriefingService.Briefing briefing = caseBriefingService.generate(caseId, actorEmail, jobId);
            caseBriefingService.store(caseId, briefing);
            progressService.completeWithData(jobId, null, briefing, "Fallbriefing erstellt.");
        } catch (Exception e) {
            log.warn("Fallbriefing für {} fehlgeschlagen: {}", caseId, e.getMessage());
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive("briefing:" + caseId, jobId);
        }
    }

    /**
     * Exports the stored briefing as PDF (rendered as-is, no pipeline
     * re-run). Mit {@code inline=true} wird die PDF im Browser angezeigt
     * ("PDF öffnen" in einem neuen Tab); ohne den Parameter als Download
     * ("Als PDF exportieren").
     */
    @GetMapping("/cases/{id}/briefing/pdf")
    public ResponseEntity<byte[]> exportBriefingPdf(@PathVariable String id,
                                                    @RequestParam(value = "inline", required = false) String inline,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user);
        CaseBriefingService.Briefing briefing = caseBriefingService.load(id);
        if (briefing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Für diesen Fall wurde noch kein Fallbriefing erstellt.");
        }
        // Vorgangskontext für die PDF-Kopfzeile (Auslöser, Fallart, Zuständige,
        // abgeschlossen) — die Briefing-Inhalte selbst bleiben unverändert.
        Map<String, Object> extra = new HashMap<>();
        try {
            WorkspaceEntity ws = workspaceService.findById(id).orElse(null);
            if (ws != null) {
                extra.put("caseOrigin", CaseDetailController.caseOriginOf(ws));
                extra.put("caseCategory", verwaltungsassistent.web.planning.CasePlanningService
                        .categoryOf(ws, ws.getPhaseDataMap()));
                extra.put("caseClosed", ws.getStatus() == WorkspaceStatus.CLOSED);
                if (ws.getOwnerId() != null) {
                    userAccountRepository.findByEmail(ws.getOwnerId().toLowerCase())
                            .ifPresent(u -> {
                                extra.put("caseOwnerName", u.getDisplayName() != null
                                        ? u.getDisplayName() : ws.getOwnerId());
                                extra.put("caseOwnerEmail", ws.getOwnerId());
                            });
                }
            }
        } catch (Exception e) {
            log.debug("Briefing-Kopfkontext für {} nicht lesbar: {}", id, e.getMessage());
        }
        try {
            byte[] pdf = briefingPdfExporter.export(briefing, extra);
            String fileName = "fallbriefing-" + briefing.vorgangsnummer()
                    .replaceAll("[^a-zA-Z0-9äöüÄÖÜß-]", "_") + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/pdf"))
                    .header("Content-Disposition",
                            ("true".equals(inline) ? "inline" : "attachment") + "; filename=\"" + fileName + "\"")
                    .body(pdf);
        } catch (java.io.IOException e) {
            log.error("Fallbriefing-PDF für Fall {} fehlgeschlagen: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Die PDF-Erstellung ist fehlgeschlagen.");
        }
    }

    // --- Phase advance ---

    /**
     * Überführung des temporären View-Anspruchs in eine echte Zuweisung,
     * sobald die Mitarbeiterin einen echten Verarbeitungsschritt startet
     * (Phasen-Vorlauf in die Analyse/Entscheidungs-Phasen oder
     * Ingestion-fortführen). Fremder Anspruch → 423; ohne Anspruch gelten
     * die bisherigen Autorisierungs-Semantiken (kein Umweg, keine Erfindung).
     * Nutzt denselben Pfad wie der Analyse-Start im DecisionWorkspace.
     */
    private void promoteViewClaimToAssignment(String caseId, AuthenticatedUser user) {
        if (user == null || user.email() == null) {
            return;
        }
        var ctx = verwaltungsassistent.web.config.SpringContextProvider.context();
        if (ctx == null) {
            return;
        }
        try {
            var claimSvc = ctx.getBean(
                    verwaltungsassistent.web.planning.CaseViewClaimService.class);
            var entity = workspaceService.findById(caseId).orElse(null);
            if (verwaltungsassistent.web.planning.CaseViewClaimService.isClaimable(entity)
                    && claimSvc.promoteIfClaimedBy(caseId, user.email())) {
                ctx.getBean(verwaltungsassistent.web.planning.CaseAssignmentService.class)
                        .assign(caseId, user.email(), true);
            }
        } catch (verwaltungsassistent.web.planning.CaseViewClaimService
                .ClaimConflictException e) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Dieser Vorgang wird gerade von einer anderen Mitarbeiterin bearbeitet.");
        }
    }

    /** Phasen, ab denen ein Vorgang tatsächlich verarbeitet wird (kein reines Ansehen). */
    private static boolean isProcessingPhase(reasoning.common.model.WorkspacePhase phase) {
        if (phase == null) {
            return false;
        }
        return switch (phase.name()) {
            case "ANALYSIS", "REVIEW", "DECISION", "COMPLETE" -> true;
            default -> false;
        };
    }

    @PostMapping("/cases/{id}/phase")
    public String advancePhase(@PathVariable String id,
                               @RequestParam(defaultValue = "advance") String direction,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        caseAccessGuard.requireWriteAccess(id, user);
        WorkspaceEntity loaded = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
        // Abgeschlossener Vorgang: Phasen-Navigation nur über die EXPLIZITE
        // Wiederaufnahme (Phase 2D.7) — kein stiller Phasen-Rückbau hinter dem
        // Status "Geschlossen".
        if (loaded.getStatus() == WorkspaceStatus.CLOSED) {
            CaseDetailViewModel vm = buildViewModel(workspaceService.toDto(loaded), loaded, user,
                    "Der Vorgang ist abgeschlossen — Phasenänderungen sind nur nach einer Wiederaufnahme möglich.");
            model.addAttribute("case", vm);
            model.addAttribute("caseId", id);
            return "cases/detail-fragments :: phaseState";
        }
        WorkspaceEntity entity;
        try {
            if ("previous".equals(direction)) {
                entity = workspaceService.previousPhase(id);
            } else {
                entity = workspaceService.advancePhase(id);
            }
        } catch (RuntimeException e) {
            log.warn("Phase change failed for case {}: {}", id, e.getMessage());
            WorkspaceEntity current = workspaceService.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
            CaseDetailViewModel vm = buildViewModel(workspaceService.toDto(current), current, user,
                    e.getMessage() != null ? e.getMessage() : "Die Verarbeitung konnte nicht fortgesetzt werden.");
            model.addAttribute("case", vm);
            model.addAttribute("caseId", id);
            return "cases/detail-fragments :: phaseState";
        }
        // Vorlauf in eine Verarbeitungs-Phase (Analyse/…): View-Anspruch wird
        // zur echten Zuweisung (Arbeitspool → „mir zugewiesen").
        if ("advance".equals(direction) && isProcessingPhase(entity.getPhase())) {
            promoteViewClaimToAssignment(id, user);
            entity = workspaceService.findById(id).orElse(entity);
        }
        WorkspaceDto dto = workspaceService.toDto(entity);
        CaseDetailViewModel vm = buildViewModel(dto, entity, user);
        model.addAttribute("case", vm);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: phaseState";
    }

    /** Re-renders the phase header (progress + phase section) — used to refresh gate state after checklist changes. */
    @GetMapping("/cases/{id}/phase-state")
    public String phaseStateFragment(@PathVariable String id,
                                     @AuthenticationPrincipal AuthenticatedUser user,
                                     Model model) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        CaseDetailViewModel vm = buildViewModel(workspaceService.toDto(entity), entity, user);
        model.addAttribute("case", vm);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: phaseState";
    }

    /**
     * Explicit human acknowledgment that Ingestion is resolved although no case
     * documents exist. Only marks the persisted per-case state; no documents are
     * pretended to have been ingested.
     */
    @PostMapping("/cases/{id}/phase/ingestion-resolve")
    public String resolveIngestion(@PathVariable String id,
                                   @AuthenticationPrincipal AuthenticatedUser user,
                                   Model model) {
        caseAccessGuard.requireWriteAccess(id, user);
        WorkspaceEntity entity = workspaceService.resolveIngestionWithoutDocuments(id);
        // „ohne Dokumente fortfahren" startet die Verarbeitung des Vorgangs:
        // View-Anspruch → echte Zuweisung (Arbeitspool → „mir zugewiesen").
        promoteViewClaimToAssignment(id, user);
        entity = workspaceService.findById(id).orElse(entity);
        WorkspaceDto dto = workspaceService.toDto(entity);
        CaseDetailViewModel vm = buildViewModel(dto, entity, user);
        model.addAttribute("case", vm);
        model.addAttribute("caseId", id);
        return "cases/detail-fragments :: phaseState";
    }

    /** Data holder for tab bar configuration — avoids T() in templates. */
    public record TabDef(String id, String label, String url) {
        public String getContent() { return null; }
    }

    public static TabDef tabDef(String id, String label, String url) {
        return new TabDef(id, label, url);
    }

    // --- Helpers ---

    private CaseDetailViewModel buildViewModel(WorkspaceDto dto, WorkspaceEntity entity, AuthenticatedUser user) {
        return buildViewModel(dto, entity, user, null);
    }

    private CaseDetailViewModel buildViewModel(WorkspaceDto dto, WorkspaceEntity entity,
                                               AuthenticatedUser user, String phaseError) {
        List<PhaseDef> phases = List.of(
                new PhaseDef("Einrichtung"),
                new PhaseDef("Ingestion"),
                new PhaseDef("Analyse"),
                new PhaseDef("Überprüfung"),
                new PhaseDef("Abschluss"));

        WorkspacePhase currentPhase = dto.phase() != null ? dto.phase() : WorkspacePhase.SETUP;
        int phaseIndex = currentPhase.ordinal();
        String blockReason = workspaceService.advanceBlockReason(dto.id());
        // Abgeschlossener Vorgang = Endzustand: keine Phasen-Navigation
        // (weder zurück noch vor), bis die EXPLIZITE Wiederaufnahme erfolgt
        // (Phase 2D.7 — konsistent mit dem Abschluss-Abschnittstext).
        boolean caseClosed = dto.status() == WorkspaceStatus.CLOSED;
        boolean canAdvance = !caseClosed && blockReason == null && currentPhase != WorkspacePhase.COMPLETE;
        // Die Blockier-Hinweisbox soll den Nutzer nicht im eigenen Zustand
        // verwirren: In der Analyse-Phase OHNE Analyse-Marker ist "Die Analyse
        // muss zuerst durchgeführt werden." genau das, was die Phase selbst
        // anbietet ("Analyse starten") — die Warnung ist dort eine widersprüch-
        // liche Zwischenbotschaft und wird ausgeblendet.
        boolean showAdvanceBlockReason = blockReason != null
                && !(currentPhase == WorkspacePhase.ANALYSIS && analysisMarker(entity) == null);
        boolean canGoBack = !caseClosed && currentPhase != WorkspacePhase.SETUP;

        String nextLabel = currentPhase != WorkspacePhase.COMPLETE
                ? phaseLabel(WorkspacePhase.values()[phaseIndex + 1]) : null;
        String prevLabel = canGoBack ? phaseLabel(WorkspacePhase.values()[phaseIndex - 1]) : null;

        List<DocumentItem> docs = dto.documents() != null ? dto.documents().stream()
                .map(d -> new DocumentItem(d.documentId(),
                        resolveDocTitle(d, user),
                        d.documentType() != null ? DocumentController.categoryLabel(d.documentType().name()) : "",
                        ""))
                .toList() : List.of();

        // Timeline-Reihenfolge ist an der QUELLE deterministisch (Repository:
        // eventDate ASC, createdAt ASC — Phase 2D.6); die Web-Schicht sortiert
        // nicht erneut, sondern übernimmt die geordnete Liste des DTO.
        List<TimelineItem> timeline = dto.timelineEvents() != null ? dto.timelineEvents().stream()
                .map(e -> new TimelineItem(e.id(),
                        e.eventDate() != null ? e.eventDate().format(DATE_FMT) : "—",
                        e.title() != null ? e.title() : "",
                        null,
                        e.eventType() != null ? e.eventType().name() : "",
                        e.aiGenerated(), e.confidence()))
                .toList() : List.of();
        // „Letzte Ereignisse" auf der Übersicht: die NEUESTEN Ereignisse
        // (chronologisch aufsteigend gerendert) — nicht die ältesten fünf.
        List<TimelineItem> recentTimeline = timeline.size() <= 5
                ? timeline : timeline.subList(timeline.size() - 5, timeline.size());

        List<NoteItem> notes = parseNotes(entity.getPhaseData());
        List<ChecklistItem> checklist = parseChecklist(entity.getPhaseData(), entity.getPhase());
        DocStatusSummary docStatus = summarizeDocuments(dto, user);
        PhaseSection phaseSection = buildPhaseSection(currentPhase, dto, entity, checklist, docStatus);

        // Sonderfall "Analyse starten" im Phasen-Balken: Ist die Ingestion
        // abgeschlossen (dasselbe Gate wie im Ingestion-Abschnitt: Dokumente
        // verarbeitet ODER explizit ohne Dokumente fortgeführt) und noch keine
        // Analyse gestartet, ist der ERSTE aktionsfähige Button die Analyse
        // selbst — der Phasen-Balken ruft den Analyse-Start-Endpoint mit
        // advance=true auf (Phase INGESTION → ANALYSIS UND Start in einem
        // Schritt). Es gibt keinen Zwischenschritt "Analyse →" gefolgt von
        // einem zweiten "Analyse starten" mehr.
        boolean ingestionGateOpen = (docStatus.total() > 0 && docStatus.ready() > 0)
                || Boolean.TRUE.equals(parseJson(entity.getPhaseData()).get("ingestionResolved"));
        boolean nextPhaseStartsAnalysis = currentPhase == WorkspacePhase.INGESTION
                && ingestionGateOpen
                && analysisMarker(entity) == null;
        String nextPhaseHxPost = nextPhaseStartsAnalysis
                ? "/cases/" + dto.id() + "/decision/analyze?redirect=true&advance=true"
                : "/cases/" + dto.id() + "/phase?direction=advance";

        return new CaseDetailViewModel(
                dto.id(), dto.name() != null ? dto.name() : dto.workspaceCode(),
                dto.workspaceCode(), dto.description(),
                workspaceTypeLabel(entity.getWorkspaceType()),
                CaseController.statusLabel(dto.status()),
                CaseController.statusVariant(dto.status()),
                phaseLabel(currentPhase), phaseIndex,
                entity.getOwnerId(),
                dto.documents() != null ? dto.documents().size() : 0,
                emailCountOf(dto.id()),
                dto.timelineEvents() != null ? dto.timelineEvents().size() : 0,
                workspaceService.getCompletedSteps(dto.id()).size(),
                canAdvance, blockReason, showAdvanceBlockReason, canGoBack, nextLabel, nextPhaseHxPost,
                nextPhaseStartsAnalysis, prevLabel,
                dto.createdAt() != null
                        ? DATE_FMT.format(LocalDate.ofInstant(dto.createdAt(), ZoneId.systemDefault())) : "—",
                dto.updatedAt() != null
                        ? DATE_FMT.format(LocalDate.ofInstant(dto.updatedAt(), ZoneId.systemDefault())) : "—",
                caseOriginOf(entity),
                phases, docs, docs.size() <= 5 ? docs : docs.subList(0, 5),
                timeline, recentTimeline,
                notes, checklist, phaseError, phaseSection);
    }

    // --- Phase content (was wurde gemacht / Ergebnis / offene Punkte / nächster Schritt) ---

    private record DocStatusSummary(int total, int ready, int processing, int failed,
                                    int unknown, List<String> processingNames, List<String> failedNames) {}

    private DocStatusSummary summarizeDocuments(WorkspaceDto dto, AuthenticatedUser user) {
        int total = 0, ready = 0, processing = 0, failed = 0, unknown = 0;
        List<String> processingNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();
        if (dto.documents() != null) {
            for (WorkspaceDocumentDto d : dto.documents()) {
                total++;
                String name = d.documentName() != null ? d.documentName() : d.documentId();
                try {
                    Document doc = documentFacade.getDocument(UUID.fromString(d.documentId()),
                            user != null ? user.id().toString() : "system");
                    switch (doc.status()) {
                        case READY -> ready++;
                        case INGESTION_PENDING, INGESTING, DRAFT -> { processing++; processingNames.add(name); }
                        case FAILED, DELETED -> { failed++; failedNames.add(name); }
                        default -> unknown++;
                    }
                } catch (Exception e) {
                    unknown++;
                }
            }
        }
        return new DocStatusSummary(total, ready, processing, failed, unknown, processingNames, failedNames);
    }

    private PhaseSection buildPhaseSection(WorkspacePhase phase, WorkspaceDto dto, WorkspaceEntity entity,
                                           List<ChecklistItem> checklist, DocStatusSummary docs) {
        return switch (phase) {
            case SETUP -> setupSection(dto, entity, docs);
            case INGESTION -> ingestionSection(dto, entity, docs);
            case ANALYSIS -> analysisSection(dto, entity);
            case REVIEW -> reviewSection(dto, entity, checklist);
            case COMPLETE -> completeSection(dto, entity, checklist, docs);
        };
    }

    private PhaseSection setupSection(WorkspaceDto dto, WorkspaceEntity entity, DocStatusSummary docs) {
        Map<String, Object> data = parseJson(entity.getPhaseData());
        List<InfoRow> info = new ArrayList<>();
        info.add(new InfoRow("Anliegen", dto.name() != null ? dto.name() : dto.workspaceCode()));
        info.add(new InfoRow("Vorgang", dto.workspaceCode()));
        String source = (String) data.get("source");
        info.add(new InfoRow("Auslöser", source != null ? source : "Manuell angelegt"));
        Object citizen = data.get("citizen");
        if (citizen != null) info.add(new InfoRow("Person", citizen.toString()));

        // Ausgangsanfrage: the originating e-mail, linked by its analysis id
        // (stored in phase data), never copied into the case.
        String sourceEmailId = (String) data.get("sourceEmailId");
        if (sourceEmailId != null) {
            String at = (String) data.get("sourceEmailAt");
            String received = at != null
                    ? "E-Mail vom " + DateTimeFormats.formatInstant(Instant.parse(at), null) : "E-Mail";
            info.add(new InfoRow("Ausgangsanfrage", received));
            Object emailSubject = data.get("sourceEmailSubject");
            if (emailSubject != null && !emailSubject.toString().isBlank()) {
                info.add(new InfoRow("Betreff", emailSubject.toString()));
            }
        }

        info.add(new InfoRow("Status", CaseController.statusLabel(dto.status())));
        info.add(new InfoRow("Erstellt", dto.createdAt() != null
                ? DATE_FMT.format(LocalDate.ofInstant(dto.createdAt(), ZoneId.systemDefault())) : "—"));

        List<String> done = new ArrayList<>();
        done.add("Fall angelegt und beschrieben");
        done.add("Falltyp: " + workspaceTypeLabel(entity.getWorkspaceType()));
        List<String> open = new ArrayList<>();
        if (docs.total() == 0) {
            open.add("Noch keine Dokumente zugeordnet");
        } else if (docs.processing() > 0) {
            open.add(docs.processing() + " Dokument(e) werden noch verarbeitet");
        }
        List<ActionLink> actions = new ArrayList<>(List.of(
                new ActionLink("Fall umbenennen", "/cases/" + dto.id() + "/settings", ""),
                new ActionLink("Dokumente verwalten", "tab:documents", "")));
        if (sourceEmailId != null) {
            actions.add(new ActionLink("Zur E-Mail-Analyse", "/emails?open=" + sourceEmailId, ""));
        }
        return new PhaseSection("Einrichtung abgeschlossen", "success", "Einrichtung",
                info, done, open, "Unterlagen und vorhandene Dokumente verarbeiten – Phase Ingestion", actions);
    }

    private PhaseSection ingestionSection(WorkspaceDto dto, WorkspaceEntity entity, DocStatusSummary docs) {
        Map<String, Object> data = parseJson(entity.getPhaseData());
        boolean resolvedWithoutDocs = Boolean.TRUE.equals(data.get("ingestionResolved"));

        List<InfoRow> info = new ArrayList<>();
        info.add(new InfoRow("Dokumente gesamt", String.valueOf(docs.total())));
        info.add(new InfoRow("Erfolgreich verarbeitet", String.valueOf(docs.ready())));
        info.add(new InfoRow("In Verarbeitung", String.valueOf(docs.processing())));
        info.add(new InfoRow("Fehler", String.valueOf(docs.failed())));

        String statusLabel;
        String variant;
        if (docs.total() == 0 && !resolvedWithoutDocs) {
            statusLabel = "Keine Dokumente zugeordnet";
            variant = "warning";
        } else if (docs.total() == 0) {
            statusLabel = "Ingestion ohne Unterlagen fortgeführt";
            variant = "warning";
        } else if (docs.processing() > 0 && docs.ready() == 0) {
            statusLabel = "In Verarbeitung";
            variant = "info";
        } else if (docs.ready() == 0 && docs.failed() > 0 && docs.processing() == 0) {
            // Issue 8: alle Dokumente fehlgeschlagen — kein "In Verarbeitung",
            // sondern ein ehrlicher Fehlerzustand MIT Erholungspfad.
            statusLabel = "Dokumentverarbeitung fehlgeschlagen";
            variant = "error";
        } else {
            statusLabel = "Ingestion abgeschlossen";
            variant = "success";
        }
        List<String> done = docs.total() > 0
                ? List.of(docs.total() + " Dokument(e) verarbeitet (" + docs.ready() + " erfolgreich)")
                : List.of();
        List<String> open = new ArrayList<>();
        if (docs.total() == 0 && !resolvedWithoutDocs) {
            open.add("Unterlagen liegen derzeit nicht vor – als fehlende Nachweise behandeln");
            open.add("Analyse kann trotzdem auf Basis der Wissensbasis durchgeführt werden");
        } else if (docs.total() == 0) {
            open.add("Unterlagen noch nicht vorhanden – wurde von der Sachbearbeiterin bestätigt");
        } else {
            open.addAll(docs.processingNames().stream()
                    .map(n -> "In Verarbeitung: " + n).toList());
            open.addAll(docs.failedNames().stream()
                    .map(n -> "Fehler bei: " + n).toList());
        }
        List<ActionLink> actions = new ArrayList<>();
        actions.add(new ActionLink("Dokumente verwalten", "tab:documents", ""));
        boolean gateSatisfied = (docs.total() > 0 && docs.ready() > 0) || resolvedWithoutDocs;
        boolean allFailed = docs.total() > 0 && docs.ready() == 0 && docs.processing() == 0 && docs.failed() > 0;
        if ((docs.total() == 0 || allFailed) && !resolvedWithoutDocs) {
            // Erholungspfad: fehlgeschlagene Dokumente ersetzen/lösen über die
            // Dokumenten-Verwaltung; alternativ EXPLIZIT ohne nutzbare Unterlagen
            // fortfahren (gleiche bewusste Entscheidung wie im Null-Dokument-Fall).
            actions.add(new ActionLink("Ohne Dokumente fortfahren",
                    "post:/cases/" + dto.id() + "/phase/ingestion-resolve", "primary"));
        }
        // Ist das Ingestion-Gate offen, trägt der PHASEN-BALKEN die einzige
        // "Analyse starten"-Aktion (startet die Analyse mit advance=true) —
        // hier gibt es keinen zweiten, gleich aussehenden Start-Button.
        String nextStep;
        if (allFailed) {
            nextStep = "Fehlgeschlagene Dokumente ersetzen (Dokumente verwalten) oder explizit ohne Unterlagen fortfahren – erst dann Phase Analyse";
        } else if (!gateSatisfied) {
            nextStep = "Dokumente zuordnen oder fehlende Unterlagen vormerken – erst dann Phase Analyse";
        } else {
            nextStep = "Analyse starten – die Analyse läuft dann im Entscheidungs-Workspace";
        }
        return new PhaseSection(statusLabel, variant, "Ingestion", info, done, open, nextStep, actions);
    }

    private PhaseSection analysisSection(WorkspaceDto dto, WorkspaceEntity entity) {
        List<TimelineItem> aiEvents = dto.timelineEvents() != null ? dto.timelineEvents().stream()
                .filter(TimelineEventDto::aiGenerated)
                .map(e -> new TimelineItem(e.id(),
                        e.eventDate() != null ? e.eventDate().format(DATE_FMT) : "—",
                        e.title() != null ? e.title() : "",
                        null,
                        e.eventType() != null ? e.eventType().name() : "",
                        true, e.confidence()))
                .toList() : List.of();

        Map<String, Object> analysis = analysisMarker(entity);
        String status = analysis != null ? String.valueOf(analysis.getOrDefault("status", "")) : "";
        int sourceCount = analysis != null && analysis.get("sourceCount") instanceof Number n ? n.intValue() : 0;

        List<InfoRow> info = new ArrayList<>();
        info.add(new InfoRow("Erkannte Ereignisse", String.valueOf(aiEvents.size())));
        if ("COMPLETED".equals(status)) {
            info.add(new InfoRow("Wissensbasis-Quellen", String.valueOf(sourceCount)));
        } else {
            info.add(new InfoRow("Quellen", String.valueOf(dto.documents() != null ? dto.documents().size() : 0)));
        }

        List<String> done = new ArrayList<>();
        List<String> open = new ArrayList<>();
        String statusLabel;
        String variant;
        List<ActionLink> actions;
        String nextStep;

        if ("COMPLETED".equals(status)) {
            // Ein COMPLETED-MARKER allein genügt nicht für den PDF-Export: es
            // muss ein abgeschlossener Lauf MIT abrufbarem Ergebnis existieren
            // (der Marker kann nach Abstürzen/Neustarts ohne Lauf stehenbleiben).
            boolean usable = workspaceService.latestCompletedAnalysisRun(dto.id())
                    .map(workspaceService::deserializeAnalysisResult)
                    .isPresent();
            if (!usable) {
                statusLabel = "Analyseergebnis nicht verfügbar";
                variant = "warning";
                open.add("Die Analyse wurde abgeschlossen, aber das gespeicherte Ergebnis fehlt.");
                nextStep = "Analyse erneut durchführen – erst dann Phase Überprüfung";
                actions = List.of(
                        new ActionLink("Analyse erneut starten",
                                "post:/cases/" + dto.id() + "/decision/analyze?redirect=true", "primary"));
            } else {
                statusLabel = "Analyse abgeschlossen";
                variant = "success";
                if (!aiEvents.isEmpty()) {
                    done.add(aiEvents.size() + " Ereignis(se) aus Dokumenten extrahiert");
                    aiEvents.stream().limit(5).forEach(e -> done.add("· " + e.title()));
                }
                done.add(sourceCount + " relevante Quelle(n) aus der Wissensbasis herangezogen");
                if (sourceCount == 0 && aiEvents.isEmpty()) {
                    open.add("Keine ausreichend belegten Ergebnisse gefunden");
                } else {
                    open.add("Ergebnisse fachlich prüfen und offene Punkte klären");
                }
                nextStep = "Ergebnisse prüfen – Phase Überprüfung";
                // Beide Aktionen führen NICHT in eine Schleife: "Analyseergebnis öffnen"
                // führt zur Ergebnis-Seite des Falls, der PDF-Button exportiert die
                // Entscheidungsvorlage direkt (Attachment) — kein Umweg über die
                // Entscheidungs-Seite.
                actions = List.of(
                        new ActionLink("Analyseergebnis öffnen", "/cases/" + dto.id() + "/decision", "primary"),
                        new ActionLink("PDF öffnen",
                                "/cases/" + dto.id() + "/decision/export-pdf?inline=true", ""),
                        new ActionLink("PDF herunterladen",
                                "/cases/" + dto.id() + "/decision/export-pdf", ""));
            }
        } else if ("FAILED".equals(status)) {
            statusLabel = "Analyse fehlgeschlagen";
            variant = "error";
            open.add("Die Analyse ist fehlgeschlagen. Bitte erneut versuchen.");
            nextStep = "Analyse erneut durchführen – erst dann Phase Überprüfung";
            actions = List.of(
                    // Startet den Neustart WIRKLICH (POST) und führt zur
                    // Entscheidungs-Seite, die den Fortschritt anzeigt — ein
                    // reiner Navigations-Link zur Entscheidungs-Seite zeigte dort
                    // nur den (falschen) "keine KI-Analyse"-Leerzustand.
                    new ActionLink("Erneut versuchen",
                            "post:/cases/" + dto.id() + "/decision/analyze?redirect=true", "primary"));
        } else if ("RUNNING".equals(status)) {
            statusLabel = "Analyse läuft";
            variant = "info";
            open.add("Die Analyse wird durchgeführt – Ergebnis wird im Entscheidungs-Workspace angezeigt");
            nextStep = "Ergebnis abwarten – erst dann Phase Überprüfung";
            actions = List.of(new ActionLink("Analyse anzeigen", "/cases/" + dto.id() + "/decision", "primary"));
        } else {
            statusLabel = "Noch nicht durchgeführt";
            variant = "neutral";
            open.add("Noch keine KI-Analyse durchgeführt – Analyse im Entscheidungs-Workspace starten");
            nextStep = "Analyse durchführen – erst dann Phase Überprüfung";
            actions = List.of(
                    // Startet die Analyse wirklich (POST) und führt dann zur
                    // Entscheidungs-Seite, die den Fortschritt anzeigt — kein
                    // reiner Navigations-Link, der dort einen zweiten
                    // "Analyse starten"-Button übrig lässt. Der Assistent ist
                    // über die Hauptnavigation erreichbar; er würde die
                    // Fall-Analyse nicht voranbringen.
                    new ActionLink("Analyse starten", "post:/cases/" + dto.id() + "/decision/analyze?redirect=true", "primary"));
        }

        return new PhaseSection(statusLabel, variant, "Analyse", info, done, open, nextStep, actions);
    }

    /** Reads the persisted analysis marker (written by the decision pipeline) from phase data. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> analysisMarker(WorkspaceEntity entity) {
        Object marker = parseJson(entity.getPhaseData()).get("analysis");
        return marker instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private PhaseSection reviewSection(WorkspaceDto dto, WorkspaceEntity entity, List<ChecklistItem> checklist) {
        List<ChecklistItem> done = checklist.stream()
                .filter(i -> i.completed() || i.notRequired()).toList();
        List<ChecklistItem> open = checklist.stream()
                .filter(i -> !i.completed() && !i.notRequired()).toList();
        boolean allDone = !checklist.isEmpty() && open.isEmpty();

        List<InfoRow> info = new ArrayList<>();
        info.add(new InfoRow("Prüfpunkte", done.size() + " von " + checklist.size() + " abgeschlossen"));
        // Phase 2D.4 — Entscheidungszustand der Sachbearbeitung (phaseData.
        // decision, dieselbe Quelle wie die Entscheidungs-Seite): die
        // Überprüfung liegt NACH der Entscheidung im Arbeitsfluss, daher zeigt
        // sie auch, ob diese dokumentiert ist — oder ehrlich, dass sie es
        // nicht ist.
        DecisionWorkspaceController.DecisionInfo decision =
                DecisionWorkspaceController.decisionFromPhaseData(parseJson(entity.getPhaseData()));
        info.add(new InfoRow("Entscheidung der Sachbearbeitung", decision != null
                ? "Dokumentiert durch " + decision.confirmedByName() + " am " + decision.confirmedAt()
                        + " · Analyse #" + decision.analysisVersion()
                : "Noch nicht dokumentiert"));

        // Issue 3b — Quelle der Wahrheit konsistent: die Überprüfungs-Phase zeigt
        // den Analyse-Zustand aus DEMSELBEN persistierten Marker/Lauf wie die
        // Entscheidungs-Seite. Ein abgeschlossener Lauf mit abrufbarem Ergebnis
        // ist hier sichtbar; ein COMPLETED-Marker ohne nutzbares Ergebnis wird
        // ehrlich mit funktionierendem Neustart angezeigt — kein toter
        // Entscheidung ↔ Fall-Loop.
        String analysisStatus = "";
        Map<String, Object> marker = analysisMarker(entity);
        if (marker != null) {
            Object s = marker.get("status");
            analysisStatus = s != null ? String.valueOf(s) : "";
        }
        boolean usableAnalysis = workspaceService.latestCompletedAnalysisRun(dto.id())
                .map(workspaceService::deserializeAnalysisResult)
                .isPresent();
        List<ActionLink> actions = new ArrayList<>(List.of(
                new ActionLink("Checkliste öffnen", "tab:checklist", "")));
        if (usableAnalysis) {
            info.add(new InfoRow("KI-Analyse", "Abgeschlossen — Ergebnis verfügbar"));
            actions.add(new ActionLink("Analyseergebnis öffnen", "/cases/" + dto.id() + "/decision", "primary"));
        } else if ("COMPLETED".equals(analysisStatus)) {
            info.add(new InfoRow("KI-Analyse", "Ergebnis nicht verfügbar"));
            actions.add(new ActionLink("Analyse erneut starten",
                    "post:/cases/" + dto.id() + "/decision/analyze?redirect=true", "primary"));
        }

        String statusLabel = checklist.isEmpty() ? "Keine Prüfpunkte definiert"
                : allDone ? "Überprüfung abgeschlossen" : "Überprüfung läuft";
        String variant = checklist.isEmpty() ? "neutral" : allDone ? "success" : "info";

        // Die Phase ist per Checkliste abschließbar — der nächste sinnvolle
        // Schritt hängt vom Entscheidungszustand ab: erst die dokumentierte
        // Entscheidung der Sachbearbeitung, dann die Abschluss-Phase.
        String nextStep = !allDone ? "Offene Prüfpunkte abarbeiten – danach Phase Abschluss"
                : decision == null
                        ? "Prüfung abgeschlossen – Entscheidung der Sachbearbeitung noch nicht dokumentiert (Analyseergebnis öffnen)"
                        : "Prüfung abgeschlossen – Phase Abschluss";
        return new PhaseSection(statusLabel, variant, "Überprüfung",
                info, done.stream().map(ChecklistItem::label).toList(),
                open.stream().map(ChecklistItem::label).toList(), nextStep, actions);
    }

    private PhaseSection completeSection(WorkspaceDto dto, WorkspaceEntity entity,
                                         List<ChecklistItem> checklist, DocStatusSummary docs) {
        WorkspaceStatus status = entity.getStatus();
        Map<String, Object> phaseData = parseJson(entity.getPhaseData());
        // Entscheidungszustand aus phaseData.decision (Phase 2D.3, identische
        // Quelle wie die Entscheidungs-Seite) — nie erfunden, nie aus der
        // KI-Empfehlung abgeleitet.
        DecisionWorkspaceController.DecisionInfo decision =
                DecisionWorkspaceController.decisionFromPhaseData(phaseData);
        List<String> openChecklist = checklist.stream()
                .filter(i -> !i.completed() && !i.notRequired())
                .map(ChecklistItem::label).toList();
        long checked = checklist.stream().filter(i -> i.completed() || i.notRequired()).count();

        List<InfoRow> info = new ArrayList<>();
        info.add(new InfoRow("Prüfpunkte", checked + " von " + checklist.size() + " abgeschlossen"));
        // Phase 2D.4: die dokumentierte Entscheidung ist Teil des Abschluss-
        // Flusses sichtbar — dokumentiert (wer/wann/Analyse-#) oder ehrlich
        // als noch offen. Die KI-Empfehlung allein ist keine Entscheidung.
        info.add(new InfoRow("Entscheidung der Sachbearbeitung", decision != null
                ? "Dokumentiert durch " + decision.confirmedByName() + " am " + decision.confirmedAt()
                        + " · Analyse #" + decision.analysisVersion()
                : "Noch nicht dokumentiert"));
        info.add(new InfoRow("Dokumente", String.valueOf(docs.total())));
        info.add(new InfoRow("Ereignisse", String.valueOf(dto.timelineEvents() != null ? dto.timelineEvents().size() : 0)));
        // Abgeschlossener Vorgang: Abschluss-Zeitpunkt und -Person aus den
        // phaseData (dieselbe Quelle wie der Verlaufseintrag) — der Endzustand
        // bleibt lesbar, auch für Leitungs-Konten.
        if (status == WorkspaceStatus.CLOSED) {
            if (phaseData.get("closedAt") != null) {
                info.add(new InfoRow("Abgeschlossen am", String.valueOf(phaseData.get("closedAt"))));
            }
            if (phaseData.get("closedBy") != null && !String.valueOf(phaseData.get("closedBy")).isBlank()) {
                info.add(new InfoRow("Abgeschlossen durch", String.valueOf(phaseData.get("closedBy"))));
            }
        }

        // Ein abgeschlossener Fall (Status GESCHLOSSEN) ist ein Endzustand:
        // keine "Nächster Schritt"-Sprache, keine offene Arbeit. Nur explizites
        // Wiederaufnehmen öffnet den Vorgang erneut — nie eine stillschweigende
        // Rückwandlung.
        boolean closed = status == WorkspaceStatus.CLOSED;
        boolean usable = workspaceService.latestCompletedAnalysisRun(dto.id())
                .map(workspaceService::deserializeAnalysisResult)
                .isPresent();

        List<String> done = new ArrayList<>();
        done.add("Alle fünf Phasen durchlaufen");
        done.add(docs.total() + " Dokument(e) verarbeitet");
        done.add(checked + " von " + checklist.size() + " Prüfpunkten abgeschlossen");
        if (decision != null) {
            done.add("Entscheidung der Sachbearbeitung dokumentiert");
        }
        if (closed) {
            done.add("Vorgang abgeschlossen und dokumentiert");
        }

        List<String> open;
        if (closed) {
            open = List.of("Keine offenen Punkte — der Vorgang ist abgeschlossen");
        } else {
            open = new ArrayList<>(openChecklist);
            if (decision == null) {
                open.add("Entscheidung der Sachbearbeitung dokumentieren — die KI-Empfehlung allein ist keine Entscheidung");
            }
            if (open.isEmpty()) {
                open = List.of("Keine offenen Prüfpunkte");
            }
        }

        List<ActionLink> actions = new ArrayList<>();
        if (closed) {
            if (usable) {
                actions.add(new ActionLink("Entscheidung ansehen", "/cases/" + dto.id() + "/decision", "primary"));
                actions.add(new ActionLink("PDF öffnen",
                        "/cases/" + dto.id() + "/decision/export-pdf?inline=true", ""));
                actions.add(new ActionLink("PDF herunterladen",
                        "/cases/" + dto.id() + "/decision/export-pdf", ""));
            }
            // Wiederaufnahme ist eine EXPLIZITE Aktion (bestehender Endpoint,
            // mit Bearbeitungshistorie) — der Vorgang wird nie automatisch
            // wieder geöffnet. Bewusst als VOLLER Formular-POST (nicht hx-post):
            // die ganze Seite muss den neuen Status spiegeln (Kopfzeile,
            // Aktionsleiste, Arbeitszustand), nicht nur der Phasen-Block.
            actions.add(new ActionLink("Vorgang wiederaufnehmen",
                    "form-post:/cases/" + dto.id() + "/reopen", ""));
            return new PhaseSection("Vorgang abgeschlossen", "success", "Abschluss",
                    info, done, open, null, actions);
        }

        // Offener Vorgang in der Abschluss-Phase (Status noch aktiv): die
        // Darstellung lebt aus dem ECHTEN Zustand — „abschlussbereit" erst,
        // wenn die Entscheidung dokumentiert IST und keine Prüfpunkte offen
        // sind. Der Abschluss selbst bleibt eine explizite Handlung der
        // Sachbearbeitung: der bestehende Endpoint /cases/{id}/close bleibt
        // serverseitig autoritativ, diese Anzeige erklärt nur den Zustand.
        actions.add(new ActionLink(decision != null ? "Entscheidung ansehen" : "Entscheidung vorbereiten",
                "/cases/" + dto.id() + "/decision", "primary"));
        // Die PDF-Verfügbarkeit hängt vom persistierten Analyse-Ergebnis ab,
        // nicht von der aktuellen Phase.
        if (usable) {
            actions.add(new ActionLink("PDF öffnen",
                    "/cases/" + dto.id() + "/decision/export-pdf?inline=true", ""));
            actions.add(new ActionLink("PDF herunterladen",
                    "/cases/" + dto.id() + "/decision/export-pdf", ""));
        }
        if (!openChecklist.isEmpty()) {
            actions.add(new ActionLink("Checkliste öffnen", "tab:checklist", ""));
        }
        // Abschluss-Phase (Vorgang noch offen): der natürliche letzte Schritt
        // der Sachbearbeiterin ist das Abschließen DIREKT hier auf der
        // Fallseite (bestehender Endpoint /cases/{id}/close, inkl. optionalem
        // Komplexitäts-Feedback) — nicht erst über die Fall-Liste.
        actions.add(new ActionLink("Vorgang abschließen",
                "form-post:/cases/" + dto.id() + "/close", ""));

        String statusLabel;
        String statusVariant;
        String nextStep;
        if (decision == null) {
            statusLabel = "Entscheidung nicht dokumentiert";
            statusVariant = "warning";
            nextStep = openChecklist.isEmpty()
                    ? "Die Entscheidung ist noch nicht dokumentiert — die KI-Empfehlung allein ist keine Entscheidung. "
                            + "Dokumentieren Sie Ihre Entscheidung im Analyseergebnis; erst danach ist der Vorgang abschlussbereit."
                    : "Die Entscheidung ist noch nicht dokumentiert — die KI-Empfehlung allein ist keine Entscheidung. "
                            + "Dokumentieren Sie Ihre Entscheidung im Analyseergebnis und schließen Sie die offenen Prüfpunkte ab; "
                            + "erst danach ist der Vorgang abschlussbereit.";
        } else if (!openChecklist.isEmpty()) {
            statusLabel = "Offene Prüfpunkte";
            statusVariant = "info";
            nextStep = "Offene Prüfpunkte abschließen — erst danach ist der Vorgang abschlussbereit.";
        } else {
            statusLabel = "Abschluss bereit";
            statusVariant = "success";
            nextStep = "Der Vorgang kann jetzt abgeschlossen werden. „Vorgang abschließen“ setzt den Status auf "
                    + "„Geschlossen“ — Historie, Entscheidung, Dokumente und Entwürfe bleiben erhalten.";
        }
        return new PhaseSection(statusLabel, statusVariant, "Abschluss", info, done, open, nextStep, actions);
    }

    // Phase data parsing

    @SuppressWarnings("unchecked")
    private List<NoteItem> parseNotes(String phaseData) {
        try {
            Map<String, Object> data = parseJson(phaseData);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getOrDefault("notes", List.of());
            return raw.stream()
                    .map(m -> new NoteItem(
                            (String) m.getOrDefault("id", ""),
                            (String) m.getOrDefault("text", ""),
                            (String) m.getOrDefault("createdBy", ""),
                            (String) m.getOrDefault("createdAt", "")))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void saveNotes(WorkspaceEntity entity, List<NoteItem> notes) {
        List<Map<String, Object>> raw = notes.stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.id());
                    m.put("text", n.text());
                    m.put("createdBy", n.createdBy());
                    m.put("createdAt", n.createdAt());
                    return m;
                }).toList();
        Map<String, Object> data = parseJson(entity.getPhaseData());
        data.put("notes", raw);
        try {
            entity.setPhaseData(mapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            entity.setPhaseData("{}");
        }
        workspaceService.save(entity);
    }

    @SuppressWarnings("unchecked")
    private List<ChecklistItem> parseChecklist(String phaseData, WorkspacePhase currentPhase) {
        try {
            Map<String, Object> data = parseJson(phaseData);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getOrDefault("checklist", List.of());
            if (raw.isEmpty()) {
                // Seed with default checklist items for the current phase
                return defaultChecklist(currentPhase);
            }
            return raw.stream()
                    .map(m -> new ChecklistItem(
                            (String) m.getOrDefault("id", ""),
                            (String) m.getOrDefault("label", ""),
                            (String) m.getOrDefault("phase", ""),
                            (Boolean) m.getOrDefault("completed", false),
                            (Boolean) m.getOrDefault("notRequired", false)))
                    .toList();
        } catch (Exception e) {
            return defaultChecklist(currentPhase);
        }
    }

    private void saveChecklist(WorkspaceEntity entity, List<ChecklistItem> items) {
        List<Map<String, Object>> raw = items.stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.id());
                    m.put("label", i.label());
                    m.put("phase", i.phase());
                    m.put("completed", i.completed());
                    m.put("notRequired", i.notRequired());
                    return m;
                }).toList();
        Map<String, Object> data = parseJson(entity.getPhaseData());
        data.put("checklist", raw);
        try {
            entity.setPhaseData(mapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            entity.setPhaseData("{}");
        }
        workspaceService.save(entity);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Auslöser des Vorgangs für die Kopfzeile ("Auslöser: E-Mail vom …"):
     * bevorzugt die Eingangszeit der Ausgangs-E-Mail, sonst die Quellen-
     * Beschreibung aus den phaseData, sonst null (kein Auslöser bekannt).
     */
    private static String caseOriginOf(WorkspaceEntity entity) {
        Map<String, Object> data = parseJsonStatic(entity.getPhaseData());
        String emailAt = (String) data.get("sourceEmailAt");
        if (emailAt != null && !emailAt.isBlank()) {
            try {
                return "E-Mail vom " + DateTimeFormats.formatInstant(Instant.parse(emailAt), null);
            } catch (Exception e) {
                // fall through zur Quellen-Beschreibung
            }
        }
        Object source = data.get("source");
        if (source != null && !String.valueOf(source).isBlank()) {
            return String.valueOf(source);
        }
        return null;
    }

    private static Map<String, Object> parseJsonStatic(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    // Label helpers

    static String phaseLabel(WorkspacePhase p) {
        return CaseController.phaseLabel(p);
    }

    /** Verständliche deutsche Bezeichnung der Ereignisart (Timeline-Typ). */
    public static String timelineTypeLabel(String type) {
        if (type == null || type.isBlank()) return "Sonstiges";
        return switch (type.toUpperCase()) {
            case "EVENT" -> "Ereignis";
            case "DECISION" -> "Entscheidung";
            case "COMMUNICATION" -> "Kommunikation";
            case "DEADLINE" -> "Frist";
            case "MILESTONE" -> "Meilenstein";
            case "CHANGE" -> "Änderung";
            case "DISCOVERY" -> "Erkenntnis";
            case "PUBLICATION" -> "Veröffentlichung";
            case "CREATED" -> "Erstellung";
            case "ASSIGNED" -> "Zuweisung";
            case "STATUS_CHANGE", "STATUSCHANGE" -> "Statusänderung";
            default -> type;
        };
    }

    static String workspaceTypeLabel(String type) {
        if (type == null) return "Allgemein";
        return switch (type.toUpperCase()) {
            case "GENERAL" -> "Allgemein";
            case "RESEARCH" -> "Recherche";
            case "ANALYSIS" -> "Analyse";
            case "REVIEW" -> "Überprüfung";
            case "TECHNICAL_DOCUMENTATION" -> "Technische Dokumentation";
            case "CASE_FILE" -> "Fallakte";
            default -> type;
        };
    }

    static List<ChecklistItem> defaultChecklist(WorkspacePhase phase) {
        String phaseLabel = phaseLabel(phase);
        return switch (phase) {
            case SETUP -> List.of(
                    new ChecklistItem("setup-1", "Fallbezeichnung festgelegt", phaseLabel, false, false),
                    new ChecklistItem("setup-2", "Falltyp ausgewählt", phaseLabel, false, false),
                    new ChecklistItem("setup-3", "Beschreibung erfasst", phaseLabel, false, false));
            case INGESTION -> List.of(
                    new ChecklistItem("ing-1", "Erste Dokumente hochgeladen", phaseLabel, false, false),
                    new ChecklistItem("ing-2", "Dokumente auf Vollständigkeit geprüft", phaseLabel, false, false),
                    new ChecklistItem("ing-3", "Dokumententypen zugeordnet", phaseLabel, false, false));
            case ANALYSIS -> List.of(
                    new ChecklistItem("ana-1", "Dokumentanalyse gestartet", phaseLabel, false, false),
                    new ChecklistItem("ana-2", "Timeline-Ereignisse geprüft", phaseLabel, false, false),
                    new ChecklistItem("ana-3", "Schlüsseldokumente identifiziert", phaseLabel, false, false));
            case REVIEW -> List.of(
                    new ChecklistItem("rev-1", "Angaben zur Person geprüft", phaseLabel, false, false),
                    new ChecklistItem("rev-2", "Vorhandene Unterlagen geprüft", phaseLabel, false, false),
                    new ChecklistItem("rev-3", "Fehlende Unterlagen erfasst", phaseLabel, false, false),
                    new ChecklistItem("rev-4", "Relevante Rechtsgrundlagen geprüft", phaseLabel, false, false));
            case COMPLETE -> List.of(
                    new ChecklistItem("comp-1", "Fall abgeschlossen", phaseLabel, true, false),
                    new ChecklistItem("comp-2", "Dokumente archiviert", phaseLabel, true, false),
                    new ChecklistItem("comp-3", "Abschlussbericht erstellt", phaseLabel, true, false));
        };
    }
}
