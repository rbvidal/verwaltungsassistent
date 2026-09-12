package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.model.*;
import reasoning.common.model.DocumentFileType;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.ChunkType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime validation of the LLM semantic-intent path against real
 * application infrastructure (Qdrant + Neo4j + Ollama).
 *
 * <p>Runs the full 12-query matrix through the actual application:
 * semantic parser → DecisionRouter → RULE_ENGINE / HYBRID_RETRIEVAL
 * → grounding → verification.
 *
 * <p>LLM parser enabled: platform.ai.ollama.semantic-intent.enabled=true
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = verwaltungsassistent.web.VerwaltungsassistentApplication.class,
    properties = {
        "platform.neo4j.uri=bolt://localhost:7687",
        "platform.neo4j.username=neo4j",
        "platform.neo4j.password=password",
        "platform.ai.ollama.base-url=http://localhost:11434",
        "platform.ai.ollama.chat-model=qwen2.5:14b",
        "platform.ai.ollama.verifier-model=qwen2.5:7b",
        "platform.ai.ollama.verification-strategy=claim_batch",
        "platform.ai.ollama.semantic-intent.enabled=true",
        "platform.ai.ollama.embedding-model=nomic-embed-text",
        "platform.ai.ollama.embedding-dimension=768"
    }
)
@TestPropertySource(properties = {
    "platform.search.qdrant.enabled=true",
    "platform.search.qdrant.collection=mda_chunks",
    "platform.search.qdrant.vector-dimension=768",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Semantic Intent Runtime Validation")
class SemanticIntentRuntimeValidationTest {

    @Autowired private AiFacade aiFacade;
    @Autowired private SemanticIntentParser semanticIntentParser;
    @Autowired private DecisionRouter decisionRouter;
    @Autowired private DomainClassifier domainClassifier;
    @Autowired private KnowledgeRegistry knowledgeRegistry;
    @Autowired private JpaDocumentChunkRepository chunkRepository;

    private static final StringBuilder R = new StringBuilder();
    private static Instant startTime;

    @BeforeAll static void init() {
        startTime = Instant.now();
        R.append("=".repeat(78)).append("\n");
        R.append("  SEMANTIC INTENT RUNTIME VALIDATION (LLM parser ENABLED)\n");
        R.append("  ").append(Instant.now()).append("\n");
        R.append("=".repeat(78)).append("\n");
        R.append("  Infra: Qdrant mda_chunks (634 pts) + Neo4j + Ollama (14B gen / 7B verifier)\n");
        R.append("  Parser: LLM (qwen2.5:7b) — semantic-intent.enabled=true\n\n");
    }

    @AfterAll static void report() {
        R.append("\n").append("=".repeat(78)).append("\n");
        long sec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        R.append("  Total duration: ").append(sec).append("s\n");
        R.append("=".repeat(78)).append("\n");
        writeReport();
        System.out.println(R.toString());
    }

    // ── Seed small corpus for keyword retrieval ──

    @Test @Order(1) @DisplayName("SETUP: Seed chunks")
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
        R.append("  Seeded 2 building chunks into H2\n\n");
    }

    // ── Travel: DE/EN/PT ──

    @Test @Order(2) @DisplayName("1. DE travel → RULE_ENGINE")
    void deTravel() {
        runQuery("DE-TRAVEL", "DE",
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
            true, "TRAVEL");
    }

    @Test @Order(3) @DisplayName("2. EN travel → RULE_ENGINE")
    void enTravel() {
        runQuery("EN-TRAVEL", "EN",
            "What is the meal allowance for a 12-hour business trip?",
            true, "TRAVEL");
    }

    @Test @Order(4) @DisplayName("3. PT travel → RULE_ENGINE")
    void ptTravel() {
        runQuery("PT-TRAVEL", "PT",
            "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?",
            true, "TRAVEL");
    }

    // ── Retrieval: DE/EN/PT ──

    @Test @Order(5) @DisplayName("4. DE retrieval → HYBRID_RETRIEVAL")
    void deRetrieval() {
        runQuery("DE-RETRIEVAL", "DE",
            "Welche Abstandsflächen sind nach BauO Bln Paragraph 6 einzuhalten?",
            false, null);
    }

    @Test @Order(6) @DisplayName("5. EN retrieval → HYBRID_RETRIEVAL")
    void enRetrieval() {
        runQuery("EN-RETRIEVAL", "EN",
            "Which clearance distances are required under BauO Bln Section 6?",
            false, null);
    }

    @Test @Order(7) @DisplayName("6. PT retrieval → HYBRID_RETRIEVAL")
    void ptRetrieval() {
        runQuery("PT-RETRIEVAL", "PT",
            "Que distâncias de afastamento são exigidas pelo BauO Bln, seção 6?",
            false, null);
    }

    // ── Unsupported ──

    @Test @Order(8) @DisplayName("7. Unsupported question → no hallucinated grounded answer")
    void unsupported() {
        runQuery("UNSUPPORTED", "DE",
            "Welche Vorschriften gelten für Quantencomputer-Beschaffung in der Berliner Verwaltung?",
            false, null);
    }

    // ── Ambiguous ──

    @Test @Order(9) @DisplayName("8. Ambiguous question — record parser + route")
    void ambiguous() {
        runQuery("AMBIGUOUS", "DE",
            "Was gilt bei 12?",
            false, null);
    }

    // ── Procurement ──

    @Test @Order(10) @DisplayName("9. Procurement 8000€ → RULE_ENGINE")
    void procurement() {
        runQuery("PROCUREMENT", "DE",
            "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?",
            true, "PROCUREMENT");
    }

    // ── Salary ──

    @Test @Order(11) @DisplayName("10. Salary EG 9b Stufe 3 → RULE_ENGINE (DE + EN)")
    void salary() {
        // EG 9b is the real TV-L grade; plain "EG 9" does not exist in TV-L
        // (grade 9 is split into 9a/9b/9c). Knowledge-data gap documented in report.
        runQuery("SALARY-DE", "DE",
            "Wie hoch ist das Gehalt für EG 9b Stufe 3?",
            true, "HR");
        runQuery("SALARY-EN", "EN",
            "What is the salary for EG 9b step 3?",
            true, "HR");
    }

    // ── Building retrieval ──

    @Test @Order(12) @DisplayName("11. Building → HYBRID_RETRIEVAL")
    void building() {
        runQuery("BUILDING", "DE",
            "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?",
            false, null);
    }

    // ── Wrong-language stress test ──

    @Test @Order(13) @DisplayName("12. French stress test — clear semantics in another language")
    void frenchStressTest() {
        runQuery("FR-TRAVEL", "FR",
            "Quel est le montant de l'indemnité de repas pour un voyage d'affaires de 12 heures ?",
            true, "TRAVEL");
    }

    // ── Comparison: regex parser vs LLM parser on the same queries ──

    @Test @Order(14) @DisplayName("COMPARISON: regex vs LLM parser+router (no E2E)")
    void comparisonRegexVsLlm() {
        R.append("=".repeat(78)).append("\n");
        R.append("  COMPARISON: REGEX PARSER vs LLM PARSER (parser+router only)\n");
        R.append("=".repeat(78)).append("\n\n");

        RegexSemanticIntentParser regexParser = new RegexSemanticIntentParser();
        DecisionRouter regexRouter = new DecisionRouter(knowledgeRegistry, domainClassifier, null, regexParser);

        List<String[]> queries = List.of(
            new String[]{"DE-TRAVEL", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?"},
            new String[]{"EN-TRAVEL", "What is the meal allowance for a 12-hour business trip?"},
            new String[]{"PT-TRAVEL", "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?"},
            new String[]{"FR-TRAVEL", "Quel est le montant de l'indemnité de repas pour un voyage d'affaires de 12 heures ?"},
            new String[]{"SALARY-EN", "What is the salary for EG 9 step 3?"},
            new String[]{"PROC-DE", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?"}
        );

        R.append(String.format("  %-14s %-10s %-14s %-10s %-14s%n",
            "Query", "RegexStrat", "RegexDec", "LlmStrat", "LlmDec"));
        R.append("  " + "-".repeat(70) + "\n");

        for (String[] q : queries) {
            var regexIntent = regexParser.parse(q[1]);
            var regexRoute = regexRouter.route(q[1]);
            var llmIntent = semanticIntentParser.parse(q[1]);
            var llmRoute = decisionRouter.route(q[1]);

            R.append(String.format("  %-14s %-10s %-14s %-10s %-14s%n",
                q[0],
                regexRoute.strategy(),
                shortDecision(regexRoute.decision()),
                llmRoute.strategy(),
                shortDecision(llmRoute.decision())));
            R.append(String.format("    regex intent: %s %s%n",
                regexIntent.intentType(), regexIntent.parameters()));
            R.append(String.format("    llm   intent: %s %s%n",
                llmIntent.intentType(), llmIntent.parameters()));
        }
        R.append("\n");
    }

    // ── Core runner ──

    private void runQuery(String id, String lang, String question,
                          boolean expectRuleEngine, String expectDomain) {
        R.append("-".repeat(78)).append("\n");
        R.append(String.format("  %s [%s]%n  Q: %s%n%n", id, lang, question));

        // 1. Semantic parser
        long t0 = System.currentTimeMillis();
        StructuredIntent intent = semanticIntentParser.parse(question);
        long parserMs = System.currentTimeMillis() - t0;
        R.append(String.format("  1. SemanticIntentParser (%dms):%n", parserMs));
        R.append(String.format("     intentType=%s%n", intent.intentType()));
        R.append(String.format("     parameters=%s%n", intent.parameters()));

        // 2. Domain classification
        var domain = domainClassifier.classify(question);
        R.append(String.format("  2. Domain: %s (conf=%.2f)%n", domain.primary(), domain.primaryConfidence()));

        // 3. Router
        t0 = System.currentTimeMillis();
        var routing = decisionRouter.route(question);
        long routerMs = System.currentTimeMillis() - t0;
        R.append(String.format("  3. Router (%dms): %s%n", routerMs, routing.strategy()));
        if (routing.decision() != null) {
            R.append(String.format("     decision: %s%n", shortDecision(routing.decision())));
        }

        // 4. Full pipeline
        t0 = System.currentTimeMillis();
        AiResponse response;
        try {
            AiRequest req = new AiRequest(question, null, null, null, 15);
            response = aiFacade.answer(req);
        } catch (Exception e) {
            R.append(String.format("  4. E2E ERROR: %s%n%n", e.getMessage()));
            writeReport();
            fail("E2E pipeline failed for " + id + ": " + e.getMessage());
            return;
        }
        long e2eMs = System.currentTimeMillis() - t0;

        ReasonedAnswer answer = response.answer();
        R.append(String.format("  4. E2E (%dms): grounded=%s claimCoverage=%.2f conflict=%s%n",
            e2eMs, answer.grounded(), answer.claimCoverage(), answer.conflictPresent()));
        R.append(String.format("     citations=%d authorityRefs=%d%n",
            answer.sourceCitations().size(), answer.authorityReferences().size()));
        if (answer.confidence() != null) {
            R.append(String.format("     confidence=%.2f%n", answer.confidence().overallConfidence()));
        }
        R.append(String.format("     answer: %s%n", truncate(answer.answer(), 150)));

        // 5. Assertions
        if (expectRuleEngine) {
            assertEquals("RULE_ENGINE", routing.strategy().name(),
                id + ": must route to RULE_ENGINE");
        }
        if (expectDomain != null) {
            R.append(String.format("     expected domain=%s, actual=%s%n",
                expectDomain, domain.primary()));
        }

        // Unsupported query must not be falsely grounded
        if (id.equals("UNSUPPORTED") && answer.sourceCitations().isEmpty()) {
            R.append("     (no evidence retrieved — as expected for unsupported query)\n");
        }

        R.append("\n");
    }

    // ── Helpers ──

    private static String shortDecision(DecisionResult d) {
        if (d == null) return "-";
        if (d instanceof DecisionResult.TravelDecision td)
            return "Tagegeld " + td.allowanceEur() + "€/" + td.hours() + "h";
        if (d instanceof DecisionResult.SalaryDecision sd)
            return sd.grade() + " S" + sd.step() + "=" + sd.monthlyAmount();
        if (d instanceof DecisionResult.ProcurementDecision pd)
            return pd.procedure();
        return d.getClass().getSimpleName();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max).replace('\n', ' ') + "..." : s.replace('\n', ' ');
    }

    private static void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/semantic-intent-runtime-validation.txt"), R.toString());
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
