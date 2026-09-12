package verwaltungsassistent.web.service;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditQuery;
import reasoning.audit.api.AuditService;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.IngestionStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.infrastructure.persistence.DocumentEntity;
import reasoning.document.infrastructure.persistence.JpaDocumentEntityRepository;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.viewmodel.DashboardViewModel;
import verwaltungsassistent.web.viewmodel.DashboardViewModel.EmailWorkRow;
import verwaltungsassistent.web.viewmodel.DashboardViewModel.RecentActivity;
import verwaltungsassistent.web.viewmodel.DashboardViewModel.WorkspaceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DocumentFacade documentFacade;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final JpaDocumentEntityRepository documentEntityRepository;
    private final UserAccountRepository userAccountRepository;
    private final verwaltungsassistent.web.planning.PriorityCalculationService priorityCalculationService;

    public DashboardService(DocumentFacade documentFacade,
                            WorkspaceService workspaceService,
                            AuditService auditService,
                            JpaIncomingEmailRepository incomingEmailRepository,
                            JpaDocumentEntityRepository documentEntityRepository,
                            UserAccountRepository userAccountRepository,
                            verwaltungsassistent.web.planning.PriorityCalculationService priorityCalculationService) {
        this.documentFacade = documentFacade;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
        this.incomingEmailRepository = incomingEmailRepository;
        this.documentEntityRepository = documentEntityRepository;
        this.userAccountRepository = userAccountRepository;
        this.priorityCalculationService = priorityCalculationService;
    }

    public DashboardViewModel build(AuthenticatedUser user) {
        int totalDocuments = countByStatus(null);
        int readyDocuments = countByStatus(DocumentStatus.READY);
        int processingDocuments = countByStatus(DocumentStatus.INGESTING)
                + countByStatus(DocumentStatus.INGESTION_PENDING);
        int failedDocuments = countByStatus(DocumentStatus.FAILED);

        int activeWorkspaces;
        try {
            activeWorkspaces = (int) workspaceService.findByOwner(user.email()).stream()
                    .filter(w -> w.getStatus() == WorkspaceStatus.ACTIVE)
                    .count();
        } catch (Exception e) {
            log.warn("Could not load workspace count", e);
            activeWorkspaces = 0;
        }

        List<RecentActivity> recentActivity;
        try {
            AuditQuery query = new AuditQuery(
                    null, null, null, null, null,
                    null, null, null, null, null,
                    0, 10, java.util.List.of());
            recentActivity = auditService.query(query).events().stream()
                    .map(this::toRecentActivity)
                    .limit(8)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load audit events", e);
            recentActivity = List.of();
        }

        int activeIngestionJobs = countJobs(IngestionStatus.RUNNING)
                + countJobs(IngestionStatus.PENDING);

        return new DashboardViewModel(
                user.displayName(),
                user.roles().stream().sorted().toList(),
                totalDocuments,
                readyDocuments,
                processingDocuments,
                failedDocuments,
                activeWorkspaces,
                activeIngestionJobs,
                recentActivity,
                recentWorkspaces(user),
                recentEmailWork(user)
        );
    }

    /** Mirrors the case-list access model: ADMIN sees all cases, others own + general pool. */
    private List<WorkspaceRow> recentWorkspaces(AuthenticatedUser user) {
        try {
            List<WorkspaceEntity> all = user.roles().contains("ADMIN")
                    ? new ArrayList<>(workspaceService.findAll())
                    : new ArrayList<>(verwaltungsassistent.web.security.WorkspaceVisibility
                            .ownAndPool(workspaceService, user.email()));
            all.sort(Comparator.comparing(WorkspaceEntity::getUpdatedAt).reversed());
            return all.stream().limit(5)
                    .map(w -> new WorkspaceRow(
                            w.getId(),
                            w.getName() != null ? w.getName() : w.getWorkspaceCode(),
                            w.getWorkspaceCode(),
                            statusLabel(w.getStatus()),
                            statusVariant(w.getStatus()),
                            w.getPhase() != null ? phaseLabel(w.getPhase()) : "—",
                            w.getPhase() != null ? w.getPhase().ordinal() : 0,
                            w.getUpdatedAt() != null
                                    ? DATE_FMT.format(w.getUpdatedAt().atZone(ZoneId.systemDefault())) : "—"))
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load recent workspaces", e);
            return List.of();
        }
    }

    /**
     * The unprocessed e-mail work feed. Administrator: all new e-mails with
     * their addressee and assignment. Employee: e-mails addressed to them
     * plus general unassigned office e-mails.
     */
    private List<EmailWorkRow> recentEmailWork(AuthenticatedUser user) {
        try {
            boolean isAdmin = user.roles() != null && user.roles().contains("ADMIN");
            List<IncomingEmailEntity> all = incomingEmailRepository
                    .findByStatusOrderByReceivedAtDesc(IncomingEmailEntity.Status.NEW);
            return all.stream()
                    .filter(e -> isAdmin
                            || (e.getAddressedTo() == AddressedTo.EMPLOYEE
                                    && user.email().equals(e.getAddressedToEmail()))
                            || (e.getAddressedTo() == AddressedTo.GENERAL
                                    && (e.getAssignedTo() == null
                                            || e.getAssignedTo().equals(user.email()))))
                    // Phase 2D.12: dieselbe Sortierung wie die E-Mail-Warteschlange —
                    // höchste Wartezeit-Dringlichkeit zuerst, bei Gleichstand
                    // neueste zuerst (einheitliche arbeitsbezogene Reihung).
                    .sorted(Comparator
                            .comparingInt((IncomingEmailEntity e) -> {
                                long days = waitingDaysOf(e);
                                return priorityCalculationService
                                        .emailPriorityClass(days).ordinal();
                            })
                            .thenComparing(IncomingEmailEntity::getReceivedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(5)
                    .map(e -> {
                        String assignee = e.effectiveAssignee();
                        // Kompakte Wartezeit-Dringlichkeit (deterministisch, kein
                        // LLM) — die Mitarbeiterin erkennt sofort, was dringt.
                        long days = waitingDaysOf(e);
                        var priorityClass = priorityCalculationService.emailPriorityClass(days);
                        return new EmailWorkRow(
                                e.getId().toString(),
                                e.getSubject(),
                                addressedToLabel(e),
                                assignee,
                                e.getStatus() == IncomingEmailEntity.Status.NEW
                                        ? (assignee != null ? "Zugewiesen an: " + assignee : "Nicht zugewiesen")
                                        : statusLabel(e.getStatus()),
                                e.getStatus() == IncomingEmailEntity.Status.NEW
                                        ? (assignee != null ? "info" : "warning") : "success",
                                e.getReceivedAt(),
                                dringlichkeitLabel(priorityClass),
                                priorityCalculationService.variant(priorityClass),
                                e.isReviewRequired());
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load e-mail work feed", e);
            return List.of();
        }
    }

    /** Ganze Kalendertage seit Eingang (lokale Zeit) — dieselbe Basis wie die Warteschlange. */
    private static long waitingDaysOf(IncomingEmailEntity e) {
        return e.getReceivedAt() != null
                ? Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(
                        e.getReceivedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        java.time.LocalDate.now()))
                : 0;
    }

    private static String addressedToLabel(IncomingEmailEntity e) {
        if (e.getAddressedTo() == AddressedTo.EMPLOYEE) {
            return e.getAddressedToEmail() != null ? e.getAddressedToEmail() : "Mitarbeiter/in";
        }
        return "Bürgeramt – Allgemeine Anfragen";
    }

    /** Grammatikalisch korrekte Dringlichkeits-Aussage ("Sehr hohe Dringlichkeit", …). */
    private static String dringlichkeitLabel(verwaltungsassistent.web.planning.PriorityCalculationService.PriorityClass pc) {
        return switch (pc) {
            case SEHR_HOCH -> "Sehr hohe Dringlichkeit";
            case HOCH -> "Hohe Dringlichkeit";
            case MITTEL -> "Mittlere Dringlichkeit";
            case NIEDRIG -> "Niedrige Dringlichkeit";
        };
    }

    private static String statusLabel(IncomingEmailEntity.Status status) {
        if (status == null) return "—";
        return switch (status) {
            case NEW -> "Neu";
            case IN_PROGRESS -> "In Bearbeitung";
            case COMPLETED -> "Erledigt";
        };
    }

    private static String statusLabel(WorkspaceStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case ACTIVE -> "Aktiv";
            case DRAFT -> "Entwurf";
            case CLOSED -> "Geschlossen";
            case ARCHIVED -> "Archiviert";
        };
    }

    private static String statusVariant(WorkspaceStatus s) {
        if (s == null) return "neutral";
        return switch (s) {
            case ACTIVE -> "success";
            case DRAFT -> "warning";
            case CLOSED -> "neutral";
            case ARCHIVED -> "info";
        };
    }

    private static String phaseLabel(WorkspacePhase p) {
        return switch (p) {
            case SETUP -> "Einrichtung";
            case INGESTION -> "Ingestion";
            case ANALYSIS -> "Analyse";
            case REVIEW -> "Überprüfung";
            case COMPLETE -> "Abschluss";
        };
    }

    // ── Leitungs-Übersicht (Phase 2C.5) ──────────────────────────────────────

    /** Aufsichts-Übersicht für das Leitungs-Konto: echte, vorhandene Zahlen der
     *  gesamten Verwaltung — keine Empfehlung, keine Bewertung von Mitarbeitern. */
    public record SupervisionView(
            int openCases, int poolCases, int inWorkCases, int pausedCases,
            int openEmails, int emailsToday,
            List<TeamCaseRow> recentlyCreated,
            List<TeamCaseRow> recentlyClosed,
            List<EmployeeLoad> byEmployee,
            String heroLine) {}

    /** Ein Vorgang in der Leitungs-Sicht (Eigentümer als E-Mail, sonst "—"). */
    public record TeamCaseRow(String id, String name, String workspaceCode,
                              String statusLabel, String phaseLabel, String owner,
                              String dateLabel) {}

    /** Offene Vorgänge je Mitarbeiterin (Aufsicht; KEINE Bewertung/Reihung). */
    public record EmployeeLoad(String email, int openCount) {}

    private static final DateTimeFormatter SUP_DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Supervisory dashboard model — derived from the existing workspace/email
     *  repositories only (no analytics, no new aggregation jobs). */
    public SupervisionView supervisionView(AuthenticatedUser user) {
        try {
            List<WorkspaceEntity> all = new ArrayList<>(workspaceService.findAll());
            List<WorkspaceEntity> open = all.stream()
                    .filter(w -> w.getStatus() == WorkspaceStatus.ACTIVE
                            || w.getStatus() == WorkspaceStatus.DRAFT)
                    .toList();
            int pool = 0;
            int inWork = 0;
            int paused = 0;
            Map<String, Integer> byOwner = new java.util.LinkedHashMap<>();
            for (WorkspaceEntity w : open) {
                if (verwaltungsassistent.web.service.DemoDataService
                        .isGeneralPoolOwner(w.getOwnerId())) {
                    pool++;
                } else {
                    inWork++;
                    byOwner.merge(w.getOwnerId(), 1, Integer::sum);
                }
                if (isPaused(w)) {
                    paused++;
                }
            }
            List<EmployeeLoad> loads = byOwner.entrySet().stream()
                    .map(e -> new EmployeeLoad(e.getKey(), e.getValue()))
                    .sorted(java.util.Comparator.comparingInt(EmployeeLoad::openCount).reversed()
                            .thenComparing(EmployeeLoad::email))
                    .toList();
            List<TeamCaseRow> created = open.stream()
                    .sorted(java.util.Comparator.comparing(
                            WorkspaceEntity::getCreatedAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .limit(6)
                    .map(w -> toTeamRow(w, w.getCreatedAt()))
                    .toList();
            List<TeamCaseRow> closed = all.stream()
                    .filter(w -> w.getStatus() == WorkspaceStatus.CLOSED)
                    .sorted(java.util.Comparator.comparing(
                            WorkspaceEntity::getUpdatedAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .limit(6)
                    .map(w -> toTeamRow(w, w.getUpdatedAt()))
                    .toList();
            List<IncomingEmailEntity> emails = incomingEmailRepository
                    .findByStatusOrderByReceivedAtDesc(IncomingEmailEntity.Status.NEW);
            int emailsToday = (int) emails.stream()
                    .filter(e -> e.getReceivedAt() != null
                            && e.getReceivedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                    .equals(java.time.LocalDate.now()))
                    .count();
            int openEmails = emails.size();
            return new SupervisionView(
                    open.size(), pool, inWork, paused, openEmails, emailsToday,
                    created, closed, loads,
                    "Leitungs-Übersicht: " + open.size() + " offene Vorgänge, davon "
                            + pool + " nicht zugewiesen · " + openEmails + " offene E-Mail-Eingänge");
        } catch (Exception e) {
            log.warn("Leitungs-Übersicht nicht berechenbar", e);
            return new SupervisionView(0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(),
                    "Leitungs-Übersicht ist derzeit nicht verfügbar.");
        }
    }

    private TeamCaseRow toTeamRow(WorkspaceEntity w, java.time.Instant when) {
        return new TeamCaseRow(
                w.getId(),
                w.getName() != null ? w.getName() : w.getWorkspaceCode(),
                w.getWorkspaceCode(),
                statusLabel(w.getStatus()),
                w.getPhase() != null ? phaseLabel(w.getPhase()) : "—",
                // Phase 2D.12/2D.13: Zuständigkeits-Feld eindeutig lesbar —
                // Pool-Vorgänge (nicht zugewiesen ODER alter Pool-Marker
                // admin@verwaltungsassistent.local) heißen "Nicht zugewiesen", nie nackt "—".
                verwaltungsassistent.web.service.DemoDataService
                        .isGeneralPoolOwner(w.getOwnerId())
                        ? "Nicht zugewiesen"
                        : w.getOwnerId(),
                when != null ? SUP_DATE_FMT.format(when.atZone(ZoneId.systemDefault())) : "—");
    }

    private static boolean isPaused(WorkspaceEntity w) {
        if (w.getPhaseData() == null || w.getPhaseData().isBlank()) {
            return false;
        }
        try {
            java.util.Map<String, Object> data = SUP_MAPPER.readValue(
                    w.getPhaseData(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object raw = data.get("workState");
            return raw instanceof java.util.Map<?, ?> m
                    && "PAUSED".equals(String.valueOf(m.get("state")));
        } catch (Exception e) {
            return false;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SUP_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private int countJobs(IngestionStatus status) {
        try {
            IngestionJobFilter filter = new IngestionJobFilter(null, status, null, 0, 1);
            return (int) documentFacade.findIngestionJobs(filter).totalElements();
        } catch (Exception e) {
            log.warn("Could not count ingestion jobs with status {}", status, e);
            return 0;
        }
    }

    private int countByStatus(DocumentStatus status) {
        try {
            DocumentFilter filter = new DocumentFilter(
                    status, null, null, null, null,
                    null, null, 0, 1);
            return (int) documentFacade.findDocuments(filter).totalElements();
        } catch (Exception e) {
            log.warn("Could not count documents with status {}", status, e);
            return 0;
        }
    }

    /**
     * Zeile der "Letzte Aktivitäten"-Tabelle (Phase 2D.12): Beschreibung und
     * Entitäts-Label auf Deutsch; die Kennung zeigt, wo möglich, einen
     * menschenlesbaren Bezug (Dokumenttitel bzw. Kontoname) statt der
     * technischen UUID — die technische ID bleibt sekundär verfügbar
     * ({@code entityId}).
     */
    private RecentActivity toRecentActivity(AuditEvent event) {
        String entityType = event.entityType();
        String entityId = event.entityId();
        String entityLabel = entityTypeLabel(entityType);
        String entityRef = entityId != null ? readableReferenceOf(entityType, entityId) : null;
        return new RecentActivity(
                event.timestamp(),
                activityLabel(event.eventType() != null ? event.eventType().name() : "—"),
                entityType,
                entityId,
                entityLabel,
                entityRef
        );
    }

    /** Menschenlesbarer Bezug eines Audit-Eintrags (lesend, ohne neue Audit-Ereignisse). */
    private String readableReferenceOf(String entityType, String entityId) {
        try {
            UUID uuid = UUID.fromString(entityId);
            if ("DOCUMENT".equals(entityType)) {
                return documentEntityRepository.findById(uuid)
                        .map(DocumentEntity::getTitle)
                        .filter(t -> t != null && !t.isBlank())
                        .orElse(null);
            }
            if ("AUTH_USER".equals(entityType)) {
                return userAccountRepository.findById(uuid)
                        .map(u -> {
                            String name = u.getDisplayName();
                            return name != null && !name.isBlank() ? name : u.getEmail();
                        })
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Bezug von Audit-Eintrag {}/{} nicht lesbar: {}",
                    entityType, entityId, e.getMessage());
        }
        return null;
    }

    /** Verständliches deutsches Label der Entitäts-Art (statt technischem Wert). */
    public static String entityTypeLabel(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "—";
        }
        return switch (entityType) {
            case "AUTH_USER" -> "Benutzer";
            case "DOCUMENT" -> "Dokument";
            case "AI_INFERENCE" -> "KI-Analyse";
            case "RETRIEVAL" -> "Suche";
            default -> entityType;
        };
    }

    /** Maps technical event type names to German labels for the activity feed. */
    private static String activityLabel(String eventTypeName) {
        return switch (eventTypeName) {
            case "USER_LOGIN" -> "Anmeldung";
            case "USER_LOGIN_FAILED" -> "Anmeldung fehlgeschlagen";
            case "USER_LOGOUT" -> "Abmeldung";
            case "USER_CREATED" -> "Benutzer angelegt";
            case "USER_REGISTRATION_FAILED" -> "Benutzer-Registrierung fehlgeschlagen";
            case "TOKEN_REFRESHED" -> "Sitzung erneuert";
            case "TOKEN_REFRESH_FAILED" -> "Sitzungs-Erneuerung fehlgeschlagen";
            case "ROLE_CHANGED" -> "Rolle geändert";
            case "MODEL_INFERENCE" -> "KI-Analyse";
            case "SEARCH_EXECUTED" -> "Suche";
            case "RETRIEVAL_EXECUTED" -> "Dokumentabruf";
            case "RERANKING_EXECUTED" -> "Neu-Reihung";
            case "PROMPT_EXECUTED" -> "Prompt ausgeführt";
            case "SUMMARY_GENERATED" -> "Zusammenfassung erstellt";
            case "CONTENT_EXTRACTION_EXECUTED" -> "Texterkennung";
            case "DOCUMENT_UPLOADED" -> "Dokument hochgeladen";
            case "DOCUMENT_INGESTED" -> "Dokument verarbeitet";
            case "DOCUMENT_UPDATED" -> "Dokument aktualisiert";
            case "DOCUMENT_VIEWED" -> "Dokument geöffnet";
            case "DOCUMENT_DELETED" -> "Dokument gelöscht";
            case "CASE_CREATED" -> "Fall angelegt";
            case "CASE_UPDATED" -> "Fall bearbeitet";
            case "CASE_ARCHIVED" -> "Fall archiviert";
            case "CASE_CLOSED" -> "Fall geschlossen";
            default -> eventTypeName.replace("_", " ").substring(0, 1).toUpperCase()
                    + eventTypeName.replace("_", " ").substring(1).toLowerCase();
        };
    }
}
