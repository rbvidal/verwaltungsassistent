package verwaltungsassistent.web.planning;

import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.workspace.api.TimelineEventEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.planning.persistence.CasePlanningEntity;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.service.CaseBriefingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Arbeitsplanungs-Orchestrator (Phase 1): sammelt die strukturierten
 * Fall-Fakten, berechnet Priorität (deterministisch) und Bearbeitbarkeit,
 * persistiert den erklärbaren Planungsstand je Fall und stellt die
 * Planungs-Sichten für Phase 2 bereit (persönliche Liste, globaler Pool).
 *
 * <p>Bewusst wiederverwendet statt neu erfunden:</p>
 * <ul>
 *   <li><b>Frische/Fingerabdruck:</b> {@link CaseBriefingService#fingerprint}
 *       — derselbe Mechanismus wie beim Fallbriefing. Unveränderter
 *       Fingerabdruck → gespeicherter Planungsstand wird wiederverwendet
 *       (keine Neuberechnung, keine zweite Frische-Mechanik).</li>
 *   <li><b>Analyse-Wiederverwendung:</b> die persistierten Analyse-Läufe
 *       ({@code workspace_analysis_runs}) über
 *       {@code latestCompletedAnalysisRun/deserializeAnalysisResult} und die
 *       Beleg-Identitäten ({@code deserializeEvidenceIds}) — dieselbe
 *       Frische-Prüfung wie die Entscheidungs-Seite. Es wird NIE eine neue
 *       LLM-Analyse nur für die Planung angestoßen.</li>
 * </ul>
 */
@Service
public class CasePlanningService {

    private static final Logger log = LoggerFactory.getLogger(CasePlanningService.class);
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final JpaCasePlanningRepository planningRepository;
    private final CaseBriefingService caseBriefingService;
    private final PriorityCalculationService priorityService;
    private final WorkabilityService workabilityService;
    private final CaseWorkStateService caseWorkStateService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CasePlanningService(WorkspaceService workspaceService,
                               DocumentFacade documentFacade,
                               JpaIncomingEmailRepository incomingEmailRepository,
                               JpaCasePlanningRepository planningRepository,
                               CaseBriefingService caseBriefingService,
                               PriorityCalculationService priorityService,
                               WorkabilityService workabilityService,
                               CaseWorkStateService caseWorkStateService) {
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.incomingEmailRepository = incomingEmailRepository;
        this.planningRepository = planningRepository;
        this.caseBriefingService = caseBriefingService;
        this.priorityService = priorityService;
        this.workabilityService = workabilityService;
        this.caseWorkStateService = caseWorkStateService;
    }

    // ── Planungsstand ──

    /**
     * Liefert den Planungsstand eines Falls — gespeichert, wenn die
     * Fall-Eingaben unverändert sind (Fingerabdruck), sonst neu berechnet und
     * persistiert. Abgeschlossene/archivierte Fälle haben keinen Planungsstand.
     */
    public CasePlanningView planningFor(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null) {
                return null;
            }
            if (ws.getStatus() == WorkspaceStatus.CLOSED || ws.getStatus() == WorkspaceStatus.ARCHIVED) {
                return null;
            }
            // EXPLIZITE Warteentscheidungen (waitingOn) werden hier bewusst
            // NICHT verändert (Phase 2B.7): Ein von der Mitarbeiterin gesetzter
            // Wartegrund ist ein erstklassiger Workflow-Zustand und darf durch
            // eine Leseoperation nie stillschweigend gelöscht werden. Die
            // automatische Auflösung eines DOKUMENT-Blocks ist rein fakten-
            // getrieben (WorkabilityService: Unterlagen vorhanden/aufgelöst →
            // bearbeitbar) — der explizite Wartehinweis bleibt als Zustand und
            // Historie erhalten, bis die Mitarbeiterin fortsetzt.
            String fingerprint = caseBriefingService.fingerprint(caseId);
            CasePlanningEntity row = planningRepository.findByCaseId(UUID.fromString(caseId)).orElse(null);
            if (row != null && fingerprint.equals(row.getBasisFingerprint())) {
                return toView(row);
            }
            CaseFacts facts = gatherFacts(ws);
            PriorityCalculationService.PriorityResult priority = priorityService.calculate(facts);
            WorkabilityService.Workability workability = workabilityService.determine(facts);
            // Antwort-ausstehend-Klassifikation: Ein Vorgang, der aus einer
            // OFFENEN Bürger-E-Mail entstand und noch keine Unterlagen bzw.
            // keine explizite Auflösung hat, wartet NICHT auf Bürger-Unterlagen
            // — die Verwaltung schuldet die Antwort. Der Vorgang ist dann
            // bearbeitbar (RESPONSE_PENDING statt WAITING_FOR_DOCUMENTS).
            if (workability.state() == WorkabilityState.WAITING_FOR_DOCUMENTS
                    && emailRequestPending(ws)) {
                workability = new WorkabilityService.Workability(
                        true, null, WorkabilityState.RESPONSE_PENDING);
            }
            CasePlanningEntity updated = row != null ? row : new CasePlanningEntity(UUID.fromString(caseId));
            updated.setPriorityScore(priority.score());
            updated.setPriorityClass(priority.priorityClass().name());
            updated.setPriorityReason(priority.summary());
            updated.setFactorsJson(writeJson(priority.factors()));
            updated.setWorkable(workability.workable());
            updated.setBlockedReason(workability.blockedReason());
            updated.setWorkabilityState(workability.state().name());
            updated.setCalculatedAt(Instant.now());
            updated.setBasisAnalysisVersion(facts.analysisVersion());
            updated.setBasisFingerprint(fingerprint);
            planningRepository.save(updated);
            return toView(updated);
        } catch (Exception e) {
            log.warn("Planungsstand für Fall {} nicht berechenbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    // ── Planungs-Sichten für Phase 2 ──

    /**
     * Persönliche Planung einer Mitarbeiterin: bearbeitbare Fälle (Kandidaten
     * für "jetzt als Nächstes", absteigend nach Priorität, mit Rang) und
     * persönlicher Rückstand (begonnene, derzeit blockierte Fälle).
     * Bewusst KEINE automatische Zuordnung — der Vergleich persönlicher
     * Rückstand vs. globaler Pool obliegt Phase 2 nach demselben Modell.
     */
    public PersonalPlanning personalPlanning(String ownerEmail) {
        List<CasePlanningView> workable = new ArrayList<>();
        List<CasePlanningView> backlog = new ArrayList<>();
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return new PersonalPlanning(workable, backlog);
        }
        try {
            for (WorkspaceEntity ws : workspaceService.findByOwner(ownerEmail)) {
                if (!isOpen(ws.getStatus())) {
                    continue;
                }
                CasePlanningView view = planningFor(ws.getId());
                if (view == null) {
                    continue;
                }
                (view.workable() ? workable : backlog).add(view);
            }
        } catch (Exception e) {
            log.warn("Persönliche Planung für {} nicht lesbar: {}", ownerEmail, e.getMessage());
        }
        workable.sort(byScoreDesc());
        backlog.sort(byScoreDesc());
        for (int i = 0; i < workable.size(); i++) {
            workable.set(i, withRank(workable.get(i), i + 1));
        }
        return new PersonalPlanning(workable, backlog);
    }

    /**
     * Globaler Arbeitspool (Phase 2-Abfragegrundlage): alle offenen,
     * bearbeitbaren Fälle mit Planungsstand, absteigend nach Priorität.
     * Keine automatische Zuweisung; die Zuordnung bleibt explizite Aktion.
     */
    public List<CasePlanningView> globalWorkPool() {
        List<CasePlanningView> pool = new ArrayList<>();
        try {
            for (WorkspaceEntity ws : workspaceService.findAll()) {
                if (!isOpen(ws.getStatus())) {
                    continue;
                }
                CasePlanningView view = planningFor(ws.getId());
                if (view != null && view.workable()) {
                    pool.add(view);
                }
            }
        } catch (Exception e) {
            log.warn("Globaler Arbeitspool nicht lesbar: {}", e.getMessage());
        }
        pool.sort(byScoreDesc());
        return pool;
    }

    // ── Anzeige-Baustein für die Fallseite ──

    /**
     * Kompakte, nicht-modale Planungs-Meldung für die Fallseite:
     * "Als Nächstes bearbeiten" / "Warte noch auf …" (blockiert) /
     * "Persönlicher Rückstand" (eigener, blockierter Fall). Keine Popups.
     */
    public PlanningAlert alertFor(String caseId, String currentUserEmail) {
        CasePlanningView view = planningFor(caseId);
        if (view == null) {
            return null;
        }
        boolean mine = currentUserEmail != null && currentUserEmail.equalsIgnoreCase(ownerOf(caseId));
        Integer rank = mine ? rankInPersonalQueue(caseId, currentUserEmail) : null;
        // "Priorität: X" = aktuelle Wichtigkeit; "Platz N in Ihrer Arbeitsliste" =
        // Rang unter den bearbeitbaren Fällen — bewusst KEINE Tagesplanung
        // ("heute empfohlen" bleibt Phase 2 vorbehalten).
        String priority = "Priorität: " + view.priorityClassLabel();
        String rankSuffix = rank != null ? " · Platz " + rank + " in Ihrer Arbeitsliste" : "";
        if (!view.workable()) {
            String headline = shortBlockedHeadline(view.blockedReason());
            if (mine) {
                // Ehrliche Zustands-Darstellung für Vorgänge, die automatisch
                // aus einer E-Mail entstanden sind, an denen die Mitarbeiterin
                // aber noch NICHT gearbeitet hat (kein workState): "begonnen"
                // wird nur behauptet, wenn der Arbeitszustand es tatsächlich
                // sagt (Phase 2C.3b — neuer Vorgang wartet auf Bearbeitung).
                if (freshEmailCreatedCase(caseId)) {
                    return new PlanningAlert("warning", "Neuer Vorgang",
                            "Der Vorgang wurde aus einer E-Mail erstellt und wartet auf die weitere Bearbeitung. "
                                    + (view.blockedReason() != null ? view.blockedReason()
                                            : "Der Vorgang ist derzeit noch nicht bearbeitbar.")
                                    + " " + priority + rankSuffix,
                            view.reasons());
                }
                return new PlanningAlert("warning", "Persönlicher Rückstand",
                        "Sie haben diesen Fall bereits begonnen. "
                                + (view.blockedReason() != null ? view.blockedReason()
                                        : "Der Fall ist derzeit nicht bearbeitbar.")
                                + " " + priority + rankSuffix,
                        view.reasons());
            }
            return new PlanningAlert("warning", headline,
                    "Dieser Fall ist derzeit blockiert. " + priority + rankSuffix,
                    view.reasons());
        }
        // Antwort ausstehend (E-Mail-Vorgang ohne Bürger-Unterlagen-Blockade):
        // ehrliche Klassifikation — die Verwaltung schuldet die Antwort, der
        // Vorgang ist bearbeitbar. Nie wird behauptet, die Anwendung habe etwas
        // versendet (streng eingangsorientiert).
        if ("RESPONSE_PENDING".equals(view.workabilityState())) {
            List<String> reasons = new ArrayList<>(view.reasons());
            reasons.add(0, "Antwort ausstehend – die Anfrage wurde noch nicht beantwortet");
            return new PlanningAlert("info", "Antwort ausstehend",
                    "Die Bürgerin bzw. der Bürger wartet auf eine Antwort der Verwaltung – "
                            + "bearbeiten Sie diesen Vorgang. " + priority + rankSuffix,
                    reasons);
        }
        return new PlanningAlert("success", "Als Nächstes bearbeiten",
                "Dieser Fall ist vollständig bearbeitbar. " + priority + rankSuffix,
                view.reasons());
    }

    /**
     * Vorgang aus einer offenen Bürger-Anfrage: phaseData trägt die Quelle
     * (sourceEmailId), es gibt noch keine explizite Arbeitsaufnahme (workState)
     * und mindestens eine zugeordnete E-Mail ist noch offen (NEW/IN_PROGRESS).
     * In diesem Zustand schuldet die Verwaltung die Bearbeitung/Antwort —
     * es fehlen keine Bürger-Unterlagen.
     */
    private boolean emailRequestPending(WorkspaceEntity ws) {
        try {
            if (ws.getPhaseData() == null || ws.getPhaseData().isBlank()) {
                return false;
            }
            Map<String, Object> data = ws.getPhaseDataMap();
            if (data.get("sourceEmailId") == null) {
                return false;
            }
            Object raw = data.get("workState");
            if (raw instanceof Map<?, ?> m && m.get("state") != null) {
                return false;
            }
            List<IncomingEmailEntity> emails = incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(ws.getId()));
            return emails.stream().anyMatch(e -> e.getStatus() == IncomingEmailEntity.Status.NEW
                    || e.getStatus() == IncomingEmailEntity.Status.IN_PROGRESS);
        } catch (Exception e) {
            log.debug("Antwort-ausstehend-Prüfung für {} nicht möglich: {}", ws.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Aus einer E-Mail erstellter Vorgang, an dem noch nicht gearbeitet wurde:
     * phaseData trägt die Quelle (sourceEmailId), aber noch keinen workState
     * (kein Beginnen/Pausieren/Fortsetzen). Nur dann ist die Darstellung
     * "Neuer Vorgang – wartet auf Bearbeitung" wahr; sobald ein workState
     * existiert, gilt der Vorgang als begonnen.
     */
    private boolean freshEmailCreatedCase(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null || ws.getPhaseData() == null || ws.getPhaseData().isBlank()) {
                return false;
            }
            Map<String, Object> data = ws.getPhaseDataMap();
            if (data.get("sourceEmailId") == null) {
                return false;
            }
            Object raw = data.get("workState");
            return !(raw instanceof Map<?, ?> m && m.get("state") != null);
        } catch (Exception e) {
            log.debug("Zustands-Prüfung (E-Mail-erstellt/ungebegonnen) für {} nicht möglich: {}", caseId, e.getMessage());
            return false;
        }
    }

    // ── Fakten-Sammlung ──

    private CaseFacts gatherFacts(WorkspaceEntity ws) {
        String caseId = ws.getId();
        Map<String, Object> phaseData = ws.getPhaseDataMap();
        String category = categoryOf(ws, phaseData);
        boolean ingestionResolved = Boolean.TRUE.equals(phaseData.get("ingestionResolved"));
        String analysisStatus = analysisMarkerStatus(phaseData);

        DocCounts counts = docStatusCounts(caseId);
        int ready = counts.ready(), processing = counts.processing(), failed = counts.failed(), total = counts.total();

        // Analyse: persistierter Lauf NUR wiederverwenden, wenn die
        // Dokumentengrundlage unverändert ist (dieselbe Beleg-Identitäts-
        // Prüfung wie die Entscheidungs-Seite).
        Integer analysisVersion = null;
        Boolean grounded = null;
        Integer evidenceCount = null;
        List<String> missingDocs = List.of();
        boolean analysisStale = false;
        try {
            var run = workspaceService.latestCompletedAnalysisRun(caseId).orElse(null);
            if (run != null) {
                Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
                if (result != null) {
                    analysisVersion = run.getVersion();
                    grounded = result.get("grounded") instanceof Boolean b ? b : null;
                    Object ev = result.get("evidenceItems");
                    evidenceCount = ev instanceof List<?> list ? list.size() : null;
                    Object missing = result.get("missingDocs");
                    missingDocs = missing instanceof List<?> list
                            ? list.stream().map(String::valueOf).toList() : List.of();
                    List<String> current = workspaceService.getWorkspaceDocuments(caseId).stream()
                            .map(WorkspaceDocumentLinkEntity::getDocumentUuid)
                            .filter(Objects::nonNull)
                            .map(UUID::toString)
                            .sorted()
                            .toList();
                    List<String> stored = workspaceService.deserializeEvidenceIds(run);
                    if (stored != null) {
                        List<String> storedIds = stored.stream()
                                .map(s -> s.contains("@") ? s.substring(0, s.indexOf('@')) : s)
                                .sorted()
                                .toList();
                        analysisStale = !storedIds.equals(current);
                    } else {
                        analysisStale = run.getDocumentCount() != current.size();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Analyse-Status für Planung von {} nicht lesbar: {}", caseId, e.getMessage());
        }

        // Wartezeit: älteste offene Bürger-E-Mail, sonst Alter des Falls.
        // beingWorked: NUR der explizite workState (ACTIVE) definiert aktive
        // Bearbeitung — eine IN_PROGRESS-E-Mail ist nur ein Hinweis.
        long waitingDays = 0;
        String waitingReason = "";
        boolean beingWorked = "ACTIVE".equals(workStateOf(phaseData));
        try {
            List<IncomingEmailEntity> emails = incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId));
            Instant oldest = emails.stream()
                    .filter(e -> e.getStatus() == IncomingEmailEntity.Status.NEW
                            || e.getStatus() == IncomingEmailEntity.Status.IN_PROGRESS)
                    .map(IncomingEmailEntity::getReceivedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (oldest != null) {
                waitingDays = Math.max(0, ChronoUnit.DAYS.between(
                        oldest.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()));
                waitingReason = "Bürger wartet seit " + waitingDays + " Tag" + (waitingDays == 1 ? "" : "en")
                        + " (E-Mail vom " + DateTimeFormatter.ofPattern("dd.MM.yyyy")
                                .format(oldest.atZone(ZoneId.systemDefault())) + ")";
            } else if (ws.getCreatedAt() != null) {
                waitingDays = Math.max(0, ChronoUnit.DAYS.between(
                        ws.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()));
                waitingReason = "Fall liegt seit " + waitingDays + " Tag" + (waitingDays == 1 ? "" : "en") + " vor";
            }
        } catch (Exception e) {
            log.debug("Wartezeit für Planung von {} nicht lesbar: {}", caseId, e.getMessage());
        }

        // Nächste Frist: frühestes DEADLINE-Ereignis der Timeline.
        Integer deadlineDays = null;
        String deadlineDateLabel = "";
        try {
            LocalDate earliest = workspaceService.getTimeline(caseId).stream()
                    .filter(ev -> ev.getEventType() == reasoning.workspace.model.TimelineEventType.DEADLINE)
                    .map(TimelineEventEntity::getEventDate)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (earliest != null) {
                deadlineDays = (int) ChronoUnit.DAYS.between(LocalDate.now(), earliest);
                deadlineDateLabel = DEADLINE_FMT.format(earliest);
            }
        } catch (Exception e) {
            log.debug("Fristen für Planung von {} nicht lesbar: {}", caseId, e.getMessage());
        }

        Object geoPriority = phaseData.get("geoPriority");
        String statedUrgency = geoPriority != null ? String.valueOf(geoPriority) : "";
        String waitingOnType = waitingOnTypeOf(phaseData);

        return new CaseFacts(caseId, ws.getName(), ws.getDescription(), category,
                ws.getStatus(), ws.getPhase(), phaseLabel(ws.getPhase()), ws.getCreatedAt(),
                ready, processing, failed, total, ingestionResolved,
                analysisStatus, analysisVersion, analysisStale, grounded, evidenceCount,
                missingDocs, waitingDays, waitingReason, deadlineDays, deadlineDateLabel, statedUrgency,
                beingWorked, waitingOnType);
    }

    /** Strukturierte Fall-Fakten eines Falls (für NextBestWork/Werkzeuge). */
    public CaseFacts factsFor(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            return ws != null ? gatherFacts(ws) : null;
        } catch (Exception e) {
            log.debug("Fakten für Fall {} nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    /** Dokument-Statuszählung eines Falls (gemeinsame Quelle für Fakten und Wartehinweis-Bereinigung). */
    private DocCounts docStatusCounts(String caseId) {
        int ready = 0, processing = 0, failed = 0, total = 0;
        for (WorkspaceDocumentLinkEntity link : workspaceService.getWorkspaceDocuments(caseId)) {
            total++;
            try {
                DocumentStatus status = documentFacade
                        .getDocument(link.getDocumentUuid(), "system").status();
                switch (status) {
                    case READY -> ready++;
                    case INGESTION_PENDING, INGESTING, DRAFT -> processing++;
                    case FAILED, DELETED -> failed++;
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.debug("Dokumentstatus für Planung von {} nicht lesbar: {}", link.getDocumentId(), e.getMessage());
            }
        }
        return new DocCounts(ready, processing, failed, total);
    }

    private record DocCounts(int ready, int processing, int failed, int total) {}

    @SuppressWarnings("unchecked")
    private static String workStateOf(Map<String, Object> phaseData) {
        Object raw = phaseData.get("workState");
        return raw instanceof Map<?, ?> m && m.get("state") != null
                ? String.valueOf(m.get("state")) : null;
    }

    @SuppressWarnings("unchecked")
    private static String waitingOnTypeOf(Map<String, Object> phaseData) {
        Object raw = phaseData.get("waitingOn");
        return raw instanceof Map<?, ?> m && m.get("type") != null
                ? String.valueOf(m.get("type")) : null;
    }

    private static boolean isOpen(WorkspaceStatus status) {
        return status == WorkspaceStatus.DRAFT || status == WorkspaceStatus.ACTIVE;
    }

    private String ownerOf(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            return ws != null ? ws.getOwnerId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer rankInPersonalQueue(String caseId, String ownerEmail) {
        List<CasePlanningView> queue = personalPlanning(ownerEmail).workable();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).caseId().equals(caseId)) {
                return i + 1;
            }
        }
        return null;
    }

    public static String categoryOf(WorkspaceEntity ws, Map<String, Object> phaseData) {
        Object stored = phaseData.get("caseCategory");
        if (stored != null && !String.valueOf(stored).isBlank()) {
            return String.valueOf(stored);
        }
        if ("GEO".equalsIgnoreCase(ws.getWorkspaceType())) {
            return "Geovorgang";
        }
        if (ws.getWorkspaceType() != null && !ws.getWorkspaceType().isBlank()) {
            return ws.getWorkspaceType();
        }
        return "Allgemein";
    }

    @SuppressWarnings("unchecked")
    private static String analysisMarkerStatus(Map<String, Object> phaseData) {
        Object marker = phaseData.get("analysis");
        if (marker instanceof Map<?, ?> m && m.get("status") != null) {
            return String.valueOf(m.get("status"));
        }
        return "";
    }

    private static String phaseLabel(WorkspacePhase phase) {
        if (phase == null) {
            return "Einrichtung";
        }
        return switch (phase) {
            case SETUP -> "Einrichtung";
            case INGESTION -> "Ingestion";
            case ANALYSIS -> "Analyse";
            case REVIEW -> "Überprüfung";
            case COMPLETE -> "Abschluss";
        };
    }

    private static String shortBlockedHeadline(String blockedReason) {
        if (blockedReason == null) {
            return "Derzeit blockiert";
        }
        String lower = blockedReason.toLowerCase();
        if (lower.contains("unterlagen")) {
            return "Warte noch auf Unterlagen";
        }
        if (lower.contains("dokumentverarbeitung")) {
            return "Dokumente werden verarbeitet";
        }
        if (lower.contains("analyse läuft")) {
            return "Analyse läuft noch";
        }
        return "Derzeit blockiert";
    }

    // ── Views ──

    private CasePlanningView toView(CasePlanningEntity entity) {
        List<String> reasons = List.of();
        try {
            Map<String, Object> factors = mapper.readValue(
                    entity.getFactorsJson() == null ? "{}" : entity.getFactorsJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            Object rawReasons = factors.get("reasons");
            if (rawReasons instanceof List<?> list) {
                reasons = list.stream().map(String::valueOf).toList();
            }
        } catch (Exception e) {
            log.debug("Faktoren von Planungsstand {} nicht lesbar: {}", entity.getCaseId(), e.getMessage());
        }
        return new CasePlanningView(
                entity.getCaseId().toString(),
                entity.getPriorityScore(),
                labelOf(entity.getPriorityClass()),
                entity.getPriorityReason() != null ? entity.getPriorityReason() : "",
                entity.isWorkable(),
                entity.getBlockedReason(),
                entity.getWorkabilityState(),
                entity.getBasisAnalysisVersion(),
                analysisStaleFrom(entity),
                reasons,
                null,
                entity.getCalculatedAt());
    }

    private static boolean analysisStaleFrom(CasePlanningEntity entity) {
        try {
            Map<String, Object> factors = new ObjectMapper().readValue(
                    entity.getFactorsJson() == null ? "{}" : entity.getFactorsJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return Boolean.TRUE.equals(factors.get("analysisStale"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String labelOf(String priorityClass) {
        if (priorityClass == null) {
            return "—";
        }
        try {
            return PriorityCalculationService.PriorityClass.valueOf(priorityClass).label();
        } catch (IllegalArgumentException e) {
            return priorityClass;
        }
    }

    private static CasePlanningView withRank(CasePlanningView view, int rank) {
        return new CasePlanningView(view.caseId(), view.priorityScore(), view.priorityClassLabel(),
                view.priorityReason(), view.workable(), view.blockedReason(), view.workabilityState(),
                view.analysisVersion(), view.analysisStale(), view.reasons(), rank, view.calculatedAt());
    }

    private static Comparator<CasePlanningView> byScoreDesc() {
        return Comparator.comparingInt(CasePlanningView::priorityScore).reversed()
                .thenComparing(CasePlanningView::caseId);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ── Ergebnis-Datenträger ──

    /** Planungsstand eines Falls für die Anzeige (Klassen-Label statt Rohwert). */
    public record CasePlanningView(String caseId, int priorityScore, String priorityClassLabel,
                                   String priorityReason, boolean workable, String blockedReason,
                                   String workabilityState, Integer analysisVersion, boolean analysisStale,
                                   List<String> reasons, Integer rank, Instant calculatedAt) {}

    /** Persönliche Planung: bearbeitbare Kandidaten (mit Rang) + persönlicher Rückstand. */
    public record PersonalPlanning(List<CasePlanningView> workable, List<CasePlanningView> backlog) {}

    /** Nicht-modale Planungs-Meldung für die Fallseite. */
    public record PlanningAlert(String variant, String headline, String detail, List<String> reasons) {}
}
