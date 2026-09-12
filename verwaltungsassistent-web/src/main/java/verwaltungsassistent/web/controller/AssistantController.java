package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiConversationContext;
import verwaltungsassistent.web.demo.DemoAiConcurrencyGuard;
import org.springframework.beans.factory.ObjectProvider;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.RetrievalScope;
import reasoning.common.model.WorkspacePhase;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.search.model.SearchFilter;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.service.AssistantAnswerPdfExporter;
import verwaltungsassistent.web.service.DocumentViewerService;
import verwaltungsassistent.web.service.GeneralChatService;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.AssistantOutcome;
import verwaltungsassistent.web.service.JobProgressService.Job;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import reasoning.auth.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The interactive German Verwaltungsassistent assistant surface. Reuses the existing AI
 * pipeline (semantic intent -> deterministic rules -> retrieval ->
 * generation -> independent verification with fail-closed handling);
 * this controller only renders its results.
 *
 * <p>Requests run in a background worker; the browser polls
 * /assistant/progress/{jobId} for user-facing stage messages and receives
 * the final rendered answer when the pipeline finishes.</p>
 */
@Controller
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AiFacade aiFacade;
    private final DecisionRouter decisionRouter;
    private final JobProgressService progressService;
    private final DocumentViewerService documentViewerService;
    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final CaseAccessGuard caseAccessGuard;
    private final GeneralChatService generalChatService;
    private final AssistantAnswerPdfExporter assistantAnswerPdfExporter;
    private final ObjectProvider<DemoAiConcurrencyGuard> aiGuardProvider;
    private final ExecutorService executor;

    public AssistantController(AiFacade aiFacade,
                               DecisionRouter decisionRouter,
                               JobProgressService progressService,
                               DocumentViewerService documentViewerService,
                               WorkspaceService workspaceService,
                               DocumentFacade documentFacade,
                               CaseAccessGuard caseAccessGuard,
                               GeneralChatService generalChatService,
                               AssistantAnswerPdfExporter assistantAnswerPdfExporter,
                               ObjectProvider<DemoAiConcurrencyGuard> aiGuardProvider) {
        this.aiFacade = aiFacade;
        this.decisionRouter = decisionRouter;
        this.progressService = progressService;
        this.documentViewerService = documentViewerService;
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.caseAccessGuard = caseAccessGuard;
        this.generalChatService = generalChatService;
        this.assistantAnswerPdfExporter = assistantAnswerPdfExporter;
        this.aiGuardProvider = aiGuardProvider;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "assistant-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @GetMapping("/assistant")
    public String assistant(
            @RequestParam(required = false) String caseId,
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "tab", required = false) String tab,
            @AuthenticationPrincipal AuthenticatedUser user,
            Model model,
            jakarta.servlet.http.HttpSession session) {
        addChrome(model);
        model.addAttribute("answered", false);
        // Dashboard "Schnellabfrage" prefills the question; direct navigation
        // without the parameter keeps the textarea empty.
        model.addAttribute("question", question != null ? question : "");
        // "assistant" (municipal, default) or "general" (Allgemeine Fragen)
        model.addAttribute("assistantTab", "general".equals(tab) ? "general" : "assistant");
        model.addAttribute("generalTurns", generalTurns(session));
        addCaseContext(caseId, user, model);
        return "assistant/chat";
    }

    /**
     * „Allgemeine Fragen": direkter LLM-Dialog, bewusst ohne die kommunale
     * Pipeline (kein Retrieval, kein Grounding, kein Verifier, keine Belege).
     * Der Verlauf lebt in der HTTP-Session; es wird nichts persistiert.
     */
    @PostMapping("/assistant/general/ask")
    public String generalAsk(@RequestParam("question") String question,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             jakarta.servlet.http.HttpSession session,
                             Model model) {
        addChrome(model);
        model.addAttribute("assistantTab", "general");
        requireOperativeUser(user);
        String q = question != null ? question.trim() : "";
        List<GeneralChatService.ChatTurn> turns = generalTurns(session);
        if (q.isEmpty()) {
            model.addAttribute("generalError",
                    "Bitte geben Sie eine Frage ein.");
            model.addAttribute("generalTurns", turns);
            return "assistant/general-fragments :: conversation";
        }
        DemoAiConcurrencyGuard aiGuard = aiGuardProvider.getIfAvailable();
        String aiUserKey = user != null && user.email() != null && !user.email().isBlank()
                ? user.email() : "anonymous";
        if (aiGuard != null && !aiGuard.tryAcquire(aiUserKey)) {
            model.addAttribute("generalError", DemoAiConcurrencyGuard.CAPACITY_MESSAGE);
            model.addAttribute("aiCapacityRejected", true);
            model.addAttribute("generalTurns", turns);
            return "assistant/general-fragments :: conversation";
        }
        try {
            String answer = generalChatService.answer(turns, q);
            List<GeneralChatService.ChatTurn> updated = new ArrayList<>(turns);
            updated.add(new GeneralChatService.ChatTurn("user", q));
            updated.add(new GeneralChatService.ChatTurn("assistant", answer));
            if (updated.size() > GeneralChatService.MAX_HISTORY_TURNS * 2) {
                updated = new ArrayList<>(updated.subList(
                        updated.size() - GeneralChatService.MAX_HISTORY_TURNS * 2, updated.size()));
            }
            session.setAttribute("generalChatTurns", updated);
            model.addAttribute("generalTurns", updated);
        } catch (GeneralChatService.ChatUnavailableException e) {
            log.warn("General chat unavailable: {}", e.getMessage());
            model.addAttribute("generalError",
                    "Die Anfrage konnte momentan nicht beantwortet werden. Bitte versuchen Sie es erneut.");
            model.addAttribute("generalTurns", turns);
        } finally {
            if (aiGuard != null) {
                aiGuard.release(aiUserKey);
            }
        }
        return "assistant/general-fragments :: conversation";
    }

    /** Session-scoped conversation of the „Allgemeine Fragen" tab (never persisted). */
    @SuppressWarnings("unchecked")
    private static List<GeneralChatService.ChatTurn> generalTurns(jakarta.servlet.http.HttpSession session) {
        if (session == null) return List.of();
        Object stored = session.getAttribute("generalChatTurns");
        return stored instanceof List<?> list ? (List<GeneralChatService.ChatTurn>) list : List.of();
    }

    // ── Interaktive Pipeline-Visualisierung ───────────────────────────────

    /** One node of the interactive pipeline diagram (label, state, runtime info). */
    public record PipelineNode(String id, String label, String state, String info) {}

    /**
     * Baut die Pipeline-Knoten aus dem echten Laufzeit-Zustand des Jobs —
     * delegiert an die gemeinsame Pipeline-Visualisierung
     * ({@link verwaltungsassistent.web.service.PipelineDiagramSupport}),
     * die auch Entscheidungsanalyse und E-Mail-Analyse verwenden.
     */
    static List<PipelineNode> pipelineNodes(verwaltungsassistent.web.service.JobProgressService.Job job) {
        return verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job).stream()
                .map(n -> new PipelineNode(n.id(), n.label(), n.state(), n.info()))
                .toList();
    }

    /**
     * Der Assistent (beide Tabs) startet KI-Verarbeitung: das aufsichtliche
     * Leitungs-Konto (ADMIN) ist schreibgeschützt und darf keine KI-Aufträge
     * auslösen — serverseitig abgewiesen (die UI blendet die Eingaben für
     * Leitungs-Konten zusätzlich aus).
     */
    private void requireOperativeUser(AuthenticatedUser user) {
        if (CaseAccessGuard.isSupervisory(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — der Assistent steht nur der Sachbearbeitung zur Verfügung.");
        }
    }

    @PostMapping("/assistant/ask")
    public String ask(@RequestParam("question") String question,
                      @RequestParam(required = false) String caseId,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      @AuthenticationPrincipal AuthenticatedUser user,
                      Model model) {
        addChrome(model);
        requireOperativeUser(user);
        addCaseContext(caseId, user, model);
        model.addAttribute("question", question != null ? question : "");

        String q = question != null ? question.trim() : "";
        if (q.isEmpty()) {
            model.addAttribute("answered", false);
            model.addAttribute("askError", "Bitte geben Sie eine Frage ein.");
            return hxRequest != null ? "assistant/fragments :: answer" : "assistant/chat";
        }

        CaseScope scope = resolveCaseScope(caseId, user);
        Job progress = progressService.create(JobProgressService.Kind.ASSISTANT, q);
        model.addAttribute("answered", false);
        model.addAttribute("progressJobId", progress.jobId);
        model.addAttribute("progressMessages", progress.messages);
        model.addAttribute("progressState", progress.state);
        model.addAttribute("progressNodes", pipelineNodes(progress));
        String actorEmail = user != null ? user.email() : null;
        executor.submit(() -> process(progress.jobId, q, actorEmail, scope));

        if (hxRequest != null) {
            return "assistant/fragments :: progressPanel";
        }
        return "assistant/chat";
    }

    /**
     * Poll endpoint for the progress panel. Returns the panel fragment while
     * the request runs and the final answer fragment once it has completed.
     */
    @GetMapping("/assistant/progress/{jobId}")
    public String progress(@PathVariable String jobId, Model model) {
        addChrome(model);
        Job progress = progressService.get(jobId);
        if (progress == null) {
            // unknown/expired entry — NOT a pipeline failure
            model.addAttribute("answered", false);
            model.addAttribute("progressExpired",
                    "Die Anfrage ist nicht mehr verfügbar. Bitte stellen Sie die Frage erneut.");
            return "assistant/fragments :: progressPanel";
        }
        if ("ERROR".equals(progress.state)) {
            model.addAttribute("answered", false);
            model.addAttribute("progressError",
                    progress.errorMessage != null && !progress.errorMessage.isBlank()
                            ? progress.errorMessage
                            : "Die Anfrage konnte nicht vollständig verarbeitet werden.");
            // Kapazitäts-Ablehnung (DemoAiConcurrencyGuard): die UI zeigt den
            // Recovery-Watcher ("KI wieder verfügbar") — kein Auto-Retry.
            model.addAttribute("aiCapacityRejected",
                    progress.errorMessage != null
                            && DemoAiConcurrencyGuard.CAPACITY_MESSAGE.equals(progress.errorMessage));
            return "assistant/fragments :: progressPanel";
        }
        if ("DONE".equals(progress.state)) {
            // Die Frage wird mitgegeben, damit der Quellen-Dialog die passenden
            // Textstellen im Beleg-Auszug hervorheben kann; die jobId erlaubt
            // den PDF-Export der fertigen Antwort ("Antwort als PDF erstellen").
            model.addAttribute("question", progress.title != null ? progress.title : "");
            model.addAttribute("progressJobId", jobId);
            renderOutcome((AssistantOutcome) progress.outcome, progress.terminalMessage, model);
            return "assistant/fragments :: answer";
        }
        model.addAttribute("answered", false);
        model.addAttribute("progressJobId", progress.jobId);
        model.addAttribute("progressMessages", progress.messages);
        model.addAttribute("progressState", progress.state);
        model.addAttribute("progressNodes", pipelineNodes(progress));
        return "assistant/fragments :: progressPanel";
    }

    /**
     * Exports a completed assistant answer as PDF ("Antwort als PDF erstellen").
     * The answer is taken from the finished job — the pipeline is never re-run,
     * so no additional AI calls are triggered. Conceptually separate from the
     * formal case decision document (Entscheidungsvorlage).
     */
    /**
     * Poll target of the AI-capacity recovery watcher (rendered only next to
     * a capacity rejection). Answers 204 while the current user's recorded
     * rejection still waits for a free slot, 286 when no notice is pending
     * (htmx stops polling), and renders the green "KI wieder verfügbar"
     * notice exactly once when a slot is usable again. Purely advisory — the
     * rejected request is never queued and never automatically re-executed.
     */
    @GetMapping("/assistant/ai-capacity/notice")
    public String aiCapacityNotice(@AuthenticationPrincipal AuthenticatedUser user,
                                   HttpServletResponse response) {
        DemoAiConcurrencyGuard aiGuard = aiGuardProvider.getIfAvailable();
        if (aiGuard == null) {
            response.setStatus(286);
            return null;
        }
        String aiUserKey = user != null && user.email() != null && !user.email().isBlank()
                ? user.email() : "anonymous";
        if (!aiGuard.awaitingRecovery(aiUserKey)) {
            response.setStatus(286);
            return null;
        }
        if (!aiGuard.recoveryAvailable(aiUserKey)) {
            response.setStatus(204);
            return null;
        }
        aiGuard.markRecoveryDelivered(aiUserKey);
        return "fragments/components :: aiCapacityRecoveryNotice";
    }

    @GetMapping("/assistant/answer/export-pdf")
    public ResponseEntity<byte[]> exportAnswerPdf(@RequestParam("job") String jobId) {
        Job job = progressService.get(jobId);
        if (job == null || !(job.outcome instanceof AssistantOutcome outcome)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Die Antwort ist nicht mehr verfügbar. Bitte stellen Sie die Frage erneut.");
        }
        String question = job.title != null ? job.title : "";
        try {
            byte[] pdf = assistantAnswerPdfExporter.export(question, outcome, java.time.LocalDateTime.now());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/pdf"))
                    .header("Content-Disposition", "attachment; filename=\"assistentenantwort.pdf\"")
                    .body(pdf);
        } catch (java.io.IOException | RuntimeException e) {
            log.error("Assistant answer PDF export failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Die PDF-Erstellung ist fehlgeschlagen.");
        }
    }

    /** Runs the existing pipeline in the background and stores the rendered outcome. */
    private void process(String jobId, String question, String actorEmail, CaseScope scope) {
        try {
            var routing = decisionRouter.route(question);
            Object searchFilter = scope != null && !scope.documentIds().isEmpty()
                    ? new SearchFilter(scope.documentIds(), null, null, null, null, null, null, null, List.of())
                    : null;
            AiRequest request = new AiRequest(question, null, searchFilter,
                    new AiConversationContext(List.of(), actorEmail, null, null, jobId), 15,
                    RetrievalScope.CURRENT_WORKSPACE, scope != null ? scope.workspaceId() : null);
            AiResponse response = aiFacade.answer(request);
            String answerText = response.answer().answer();
            boolean failClosed = answerText != null
                    && answerText.contains("keine ausreichenden Informationen");
            Integer confidencePct = null;
            ConfidenceProfile conf = response.answer().confidence();
            if (conf != null) {
                confidencePct = Integer.valueOf(
                        String.format(Locale.GERMANY, "%.0f", conf.overallConfidence() * 100));
            }
            AssistantOutcome outcome = new AssistantOutcome(
                    answerText,
                    response.answer().grounded(),
                    strategyLabel(routing.strategy().name()),
                    response.answer().sourceCitations(),
                    response.answer().authorityReferences(),
                    confidencePct,
                    failClosed);
            String terminalMessage = failClosed
                    ? "Die Informationen in unserem System reichen für diese Frage derzeit nicht aus."
                    : "Die Prüfung wurde abgeschlossen.";
            progressService.complete(jobId, outcome, terminalMessage);
        } catch (verwaltungsassistent.web.demo.AiCapacityExceededException ce) {
            log.warn("Assistant request rejected – KI-Verarbeitung ausgelastet (job {}): {}", jobId, ce.getMessage());
            progressService.fail(jobId, ce.getMessage());
        } catch (Exception e) {
            log.error("Assistant request failed: {}", e.getMessage(), e);
            progressService.fail(jobId);
        }
    }

    /** Populates the model for the final answer fragment. */
    private void renderOutcome(AssistantOutcome outcome, String terminalMessage, Model model) {
        if (outcome == null) {
            model.addAttribute("answered", false);
            model.addAttribute("askError",
                    "Die Anfrage konnte nicht verarbeitet werden. Bitte versuchen Sie es erneut.");
            return;
        }
        model.addAttribute("answered", true);
        model.addAttribute("answerText", outcome.answerText());
        model.addAttribute("grounded", outcome.grounded());
        model.addAttribute("strategy", outcome.strategy());
        java.util.List<DocumentViewerService.SourceView> citations =
                documentViewerService.fromCitations(outcome.citations());
        // Regelbasierte Antworten (keine Retrieval-Zitate): die Rechtsgrundlagen
        // (strukturiertes Wissen, z. B. TV-L-Tabelle) werden als Beleg-Quellen
        // dargestellt, damit auch dieser Pfad einen Evidence Explorer hat.
        // Traceability: Wenn zur Wissensquelle (z. B. "TV-L Entgelttabellen
        // 2025") ein echtes Dokument im Bestand existiert, wird es angebunden —
        // die Quelle wird klickbar und öffnet das Dokument (bzw. den
        // Abschnitt, der den Auszug enthält). Ohne passendes Dokument bleibt
        // der Eintrag unverlinkt; es wird nie ein Dokument erfunden.
        if (citations.isEmpty() && outcome.authorities() != null && !outcome.authorities().isEmpty()) {
            citations = outcome.authorities().stream()
                    .filter(a -> a.entryTitle() != null && !a.entryTitle().isBlank())
                    .map(a -> {
                        UUID docId = documentViewerService.resolveDocumentIdByTitle(a.entryTitle());
                        return new DocumentViewerService.SourceView(
                                docId,
                                documentViewerService.resolveChunkByExcerpt(docId, a.excerpt()),
                                0,
                                a.entryTitle(),
                                null,
                                a.excerpt() != null && !a.excerpt().isBlank()
                                        ? a.excerpt()
                                        : (a.basis() != null && !a.basis().isBlank() ? a.basis() : a.entryTitle()),
                                a.relevanceScore() > 0 ? a.relevanceScore() : 0.9,
                                a.tier() != null ? a.tier().name() : "Primär",
                                "success", List.of("Strukturiertes Wissen"),
                                null, null, null, null, null);
                    })
                    .toList();
        }
        // Sachbearbeiter-Sicht: Belege nach Dokument gruppieren (eine Karte pro
        // Dokument, zusammengeführte Passage) statt mehrerer Chunk-Einträge.
        model.addAttribute("citations", documentViewerService.groupByDocument(citations));
        model.addAttribute("authorities", outcome.authorities());
        // Rechtsgrundlagen-Titel → hinterlegtes Dokument (nur echte Treffer;
        // ohne Dokument bleibt die Quelle unverlinkt).
        java.util.Map<String, String> authorityDocIds = new java.util.LinkedHashMap<>();
        if (outcome.authorities() != null) {
            for (var a : outcome.authorities()) {
                if (a.entryTitle() != null && !authorityDocIds.containsKey(a.entryTitle())) {
                    UUID docId = documentViewerService.resolveDocumentIdByTitle(a.entryTitle());
                    if (docId != null) {
                        authorityDocIds.put(a.entryTitle(), docId.toString());
                    }
                }
            }
        }
        model.addAttribute("authorityDocIds", authorityDocIds);
        model.addAttribute("confidencePct", outcome.confidencePct() != null
                ? String.valueOf(outcome.confidencePct()) : null);
        model.addAttribute("terminalMessage", terminalMessage);
    }

    /**
     * Resolves the caseId query parameter into visible case context. Invalid
     * or inaccessible cases never silently open a fake context — the user
     * gets an explicit notice and the assistant stays global.
     */
    private void addCaseContext(String caseId, AuthenticatedUser user, Model model) {
        if (caseId == null || caseId.isBlank()) {
            model.addAttribute("caseId", "");
            return;
        }
        model.addAttribute("caseId", caseId);
        try {
            WorkspaceEntity ws = caseAccessGuard.requireAccess(caseId, user);
            List<WorkspaceDocumentLinkEntity> links = workspaceService.getWorkspaceDocuments(caseId);
            model.addAttribute("caseContext", new CaseContextView(
                    ws.getId(), ws.getName(),
                    ws.getWorkspaceCode() != null ? ws.getWorkspaceCode() : "",
                    ws.getPhase() != null ? phaseLabel(ws.getPhase()) : "",
                    links.size(), documentTitles(links)));
        } catch (Exception e) {
            // 404/403 from the guard, malformed case IDs and storage errors all
            // fall back to the global assistant with an explicit notice.
            log.info("Assistant case context rejected for {}: {}", caseId, e.getMessage());
            model.addAttribute("caseError",
                    "Der Fall konnte nicht geladen werden. Der Assistent arbeitet ohne Fallbezug.");
            model.addAttribute("caseId", "");
        }
    }

    /** Resolves the caseId into a retrieval scope (case documents); null when absent/invalid. */
    private CaseScope resolveCaseScope(String caseId, AuthenticatedUser user) {
        if (caseId == null || caseId.isBlank()) return null;
        try {
            caseAccessGuard.requireAccess(caseId, user);
            Set<UUID> docIds = workspaceService.getWorkspaceDocuments(caseId).stream()
                    .map(WorkspaceDocumentLinkEntity::getDocumentUuid)
                    .collect(Collectors.toSet());
            return new CaseScope(UUID.fromString(caseId), docIds);
        } catch (Exception e) {
            log.info("Assistant request without case scope ({}): {}", caseId, e.getMessage());
            return null;
        }
    }

    private List<String> documentTitles(List<WorkspaceDocumentLinkEntity> links) {
        List<String> titles = new ArrayList<>(links.size());
        for (WorkspaceDocumentLinkEntity link : links) {
            String title = link.getDocumentName() != null && !link.getDocumentName().isBlank()
                    ? link.getDocumentName() : "Dokument";
            if (link.getDocumentUuid() != null) {
                try {
                    Document doc = documentFacade.getDocument(link.getDocumentUuid(), "system");
                    if (doc != null && doc.metadata() != null && doc.metadata().title() != null
                            && !doc.metadata().title().isBlank()) {
                        title = doc.metadata().title();
                    }
                } catch (Exception e) {
                    log.debug("Document title lookup failed for {}: {}", link.getDocumentUuid(), e.getMessage());
                }
            }
            titles.add(title);
        }
        return titles;
    }

    private static String phaseLabel(WorkspacePhase phase) {
        return switch (phase) {
            case SETUP -> "Anlage";
            case INGESTION -> "Dokumentenerfassung";
            case ANALYSIS -> "Analyse";
            case REVIEW -> "Prüfung";
            case COMPLETE -> "Abschluss";
        };
    }

    /** Immutable retrieval scope for one case, captured at request submission. */
    private record CaseScope(UUID workspaceId, Set<UUID> documentIds) {}

    /** Visible case context for the assistant page header. */
    public record CaseContextView(String id, String name, String code, String phaseLabel,
                                  int documentCount, List<String> documentTitles) {}

    private static String strategyLabel(String strategy) {
        if (strategy == null) return "Hybride Suche";
        return switch (strategy) {
            case "HYBRID_RETRIEVAL" -> "Hybride Suche";
            case "RULE_ENGINE" -> "Regelbasiert";
            case "GRAPH_REASONING" -> "Hybride Suche";
            case "INDEX_INSPECTION" -> "Index-Prüfung";
            default -> strategy;
        };
    }

    private void addChrome(Model model) {
        model.addAttribute("pageTitle", "Assistent");
        model.addAttribute("activeSection", "assistant");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Assistent", "/assistant")));
    }
}
