package verwaltungsassistent.web.planning;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspacePhase;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.planning.CasePlanningService.CasePlanningView;
import verwaltungsassistent.web.planning.CasePlanningService.PersonalPlanning;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Next-Best-Work-Engine (Phase 2B): bestimmt für EINE Mitarbeiterin deterministisch
 * den sinnvollsten nächsten Vorgang.
 *
 * <pre>
 * persönlich bearbeitbar ∪ persönlicher Rückstand ∪ global bearbeitbar
 *        ↓  harte Eligibility (inkl. Zugriff, aktiv von anderer Person)
 *        ↓  Signale + Restaufwand (EffortEstimator, Phase-2A-Statistik)
 *        ↓  Kapazität (nur AKTIVE Fälle zählen) — weich
 *        ↓  NextBestWorkScoringPolicy (isoliert, benannt, MVP-Koeffizienten)
 *        ↓  Reihung (kritische Frist-Bande → Score → heute abschließbar)
 *        ↓  RecommendationResult + Entscheidungs-Trace + deutsche Gründe
 * </pre>
 *
 * <p>Empfehlungen sind abgeleitete, nie persistierte Ergebnisse (kein
 * next_best_case/priority_today). Kein LLM, keine Schlüsselwort-Logik, keine
 * Mitarbeiter-Leistungswerte. Die LLM-Rolle bleibt auf strukturierte Fakten
 * beschränkt; diese Engine entscheidet deterministisch.</p>
 */
@Service
public class NextBestWorkService {

    private final CasePlanningService casePlanningService;
    private final CaseWorkStateService caseWorkStateService;
    private final EffortEstimator effortEstimator;
    private final NextBestWorkScoringPolicy policy;
    private final WorkspaceService workspaceService;

    public NextBestWorkService(CasePlanningService casePlanningService,
                               CaseWorkStateService caseWorkStateService,
                               EffortEstimator effortEstimator,
                               NextBestWorkScoringPolicy policy,
                               WorkspaceService workspaceService) {
        this.casePlanningService = casePlanningService;
        this.caseWorkStateService = caseWorkStateService;
        this.effortEstimator = effortEstimator;
        this.policy = policy;
        this.workspaceService = workspaceService;
    }

    private static final Set<String> EXCLUDED_STATES = Set.of(
            "WAITING_FOR_DOCUMENTS", "WAITING_FOR_CITIZEN", "WAITING_FOR_EXTERNAL",
            "WAITING_FOR_OTHER", "DOCUMENT_PROCESSING", "ANALYSIS_RUNNING",
            "NO_INFORMATION", "COMPLETED");

    // ── Ergebnis-Datenträger ──

    /**
     * Mitarbeiterfreundliches Empfehlungs-Level: die interne Reihung (Rank)
     * wird für die Mitarbeiterin in eine verständliche Stufe übersetzt —
     * "Sehr hohe Empfehlung" statt "Rank 1 / Score 87".
     */
    public static String recommendationLevelLabel(int rank) {
        return switch (rank) {
            case 1 -> "Sehr hohe Empfehlung";
            case 2 -> "Hohe Empfehlung";
            case 3 -> "Mittlere Empfehlung";
            default -> "Geringe Empfehlung";
        };
    }

    /**
     * Farb-Variante des Empfehlungs-Levels (nur Präsentation): semantische
     * Abstufung statt generischem Grün — sehr hoch = stärkste positive
     * Behandlung, hoch = abgeschwächtes Grün, mittel = neutrales Blau,
     * gering = muted. Schwache Empfehlung ist bewusst KEIN Rot.
     */
    public static String recommendationLevelBadgeVariant(int rank) {
        return switch (rank) {
            case 1 -> "recommend-very-high";
            case 2 -> "recommend-high";
            case 3 -> "recommend-medium";
            default -> "recommend-low";
        };
    }

    /** Farb-Variante des Prioritäts-Labels für Anzeige-Badges (nur Präsentation —
     *  Klassennamen identisch zu {@code PriorityCalculationService.variant}). */
    public static String priorityBadgeVariant(String priorityClassLabel) {
        if (priorityClassLabel == null) {
            return "neutral";
        }
        return switch (priorityClassLabel) {
            case "Sehr hoch" -> "priority-critical";
            case "Hoch" -> "priority-high";
            case "Mittel" -> "priority-medium";
            case "Niedrig" -> "priority-low";
            default -> "neutral";
        };
    }

    /** Empfehlungsergebnis für eine Mitarbeiterin (abgeleitet, nie persistiert). */
    public record RecommendationResult(String employeeEmail,
                                       Recommendation recommended,
                                       List<Recommendation> rankedCandidates,
                                       List<ExcludedCandidate> excludedCandidates,
                                       List<TraceEntry> trace) {}

    /** Eine Empfehlung mit deutscher Begründung (kein Score, keine Scheinkonfidenz). */
    public record Recommendation(String caseId, String caseName, int rank, String headline,
                                 String priorityClassLabel, List<String> reasons,
                                 List<String> tradeOffs, String workabilityState,
                                 Integer remainingEffortMinutes, boolean canFinishToday,
                                 Instant calculatedAt) {}

    /** Ausgeschlossener Kandidat mit internem Ausschlussgrund. */
    public record ExcludedCandidate(String caseId, String caseName, String exclusionReason) {}

    /** Entscheidungs-Trace (Debug/Admin; die Mitarbeiterin sieht ihn nicht). */
    public record TraceEntry(String caseId, boolean eligible, int priority, Integer deadlineDays,
                             long waitingDays, int remainingEffort, String continuity,
                             String contextSwitch, String completionValue, String capacity,
                             double score, Integer rank, String note) {}

    // ── Einstieg ──

    public RecommendationResult recommendFor(AuthenticatedUser user) {
        if (user == null || user.email() == null) {
            return new RecommendationResult(null, null, List.of(), List.of(), List.of());
        }
        // Das Leitungs-Konto gehört nicht zur Mitarbeiter-Universum: keine
        // persönliche Arbeitsliste, kein Kandidat, keine Empfehlung — die
        // Leitung beobachtet, sie bearbeitet nicht (Phase 2C.5).
        if (verwaltungsassistent.web.security.CaseAccessGuard.isSupervisory(user)) {
            return new RecommendationResult(user.email(), null, List.of(), List.of(), List.of());
        }
        String employeeEmail = user.email();
        Instant calculatedAt = Instant.now();
        List<ExcludedCandidate> excluded = new ArrayList<>();
        List<TraceEntry> trace = new ArrayList<>();

        // 1. Kandidaten-Pools (persönlich bearbeitbar ∪ persönlicher Rückstand ∪ global bearbeitbar).
        CasePlanningService.PersonalPlanning personal = casePlanningService.personalPlanning(employeeEmail);
        List<CasePlanningView> pool = casePlanningService.globalWorkPool();
        Map<String, CasePlanningView> candidates = new LinkedHashMap<>();
        for (CasePlanningView v : personal.workable()) {
            candidates.putIfAbsent(v.caseId(), v);
        }
        for (CasePlanningView v : pool) {
            candidates.putIfAbsent(v.caseId(), v);
        }

        // 2. Signale + Restaufwand je Kandidat (erster Durchlauf; Kontext-Signale folgen).
        Map<String, EvaluatedCandidate> evaluated = new LinkedHashMap<>();
        for (CasePlanningView view : candidates.values()) {
            TraceEntry excludedTrace = excludeIfIneligible(view, employeeEmail, user, excluded);
            if (excludedTrace != null) {
                trace.add(excludedTrace);
                continue;
            }
            WorkspaceEntity ws = workspaceService.findById(view.caseId()).orElse(null);
            CaseFacts facts = casePlanningService.factsFor(view.caseId());
            if (ws == null || facts == null) {
                excluded.add(new ExcludedCandidate(view.caseId(), caseNameOf(view.caseId()), "Fall nicht mehr verfügbar."));
                trace.add(new TraceEntry(view.caseId(), false, view.priorityScore(), null, 0, 0,
                        "—", "—", "—", "—", 0, null, "Fall nicht mehr verfügbar"));
                continue;
            }
            Map<String, Object> work = caseWorkStateService.currentState(view.caseId());
            Map<?, ?> workState = mapOf(work.get("workState"));
            String worker = strOf(workState.get("employee"));
            String state = strOf(workState.get("state"));
            boolean beingWorkedByMe = "ACTIVE".equals(state) && employeeEmail.equalsIgnoreCase(worker);
            int accumulated = intOf(workState.get("accumulatedMinutes"));

            int remaining = effortEstimator.remainingEffortMinutes(facts.category(), facts.phase(),
                    accumulated, Boolean.TRUE.equals(facts.grounded()),
                    facts.evidenceCount() != null ? facts.evidenceCount() : 0,
                    facts.missingDocs() != null ? facts.missingDocs().size() : 0);

            boolean nearComplete = facts.phase() == WorkspacePhase.REVIEW
                    || facts.phase() == WorkspacePhase.COMPLETE;
            boolean canFinishNow = view.workable()
                    && (facts.phase() == WorkspacePhase.REVIEW || facts.phase() == WorkspacePhase.COMPLETE)
                    && Boolean.TRUE.equals(facts.grounded())
                    && (facts.missingDocs() == null || facts.missingDocs().isEmpty());
            boolean startedByMe = beingWorkedByMe || employeeEmail.equalsIgnoreCase(ws.getOwnerId())
                    || (worker != null && employeeEmail.equalsIgnoreCase(worker));

            evaluated.put(view.caseId(), new EvaluatedCandidate(view, ws, facts, remaining,
                    beingWorkedByMe, startedByMe, canFinishNow, nearComplete,
                    "ACTIVE".equals(state) && !beingWorkedByMe ? worker : null));
        }

        // 3. Arbeitskontext: AKTIVE Fälle der Mitarbeiterin (nur echte aktive Arbeit zählt).
        Set<String> activeCategories = new LinkedHashSet<>();
        int workload = 0;
        for (EvaluatedCandidate c : evaluated.values()) {
            if (c.beingWorkedByMe()) {
                workload += c.remainingEffort();
                activeCategories.add(c.facts().category());
            }
        }

        // 4. Bewertung (zweiter Durchlauf: Kontext-Signale + Politik).
        List<RankedCandidate> ranked = new ArrayList<>();
        for (EvaluatedCandidate c : evaluated.values()) {
            NextBestWorkScoringPolicy.CandidateSignals signals = signalsOf(c, employeeEmail, activeCategories);
            NextBestWorkScoringPolicy.PolicyResult result = policy.evaluate(
                    c.view().priorityScore(), signals, workload);
            ranked.add(new RankedCandidate(c, signals, result));
        }
        ranked.sort(comparator());

        // 5. Ränge, Empfehlung, Erklärungen, Trace.
        List<Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            RankedCandidate c = ranked.get(i);
            int rank = i + 1;
            Recommendation rec = toRecommendation(c, rank, calculatedAt);
            recommendations.add(rec);
            trace.add(toTrace(c, rank));
        }
        Recommendation recommended = recommendations.isEmpty() ? null : recommendations.get(0);
        RecommendationResult result = new RecommendationResult(employeeEmail, recommended,
                recommendations, excluded, trace);
        // Erklärungsebene (Phase 2B.7): Blockierte eigene Fälle sind NIE
        // Kandidaten, können aber erklären, warum ein anderer Fall empfohlen
        // wurde ("Höhere Priorität, aber derzeit blockiert …").
        return withTradeOffs(result, blockedExplanationLines(personal, ranked));
    }

    /**
     * Erklärende Alternativen aus dem persönlichen Rückstand: blockierte eigene
     * Fälle mit mindestens der Priorität der Empfehlung. Sie bleiben VOLLSTÄNDIG
     * vom Ranking ausgeschlossen — sie erscheinen ausschließlich in der
     * "Warum nicht?"-Erklärung.
     */
    private List<String> blockedExplanationLines(CasePlanningService.PersonalPlanning personal,
                                                 List<RankedCandidate> ranked) {
        List<String> lines = new ArrayList<>();
        if (ranked.isEmpty()) {
            return lines;
        }
        int topPriority = ranked.get(0).view().priorityScore();
        for (CasePlanningView view : personal.backlog()) {
            if (lines.size() >= 2) {
                break;
            }
            if (view.workabilityState() == null || view.priorityScore() < topPriority) {
                continue;
            }
            String reason = view.blockedReason() != null && !view.blockedReason().isBlank()
                    ? view.blockedReason() : stateReason(view.workabilityState());
            lines.add(caseNameOf(view.caseId()) + ": höhere Priorität, aber derzeit blockiert (" + reason + ")");
        }
        return lines;
    }

    // ── Eligibility ──

    /** Liefert einen Trace-Eintrag, wenn der Kandidat hart ausgeschlossen ist; sonst null. */
    private TraceEntry excludeIfIneligible(CasePlanningView view, String employeeEmail,
                                           AuthenticatedUser user, List<ExcludedCandidate> excluded) {
        String reason = null;
        if (view.workabilityState() == null || !view.workable()
                || EXCLUDED_STATES.contains(view.workabilityState())) {
            reason = stateReason(view.workabilityState());
        } else if (!canAccess(view.caseId(), user)) {
            reason = "Kein Zugriff auf diesen Fall.";
        } else {
            Map<String, Object> work = caseWorkStateService.currentState(view.caseId());
            Map<?, ?> workState = mapOf(work.get("workState"));
            String worker = strOf(workState.get("employee"));
            if ("ACTIVE".equals(strOf(workState.get("state")))
                    && worker != null && !employeeEmail.equalsIgnoreCase(worker)) {
                reason = "Wird derzeit von " + worker + " bearbeitet.";
            }
        }
        if (reason == null) {
            return null;
        }
        excluded.add(new ExcludedCandidate(view.caseId(), caseNameOf(view.caseId()), reason));
        return new TraceEntry(view.caseId(), false, view.priorityScore(), null, 0, 0,
                "—", "—", "—", "—", 0, null, reason);
    }

    private String caseNameOf(String caseId) {
        return workspaceService.findById(caseId)
                .map(WorkspaceEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(caseId);
    }

    private boolean canAccess(String caseId, AuthenticatedUser user) {
        if (user != null && user.roles() != null && user.roles().contains("ADMIN")) {
            return true;
        }
        WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
        if (ws == null || user == null || user.email() == null) {
            return false;
        }
        // Allgemeiner Arbeitspool: unzugewiesene Vorgänge sind für jede
        // Mitarbeiterin Kandidaten — die Zuordnung bleibt eine EXPLIZITE
        // Übernahme (Recommendation setzt niemals automatisch um).
        if (verwaltungsassistent.web.service.DemoDataService.isGeneralPoolOwner(ws.getOwnerId())) {
            return true;
        }
        return user.email().equalsIgnoreCase(ws.getOwnerId());
    }

    private static String stateReason(String state) {
        if (state == null) {
            return "Fall abgeschlossen bzw. archiviert.";
        }
        return switch (state) {
            case "WAITING_FOR_DOCUMENTS" -> "Wartet auf Unterlagen.";
            case "WAITING_FOR_CITIZEN" -> "Wartet auf Rückmeldung der Bürgerin bzw. des Bürgers.";
            case "WAITING_FOR_EXTERNAL" -> "Wartet auf Rückmeldung einer anderen Behörde.";
            case "WAITING_FOR_OTHER" -> "Pausiert (Sonstiges).";
            case "DOCUMENT_PROCESSING" -> "Dokumentverarbeitung läuft noch.";
            case "ANALYSIS_RUNNING" -> "Die Analyse läuft noch.";
            case "NO_INFORMATION" -> "Keine Informationen zum Vorgang vorhanden.";
            case "COMPLETED" -> "Fall abgeschlossen bzw. archiviert.";
            default -> "Derzeit nicht bearbeitbar.";
        };
    }

    // ── Signale ──

    private NextBestWorkScoringPolicy.CandidateSignals signalsOf(EvaluatedCandidate c,
                                                                 String employeeEmail,
                                                                 Set<String> activeCategories) {
        boolean hasActiveWork = !activeCategories.isEmpty();
        boolean sameDomainAsActive = hasActiveWork && activeCategories.contains(c.facts().category());
        boolean switchingFromActive = hasActiveWork && !c.beingWorkedByMe();
        boolean neverTouched = !c.startedByMe();
        Integer deadlineDays = c.facts().deadlineDays();
        return new NextBestWorkScoringPolicy.CandidateSignals(
                c.remainingEffort(), c.canFinishNow(), c.nearComplete(),
                c.facts().waitingDays() > 0, c.facts().phase() != null,
                c.startedByMe(), c.beingWorkedByMe(),
                hasActiveWork && !sameDomainAsActive, neverTouched, switchingFromActive,
                deadlineDays != null && deadlineDays <= 0);
    }

    private Comparator<RankedCandidate> comparator() {
        return Comparator
                .comparing((RankedCandidate c) -> c.signals().criticalDeadline() ? 0 : 1)
                .thenComparing(c -> c.result().score(), Comparator.reverseOrder())
                .thenComparing(c -> c.result().canFinishToday(), Comparator.reverseOrder())
                .thenComparing(c -> c.view().caseId());
    }

    // ── Erklärungen ──

    private Recommendation toRecommendation(RankedCandidate c, int rank, Instant calculatedAt) {
        CasePlanningView view = c.view();
        CaseFacts facts = c.facts();
        NextBestWorkScoringPolicy.PolicyResult result = c.result();
        List<String> reasons = new ArrayList<>();

        if ("Hoch".equals(view.priorityClassLabel()) || "Sehr hoch".equals(view.priorityClassLabel())) {
            reasons.add("Hohe Priorität");
        }
        if (facts.deadlineDays() != null) {
            if (facts.deadlineDays() <= 0) {
                reasons.add(facts.deadlineDays() == 0 ? "Frist heute" : "Frist überfällig");
            } else {
                reasons.add("Frist in " + facts.deadlineDays() + " Tagen");
            }
        }
        if (facts.waitingDays() > 0) {
            reasons.add("Bürger wartet seit " + facts.waitingDays() + " Tagen");
        }
        if ("RESPONSE_PENDING".equals(view.workabilityState())) {
            // E-Mail-Vorgang ohne Bürger-Unterlagen-Blockade: die Verwaltung
            // schuldet die Antwort — niemals wird ein Versand behauptet.
            reasons.add("Antwort ausstehend – Bürgeranfrage noch nicht beantwortet");
        }
        if (view.workable()) {
            reasons.add("Vollständig bearbeitbar");
        }
        if (c.nearComplete()) {
            reasons.add("Kurz vor dem Abschluss");
        }
        if (c.beingWorkedByMe()) {
            reasons.add("Aktuell von Ihnen bearbeitet");
        } else if (c.startedByMe()) {
            reasons.add("Bereits von Ihnen begonnen");
        }
        reasons.add(result.canFinishToday()
                ? "Kann voraussichtlich heute abgeschlossen werden"
                : "Übersteigt voraussichtlich die heute verfügbare Restkapazität");

        return new Recommendation(view.caseId(), c.candidate().ws().getName() != null ? c.candidate().ws().getName() : view.caseId(), rank,
                "Nächster empfohlener Vorgang", view.priorityClassLabel(), reasons,
                List.of(), view.workabilityState(), c.remainingEffort(),
                result.canFinishToday(), calculatedAt);
    }

    /** "Warum nicht Vorgang X?" — aus den tatsächlich verwendeten Faktoren. */
    public RecommendationResult withTradeOffs(RecommendationResult result, List<String> blockedLines) {
        if (result.recommended() == null) {
            return result;
        }
        Recommendation top = result.recommended();
        List<Recommendation> alternatives = new ArrayList<>();
        for (int i = 1; i < Math.min(4, result.rankedCandidates().size()); i++) {
            alternatives.add(result.rankedCandidates().get(i));
        }
        List<String> tradeOffs = new ArrayList<>();
        for (Recommendation alt : alternatives) {
            tradeOffs.add("Bearbeitbar, aber " + alternativeDifference(top, alt));
        }
        for (ExcludedCandidate ex : result.excludedCandidates()) {
            if (tradeOffs.size() >= 3) {
                break;
            }
            if (ex.exclusionReason().contains("Unterlagen")
                    || ex.exclusionReason().contains("Rückmeldung")
                    || ex.exclusionReason().contains("Pausiert")) {
                tradeOffs.add("Höhere Priorität möglich, aber derzeit blockiert (" + ex.exclusionReason() + ")");
            }
        }
        for (String line : blockedLines) {
            if (tradeOffs.size() >= 3) {
                break;
            }
            tradeOffs.add(line);
        }
        Recommendation withTradeOffs = new Recommendation(top.caseId(), top.caseName(), top.rank(),
                top.headline(), top.priorityClassLabel(), top.reasons(), tradeOffs,
                top.workabilityState(), top.remainingEffortMinutes(), top.canFinishToday(),
                top.calculatedAt());
        List<Recommendation> ranked = new ArrayList<>(result.rankedCandidates());
        ranked.set(0, withTradeOffs);
        return new RecommendationResult(result.employeeEmail(), withTradeOffs, ranked,
                result.excludedCandidates(), result.trace());
    }

    private static String alternativeDifference(Recommendation top, Recommendation alt) {
        if (alt.remainingEffortMinutes() != null && top.remainingEffortMinutes() != null
                && alt.remainingEffortMinutes() > top.remainingEffortMinutes() * 1.5) {
            return "deutlich höherer Restaufwand (ca. " + alt.remainingEffortMinutes() + " Min.)";
        }
        if (top.reasons().stream().anyMatch(r -> r.contains("begonnen"))
                && !alt.reasons().stream().anyMatch(r -> r.contains("begonnen"))) {
            return "noch nicht von Ihnen begonnen";
        }
        if (alt.remainingEffortMinutes() != null && top.remainingEffortMinutes() != null
                && !alt.remainingEffortMinutes().equals(top.remainingEffortMinutes())) {
            return "höherer Restaufwand (ca. " + alt.remainingEffortMinutes() + " Min.)";
        }
        return "aktuell nicht die beste nächste Bearbeitung";
    }

    // ── Trace ──

    private TraceEntry toTrace(RankedCandidate c, int rank) {
        NextBestWorkScoringPolicy.PolicyResult r = c.result();
        return new TraceEntry(c.view().caseId(), true, c.view().priorityScore(),
                c.facts().deadlineDays(), c.facts().waitingDays(), c.remainingEffort(),
                c.beingWorkedByMe() ? "BEING_WORKED_BY_ME"
                        : c.startedByMe() ? "STARTED_BY_ME" : "NONE",
                r.contextPenalty() < 0.1 ? "LOW" : r.contextPenalty() < 0.25 ? "MEDIUM" : "HIGH",
                r.completionValue() >= 0.3 ? "HIGH" : r.completionValue() >= 0.15 ? "MEDIUM" : "LOW",
                r.canFinishToday() ? "SUFFICIENT" : "LIMITED",
                r.score(), rank, null);
    }

    // ── Interne Träger ──

    private record EvaluatedCandidate(CasePlanningView view, WorkspaceEntity ws, CaseFacts facts,
                                      int remainingEffort, boolean beingWorkedByMe,
                                      boolean startedByMe, boolean canFinishNow,
                                      boolean nearComplete, String activeWorker) {}

    private record RankedCandidate(EvaluatedCandidate candidate,
                                   NextBestWorkScoringPolicy.CandidateSignals signals,
                                   NextBestWorkScoringPolicy.PolicyResult result) {
        CasePlanningView view() {
            return candidate.view();
        }
        CaseFacts facts() {
            return candidate.facts();
        }
        int remainingEffort() {
            return candidate.remainingEffort();
        }
        boolean beingWorkedByMe() {
            return candidate.beingWorkedByMe();
        }
        boolean startedByMe() {
            return candidate.startedByMe();
        }
        boolean nearComplete() {
            return candidate.nearComplete();
        }
    }

    private static Map<?, ?> mapOf(Object raw) {
        return raw instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String strOf(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    private static int intOf(Object raw) {
        return raw instanceof Number n ? n.intValue() : 0;
    }
}
