package verwaltungsassistent.web.controller;

import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditQuery;
import reasoning.audit.api.AuditService;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.model.Role;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import verwaltungsassistent.web.service.InfrastructureMetricsService;
import reasoning.common.audit.AuditEventType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.model.Document;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.VectorSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Administration surface (ADMIN only — /admin/** enforced server-side by
 * SecurityConfig). Server-rendered tabs: Übersicht, Benutzer, KI/Modelle,
 * Infrastruktur. All data comes from the live services; no credentials are
 * exposed and nothing is written.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final UserAccountRepository userAccountRepository;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final AiProviderProperties aiProperties;
    private final DocumentFacade documentFacade;
    private final KnowledgeRegistry knowledgeRegistry;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final SessionRegistry sessionRegistry;
    private final ChunkManagementService chunkManagementService;
    private final VectorSearchProvider vectorSearchProvider;
    private final ObjectProvider<GraphEnrichmentService> graphServiceProvider;
    private final InfrastructureMetricsService infrastructureMetricsService;
    private final PasswordEncoder passwordEncoder;
    private final verwaltungsassistent.web.service.DemoDataService demoDataService;
    private final verwaltungsassistent.web.service.DatasetAdministrationService datasetAdministrationService;
    private final verwaltungsassistent.web.service.JobProgressService progressService;
    private final org.thymeleaf.TemplateEngine templateEngine;
    private final java.util.concurrent.ExecutorService datasetExecutor;

    // Demo-Betrieb: Leitungs-Konto vollständig schreibgeschützt (Bean fehlt
    // außerhalb des demo-Profils → kein Effekt).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private verwaltungsassistent.web.demo.DemoReadOnlyPolicy demoReadOnlyPolicy;

    private void denyDemoAdmin(reasoning.auth.api.AuthenticatedUser user) {
        if (demoReadOnlyPolicy != null) {
            demoReadOnlyPolicy.denyAdminMutation(user);
        }
    }

    public AdminController(UserAccountRepository userAccountRepository,
                           RefreshTokenSessionRepository refreshTokenSessionRepository,
                           AiProviderProperties aiProperties,
                           DocumentFacade documentFacade,
                           KnowledgeRegistry knowledgeRegistry,
                           AuditService auditService,
                           JdbcTemplate jdbcTemplate,
                           SessionRegistry sessionRegistry,
                           ChunkManagementService chunkManagementService,
                           VectorSearchProvider vectorSearchProvider,
                           ObjectProvider<GraphEnrichmentService> graphServiceProvider,
                           InfrastructureMetricsService infrastructureMetricsService,
                           PasswordEncoder passwordEncoder,
                           verwaltungsassistent.web.service.DemoDataService demoDataService,
                           verwaltungsassistent.web.service.DatasetAdministrationService datasetAdministrationService,
                           verwaltungsassistent.web.service.JobProgressService progressService,
                           org.thymeleaf.TemplateEngine templateEngine) {
        this.userAccountRepository = userAccountRepository;
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
        this.aiProperties = aiProperties;
        this.documentFacade = documentFacade;
        this.knowledgeRegistry = knowledgeRegistry;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.sessionRegistry = sessionRegistry;
        this.chunkManagementService = chunkManagementService;
        this.vectorSearchProvider = vectorSearchProvider;
        this.graphServiceProvider = graphServiceProvider;
        this.infrastructureMetricsService = infrastructureMetricsService;
        this.passwordEncoder = passwordEncoder;
        this.demoDataService = demoDataService;
        this.datasetAdministrationService = datasetAdministrationService;
        this.progressService = progressService;
        this.templateEngine = templateEngine;
        this.datasetExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dataset-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Tab: Übersicht ────────────────────────────────────────────────────────

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("kbTotalDocuments", countDocuments(null));
        model.addAttribute("kbReadyDocuments", countDocuments(DocumentStatus.READY));
        model.addAttribute("kbProcessingDocuments",
                countDocuments(DocumentStatus.INGESTING) + countDocuments(DocumentStatus.INGESTION_PENDING));
        model.addAttribute("kbFailedDocuments", countDocuments(DocumentStatus.FAILED));
        model.addAttribute("salaryTables", knowledgeRegistry.salaryTables().size());
        model.addAttribute("travelTables", knowledgeRegistry.travelTables().size());
        model.addAttribute("thresholdTables", knowledgeRegistry.thresholdTables().size());
        model.addAttribute("userCount", userAccountRepository.count());
        addChrome(model, "Übersicht", "overview");
        return "admin/index";
    }

    // ── Tab: Benutzer ─────────────────────────────────────────────────────────

    @GetMapping("/admin/users")
    public String users(@AuthenticationPrincipal AuthenticatedUser currentUser, Model model) {
        Set<String> onlineEmails = activeEmails();
        List<UserRow> users = new ArrayList<>();
        try {
            for (UserAccountEntity u : userAccountRepository.findAll()) {
                String actorId = u.getId().toString();
                Optional<AuditEvent> lastLogin = latestAuditEvent(AuditEventType.USER_LOGIN, actorId);
                Optional<AuditEvent> lastLogout = latestAuditEvent(AuditEventType.USER_LOGOUT, actorId);
                users.add(new UserRow(
                        u.getId(),
                        u.getEmail(),
                        u.getDisplayName(),
                        u.getRoles().stream().map(Enum::name).sorted().toList(),
                        u.getRoles().stream().anyMatch(r -> r.name().equals("ADMIN")),
                        u.isEnabled() && !u.isLocked(),
                        lastLogin.map(e -> DATE_FMT.format(e.timestamp().atZone(ZoneId.systemDefault()))).orElse(null),
                        lastLogout.map(e -> DATE_FMT.format(e.timestamp().atZone(ZoneId.systemDefault()))).orElse(null),
                        lastLogin.map(AuditEvent::ipAddress).filter(ip -> ip != null && !ip.isBlank()).orElse(null),
                        u.isLocked(),
                        onlineEmails.contains(u.getEmail()),
                        u.getDepartment(),
                        u.getPhone(),
                        u.getRoom(),
                        vacationSummary(u),
                        onVacationToday(u)));
            }
            users.sort(Comparator.comparing(UserRow::email));
        } catch (Exception e) {
            log.warn("Could not load users for admin page: {}", e.getMessage());
        }
        model.addAttribute("users", users);
        model.addAttribute("currentUserEmail", currentUser != null ? currentUser.email() : null);
        addChrome(model, "Benutzer", "users");
        return "admin/users";
    }

    /** Compact vacation display for the table, e.g. "Urlaub 04.09.–11.09." — only planned absences of the current year. */
    private static String vacationSummary(UserAccountEntity u) {
        if (u.getVacations() == null || u.getVacations().isEmpty()) return null;
        int year = java.time.LocalDate.now().getYear();
        List<String> parts = new ArrayList<>();
        for (var v : u.getVacations()) {
            if (v.from() != null && v.from().getYear() != year && (v.to() == null || v.to().getYear() != year)) {
                continue;
            }
            String from = v.from() != null ? v.from().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.")) : "";
            String to = v.to() != null ? v.to().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.")) : "";
            parts.add((v.type() != null ? v.type() : "Urlaub") + " " + from + "–" + to);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    // ── Benutzer anlegen ──────────────────────────────────────────────────────

    @GetMapping("/admin/users/new")
    public String newUserForm(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        // Demo-Betrieb: Leitungs-Konto ist vollständig schreibgeschützt.
        denyDemoAdmin(user);
        model.addAttribute("pageTitle", "Benutzer anlegen");
        addChrome(model, "Benutzer", "users");
        return "admin/user-new";
    }

    /**
     * Creates a user from the administration form — the authoritative
     * user-creation mechanism (public registration is removed). The employee
     * master data is stored on the existing account entity; the password is
     * encoded with the shared PasswordEncoder.
     */
    @PostMapping("/admin/users/new")
    public String createUser(@RequestParam("firstName") String firstName,
                             @RequestParam("lastName") String lastName,
                             @RequestParam("email") String email,
                             @RequestParam("password") String password,
                             @RequestParam(value = "role", defaultValue = "USER") String role,
                             @RequestParam(value = "phone", required = false) String phone,
                             @RequestParam(value = "department", required = false) String department,
                             @RequestParam(value = "office", required = false) String office,
                             @RequestParam(value = "room", required = false) String room,
                             @RequestParam(value = "position", required = false) String position,
                             @RequestParam(value = "salutation", required = false) String salutation,
                             @RequestParam(value = "enabled", defaultValue = "true") boolean enabled,
                             @RequestParam(value = "vacationFrom", required = false) String vacationFrom,
                             @RequestParam(value = "vacationTo", required = false) String vacationTo,
                             @RequestParam(value = "vacationType", required = false) String vacationType,
                             @RequestParam(value = "vacationNote", required = false) String vacationNote,
                             @AuthenticationPrincipal AuthenticatedUser currentUser,
                             RedirectAttributes redirectAttributes) {
        String normalizedEmail = email != null ? email.trim().toLowerCase() : "";
        String first = firstName != null ? firstName.trim() : "";
        String last = lastName != null ? lastName.trim() : "";
        if (first.isEmpty() || last.isEmpty() || normalizedEmail.isEmpty() || !normalizedEmail.contains("@")) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Bitte Vorname, Nachname und eine gültige E-Mail-Adresse angeben.");
            return "redirect:/admin/users/new";
        }
        if (password == null || password.length() < 8) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Das Passwort muss mindestens 8 Zeichen lang sein.");
            return "redirect:/admin/users/new";
        }
        if (userAccountRepository.findByEmail(normalizedEmail).isPresent()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Ein Benutzer mit dieser E-Mail-Adresse existiert bereits.");
            return "redirect:/admin/users/new";
        }

        denyDemoAdmin(currentUser);
        Role r = Role.fromName(role);
        // Das Leitungs-Konto (ADMIN) ist aufsichtlich und darf keine weiteren
        // ADMIN-Konten anlegen — neue Administratoren bleiben der technischen
        // Rolle SUPERADMIN vorbehalten (identisch zur Sperr-/Entfernen-Regel).
        boolean callerSuperadmin = currentUser != null && currentUser.roles() != null
                && currentUser.roles().contains("SUPERADMIN");
        if (r == Role.ADMIN && !callerSuperadmin) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Administrator-Konten können nur von der technischen Verwaltung (SUPERADMIN) angelegt werden.");
            return "redirect:/admin/users/new";
        }
        Set<Role> roles = new HashSet<>(Set.of(r));
        if (r == Role.USER) {
            roles.add(Role.ANALYST);
        }
        UserAccountEntity user = new UserAccountEntity(
                normalizedEmail,
                passwordEncoder.encode(password),
                first + " " + last,
                roles);
        user.setFirstName(first);
        user.setLastName(last);
        user.setPhone(blankToNull(phone));
        user.setDepartment(blankToNull(department));
        user.setOffice(blankToNull(office));
        user.setRoom(blankToNull(room));
        user.setPosition(blankToNull(position));
        user.setSalutation(blankToNull(salutation));
        user.setEnabled(enabled);
        if (vacationFrom != null && !vacationFrom.isBlank()) {
            try {
                user.setVacations(List.of(new UserAccountEntity.VacationPeriod(
                        java.time.LocalDate.parse(vacationFrom),
                        vacationTo != null && !vacationTo.isBlank()
                                ? java.time.LocalDate.parse(vacationTo) : java.time.LocalDate.parse(vacationFrom),
                        blankToNull(vacationType),
                        blankToNull(vacationNote))));
            } catch (Exception e) {
                log.warn("Invalid vacation period ignored for new user {}: {}", normalizedEmail, e.getMessage());
            }
        }
        userAccountRepository.save(user);
        redirectAttributes.addFlashAttribute("message",
                "Benutzer \"" + first + " " + last + "\" wurde angelegt.");
        return "redirect:/admin/users";
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * Blocks or unblocks a user account. Blocking is enforced at authentication
     * (locked accounts are rejected) and existing sessions are expired immediately.
     */
    @PostMapping("/admin/users/{id}/block")
    public String setBlocked(@PathVariable UUID id,
                             @RequestParam("blocked") boolean blocked,
                             @AuthenticationPrincipal AuthenticatedUser currentUser,
                             RedirectAttributes redirectAttributes) {
        Optional<UserAccountEntity> found = userAccountRepository.findById(id);
        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Benutzer nicht gefunden.");
            return "redirect:/admin/users";
        }
        UserAccountEntity target = found.get();
        if (target.getId().equals(currentUser.id())) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Sie können Ihren eigenen Administrator-Account nicht sperren.");
            return "redirect:/admin/users";
        }
        boolean isAdmin = target.getRoles().stream().anyMatch(r -> r.name().equals("ADMIN"));
        if (blocked && isAdmin && countActiveAdmins() <= 1) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Der letzte verbleibende Administrator kann nicht gesperrt werden.");
            return "redirect:/admin/users";
        }
        target.setLocked(blocked);
        userAccountRepository.save(target);
        if (blocked) {
            expireUserSessions(target.getEmail());
        }
        redirectAttributes.addFlashAttribute("message",
                blocked ? "Benutzer wurde gesperrt." : "Benutzer wurde wieder freigegeben.");
        return "redirect:/admin/users";
    }

    /** Removes a user account after confirmation (form already confirms client-side). */
    @PostMapping("/admin/users/{id}/remove")
    @org.springframework.transaction.annotation.Transactional
    public String removeUser(@PathVariable UUID id,
                             @AuthenticationPrincipal AuthenticatedUser currentUser,
                             RedirectAttributes redirectAttributes) {
        Optional<UserAccountEntity> found = userAccountRepository.findById(id);
        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Benutzer nicht gefunden.");
            return "redirect:/admin/users";
        }
        UserAccountEntity target = found.get();
        if (target.getId().equals(currentUser.id())) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Sie können Ihren eigenen Account nicht entfernen.");
            return "redirect:/admin/users";
        }
        boolean isAdmin = target.getRoles().stream().anyMatch(r -> r.name().equals("ADMIN"));
        if (isAdmin && countActiveAdmins() <= 1) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Der letzte verbleibende Administrator kann nicht entfernt werden.");
            return "redirect:/admin/users";
        }
        String email = target.getEmail();
        expireUserSessions(email);
        refreshTokenSessionRepository.deleteByUser(target);
        userAccountRepository.delete(target);
        redirectAttributes.addFlashAttribute("message", "Benutzer \"" + email + "\" wurde entfernt.");
        return "redirect:/admin/users";
    }

    /** Latest audit event for one actor (user UUID) + event type (real data; empty when never recorded). */
    private Optional<AuditEvent> latestAuditEvent(AuditEventType type, String actorId) {
        try {
            List<AuditEvent> events = auditService.query(new AuditQuery(
                    type, actorId, null, null, null, null, null, null, null, null,
                    0, 1, List.of())).events();
            return events.isEmpty() ? Optional.empty() : Optional.of(events.get(0));
        } catch (Exception e) {
            log.warn("Could not load latest {} for {}: {}", type, actorId, e.getMessage());
            return Optional.empty();
        }
    }

    /** E-Mail addresses with at least one live session in the session registry. */
    private Set<String> activeEmails() {
        Set<String> emails = new HashSet<>();
        try {
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                Object p = principal instanceof Authentication auth ? auth.getPrincipal() : principal;
                if (p instanceof AuthenticatedUser user
                        && !sessionRegistry.getAllSessions(principal, false).isEmpty()) {
                    emails.add(user.email());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read active sessions: {}", e.getMessage());
        }
        return emails;
    }

    private void expireUserSessions(String email) {
        try {
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                Object p = principal instanceof Authentication auth ? auth.getPrincipal() : principal;
                if (p instanceof AuthenticatedUser user && email.equalsIgnoreCase(user.email())) {
                    sessionRegistry.getAllSessions(principal, false).forEach(si -> si.expireNow());
                }
            }
        } catch (Exception e) {
            log.warn("Could not expire sessions for {}: {}", email, e.getMessage());
        }
    }

    private long countActiveAdmins() {
        return userAccountRepository.findAll().stream()
                .filter(u -> u.isEnabled() && !u.isLocked())
                .filter(u -> u.getRoles().stream().anyMatch(r -> r.name().equals("ADMIN")))
                .count();
    }

    // ── Tab: KI / Modelle ─────────────────────────────────────────────────────

    @GetMapping("/admin/models")
    public String models(Model model) {
        var ollama = aiProperties.getOllama();
        model.addAttribute("aiBaseUrl", ollama.getBaseUrl());
        model.addAttribute("aiChatModel", ollama.getChatModel());
        model.addAttribute("aiVerifierModel", ollama.getVerifierModel());
        model.addAttribute("aiVerificationStrategy", ollama.getVerificationStrategy());
        model.addAttribute("aiEmbeddingModel", ollama.getEmbeddingModel());
        model.addAttribute("aiEmbeddingDimension", ollama.getEmbeddingDimension());
        model.addAttribute("aiRequestTimeout", ollama.getRequestTimeout());
        addChrome(model, "KI / Modelle", "models");
        return "admin/models";
    }

    // ── Tab: Infrastruktur / Systemstatus ─────────────────────────────────────

    @GetMapping("/admin/infrastructure")
    public String infrastructure(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        List<ServiceRow> services = new ArrayList<>();
        model.addAttribute("metricsServiceAvailable", true);
        // Phase 2D.13: Das Qdrant-Web-Dashboard ist OHNE Passwort erreichbar —
        // klickbar nur für das versteckte SUPERADMIN-Konto. Das normale
        // ADM/Leitungs-Konto sieht weiterhin den Status, aber keinen
        // Durchklick-Link (reine Anzeige der Adresse).
        boolean qdrantClickable = user != null && user.roles() != null
                && user.roles().contains("SUPERADMIN");

        // PostgreSQL — real connectivity via the datasource
        boolean pgOk = false;
        try {
            pgOk = jdbcTemplate.queryForObject("SELECT 1", Integer.class) != null;
        } catch (Exception ignored) {}
        services.add(new ServiceRow("PostgreSQL", pgOk, "jdbc:postgresql://localhost:5432/verwaltungsassistent", false));

        // Qdrant serves its web UI (dashboard) on the same HTTP port under
        // /dashboard; the availability check stays on the collections API.
        String qdrant = qdrantEndpoint();
        services.add(new ServiceRow("Qdrant", httpOk(qdrant + "/collections"),
                qdrant + "/dashboard", qdrantClickable));

        services.add(new ServiceRow("Neo4j", httpOk(neo4jHttpEndpoint()),
                neo4jHttpEndpoint(), true));

        var ollama = aiProperties.getOllama();
        services.add(new ServiceRow("Ollama", httpOk(ollama.getBaseUrl() + "/api/tags"),
                ollama.getBaseUrl(), true));

        model.addAttribute("services", services);
        addChrome(model, "Infrastruktur", "infrastructure");
        return "admin/infrastructure";
    }

    /**
     * Live resource metrics for Administration → Infrastruktur (ADMIN only).
     * CPU/RAM from the JVM OS bean; GPU/VRAM from nvidia-smi when available.
     */
    @GetMapping(value = "/admin/infrastructure/metrics", produces = "application/json")
    @ResponseBody
    public Map<String, Object> metrics() {
        InfrastructureMetricsService.Metrics m = infrastructureMetricsService.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuPercent", m.cpuPercent());
        if (m.ram() != null) {
            out.put("ramTotalMb", m.ram().totalMb());
            out.put("ramUsedMb", m.ram().usedMb());
            out.put("ramFreeMb", m.ram().freeMb());
            out.put("ramPercent", m.ram().usedPercent());
        }
        if (m.gpuAvailable()) {
            out.put("gpuUtilization", m.gpu().utilizationPercent());
            out.put("vramUsedMb", m.gpu().vramUsedMb());
            out.put("vramTotalMb", m.gpu().vramTotalMb());
            out.put("vramPercent", m.gpu().vramPercent());
        }
        out.put("vramHistory", infrastructureMetricsService.vramHistory());
        out.put("gpuHistory", infrastructureMetricsService.gpuHistory());
        return out;
    }

    private String qdrantEndpoint() {
        String host = System.getProperty("platform.search.qdrant.host", "localhost");
        String port = System.getProperty("platform.search.qdrant.rest-port", "6333");
        return "http://" + host + ":" + port;
    }

    private String neo4jHttpEndpoint() {
        String uri = System.getProperty("platform.neo4j.uri", "bolt://localhost:7687");
        return "http://" + uri.replaceFirst("^bolt://", "").replace(":7687", ":7474");
    }

    private static boolean httpOk(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    /**
     * Purges all derived knowledge indexes (PostgreSQL chunks, Qdrant vectors,
     * Neo4j graph nodes). Uploaded PDF files and the document metadata remain
     * untouched — the documents can be re-indexed afterwards. Orphaned entries
     * from replaced uploads are removed as well (collection-wide vector wipe,
     * document-derived graph wipe). ADMIN-only via the /admin/** security rule.
     */
    @PostMapping("/admin/indices/purge")
    public String purgeIndices(@AuthenticationPrincipal AuthenticatedUser user,
                               RedirectAttributes redirectAttributes) {
        int documents = 0;
        int chunks = 0;
        try {
            for (int page = 0; ; page++) {
                DocumentFilter filter = new DocumentFilter(null, null, null, null, null, null, null, page, 100);
                var pageDocs = documentFacade.findDocuments(filter);
                if (pageDocs.documents().isEmpty()) break;
                for (Document doc : pageDocs.documents()) {
                    if (doc.status() == DocumentStatus.DELETED) continue;
                    documents++;
                    try {
                        chunks += chunkManagementService.deleteByDocumentId(doc.id());
                    } catch (Exception e) {
                        log.warn("Chunk purge failed for {}: {}", doc.id(), e.getMessage());
                    }
                }
                if (pageDocs.documents().size() < 100) break;
            }
            try {
                vectorSearchProvider.deleteAll();
            } catch (Exception e) {
                log.warn("Vector collection purge failed: {}", e.getMessage());
            }
            GraphEnrichmentService graphService = graphServiceProvider.getIfAvailable();
            if (graphService != null) {
                try {
                    graphService.deleteAllDocumentNodes();
                } catch (Exception e) {
                    log.warn("Graph purge failed: {}", e.getMessage());
                }
            }
            redirectAttributes.addFlashAttribute("message",
                    "Indizes vollständig geleert: " + chunks + " Chunks aus " + documents
                            + " Dokumenten entfernt (einschließlich verwaister Einträge). "
                            + "Die PDF-Dateien bleiben erhalten und können neu indexiert werden.");
        } catch (Exception e) {
            log.error("Index purge failed", e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Die Indizes konnten nicht vollständig geleert werden.");
        }
        return "redirect:/documents";
    }


    // ── Tab: Demo-Daten ──────────────────────────────────────────────────────

    /**
     * "Demo-Daten zurücksetzen" (Phase 2D.12: SUPERADMIN only, explicit
     * confirmation): recreates the complete deterministic demo state (users,
     * mailboxes, e-mail catalog, photos, Geovorgänge) from the persistent
     * originals and triggers the existing indexing pipeline. Persistent
     * upload files are never deleted. Das normale ADM/Leitungs-Konto ist rein
     * aufsichtlich und darf diese destruktive Operation nicht ausführen.
     */
    @PostMapping("/admin/demo-reset")
    public String demoReset(@RequestParam(value = "confirm", defaultValue = "off") String confirm,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            RedirectAttributes redirectAttributes) {
        if (user == null || user.roles() == null || !user.roles().contains("SUPERADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Nur das Superadmin-Konto darf die Demo-Daten zurücksetzen.");
        }
        if (!"on".equalsIgnoreCase(confirm)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Abbruch: Die Demo-Daten wurden NICHT zurückgesetzt. "
                            + "Zum Zurücksetzen muss die Bestätigung aktiviert sein.");
            return "redirect:/admin";
        }
        try {
            int created = demoDataService.reset();
            redirectAttributes.addFlashAttribute("message",
                    "Demo-Daten wurden auf den definierten Ausgangszustand zurückgesetzt ("
                            + created + " Datensätze). Demo-Benutzer: demo01@verwaltungsassistent.local … demo"
                            + String.format("%02d",
                                    verwaltungsassistent.web.service.DemoDataService.DEMO_USER_COUNT)
                            + "@verwaltungsassistent.local, Passwort: "
                            + verwaltungsassistent.web.service.DemoDataService.DEMO_PASSWORD
                            + ". Postfächer: " + verwaltungsassistent.web.service.DemoDataService.MAILBOX_KONTAKT
                            + ", " + verwaltungsassistent.web.service.DemoDataService.MAILBOX_INFO + ".");
        } catch (Exception e) {
            log.error("Demo reset failed", e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Das Zurücksetzen der Demo-Daten ist fehlgeschlagen: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    // ── Datensatz neu aufbauen (Admin) ─────────────────────────────────────

    private static final String DATASET_ACTIVE_KEY = "dataset-rebuild";

    /**
     * „Datensatz neu aufbauen": scannt uploads/docs|photos|video|audio, importiert
     * und indexiert Dokumente/Fotos über die bestehende Pipeline und baut die
     * Indizes des registrierten Korpus neu auf. Löscht nie Dateien. Läuft
     * asynchron über das etablierte JobProgress-Muster (Fortschritts-Panel).
     */
    @PostMapping(value = "/admin/dataset-rebuild", produces = "text/html;charset=UTF-8")
    public String datasetRebuild(@RequestParam(value = "confirm", defaultValue = "off") String confirm,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 Model model) {
        if (user == null || user.roles() == null || !user.roles().contains("SUPERADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Nur das Superadmin-Konto darf den Datensatz neu aufbauen.");
        }
        if (!"on".equalsIgnoreCase(confirm)) {
            return datasetReportFragment(model, "Bitte bestätigen Sie, dass der Datensatz neu aufgebaut werden darf.");
        }
        verwaltungsassistent.web.service.JobProgressService.Job job =
                progressService.activeJob(DATASET_ACTIVE_KEY);
        if (job == null) {
            job = progressService.create(verwaltungsassistent.web.service.JobProgressService.Kind.DATASET,
                    "Datensatz wird neu aufgebaut");
            progressService.registerActive(DATASET_ACTIVE_KEY, job.jobId);
        }
        final String jobId = job.jobId;
        datasetExecutor.submit(() -> runDatasetRebuild(jobId));
        model.addAttribute("title", "Datensatz wird neu aufgebaut");
        model.addAttribute("messages", job.messages);
        model.addAttribute("pollUrl", "/admin/dataset-rebuild/progress/" + jobId);
        model.addAttribute("emptyMessage", "Die Verzeichnisse werden geprüft …");
        model.addAttribute("hint", "Der Neuaufbau kann einen Moment dauern. Bestehende Dateien werden nicht gelöscht.");
        model.addAttribute("error", null);
        return "fragments/progress :: progressPanel";
    }

    private void runDatasetRebuild(String jobId) {
        try {
            verwaltungsassistent.web.service.DatasetAdministrationService.DatasetReport report =
                    datasetAdministrationService.rebuild(msg ->
                            progressService.recordStage(jobId, msg));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("report", report);
            String rendered = templateEngine.process("admin/dataset-report",
                    java.util.Set.of("reportFragment"),
                    new org.thymeleaf.context.Context(java.util.Locale.GERMANY, m));
            progressService.complete(jobId, rendered, "Der Datensatz wurde neu aufgebaut.");
        } catch (Exception e) {
            log.error("Datensatz-Neuaufbau fehlgeschlagen", e);
            progressService.fail(jobId);
        }
    }

    @GetMapping("/admin/dataset-rebuild/progress/{jobId}")
    public ResponseEntity<String> datasetProgress(@PathVariable String jobId) {
        var job = progressService.get(jobId);
        if (job == null) {
            Model m = new org.springframework.ui.ExtendedModelMap();
            return datasetHtml(templateEngine.process("admin/dataset-report",
                    java.util.Set.of("reportFragment"),
                    new org.thymeleaf.context.Context(java.util.Locale.GERMANY,
                            java.util.Map.of("report", null, "error",
                                    "Die Operation ist nicht mehr verfügbar. Bitte erneut ausführen."))));
        }
        if ("DONE".equals(job.state) && job.outcome != null) {
            return datasetHtml((String) job.outcome);
        }
        if ("ERROR".equals(job.state)) {
            return datasetHtml(templateEngine.process("admin/dataset-report",
                    java.util.Set.of("reportFragment"),
                    new org.thymeleaf.context.Context(java.util.Locale.GERMANY,
                            java.util.Map.of("report", null, "error",
                                    "Der Datensatz-Neuaufbau ist fehlgeschlagen. Bitte im Log nachsehen und erneut versuchen."))));
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("title", "Datensatz wird neu aufgebaut");
        attrs.put("messages", job.messages);
        attrs.put("pollUrl", "/admin/dataset-rebuild/progress/" + jobId);
        attrs.put("emptyMessage", "Die Verzeichnisse werden geprüft …");
        attrs.put("hint", "Der Neuaufbau kann einen Moment dauern. Bestehende Dateien werden nicht gelöscht.");
        return datasetHtml(templateEngine.process("fragments/progress",
                java.util.Set.of("progressPanel"),
                new org.thymeleaf.context.Context(java.util.Locale.GERMANY, attrs)));
    }

    private String datasetReportFragment(Model model, String error) {
        model.addAttribute("report", null);
        model.addAttribute("error", error);
        return "admin/dataset-report :: reportFragment";
    }

    private static ResponseEntity<String> datasetHtml(String body) {
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/html;charset=UTF-8")).body(body);
    }

    private void addChrome(Model model, String title, String tab) {
        model.addAttribute("adminTitle", title);
        model.addAttribute("adminTab", tab);
        model.addAttribute("pageTitle", "Administration — " + title);
        model.addAttribute("activeSection", "admin");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Administration", "/admin")));
    }

    private long countDocuments(DocumentStatus status) {
        try {
            DocumentFilter filter = new DocumentFilter(status, null, null, null, null, null, null, 0, 1);
            return documentFacade.findDocuments(filter).totalElements();
        } catch (Exception e) {
            log.warn("Could not count documents (status={}): {}", status, e.getMessage());
            return 0;
        }
    }

    public record UserRow(UUID id, String email, String displayName, List<String> roles,
                          boolean admin, boolean active, String lastLogin, String lastLogout,
                          String lastIp, boolean blocked, boolean online,
                          String department, String phone, String room, String vacationSummary,
                          boolean onVacation) {}

    /** True wenn heute innerhalb eines geplanten Urlaubszeitraums des Benutzers liegt. */
    private static boolean onVacationToday(UserAccountEntity u) {
        if (u.getVacations() == null || u.getVacations().isEmpty()) return false;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (var v : u.getVacations()) {
            if (v.from() != null && v.to() != null
                    && !today.isBefore(v.from()) && !today.isAfter(v.to())) {
                return true;
            }
        }
        return false;
    }

    /** Zeile der Diensteverfügbarkeit: linkable = Endpunkt als Durchklick-Link
     *  anbieten (Rollen-abhängig, z. B. Qdrant-Dashboard nur für SUPERADMIN). */
    public record ServiceRow(String name, boolean ready, String endpoint, boolean linkable) {}
}
