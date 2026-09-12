package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.planning.CasePlanningService.CasePlanningView;
import verwaltungsassistent.web.planning.NextBestWorkService;
import verwaltungsassistent.web.planning.PriorityCalculationService;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);
    private static final DateTimeFormatter EMAIL_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkspaceService workspaceService;
    private final verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard;
    private final JpaEmailAnalysisRepository emailAnalysisRepository;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final verwaltungsassistent.web.service.DemoDataService demoDataService;
    private final NextBestWorkService nextBestWorkService;
    private final verwaltungsassistent.web.planning.CasePlanningService casePlanningService;
    private final PriorityCalculationService priorityCalculationService;

    // Nur im Demo-/Playwright-Profil vorhanden (Phase 2C.3a) — wie der
    // Mailbox-Ingestions-Service in EmailController.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private verwaltungsassistent.web.mailbox.MailboxAttachmentDocumentIngestionService attachmentDocumentService;

    public CaseController(WorkspaceService workspaceService,
                          verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard,
                          JpaEmailAnalysisRepository emailAnalysisRepository,
                          JpaIncomingEmailRepository incomingEmailRepository,
                          verwaltungsassistent.web.service.DemoDataService demoDataService,
                          NextBestWorkService nextBestWorkService,
                          verwaltungsassistent.web.planning.CasePlanningService casePlanningService,
                          PriorityCalculationService priorityCalculationService) {
        this.workspaceService = workspaceService;
        this.caseAccessGuard = caseAccessGuard;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.incomingEmailRepository = incomingEmailRepository;
        this.demoDataService = demoDataService;
        this.nextBestWorkService = nextBestWorkService;
        this.casePlanningService = casePlanningService;
        this.priorityCalculationService = priorityCalculationService;
    }

    /**
     * Creates a case from an analyzed citizen e-mail. The relationship is
     * ID-based: the case stores the e-mail analysis id in its phase data and
     * the timeline records "E-Mail eingegangen", so the case can navigate
     * back to the originating e-mail without duplicating its text.
     */
    @PostMapping("/emails/{analysisId}/create-case")
    public String createCaseFromEmail(@PathVariable UUID analysisId,
                                      @AuthenticationPrincipal AuthenticatedUser user,
                                      RedirectAttributes redirectAttributes) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (verwaltungsassistent.web.security.CaseAccessGuard.isSupervisory(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — Vorgänge können nur lesend eingesehen werden.");
        }
        EmailAnalysisEntity email = emailAnalysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "E-Mail-Analyse nicht gefunden"));
        // The analysis may belong to a colleague. Access is already governed
        // by the e-mail's work queue visibility — the case is created with
        // the CURRENT user as owner (Leitungs-Konten wurden oben abgewiesen).
        if (!email.getUserEmail().equals(user.email())) {
            boolean linkedEmailWorkable = incomingEmailRepository.findAll().stream()
                    .filter(e -> analysisId.equals(e.getAnalysisId()))
                    .anyMatch(e -> e.getAssignedTo() == null || e.getAssignedTo().equals(user.email())
                            || user.email().equals(e.getAddressedToEmail()));
            if (!linkedEmailWorkable) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "E-Mail-Analyse nicht gefunden");
            }
        }

        String subject = email.getSubject() != null && !email.getSubject().isBlank()
                ? email.getSubject() : "Fall aus E-Mail";
        if (subject.length() > 120) {
            subject = subject.substring(0, 120) + "…";
        }
        String text = email.getQuestionText() != null ? email.getQuestionText().trim() : "";
        // Die Beschreibungsspalte ist varchar(255) — die Kürzung muss darunter
        // bleiben, sonst schlägt das Anlegen des Vorgangs mit
        // DataIntegrityViolationException fehl (500 statt Weiterleitung zum Fall).
        String description = text.isEmpty() ? "Vorgang aus eingehender E-Mail angelegt."
                : (text.length() > 252 ? text.substring(0, 252) + "…" : text);

        WorkspaceEntity workspace = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(subject, description, "CASE", user.email()));

        Map<String, Object> data = new LinkedHashMap<>();
        String receivedAt = email.getCreatedAt() != null
                ? EMAIL_DATE_FMT.format(email.getCreatedAt().atZone(ZoneId.systemDefault())) : "—";
        data.put("source", "E-Mail vom " + receivedAt);
        data.put("sourceEmailId", analysisId.toString());
        data.put("sourceEmailSubject", email.getSubject());
        data.put("sourceEmailAt", email.getCreatedAt() != null ? email.getCreatedAt().toString() : null);
        data.put("caseCategory", deriveCaseCategory(subject));
        try {
            workspace.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            workspace.setPhaseData("{}");
        }
        workspaceService.save(workspace);

        workspaceService.addTimelineEvent(
                workspace.getId().toString(), LocalDate.now(),
                "E-Mail eingegangen",
                "Ausgangsanfrage: \"" + (email.getSubject() != null ? email.getSubject() : "") + "\"",
                TimelineEventType.COMMUNICATION, null, 1.0, false);

        // Der neue Fall ist der Vorgang dieser E-Mail: die Zuordnung wird auf
        // der E-Mail gespeichert, damit spätere E-Mails zum selben Vorgang
        // diesem Fall zugeordnet werden können (E-Mail 1..n → ein Fall).
        try {
            incomingEmailRepository.findAll().stream()
                    .filter(e -> analysisId.equals(e.getAnalysisId()))
                    .findFirst()
                    .ifPresent(e -> {
                        e.setWorkspaceId(UUID.fromString(workspace.getId()));
                        incomingEmailRepository.save(e);
                        // Phase 2C.3a: die Anhänge dieser E-Mail (bei der
                        // Mailbox-Übernahme als Dokumente mit Provenienz-Tag
                        // email:<messageId> angelegt) an den neuen Fall hängen.
                        if (attachmentDocumentService != null
                                && e.getMessageId() != null && !e.getMessageId().isBlank()) {
                            attachmentDocumentService.attachEmailDocumentsToWorkspace(
                                    e.getMessageId(), workspace.getId());
                        }
                    });
        } catch (Exception ex) {
            log.warn("E-Mail-Zuordnung zum neuen Fall nicht gespeichert: {}", ex.getMessage());
        }

        redirectAttributes.addFlashAttribute("message",
                "Fall \"" + workspace.getWorkspaceCode() + "\" wurde aus der E-Mail angelegt. "
                        + "Die Ausgangsanfrage bleibt mit dem Vorgang verknüpft.");
        return "redirect:/cases/" + workspace.getId();
    }

    @GetMapping("/cases")
    public String listCases(Model model,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) String sort,
                            @RequestParam(defaultValue = "0") int page,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                            @RequestHeader(value = "HX-Target", required = false) String hxTarget) {

        List<WorkspaceEntity> allWorkspaces = loadWorkspaces(user);
        List<WorkspaceDto> dtos = allWorkspaces.stream()
                .map(workspaceService::toDto)
                .collect(Collectors.toCollection(ArrayList::new));
        boolean supervisory = user != null && verwaltungsassistent.web.security.CaseAccessGuard
                .isSupervisory(user);

        // In-memory filtering
        List<WorkspaceDto> filtered = filterAndSort(dtos, status, q, sort);

        // Phase 2D.12 — Mitarbeiter-Warteschlange: ohne explizite Sortierung
        // gilt die BESTEHENDE Next-Best-Work-Reihung (recommendFor, dieselbe
        // Engine wie die Dashboard-Empfehlung — KEIN zweites Ranking). Die
        // Reihenfolge wird bei JEDER Listen-Anfrage neu berechnet; der
        // "Aktualisieren"-Button ist damit ehrlich. Abgeschlossene/
        // archivierte Vorgänge stehen nach den offenen.
        boolean queueOrder = !supervisory && (sort == null || sort.isEmpty());
        Map<String, Integer> nbwRank = Map.of();
        if (queueOrder && user != null) {
            var result = nextBestWorkService.recommendFor(user);
            if (result.rankedCandidates() != null && !result.rankedCandidates().isEmpty()) {
                Map<String, Integer> ranks = new LinkedHashMap<>();
                for (var rec : result.rankedCandidates()) {
                    ranks.put(rec.caseId(), ranks.size() + 1);
                }
                nbwRank = ranks;
                filtered = new ArrayList<>(filtered);
                filtered.sort(queueComparator(nbwRank));
            }
        }

        // Convert to display rows with pre-computed German labels
        List<CaseRow> caseRows = filtered.stream()
                .map(d -> new CaseRow(
                        d.id(), d.name(), d.workspaceCode(),
                        statusLabel(d.status()), statusVariant(d.status()),
                        d.phase() != null ? phaseLabel(d.phase()) : "—",
                        d.phase() != null ? d.phase().ordinal() : 0,
                        d.documents() != null ? d.documents().size() : 0,
                        d.updatedAt() != null
                                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                                    .format(java.time.LocalDate.ofInstant(d.updatedAt(), java.time.ZoneId.systemDefault()))
                                : "—",
                        d.workspaceType() != null ? d.workspaceType() : ""))
                .collect(Collectors.toList());

        // Pagination (10 per page)
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil((double) caseRows.size() / pageSize));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = page * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, caseRows.size());
        List<CaseRow> pageItems = caseRows.subList(fromIndex, toIndex);

        // Phase 2D.12: Priorität (aus dem BESTEHENDEN Planungsstand, derselbe
        // wie Empfehlung/Fallseite), Zugehörigkeit (mir zugewiesen/Arbeitspool)
        // und Empfehlungs-Rang je Zeile — als Neben-Map, das Zeilenmodell
        // selbst bleibt unverändert.
        Map<String, String> ownerById = new HashMap<>();
        for (WorkspaceEntity w : allWorkspaces) {
            ownerById.put(w.getId(), w.getOwnerId());
        }
        model.addAttribute("caseQueueInfo",
                queueInfoFor(pageItems, ownerById, supervisory,
                        user != null ? user.email() : null, nbwRank));

        // Build query params string for HTMX links
        String queryParams = buildQueryParams(status, q, sort);

        // Table headers
        List<HeaderDef> headers = List.of(
                new HeaderDef("name", "Name", true),
                new HeaderDef("priority", "Priorität", false),
                new HeaderDef("status", "Status", false),
                new HeaderDef("phase", "Phase", true),
                new HeaderDef("documentCount", "Dokumente", true),
                new HeaderDef("updatedAt", "Aktualisiert", true),
                new HeaderDef("actions", "", false)
        );

        // Filter definitions for filterBar
        List<FilterDef> filters = List.of(
                new FilterDef("status", "Status", "select",
                        List.of(
                                new OptionDef("ACTIVE", "Aktiv"),
                                new OptionDef("DRAFT", "Entwurf"),
                                new OptionDef("CLOSED", "Geschlossen"),
                                new OptionDef("ARCHIVED", "Archiviert")
                        ), status != null ? status : ""),
                new FilterDef("q", "Suche", "text", List.of(), q != null ? q : "")
        );

        model.addAttribute("cases", pageItems);
        model.addAttribute("headers", headers);
        model.addAttribute("filters", filters);
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("baseUrl", "/cases");
        model.addAttribute("queryParams", queryParams);
        model.addAttribute("currentStatus", status);
        model.addAttribute("currentQuery", q);
        model.addAttribute("currentSort", sort);
        model.addAttribute("pageTitle", "Fälle");
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases")));

        if (hxRequest != null) {
            // Requests targeting the whole list container (sidebar status
            // filters, search box) must swap the complete layout — including
            // "Alle" (no params). Sort/pagination target only the table.
            // htmx sends the resolved target id without the leading '#'.
            boolean containerTarget = "case-list-container".equals(hxTarget)
                    || "#case-list-container".equals(hxTarget);
            boolean listRefresh = containerTarget
                    || ((status != null || q != null) && sort == null && page == 0);
            return listRefresh ? "cases/list :: caseList" : "cases/fragments :: caseTable";
        }
        return "cases/list";
    }

    @GetMapping("/cases/new")
    public String newCaseForm(Model model) {
        model.addAttribute("pageTitle", "Neuer Fall");
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb("Neuer Fall", "/cases/new")));
        return "cases/create";
    }

    @PostMapping("/cases/new")
    public String createCase(@Valid @ModelAttribute CreateCaseForm form,
                             BindingResult bindingResult,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             RedirectAttributes redirectAttributes) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (verwaltungsassistent.web.security.CaseAccessGuard.isSupervisory(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — Vorgänge können nur lesend eingesehen werden.");
        }
        if (bindingResult.hasErrors()) {
            return "cases/create";
        }
        WorkspaceEntity workspace = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(
                        form.getName().trim(),
                        form.getDescription() != null ? form.getDescription().trim() : "",
                        "CASE", user.email()));
        redirectAttributes.addFlashAttribute("message", "Fall \"" + workspace.getWorkspaceCode() + "\" wurde erfolgreich angelegt.");
        return "redirect:/cases/" + workspace.getId();
    }

    /**
     * Explicit "Demo-Daten erzeugen" (Phase 2D.12: SUPERADMIN only, wie alle
     * destruktiven Demo-Wartungsaktionen — das ADM/Leitungs-Konto ist rein
     * aufsichtlich): recreates the deterministic demo namespace
     * (demo01..demo20 users with cases and e-mails). Never runs as part of
     * the normal startup.
     */
    @PostMapping("/cases/demo-data")
    public String generateDemoData(@AuthenticationPrincipal AuthenticatedUser user,
                                   RedirectAttributes redirectAttributes) {
        if (user == null || user.roles() == null || !user.roles().contains("SUPERADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Nur das Superadmin-Konto darf Demo-Daten erzeugen.");
        }
        // Direkt reset() aufrufen (nicht generate()): generate() delegiert
        // intern an reset() und verliert dabei die @Transactional-Proxy-
        // Hülle — die Bereinigung lief dann ohne Transaktion und scheiterte
        // („No EntityManager with actual transaction available").
        int created = demoDataService.reset();
        redirectAttributes.addFlashAttribute("message",
                "Demo-Daten wurden erzeugt (" + created + " Datensätze). "
                        + "Demo-Benutzer: demo01@verwaltungsassistent.local … demo" + String.format("%02d",
                                verwaltungsassistent.web.service.DemoDataService.DEMO_USER_COUNT)
                        + "@verwaltungsassistent.local, Passwort: "
                        + verwaltungsassistent.web.service.DemoDataService.DEMO_PASSWORD);
        return "redirect:/cases";
    }

    @DeleteMapping("/cases/{id}")
    public String deleteCase(@PathVariable String id,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             Model model) {
        try {
            // GEO-Sperre: Geovorgänge können von KEINER Anwendungsrolle
            // archiviert/entfernt werden (nur Demo-Reset).
            WorkspaceEntity entity = caseAccessGuard.requireNotGeo(
                    caseAccessGuard.requireWriteAccess(id, user));
            entity.setStatus(WorkspaceStatus.ARCHIVED);
            workspaceService.save(entity);
            log.info("Archived workspace {}", id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden");
        }

        // After delete, re-render the table
        List<WorkspaceEntity> allWorkspaces = loadWorkspaces(user);
        List<WorkspaceDto> dtos = allWorkspaces.stream()
                .map(workspaceService::toDto)
                .collect(Collectors.toCollection(ArrayList::new));

        List<WorkspaceDto> filtered = filterAndSort(dtos, null, null, null);
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / pageSize));
        List<WorkspaceDto> firstPage = filtered.subList(0, Math.min(pageSize, filtered.size()));

        List<CaseRow> caseRows = firstPage.stream()
                .map(d -> new CaseRow(
                        d.id(), d.name(), d.workspaceCode(),
                        statusLabel(d.status()), statusVariant(d.status()),
                        d.phase() != null ? phaseLabel(d.phase()) : "—",
                        d.phase() != null ? d.phase().ordinal() : 0,
                        d.documents() != null ? d.documents().size() : 0,
                        d.updatedAt() != null
                                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                                    .format(java.time.LocalDate.ofInstant(d.updatedAt(), java.time.ZoneId.systemDefault()))
                                : "—",
                        d.workspaceType() != null ? d.workspaceType() : ""))
                .collect(Collectors.toList());

        List<HeaderDef> headers = List.of(
                new HeaderDef("name", "Name", true),
                new HeaderDef("priority", "Priorität", false),
                new HeaderDef("status", "Status", false),
                new HeaderDef("phase", "Phase", true),
                new HeaderDef("documentCount", "Dokumente", true),
                new HeaderDef("updatedAt", "Aktualisiert", true),
                new HeaderDef("actions", "", false)
        );

        model.addAttribute("cases", caseRows);
        model.addAttribute("headers", headers);
        model.addAttribute("page", 0);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("baseUrl", "/cases");
        model.addAttribute("queryParams", "");
        model.addAttribute("currentStatus", null);
        model.addAttribute("currentQuery", null);
        model.addAttribute("currentSort", null);
        model.addAttribute("deleteSuccess", true);

        return "cases/list :: caseList";
    }

    /**
     * Rename form for one case (name/description). Reached via the
     * "Umbenennen" action on the case list — works identically from every
     * status filter.
     */
    @GetMapping("/cases/{id}/settings")
    public String editCaseForm(@PathVariable String id,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        WorkspaceEntity entity = caseAccessGuard.requireAccess(id, user);
        WorkspaceDto dto = workspaceService.toDto(entity);
        model.addAttribute("case", dto);
        model.addAttribute("workspaceTypeLabel",
                CaseDetailController.workspaceTypeLabel(entity.getWorkspaceType()));
        model.addAttribute("settingsForm", new CaseSettingsForm(
                dto.name() != null ? dto.name() : "",
                dto.description() != null ? dto.description() : "",
                entity.getWorkspaceType() != null ? entity.getWorkspaceType() : "CASE"));
        model.addAttribute("pageTitle", "Fall umbenennen");
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb("Umbenennen", "/cases/" + id + "/settings")));
        return "cases/settings";
    }

    /** Persists the edited name/description and returns to the case detail page. */
    @PostMapping("/cases/{id}/settings")
    public String updateCase(@PathVariable String id,
                             @Valid @ModelAttribute("settingsForm") CaseSettingsForm form,
                             BindingResult bindingResult,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        WorkspaceEntity entity = caseAccessGuard.requireWriteAccess(id, user);
        if (bindingResult.hasErrors()) {
            model.addAttribute("case", workspaceService.toDto(entity));
            model.addAttribute("workspaceTypeLabel",
                    CaseDetailController.workspaceTypeLabel(entity.getWorkspaceType()));
            model.addAttribute("pageTitle", "Fall umbenennen");
            model.addAttribute("activeSection", "cases");
            model.addAttribute("breadcrumbs", List.of(
                    new HomeController.Breadcrumb("Home", "/dashboard"),
                    new HomeController.Breadcrumb("Fälle", "/cases"),
                    new HomeController.Breadcrumb("Umbenennen", "/cases/" + id + "/settings")));
            return "cases/settings";
        }
        entity.setName(form.getName().trim());
        entity.setDescription(form.getDescription() != null ? form.getDescription().trim() : "");
        entity.setUpdatedAt(java.time.Instant.now());
        workspaceService.save(entity);
        redirectAttributes.addFlashAttribute("message", "Fall wurde aktualisiert.");
        return "redirect:/cases/" + id;
    }

    /**
     * Derives a normalized case category from the e-mail subject so the PDF
     * "Fallart" box shows a procedure type rather than the raw case title.
     */
    private String deriveCaseCategory(String subject) {
        if (subject == null) return "Allgemein";
        String lower = subject.toLowerCase();
        if (lower.contains("gewerbe")) return "Gewerbeanmeldung";
        if (lower.contains("wohngeld")) return "Wohngeld";
        if (lower.contains("reisepass")) return "Reisepass";
        if (lower.contains("personalausweis")) return "Personalausweis";
        if (lower.contains("ummeldung") || lower.contains("umzug")) return "Ummeldung";
        if (lower.contains("baugenehmigung") || lower.contains("bauantrag")) return "Baugenehmigung";
        if (lower.contains("anmeldung") && lower.contains("wohn")) return "Ummeldung";
        if (lower.contains("carport") || lower.contains("garage")) return "Baugenehmigung";
        return "Allgemein";
    }

    // --- Private helpers ---

    private List<WorkspaceEntity> loadWorkspaces(AuthenticatedUser user) {
        List<WorkspaceEntity> all;
        if (user.roles().contains("ADMIN")) {
            all = new ArrayList<>(workspaceService.findAll());
        } else {
            // Eigene Vorgänge + allgemeiner Arbeitspool (unzugewiesen,
            // admin-eigen oder allgemeine Mailbox) — dieselbe Regel wie der
            // CaseAccessGuard für Detailzugriffe.
            all = new ArrayList<>(verwaltungsassistent.web.security.WorkspaceVisibility
                    .ownAndPool(workspaceService, user.email()));
        }
        // Sort by updatedAt descending by default
        all.sort(Comparator.comparing(WorkspaceEntity::getUpdatedAt).reversed());
        return all;
    }

    private List<WorkspaceDto> filterAndSort(List<WorkspaceDto> dtos, String status, String q, String sort) {
        // Filter by status
        if (status != null && !status.isEmpty()) {
            dtos = dtos.stream()
                    .filter(d -> d.status().name().equals(status))
                    .collect(Collectors.toList());
        }

        // Search by name
        if (q != null && !q.isEmpty()) {
            String lowerQ = q.toLowerCase();
            dtos = dtos.stream()
                    .filter(d -> d.name() != null && d.name().toLowerCase().contains(lowerQ))
                    .collect(Collectors.toList());
        }

        // Sort
        if (sort != null && !sort.isEmpty()) {
            Comparator<WorkspaceDto> comp = switch (sort) {
                case "name" -> Comparator.comparing(d -> d.name() != null ? d.name().toLowerCase() : "");
                case "phase" -> Comparator.comparing(d -> d.phase().ordinal());
                case "documentCount" -> Comparator.comparingInt(d -> d.documents().size());
                case "updatedAt" -> Comparator.comparing(WorkspaceDto::updatedAt);
                default -> Comparator.comparing(WorkspaceDto::updatedAt).reversed();
            };
            dtos = dtos.stream().sorted(comp).collect(Collectors.toList());
        }

        return dtos;
    }

    /**
     * Warteschlangen-Reihenfolge (Phase 2D.12): empfohlene Vorgänge zuerst
     * (Rang der BESTEHENDEN Next-Best-Work-Reihung), danach die übrigen
     * offenen Vorgänge (aktualität), zuletzt abgeschlossene/archivierte.
     * Reine Navigation — es wird weder zugeordnet noch sonst Zustand geändert.
     */
    private static Comparator<WorkspaceDto> queueComparator(Map<String, Integer> nbwRank) {
        return Comparator
                .comparingInt((WorkspaceDto d) -> {
                    if (nbwRank.containsKey(d.id())) {
                        return 0;
                    }
                    String s = d.status() != null ? d.status().name() : "";
                    return ("ACTIVE".equals(s) || "DRAFT".equals(s)) ? 1 : 2;
                })
                .thenComparingInt(d -> nbwRank.getOrDefault(d.id(), Integer.MAX_VALUE))
                .thenComparing(WorkspaceDto::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /** Zusatz-Info je Zeile (Priorität, Zugehörigkeit, Empfehlung) — abgeleitet, nie persistiert. */
    public record CaseQueueInfo(String priorityLabel, String priorityVariant,
                                String scopeLabel, String scopeVariant, boolean recommended) {}

    private Map<String, CaseQueueInfo> queueInfoFor(List<CaseRow> rows,
                                                    Map<String, String> ownerById,
                                                    boolean supervisory,
                                                    String userEmail,
                                                    Map<String, Integer> nbwRank) {
        Map<String, CaseQueueInfo> info = new LinkedHashMap<>();
        for (CaseRow row : rows) {
            String scopeLabel = null;
            String scopeVariant = null;
            boolean open = "Aktiv".equals(row.statusLabel()) || "Entwurf".equals(row.statusLabel());
            if (!supervisory && open && userEmail != null) {
                String owner = ownerById.get(row.id());
                if (verwaltungsassistent.web.service.DemoDataService.isGeneralPoolOwner(owner)) {
                    scopeLabel = "Arbeitspool";
                    scopeVariant = "neutral";
                } else if (userEmail.equalsIgnoreCase(owner)) {
                    scopeLabel = "Mir zugewiesen";
                    scopeVariant = "success";
                }
            }
            String priorityLabel = null;
            String priorityVariant = null;
            if (open) {
                try {
                    CasePlanningView view = casePlanningService.planningFor(row.id());
                    if (view != null && view.priorityClassLabel() != null
                            && !"—".equals(view.priorityClassLabel())) {
                        priorityLabel = view.priorityClassLabel();
                        priorityVariant = priorityVariantOf(view.priorityClassLabel());
                    }
                } catch (Exception e) {
                    log.debug("Priorität von Fall {} nicht lesbar: {}", row.id(), e.getMessage());
                }
            }
            Integer rank = nbwRank != null ? nbwRank.get(row.id()) : null;
            info.put(row.id(), new CaseQueueInfo(priorityLabel, priorityVariant,
                    scopeLabel, scopeVariant, rank != null && rank == 1));
        }
        return info;
    }

    private String priorityVariantOf(String label) {
        for (PriorityCalculationService.PriorityClass pc
                : PriorityCalculationService.PriorityClass.values()) {
            if (pc.label().equals(label)) {
                return priorityCalculationService.variant(pc);
            }
        }
        return "neutral";
    }

    private String buildQueryParams(String status, String q, String sort) {
        StringBuilder sb = new StringBuilder();
        if (status != null && !status.isEmpty()) {
            sb.append("status=").append(status);
        }
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

    // --- Data holder records ---

    public record HeaderDef(String key, String label, boolean sortable) {}

    public record FilterDef(String name, String label, String type, List<OptionDef> options, String value) {
        public String getPlaceholder() { return null; }
    }

    public record OptionDef(String value, String label) {}

    /** Lightweight row DTO with pre-computed German labels for template rendering. */
    public record CaseRow(String id, String name, String workspaceCode,
                          String statusLabel, String statusVariant,
                          String phaseLabel, int phaseIndex, int documentCount,
                          String updatedAt, String workspaceType) {}

    static String statusLabel(WorkspaceStatus s) {
        return switch (s) {
            case ACTIVE -> "Aktiv";
            case DRAFT -> "Entwurf";
            case CLOSED -> "Geschlossen";
            case ARCHIVED -> "Archiviert";
        };
    }

    static String statusVariant(WorkspaceStatus s) {
        return switch (s) {
            case ACTIVE -> "success";
            case DRAFT -> "warning";
            case CLOSED -> "neutral";
            case ARCHIVED -> "info";
        };
    }

    static String phaseLabel(reasoning.common.model.WorkspacePhase p) {
        return switch (p) {
            case SETUP -> "Einrichtung";
            case INGESTION -> "Ingestion";
            case ANALYSIS -> "Analyse";
            case REVIEW -> "Überprüfung";
            case COMPLETE -> "Abschluss";
        };
    }

    /** Safely parses a case ID string to UUID, throwing 404 on malformed input. */
    static UUID parseCaseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden");
        }
    }

    /** Form backing object for case creation. */
    public record CreateCaseForm(String name, String description) {
        public String getName() { return name != null ? name : ""; }
        public String getDescription() { return description != null ? description : ""; }
    }

    /** Form backing object for the case settings page. */
    public record CaseSettingsForm(
            @jakarta.validation.constraints.NotBlank(message = "Der Name des Falls darf nicht leer sein.")
            @jakarta.validation.constraints.Size(min = 3, max = 255,
                    message = "Der Name muss zwischen 3 und 255 Zeichen lang sein.")
            String name,
            String description,
            String workspaceType) {
        public String getName() { return name != null ? name : ""; }
        public String getDescription() { return description != null ? description : ""; }
        public String getWorkspaceType() { return workspaceType != null ? workspaceType : "CASE"; }
    }
}
