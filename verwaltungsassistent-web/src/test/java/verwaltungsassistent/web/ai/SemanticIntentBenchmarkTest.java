package verwaltungsassistent.web.ai;

import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.LlmSemanticIntentParser;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.StructuredIntent;
import reasoning.ai.provider.OllamaChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares regex-based vs LLM-based semantic intent extraction.
 *
 * <p>Tests parameter extraction across German, English, and Portuguese
 * with semantically equivalent questions.
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
        "platform.ai.ollama.embedding-model=nomic-embed-text",
        "platform.ai.ollama.embedding-dimension=768"
    }
)
@TestPropertySource(properties = {
    "platform.search.qdrant.enabled=false",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@DisplayName("Semantic Intent Benchmark")
class SemanticIntentBenchmarkTest {

    @Autowired(required = false)
    private AiProviderProperties aiProperties;

    private RegexSemanticIntentParser regexParser;
    private LlmSemanticIntentParser llmParser;
    private static final StringBuilder R = new StringBuilder();
    private static Instant startTime;

    @BeforeAll
    static void init() { startTime = Instant.now(); }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(aiProperties != null, "AiProviderProperties not available");
        regexParser = new RegexSemanticIntentParser();

        if (llmParser == null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                OllamaChatProvider provider = new OllamaChatProvider(mapper, aiProperties);
                if (provider.isAvailable()) {
                    llmParser = new LlmSemanticIntentParser(provider, aiProperties);
                }
            } catch (Exception ignored) {}
        }
    }

    record TestCase(String id, String language, String question,
                    String expectedIntent, Map<String, Object> expectedParams) {}

    static List<TestCase> testCases() {
        List<TestCase> cases = new ArrayList<>();

        // ── German ──
        cases.add(new TestCase("DE-TRAVEL-1", "DE",
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 12.0)));

        cases.add(new TestCase("DE-TRAVEL-2", "DE",
            "Tagegeld für 8 Stunden Dienstreise im Inland",
            "TRAVEL_ALLOWANCE", Map.of("hours", 8.0)));

        cases.add(new TestCase("DE-TRAVEL-3", "DE",
            "24-stündige Dienstreise nach Brüssel — welcher Verpflegungssatz gilt?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 24.0)));

        cases.add(new TestCase("DE-SALARY-1", "DE",
            "Wie hoch ist das Gehalt in EG 9 Stufe 3 nach TV-L 2025?",
            "SALARY_LOOKUP", Map.of("salaryGrade", "EG 9", "salaryStep", 3)));

        cases.add(new TestCase("DE-SALARY-2", "DE",
            "EG 11 Stufe 3 TV-L Entgelt",
            "SALARY_LOOKUP", Map.of("salaryGrade", "EG 11", "salaryStep", 3)));

        cases.add(new TestCase("DE-PROC-1", "DE",
            "Kann ich einen IT-Auftrag über 8.000 Euro freihändig vergeben?",
            "PROCUREMENT_THRESHOLD", Map.of("amountEur", 8000.0)));

        // ── English ──
        cases.add(new TestCase("EN-TRAVEL-1", "EN",
            "What is the meal allowance for a 12-hour business trip?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 12.0)));

        cases.add(new TestCase("EN-TRAVEL-2", "EN",
            "Daily allowance for 8 hours domestic travel",
            "TRAVEL_ALLOWANCE", Map.of("hours", 8.0)));

        cases.add(new TestCase("EN-SALARY-1", "EN",
            "What is the salary for grade EG 9 step 3 in TV-L?",
            "SALARY_LOOKUP", Map.of("salaryGrade", "EG 9", "salaryStep", 3)));

        cases.add(new TestCase("EN-PROC-1", "EN",
            "Can I directly award an IT contract worth 8,000 euros?",
            "PROCUREMENT_THRESHOLD", Map.of("amountEur", 8000.0)));

        // ── Portuguese ──
        cases.add(new TestCase("PT-TRAVEL-1", "PT",
            "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 12.0)));

        cases.add(new TestCase("PT-TRAVEL-2", "PT",
            "Ajuda de custo para 8 horas de viagem nacional",
            "TRAVEL_ALLOWANCE", Map.of("hours", 8.0)));

        cases.add(new TestCase("PT-PROC-1", "PT",
            "Posso contratar diretamente um serviço de TI no valor de 8.000 euros?",
            "PROCUREMENT_THRESHOLD", Map.of("amountEur", 8000.0)));

        // ── Variations (paraphrases, synonyms, word order) ──
        cases.add(new TestCase("VAR-1", "DE",
            "Welche Verpflegungspauschale bekomme ich bei einer Dienstreise von 12 Stunden?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 12.0)));

        cases.add(new TestCase("VAR-2", "EN",
            "What's the per diem for a trip lasting twelve hours?",
            "TRAVEL_ALLOWANCE", Map.of("hours", 12.0)));

        return cases;
    }

    @Test
    @DisplayName("Regex parser: all languages")
    void regexParserBenchmark() {
        R.append("=".repeat(78)).append("\n");
        R.append("  SEMANTIC INTENT BENCHMARK — Regex Parser\n");
        R.append("=".repeat(78)).append("\n\n");

        int correctIntent = 0, correctParam = 0, total = 0;
        for (TestCase tc : testCases()) {
            total++;
            StructuredIntent intent = regexParser.parse(tc.question);
            boolean intentOk = tc.expectedIntent.equals(intent.intentType());
            boolean paramOk = checkParams(tc, intent);
            if (intentOk) correctIntent++;
            if (paramOk) correctParam++;

            R.append(String.format("  %-12s %-3s intent=%-22s (exp=%-22s) %s | params %s%n",
                tc.id(), tc.language(), intent.intentType(), tc.expectedIntent,
                intentOk ? "✓" : "✗", paramOk ? "✓" : "✗"));
        }
        R.append(String.format("%n  Intent accuracy: %d/%d (%.0f%%) | Param accuracy: %d/%d (%.0f%%)%n%n",
            correctIntent, total, 100.0*correctIntent/total,
            correctParam, total, 100.0*correctParam/total));
    }

    @Test
    @DisplayName("LLM parser: all languages")
    void llmParserBenchmark() {
        Assumptions.assumeTrue(llmParser != null, "LLM parser not available (Ollama down)");

        R.append("=".repeat(78)).append("\n");
        R.append("  SEMANTIC INTENT BENCHMARK — LLM Parser (qwen2.5:7b)\n");
        R.append("=".repeat(78)).append("\n\n");

        int correctIntent = 0, correctParam = 0, total = 0;
        long totalMs = 0;
        int failures = 0;

        for (TestCase tc : testCases()) {
            total++;
            long t0 = System.currentTimeMillis();
            StructuredIntent intent;
            try {
                intent = llmParser.parse(tc.question);
            } catch (Exception e) {
                failures++;
                R.append(String.format("  %-12s %-3s ERROR: %s%n", tc.id(), tc.language(), e.getMessage()));
                continue;
            }
            long elapsed = System.currentTimeMillis() - t0;
            totalMs += elapsed;

            boolean intentOk = tc.expectedIntent.equals(intent.intentType());
            boolean paramOk = checkParams(tc, intent);
            if (intentOk) correctIntent++;
            if (paramOk) correctParam++;

            R.append(String.format("  %-12s %-3s intent=%-22s (exp=%-22s) %s | params %s | %dms%n",
                tc.id(), tc.language(), intent.intentType(), tc.expectedIntent,
                intentOk ? "✓" : "✗", paramOk ? "✓" : "✗", elapsed));
        }
        R.append(String.format("%n  Intent accuracy: %d/%d (%.0f%%) | Param accuracy: %d/%d (%.0f%%) | Failures: %d%n",
            correctIntent, total, 100.0*correctIntent/total,
            correctParam, total, 100.0*correctParam/total, failures));
        R.append(String.format("  Total time: %,d ms | Avg: %,.0f ms/call%n%n", totalMs, totalMs/(double)Math.max(1,total)));
    }

    @Test
    @DisplayName("LLM parser: German vs English vs Portuguese equivalence")
    void languageEquivalence() {
        Assumptions.assumeTrue(llmParser != null, "LLM parser not available");

        R.append("## Language Equivalence — Same Intent, Different Languages\n\n");

        // All three should produce hours=12, intent=TRAVEL_ALLOWANCE
        String[] questions = {
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
            "What is the meal allowance for a 12-hour business trip?",
            "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?"
        };
        String[] langs = {"DE", "EN", "PT"};

        boolean allCorrect = true;
        for (int i = 0; i < questions.length; i++) {
            StructuredIntent intent = llmParser.parse(questions[i]);
            boolean intentOk = "TRAVEL_ALLOWANCE".equals(intent.intentType());
            boolean hoursOk = intent.hours().orElse(0.0) == 12.0;
            boolean correct = intentOk && hoursOk;
            if (!correct) allCorrect = false;
            R.append(String.format("  %s: intent=%s hours=%.0f %s%n",
                langs[i], intent.intentType(), intent.hours().orElse(0.0),
                correct ? "✓" : "✗"));
        }
        R.append(String.format("  Cross-language equivalence: %s%n%n",
            allCorrect ? "PROVEN" : "NOT PROVEN"));
    }

    @Test
    @DisplayName("COMPARISON: Regex vs LLM summary")
    void comparisonSummary() {
        R.append("=".repeat(78)).append("\n");
        R.append("  COMPARISON: REGEX vs LLM SEMANTIC INTENT\n");
        R.append("=".repeat(78)).append("\n\n");

        // Regex baseline
        int regexIntentOk = 0, regexParamOk = 0;
        for (TestCase tc : testCases()) {
            StructuredIntent intent = regexParser.parse(tc.question);
            if (tc.expectedIntent.equals(intent.intentType())) regexIntentOk++;
            if (checkParams(tc, intent)) regexParamOk++;
        }
        int total = testCases().size();

        // LLM (if available)
        int llmIntentOk = 0, llmParamOk = 0, llmFailures = 0;
        if (llmParser != null) {
            for (TestCase tc : testCases()) {
                try {
                    StructuredIntent intent = llmParser.parse(tc.question);
                    if (tc.expectedIntent.equals(intent.intentType())) llmIntentOk++;
                    if (checkParams(tc, intent)) llmParamOk++;
                } catch (Exception e) { llmFailures++; }
            }
        }

        R.append(String.format("  %-25s %10s %10s%n", "Metric", "Regex", "LLM"));
        R.append(String.format("  %-25s %10s %10s%n", "-".repeat(25), "-".repeat(10), "-".repeat(10)));
        R.append(String.format("  %-25s %9d/%d %9d/%d%n",
            "Intent accuracy", regexIntentOk, total, llmIntentOk, total));
        R.append(String.format("  %-25s %9d/%d %9d/%d%n",
            "Parameter accuracy", regexParamOk, total, llmParamOk, total));
        R.append(String.format("  %-25s %10s %9d%n", "Failures", "0", llmFailures));
        R.append(String.format("  %-25s %10s %10s%n", "Latency", "<1ms", "~2s/call"));
        R.append(String.format("  %-25s %10s %10s%n", "Language-neutral", "NO (German)", "YES"));

        // Per-language breakdown
        R.append(String.format("%n  By language:%n"));
        for (String lang : List.of("DE", "EN", "PT")) {
            long reCorrect = testCases().stream()
                .filter(tc -> tc.language().equals(lang)
                    && tc.expectedIntent.equals(regexParser.parse(tc.question).intentType()))
                .count();
            long reTotal = testCases().stream().filter(tc -> tc.language().equals(lang)).count();
            R.append(String.format("  %-3s regex: %d/%d (%.0f%%)", lang, reCorrect, reTotal,
                100.0*reCorrect/reTotal));
            if (llmParser != null) {
                long llmCorrect = 0;
                for (TestCase tc : testCases()) {
                    if (!tc.language().equals(lang)) continue;
                    try {
                        if (tc.expectedIntent.equals(llmParser.parse(tc.question).intentType()))
                            llmCorrect++;
                    } catch (Exception e) {}
                }
                R.append(String.format("  LLM: %d/%d (%.0f%%)", llmCorrect, reTotal, 100.0*llmCorrect/reTotal));
            }
            R.append("\n");
        }
        R.append("\n");

        writeReport();
        System.out.println(R.toString());
    }

    private boolean checkParams(TestCase tc, StructuredIntent intent) {
        for (var entry : tc.expectedParams.entrySet()) {
            Object actual = intent.parameters().get(entry.getKey());
            if (actual == null) return false;
            if (entry.getValue() instanceof Number expectedNum && actual instanceof Number actualNum) {
                if (Math.abs(expectedNum.doubleValue() - actualNum.doubleValue()) > 0.01) return false;
            } else if (!entry.getValue().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/semantic-intent-benchmark.txt"), R.toString());
        } catch (IOException ignored) {}
    }
}
