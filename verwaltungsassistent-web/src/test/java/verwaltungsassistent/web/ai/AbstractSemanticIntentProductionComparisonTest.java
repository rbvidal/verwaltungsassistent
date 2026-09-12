package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.RetrievalPlanner;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.*;
import reasoning.common.model.DocumentFileType;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.ChunkType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controlled production-path comparison: regex vs LLM semantic intent.
 *
 * <p>One corpus, run in two modes (concrete subclasses):
 * RUN A — {@code platform.ai.ollama.semantic-intent.enabled=false} (regex)
 * RUN B — {@code platform.ai.ollama.semantic-intent.enabled=true} (LLM)
 *
 * <p>Same application, same infrastructure (Qdrant + Neo4j + Ollama),
 * same knowledge base, same questions. Only the semantic-intent mode differs.
 *
 * <p>Captures per query: language, question, intent (domain/intentType/params),
 * routing decision + source, retrieval plan (mirror of the production call),
 * final answer, grounded status, verification indicators (claimCoverage,
 * conflictPresent), repair status (not in production path), and latencies
 * (parser cold/warm, routing, rule path, retrieval path, total E2E).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractSemanticIntentProductionComparisonTest {

    @Autowired protected AiFacade aiFacade;
    @Autowired protected SemanticIntentParser semanticIntentParser;
    @Autowired protected DecisionRouter decisionRouter;
    @Autowired protected DomainClassifier domainClassifier;
    @Autowired protected RetrievalPlanner retrievalPlanner;
    @Autowired protected JpaDocumentChunkRepository chunkRepository;
    @Autowired(required = false) protected AiProviderProperties aiProperties;

    /** RUN A = "REGEX", RUN B = "LLM" */
    protected abstract String mode();

    protected abstract String reportFileName();

    // ── Latency collectors ──
    private final List<Long> parserMs = new ArrayList<>();
    private final List<Long> routeMs = new ArrayList<>();
    private final List<Long> e2eRuleMs = new ArrayList<>();
    private final List<Long> e2eRetrievalMs = new ArrayList<>();
    private int passCount = 0;
    private int failCount = 0;

    protected final StringBuilder R = new StringBuilder();
    private Instant startTime;

    // ── Corpus ──

    protected record QuerySpec(int order, String id, String language, String category,
                               String question, boolean expectRuleEngine,
                               String expectDecision) {}

    protected static final List<QuerySpec> CORPUS = List.of(
        // ── 1. Deterministic travel (mandated equivalents) ──
        new QuerySpec(1, "TRAVEL-DE-12H", "DE", "TRAVEL",
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
            true, "Tagegeld: 12"),
        new QuerySpec(2, "TRAVEL-EN-12H", "EN", "TRAVEL",
            "What is the meal allowance for a 12-hour business trip?",
            true, "Tagegeld: 12"),
        new QuerySpec(3, "TRAVEL-PT-12H", "PT", "TRAVEL",
            "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?",
            true, "Tagegeld: 12"),
        new QuerySpec(4, "TRAVEL-FR-12H", "FR", "TRAVEL",
            "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?",
            true, "Tagegeld: 12"),
        new QuerySpec(5, "TRAVEL-DE-24H", "DE", "TRAVEL",
            "Wie hoch ist die Verpflegungspauschale bei einer 24-stündigen Dienstreise?",
            true, "Tagegeld: 24"),
        new QuerySpec(6, "TRAVEL-EN-24H", "EN", "TRAVEL",
            "What is the meal allowance for a 24-hour business trip?",
            true, "Tagegeld: 24"),

        // ── 2. Deterministic salary ──
        new QuerySpec(7, "SALARY-DE-S3", "DE", "SALARY",
            "Wie hoch ist das Gehalt für EG 9b Stufe 3?",
            true, "4117.53"),
        new QuerySpec(8, "SALARY-EN-S3", "EN", "SALARY",
            "What is the salary for EG 9b step 3?",
            true, "4117.53"),
        new QuerySpec(9, "SALARY-EN-S5", "EN", "SALARY",
            "What is the salary for EG 9b step 5?",
            true, "4480.00"),
        new QuerySpec(10, "SALARY-PT-S5", "PT", "SALARY",
            "Qual é o salário para EG 9b nível 5?",
            true, "4480.00"),
        new QuerySpec(11, "SALARY-FR-S5", "FR", "SALARY",
            "Quel est le salaire pour EG 9b échelon 5 ?",
            true, "4480.00"),

        // ── 3. Deterministic procurement ──
        new QuerySpec(12, "PROC-DE-8000", "DE", "PROCUREMENT",
            "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?",
            true, "Direktauftrag"),
        new QuerySpec(13, "PROC-EN-8000", "EN", "PROCUREMENT",
            "Which procurement procedure applies to a contract of 8,000 euros?",
            true, "Direktauftrag"),
        new QuerySpec(14, "PROC-PT-8000", "PT", "PROCUREMENT",
            "Qual procedimento de contratação se aplica a um contrato de 8.000 euros?",
            true, "Direktauftrag"),
        new QuerySpec(15, "PROC-FR-8000", "FR", "PROCUREMENT",
            "Quelle procédure de passation s'applique pour un marché de 8 000 euros ?",
            true, "Direktauftrag"),
        new QuerySpec(16, "PROC-DE-85000", "DE", "PROCUREMENT",
            "Welche Vergabeart gilt für einen Auftrag über 85.000 Euro?",
            true, "Beschränkte Ausschreibung"),

        // ── 7. Numeric extraction: distance ──
        new QuerySpec(17, "DIST-DE-120KM", "DE", "DISTANCE",
            "Wie hoch ist die Kilometerpauschale bei einer Dienstreise von 120 km?",
            true, "0.35"),
        new QuerySpec(18, "DIST-EN-120KM", "EN", "DISTANCE",
            "What is the mileage allowance for a business trip of 120 km?",
            true, "0.35"),
        new QuerySpec(19, "DIST-FR-120KM", "FR", "DISTANCE",
            "Quel est le remboursement kilométrique pour un déplacement professionnel de 120 km ?",
            true, "0.35"),

        // ── 4. Building/document retrieval ──
        new QuerySpec(20, "RETR-DE-BUILDING", "DE", "RETRIEVAL",
            "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?",
            false, null),
        new QuerySpec(21, "RETR-EN-BUILDING", "EN", "RETRIEVAL",
            "What building permit do I need for a single-family house in Berlin?",
            false, null),
        new QuerySpec(22, "RETR-PT-BUILDING", "PT", "RETRIEVAL",
            "Que licença de construção preciso para uma casa unifamiliar em Berlim?",
            false, null),

        // ── 5. Unsupported ──
        new QuerySpec(23, "UNSUP-DE", "DE", "UNSUPPORTED",
            "Welche Vorschriften gelten für Quantencomputer-Beschaffung in der Berliner Verwaltung?",
            false, null),
        new QuerySpec(24, "UNSUP-EN", "EN", "UNSUPPORTED",
            "What regulations apply to quantum computing procurement in the Berlin administration?",
            false, null),

        // ── 6. Ambiguous ──
        new QuerySpec(25, "AMBIG-DE", "DE", "AMBIGUOUS",
            "Was gilt bei 12?",
            false, null),
        new QuerySpec(26, "AMBIG-EN", "EN", "AMBIGUOUS",
            "What applies at 12?",
            false, null),

        // ── 10. No parameters ──
        new QuerySpec(27, "NOPARAM-DE-TRAVEL", "DE", "NO_PARAM",
            "Welche Verpflegungspauschalen gelten bei Dienstreisen?",
            false, null)
    );

    // ── Lifecycle ──

    @BeforeAll
    void init() {
        startTime = Instant.now();
        R.append("=".repeat(80)).append("\n");
        R.append("  SEMANTIC INTENT PRODUCTION-PATH COMPARISON — RUN ").append(mode()).append("\n");
        R.append("  ").append(Instant.now()).append("\n");
        R.append("=".repeat(80)).append("\n");
        R.append("  Infra: Qdrant mda_chunks (634 pts) + Neo4j (live) + Ollama\n");
        R.append("  Models: gen=qwen2.5:14b, verifier=qwen2.5:7b, embeddings=nomic-embed-text\n");
        R.append("  semantic-intent.enabled = ").append(mode().equals("LLM")).append("\n");
        R.append("  Corpus: ").append(CORPUS.size()).append(" queries (DE/EN/PT/FR)\n\n");
    }

    @AfterAll
    void report() {
        R.append("\n").append("=".repeat(80)).append("\n");
        R.append("  LATENCY SUMMARY — RUN ").append(mode()).append("\n");
        R.append("=".repeat(80)).append("\n");
        appendStats(R, "semantic parser (warmup phase)", parserMs, 1);
        appendStats(R, "routing incl. parse", routeMs, 0);
        appendStats(R, "E2E rule-engine path", e2eRuleMs, 0);
        appendStats(R, "E2E retrieval path", e2eRetrievalMs, 0);
        R.append("\n  VERDICTS: ").append(passCount).append(" PASS / ")
            .append(failCount).append(" FAIL\n");
        long sec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        R.append("  Total duration: ").append(sec).append("s\n");
        R.append("=".repeat(80)).append("\n");
        writeReport();
        System.out.println(R.toString());
    }

    // ── Setup: seed small corpus for keyword retrieval ──

    @Test @Order(1) @DisplayName("SETUP: Seed building chunks")
    @Transactional
    void seedChunks() {
        seed(UUID.fromString("b1000000-0000-0000-0000-000000000001"),
            "Bauordnung Berlin — Abstandsflächen",
            "Nach BauO Bln Paragraph 6 müssen Abstandsflächen vor Außenwänden " +
            "von Gebäuden liegen. Die Tiefe der Abstandsfläche beträgt mindestens " +
            "0,4 der Wandhöhe. Für Einfamilienhäuser gelten erleichterte Anforderungen. " +
            "Die Abstandsflächen müssen auf dem Grundstück selbst liegen.",
            "building");
        seed(UUID.fromString("b1000000-0000-0000-0000-000000000002"),
            "Baugenehmigung Berlin — Einfamilienhaus",
            "Für ein Einfamilienhaus in Berlin ist ein vereinfachtes " +
            "Baugenehmigungsverfahren nach Paragraph 63 BauO Bln erforderlich. " +
            "Der Bauantrag ist beim zuständigen Bezirksamt einzureichen. " +
            "Erforderliche Bauvorlagen umfassen Lageplan, Bauzeichnungen und " +
            "statische Berechnungen.",
            "building");
        R.append("  Seeded 2 building chunks into H2 (keyword retrieval support)\n\n");
        writeReport();
    }

    // ── Parser warmup: cold/warm observation ──

    @Test @Order(2) @DisplayName("PARSER WARMUP: cold/warm latency")
    void parserWarmup() {
        R.append("-".repeat(80)).append("\n");
        R.append("  PARSER WARMUP PHASE (").append(mode()).append(")\n");
        String model = aiProperties != null ? aiProperties.getOllama().getVerifierModel() : "n/a";
        R.append("  parser model: ").append(model).append("\n");
        for (int i = 0; i < CORPUS.size(); i++) {
            long t0 = System.currentTimeMillis();
            semanticIntentParser.parse(CORPUS.get(i).question());
            long ms = System.currentTimeMillis() - t0;
            parserMs.add(ms);
            String tag = i == 0 ? "COLD" : "warm";
            R.append(String.format("  parse %02d [%s]: %dms%n", i + 1, tag, ms));
        }
        R.append("\n");
        writeReport();
    }

    // ── Corpus run ──

    @TestFactory @Order(3)
    List<DynamicTest> corpusTests() {
        List<DynamicTest> tests = new ArrayList<>();
        for (QuerySpec spec : CORPUS) {
            tests.add(DynamicTest.dynamicTest(
                String.format("%02d %s [%s] %s", spec.order(), spec.id(), spec.language(), spec.category()),
                () -> runQuery(spec)));
        }
        return tests;
    }

    // ── Core runner ──

    private void runQuery(QuerySpec spec) {
        String question = spec.question().trim();
        R.append("-".repeat(80)).append("\n");
        R.append(String.format("  [%02d/%02d] %s | %s | %s%n",
            spec.order(), CORPUS.size(), spec.id(), spec.language(), spec.category()));
        R.append("  Q: ").append(question).append("\n\n");

        List<Executable> checks = new ArrayList<>();
        StringBuilder verdict = new StringBuilder();
        String verdictStatus = "PASS";

        // 1. Routing (includes semantic parse in production wiring)
        long t0 = System.currentTimeMillis();
        DecisionRouter.RoutingResult routing;
        try {
            routing = decisionRouter.route(question);
        } catch (Exception e) {
            R.append("  ROUTE ERROR: ").append(e.getMessage()).append("\n\n");
            writeReport();
            fail(spec.id() + ": routing failed: " + e.getMessage());
            return;
        }
        long rtMs = System.currentTimeMillis() - t0;
        routeMs.add(rtMs);

        StructuredIntent intent = routing.intent();
        R.append(String.format("  intent            : %s domain=%s params=%s%n",
            intent != null ? intent.intentType() : "n/a",
            intent != null ? intent.domain() : "n/a",
            intent != null ? intent.parameters() : "n/a"));
        R.append(String.format("  route             : %s (%dms)%n", routing.strategy(), rtMs));

        // 2. Legacy DomainClassifier (informational — fallback used when domain==null)
        var cls = domainClassifier.classify(question);
        R.append(String.format("  legacy DomainCls  : %s (conf=%.2f)%n",
            cls.primary(), cls.primaryConfidence()));

        // 3. Decision
        if (routing.decision() != null) {
            R.append("  decision          : ").append(shortDecision(routing.decision())).append("\n");
            R.append("  decision text     : ").append(routing.decision().decision()).append("\n");
            R.append("  decision source   : ").append(routing.decision().source()).append("\n");
        } else {
            R.append("  decision          : none (retrieval path)\n");
        }

        // 4. Retrieval plan (mirror of production retrieve(request, intent) call)
        if (routing.needsRetrieval()) {
            Domain authoritative = intent != null ? intent.domain() : null;
            RetrievalPlan plan = retrievalPlanner.plan(
                new AiRequest(question, null, null, null, 15), authoritative);
            R.append(String.format("  retrieval plan    : primaryDomain=%s strategy=%s maxResults=%d (%s)%n",
                plan.primaryDomain(), plan.retrievalStrategy(), plan.maxResults(),
                authoritative != null ? "authoritative domain" : "DomainClassifier fallback"));
        } else {
            R.append("  retrieval plan    : SKIPPED (rule path)\n");
        }

        // 5. Full production pipeline
        AiResponse response;
        long e2eMs;
        try {
            t0 = System.currentTimeMillis();
            response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
            e2eMs = System.currentTimeMillis() - t0;
        } catch (Exception e) {
            R.append("  E2E ERROR: ").append(e.getMessage()).append("\n\n");
            writeReport();
            fail(spec.id() + ": E2E pipeline failed: " + e.getMessage());
            return;
        }
        if (routing.isRuleEngine()) e2eRuleMs.add(e2eMs); else e2eRetrievalMs.add(e2eMs);

        ReasonedAnswer answer = response.answer();
        R.append(String.format("  E2E               : %dms strategy=%s%n",
            e2eMs, response.metadata() != null ? response.metadata().retrievalStrategy() : "?"));
        R.append(String.format("  grounded          : %s%n", answer.grounded()));
        if (routing.isRuleEngine()) {
            R.append("  verification      : N/A (deterministic path — verifier not invoked)\n");
        } else {
            R.append(String.format("  verification      : claimCoverage=%.2f conflictPresent=%s%n",
                answer.claimCoverage(), answer.conflictPresent()));
        }
        R.append("  repair            : N/A (repair engine not in production pipeline)\n");
        if (answer.confidence() != null) {
            R.append(String.format("  confidence        : %.2f%n", answer.confidence().overallConfidence()));
        }
        List<SourceCitation> cites = answer.sourceCitations();
        R.append(String.format("  citations         : %d%n", cites.size()));
        if (!cites.isEmpty()) {
            Set<String> origins = new TreeSet<>();
            for (var c : cites) origins.addAll(c.retrievalSources());
            R.append("  retrievalSources  : ").append(origins).append("\n");
            for (int i = 0; i < Math.min(3, cites.size()); i++) {
                R.append(String.format("    cite[%d] %.2f %s %s%n", i,
                    cites.get(i).confidenceScore(),
                    truncate(cites.get(i).title(), 45),
                    cites.get(i).retrievalSources()));
            }
        }
        R.append("  answer            : ").append(truncate(answer.answer(), 220)).append("\n");

        // 6. Verdict
        R.append("  EXPECTED          : ");
        if (spec.expectRuleEngine()) {
            R.append("RULE_ENGINE");
            if (spec.expectDecision() != null) R.append(" + \"").append(spec.expectDecision()).append("\"");
        } else {
            R.append("NO forced deterministic answer (retrieval path)");
        }
        R.append("\n");

        if (spec.expectRuleEngine()) {
            checks.add(() -> assertEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
                spec.id() + ": expected RULE_ENGINE"));
            if (spec.expectDecision() != null) {
                checks.add(() -> {
                    assertNotNull(routing.decision(), spec.id() + ": expected a decision");
                    assertTrue(routing.decision().decision().contains(spec.expectDecision()),
                        spec.id() + ": decision '" + routing.decision().decision()
                            + "' must contain '" + spec.expectDecision() + "'");
                });
            }
        } else {
            checks.add(() -> assertNotEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
                spec.id() + ": must NOT force a deterministic answer"));
        }

        try {
            assertAll(spec.id(), checks);
            passCount++;
        } catch (AssertionError e) {
            verdictStatus = "FAIL";
            failCount++;
            verdict.append(e.getMessage()).append(" | ");
        }

        R.append("  VERDICT           : ").append(verdictStatus);
        if (verdict.length() > 0) R.append("  (").append(verdict).append(")");
        R.append("\n\n");
        writeReport();
    }

    // ── Helpers ──

    private static String shortDecision(DecisionResult d) {
        if (d instanceof DecisionResult.TravelDecision td) {
            if ("mileage".equals(td.category())) {
                return "Kilometerpauschale " + fmt(td.allowanceEur()) + " €/km (" + fmt(td.hours()) + " km)";
            }
            return "Tagegeld " + fmt(td.allowanceEur()) + " € / " + fmt(td.hours()) + "h";
        }
        if (d instanceof DecisionResult.SalaryDecision sd) {
            return sd.grade() + " Stufe " + sd.step() + " = " + fmt(sd.monthlyAmount()) + " €";
        }
        if (d instanceof DecisionResult.ProcurementDecision pd) {
            return pd.procedure() + " (" + fmt(pd.amount()) + " €)";
        }
        return d.getClass().getSimpleName();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max).replace('\n', ' ') + "..." : s.replace('\n', ' ');
    }

    private static void appendStats(StringBuilder sb, String label, List<Long> values, int skipFirst) {
        if (values.isEmpty() || values.size() <= skipFirst) {
            sb.append(String.format("  %-34s: no data%n", label));
            return;
        }
        List<Long> v = values.subList(skipFirst, values.size());
        List<Long> sorted = new ArrayList<>(v);
        Collections.sort(sorted);
        long sum = 0;
        for (long x : v) sum += x;
        double avg = sum / (double) v.size();
        double median = sorted.size() % 2 == 1
            ? sorted.get(sorted.size() / 2)
            : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
        sb.append(String.format("  %-34s: n=%d avg=%.0fms median=%.0fms min=%dms max=%dms%n",
            label, v.size(), avg, median, sorted.get(0), sorted.get(sorted.size() - 1)));
    }

    protected void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/" + reportFileName()), R.toString());
        } catch (IOException ignored) {}
    }

    @Transactional
    private void seed(UUID docId, String title, String text, String category) {
        chunkRepository.save(new DocumentChunkEntity(
            UUID.randomUUID(), docId, 1, ChunkType.TEXT, text,
            null, null, 0, 0, text.length(),
            title, DocumentFileType.PDF, category, Set.of(), "validation",
            "default", Instant.now(), Set.of(), null));
    }
}
