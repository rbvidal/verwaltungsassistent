package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.service.DocumentViewerService;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Corpus administration surface (AUDITOR/ADMIN — enforced server-side by
 * SecurityConfig). Exposes the REAL German evaluation corpora
 * (evaluation/benchmarks/*.json) with their metadata. Opening the page
 * performs NO evaluation; a single case can be evaluated on demand
 * (bounded, one pipeline run) via the case detail action.
 */
@Controller
public class CorpusController {

    private static final Logger log = LoggerFactory.getLogger(CorpusController.class);

    private final AiFacade aiFacade;
    private final DecisionRouter decisionRouter;
    private final JobProgressService progressService;
    private final SpringTemplateEngine templateEngine;
    private final DocumentViewerService documentViewerService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;

    public CorpusController(AiFacade aiFacade, DecisionRouter decisionRouter,
                            JobProgressService progressService,
                            SpringTemplateEngine templateEngine,
                            DocumentViewerService documentViewerService) {
        this.aiFacade = aiFacade;
        this.decisionRouter = decisionRouter;
        this.progressService = progressService;
        this.templateEngine = templateEngine;
        this.documentViewerService = documentViewerService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "evaluation-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @GetMapping("/corpus")
    public String corpus(Model model) {
        List<CorpusSummary> corpora = new ArrayList<>();
        int totalCases = 0;
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource r : resolver.getResources("classpath:evaluation/benchmarks/*.json")) {
                JsonNode cases = mapper.readTree(r.getInputStream());
                String name = r.getFilename() != null
                        ? r.getFilename().replace(".json", "") : "?";
                int easy = 0, medium = 0, hard = 0;
                for (JsonNode c : cases) {
                    String d = c.path("difficulty").asText("");
                    if ("EASY".equals(d)) easy++;
                    else if ("MEDIUM".equals(d)) medium++;
                    else if ("HARD".equals(d)) hard++;
                }
                corpora.add(new CorpusSummary(name, corpusLabel(name), cases.size(), easy, medium, hard));
                totalCases += cases.size();
            }
        } catch (IOException e) {
            log.warn("Could not load evaluation benchmarks: {}", e.getMessage());
        }
        model.addAttribute("corpora", corpora);
        model.addAttribute("totalCases", totalCases);
        model.addAttribute("pageTitle", "Beispiele");
        model.addAttribute("activeSection", "corpus");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Beispiele", "/corpus")));
        return "corpus/list";
    }

    @GetMapping("/corpus/{corpusName}")
    public String corpusDetail(@PathVariable String corpusName, Model model) {
        List<CorpusCase> cases = loadCases(corpusName);
        String label = corpusLabel(corpusName);
        model.addAttribute("corpusName", corpusName);
        model.addAttribute("corpusLabel", label);
        model.addAttribute("cases", cases);
        model.addAttribute("pageTitle", "Beispiele: " + label);
        model.addAttribute("activeSection", "corpus");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Beispiele", "/corpus"),
                new HomeController.Breadcrumb(label, "/corpus/" + corpusName)));
        return "corpus/detail";
    }

    /** On-demand evaluation of ONE case (bounded; never a full corpus run). Runs in the background with progress. */
    @PostMapping("/corpus/{corpusName}/{caseId}/evaluate")
    public String evaluate(@PathVariable String corpusName, @PathVariable String caseId,
                           @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           Model model) {
        // Auswertungen starten KI-Verarbeitung: das aufsichtliche Leitungs-
        // Konto (ADMIN) ist schreibgeschützt und wird serverseitig abgewiesen.
        if (CaseAccessGuard.isSupervisory(user)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — Auswertungen können nicht gestartet werden.");
        }
        CorpusCase corpusCase = loadCases(corpusName).stream()
                .filter(c -> c.id().equals(caseId))
                .findFirst().orElse(null);
        if (corpusCase == null) {
            model.addAttribute("evalCaseId", caseId);
            model.addAttribute("corpusName", corpusName);
            model.addAttribute("evalError", "Fall konnte nicht ausgewertet werden.");
            if (hxRequest != null) {
                return "corpus/fragments :: caseEvaluation";
            }
            return "corpus/detail";
        }

        String key = "evaluation:" + corpusName + ":" + caseId;
        Job job = progressService.activeJob(key);
        if (job == null) {
            job = progressService.create(JobProgressService.Kind.EVALUATION, "Fall wird ausgewertet …");
            progressService.registerActive(key, job.jobId);
            final String newJobId = job.jobId;
            executor.submit(() -> runEvaluation(newJobId, key, corpusName, caseId));
        }
        model.addAttribute("title", "Fall wird ausgewertet …");
        model.addAttribute("messages", job.messages);
        model.addAttribute("pollUrl", "/corpus/" + corpusName + "/" + caseId + "/evaluate/progress/" + job.jobId);
        model.addAttribute("emptyMessage", "Der Fall wird für die Auswertung vorbereitet …");
        model.addAttribute("hint", "Die Auswertung kann einen Moment dauern. Das System durchsucht die Wissensbasis und prüft die Antwort.");
        model.addAttribute("error", null);
        model.addAttribute("nodes",
                verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job));
        return "fragments/progress :: progressPanel";
    }

    /** Poll endpoint: progress panel while running, rendered result on completion. */
    @GetMapping("/corpus/{corpusName}/{caseId}/evaluate/progress/{jobId}")
    public ResponseEntity<String> evaluateProgress(@PathVariable String corpusName,
                                                   @PathVariable String caseId,
                                                   @PathVariable String jobId) {
        Job job = progressService.get(jobId);
        if (job == null || "ERROR".equals(job.state)) {
            Map<String, Object> m = new HashMap<>();
            m.put("evalCaseId", caseId);
            m.put("corpusName", corpusName);
            m.put("evalError", job == null
                    ? "Die Auswertung ist nicht mehr verfügbar. Bitte starten Sie die Auswertung erneut."
                    : "Fall konnte nicht ausgewertet werden.");
            return html(render("corpus/fragments", "caseEvaluation", m));
        }
        if ("DONE".equals(job.state)) {
            return html((String) job.outcome);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("title", "Fall wird ausgewertet …");
        m.put("messages", job.messages);
        m.put("pollUrl", "/corpus/" + corpusName + "/" + caseId + "/evaluate/progress/" + job.jobId);
        m.put("emptyMessage", "Der Fall wird für die Auswertung vorbereitet …");
        m.put("hint", "Die Auswertung kann einen Moment dauern. Das System durchsucht die Wissensbasis und prüft die Antwort.");
        m.put("error", null);
        m.put("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job));
        return html(render("fragments/progress", "progressPanel", m));
    }

    /** Runs the existing evaluation pipeline in the background and stores the rendered result fragment. */
    private void runEvaluation(String jobId, String key, String corpusName, String caseId) {
        try {
            CorpusCase corpusCase = loadCases(corpusName).stream()
                    .filter(c -> c.id().equals(caseId))
                    .findFirst().orElse(null);
            Map<String, Object> m = new HashMap<>();
            m.put("evalCaseId", caseId);
            m.put("corpusName", corpusName);
            if (corpusCase != null) {
                var routing = decisionRouter.route(corpusCase.question());
                AiRequest request = new AiRequest(corpusCase.question(), null, null,
                        new AiConversationContext(List.of(), null, null, null, jobId), 15);
                AiResponse response = aiFacade.answer(request);
                String answer = response.answer().answer();
                List<String> hits = corpusCase.expectedKeywords().stream()
                        .filter(k -> answer != null && answer.toLowerCase().contains(k.toLowerCase()))
                        .toList();
                m.put("evalQuestion", corpusCase.question());
                m.put("evalAnswer", answer);
                m.put("evalGrounded", response.answer().grounded());
                // Dokument-gruppierte Beleg-Sicht (eine Karte pro Dokument mit
                // Abschnitts-Liste) — der Quellen-Dialog erwartet die gruppierte
                // Form mit chunkIds für die Hervorhebung im Dokument-Viewer.
                m.put("evalCitations", documentViewerService.groupByDocument(
                        documentViewerService.fromCitations(response.answer().sourceCitations())));
                // Strukturierte-Wissen-Quellen (z. B. "TV-L Entgelttabellen
                // 2025") werden an das echte Dokument angebunden, sofern eines
                // im Bestand existiert — dann ist die Quelle klickbar und
                // nachvollziehbar (kein erfundener Link, wenn kein Dokument
                // existiert).
                if (response.answer().authorityReferences() != null) {
                    m.put("evalKnowledgeSources", response.answer().authorityReferences().stream()
                            .filter(a -> a.entryTitle() != null && !a.entryTitle().isBlank())
                            .map(a -> {
                                UUID docId = documentViewerService.resolveDocumentIdByTitle(a.entryTitle());
                                return new verwaltungsassistent.web.service.DocumentViewerService.SourceView(
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
                            .filter(s -> s.documentId() != null)
                            .toList());
                }
                m.put("evalStrategy", strategyLabel(routing.strategy().name()));
                m.put("evalKeywordHits", hits);
                m.put("evalExpectedKeywords", corpusCase.expectedKeywords());
                // Issue 6: die ECHTEN Pipeline-Metriken sichtbar machen —
                // Gesamtkonfidenz, Quellenlage, Abdeckung, Erklärung, Modell.
                // Keine erfundenen Werte: alles stammt aus der Pipeline-Antwort.
                reasoning.ai.model.ConfidenceProfile conf = response.answer().confidence();
                if (conf != null) {
                    m.put("evalConfidenceOverall", formatPercent(conf.overallConfidence()));
                    m.put("evalConfidenceSource", formatPercent(conf.sourceConfidence()));
                    if (conf.explanation() != null && !conf.explanation().isBlank()) {
                        m.put("evalConfidenceExplanation", conf.explanation());
                    }
                }
                var dossier = response.answer().sourceDossier();
                if (dossier != null) {
                    m.put("evalCoverage", formatPercent(dossier.coverageScore()));
                }
                if (response.metadata() != null && response.metadata().model() != null) {
                    m.put("evalModel", response.metadata().model());
                }
            } else {
                m.put("evalError", "Fall konnte nicht ausgewertet werden.");
            }
            String rendered = render("corpus/fragments", "caseEvaluation", m);
            progressService.complete(jobId, rendered, "Die Auswertung wurde abgeschlossen.");
        } catch (Exception e) {
            log.warn("Corpus case evaluation failed: {}", e.getMessage());
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive(key, jobId);
        }
    }

    /** Renders a Thymeleaf fragment to a string (used from background threads). */
    private String render(String template, String fragment, Map<String, Object> attributes) {
        Context context = new Context(Locale.GERMANY, attributes);
        return templateEngine.process(template, Set.of(fragment), context);
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(body);
    }

    private List<CorpusCase> loadCases(String corpusName) {
        if (!corpusName.matches("[a-z0-9_-]+")) return List.of();
        List<CorpusCase> out = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource r = resolver.getResource("classpath:evaluation/benchmarks/" + corpusName + ".json");
            if (!r.exists()) return List.of();
            JsonNode cases = mapper.readTree(r.getInputStream());
            for (JsonNode c : cases) {
                List<String> keywords = new ArrayList<>();
                for (JsonNode k : c.path("expectedRecommendationKeywords")) {
                    keywords.add(k.asText());
                }
                List<String> authorities = new ArrayList<>();
                for (JsonNode a : c.path("expectedAuthorities")) {
                    authorities.add(a.asText());
                }
                out.add(new CorpusCase(
                        c.path("id").asText(),
                        c.path("domain").asText(),
                        domainLabel(c.path("domain").asText()),
                        c.path("difficulty").asText(),
                        c.path("question").asText(),
                        strategyLabel(c.path("expectedIntent").path("strategy").asText()),
                        authorities,
                        keywords));
            }
        } catch (IOException e) {
            log.warn("Could not load corpus {}: {}", corpusName, e.getMessage());
        }
        return out;
    }

    public record CorpusSummary(String name, String label, int cases, int easy, int medium, int hard) {}

    public record CorpusCase(String id, String domain, String domainLabel, String difficulty,
                             String question, String strategyLabel,
                             List<String> expectedAuthorities, List<String> expectedKeywords) {}

    /** User-facing German label for a benchmark group; the internal identifier stays in the URL only. */
    static String corpusLabel(String name) {
        if (name == null) return "";
        return switch (name) {
            case "hr" -> "Personal / Tarifrecht";
            case "procurement" -> "Vergaberecht";
            case "travel" -> "Dienstreiserecht";
            case "municipal" -> "Bürgerdienste / Wissensbasis";
            default -> name;
        };
    }

    /**
     * User-facing German label for the expected execution strategy. These
     * corpora are intentionally a controlled subset of deterministic cases;
     * the label must not read like a raw engine name.
     */
    static String strategyLabel(String strategy) {
        if (strategy == null || strategy.isBlank()) return "";
        return switch (strategy.toUpperCase()) {
            case "RULE_ENGINE" -> "Regelbasiert";
            case "HYBRID_RETRIEVAL" -> "Hybride Suche";
            case "GRAPH_REASONING" -> "Hybride Suche";
            default -> strategy;
        };
    }

    /** Prozentformat (0.52 → "52%"), wie auf der Entscheidungs-Seite. */
    static String formatPercent(double score) {
        return String.format(java.util.Locale.GERMANY, "%.0f%%", score * 100);
    }

    /** User-facing German label for the domain of a single benchmark case. */
    static String domainLabel(String domain) {
        if (domain == null) return "";
        return switch (domain.toLowerCase()) {
            case "hr" -> "Personal / Tarifrecht";
            case "procurement" -> "Vergaberecht";
            case "travel" -> "Dienstreiserecht";
            default -> domain;
        };
    }
}
