package verwaltungsassistent.web.ai;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.benchmark.VerifierModelBenchmark;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.ClaimVerification.Verdict;
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
 * Runs the verifier benchmark corpus against a live Ollama instance.
 *
 * <p>Configure the verifier model via spring properties:
 * <pre>
 *   platform.ai.ollama.verifier-model=qwen2.5:14b
 *   platform.ai.ollama.verifier-model=qwen2.5:7b
 * </pre>
 *
 * <p>Produces {@code target/verifier-benchmark-report.txt}.
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
        "platform.ai.ollama.embedding-model=nomic-embed-text",
        "platform.ai.ollama.embedding-dimension=768"
    }
)
@TestPropertySource(properties = {
    "platform.search.qdrant.enabled=false",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Verifier Model Benchmark")
class VerifierBenchmarkTest {

    @Autowired(required = false)
    private ClaimVerificationService claimVerifier;

    @Autowired(required = false)
    private AiProviderProperties aiProperties;

    private static final List<VerifierModelBenchmark.Case> ALL = VerifierModelBenchmark.all();
    private static final Map<String, TestResult> results = new LinkedHashMap<>();
    private static Instant startTime;
    private static long totalMs;

    @BeforeAll
    static void init() {
        startTime = Instant.now();
    }

    @BeforeEach
    void checkAvailability() {
        Assumptions.assumeTrue(claimVerifier != null,
                "ClaimVerificationService not available — skipping benchmark");
        Assumptions.assumeTrue(aiProperties != null,
                "AiProviderProperties not available — skipping benchmark");
    }

    @Test
    @Order(1)
    @DisplayName("Benchmark: all verification cases")
    void runAllBenchmarkCases() {
        String model = aiProperties.getOllama().getVerifierModel();
        System.out.println("=".repeat(80));
        System.out.println("  VERIFIER BENCHMARK — Model: " + model);
        System.out.println("  Corpus: " + ALL.size() + " cases");
        System.out.println("=".repeat(80));
        System.out.println();
        System.out.printf("%-12s %-24s %-3s %-12s %-12s %-8s %-6s%n",
                "ID", "CATEGORY", "LNG", "EXPECTED", "ACTUAL", "CONF", "MATCH");
        System.out.println("-".repeat(80));

        int correct = 0;
        int total = 0;
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        Map<String, int[]> langStats = new LinkedHashMap<>();

        for (VerifierModelBenchmark.Case c : ALL) {
            long t0 = System.currentTimeMillis();
            ClaimVerification result;
            try {
                result = claimVerifier.verify(c.claim(), c.evidence());
            } catch (Exception e) {
                System.err.printf("ERROR %s: %s%n", c.id(), e.getMessage());
                results.put(c.id(), new TestResult(c, null, 0, 0, e.getMessage()));
                continue;
            }
            long elapsed = System.currentTimeMillis() - t0;
            totalMs += elapsed;

            boolean match = result.verdict() == c.expected();
            if (match) correct++;
            total++;

            results.put(c.id(), new TestResult(c, result.verdict(), result.confidence(), elapsed, result.reason()));

            String flag = match ? "✓" : "✗";
            System.out.printf("%-12s %-24s %-3s %-12s %-12s %-6.2f  %-6s%n",
                    c.id(), c.category(), c.language(),
                    c.expected(), result.verdict(),
                    result.confidence(), flag);

            categoryStats.computeIfAbsent(c.category(), k -> new int[2])[match ? 0 : 1]++;
            langStats.computeIfAbsent(c.language(), k -> new int[2])[match ? 0 : 1]++;
        }

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("  RESULTS SUMMARY — Model: " + model);
        System.out.println("=".repeat(80));
        double pct = 100.0 * correct / total;
        System.out.printf("  Overall accuracy:     %d/%d (%.1f%%)%n", correct, total, pct);
        System.out.printf("  Total calls:          %d%n", total);
        System.out.printf("  Total time:           %,d ms%n", totalMs);
        System.out.printf("  Avg latency per call: %,.0f ms%n", totalMs / (double) Math.max(1, total));
        System.out.println();

        System.out.println("  By category:");
        for (var entry : categoryStats.entrySet()) {
            int[] s = entry.getValue();
            int catTotal = s[0] + s[1];
            System.out.printf("    %-28s %d/%d (%.0f%%)%n",
                    entry.getKey(), s[0], catTotal, 100.0 * s[0] / catTotal);
        }
        System.out.println();

        System.out.println("  By language:");
        for (var entry : langStats.entrySet()) {
            int[] s = entry.getValue();
            int langTotal = s[0] + s[1];
            System.out.printf("    %-3s                    %d/%d (%.0f%%)%n",
                    entry.getKey(), s[0], langTotal, 100.0 * s[0] / langTotal);
        }

        // Error analysis
        System.out.println();
        System.out.println("  Error analysis:");
        results.values().stream()
                .filter(r -> !r.isMatch())
                .forEach(r -> {
                    System.out.printf("    %s: expected=%s actual=%s conf=%.2f%n",
                            r.caseData().id(), r.caseData().expected(), r.actualVerdict(), r.confidence());
                    System.out.printf("      Evidence: %s%n", truncate(r.caseData().evidence(), 120));
                    System.out.printf("      Claim:    %s%n", truncate(r.caseData().claim(), 120));
                    System.out.printf("      Reason:   %s%n", truncate(r.reason(), 150));
                });

        writeReport(model);
        assertTrue(pct >= 0.0, "Benchmark completed — see target/verifier-benchmark-report.txt");
    }

    @Test
    @Order(2)
    @DisplayName("Entailment accuracy >= 50%")
    void entailmentAccuracy() {
        assertCategoryAccuracy("entailment", 50.0);
    }

    @Test
    @Order(3)
    @DisplayName("Contradiction accuracy >= 50%")
    void contradictionAccuracy() {
        assertCategoryAccuracy("contradiction", 50.0);
    }

    @Test
    @Order(4)
    @DisplayName("Unknown accuracy >= 50%")
    void unknownAccuracy() {
        assertCategoryAccuracy("unknown", 50.0);
    }

    @Test
    @Order(5)
    @DisplayName("Numerical accuracy >= 50%")
    void numericalAccuracy() {
        assertCategoryAccuracy("numerical_contradiction", 50.0);
    }

    @Test
    @Order(6)
    @DisplayName("German accuracy >= 50%")
    void germanAccuracy() {
        long correct = results.values().stream()
                .filter(r -> r.caseData().language().equals("DE") && r.isMatch())
                .count();
        long total = results.values().stream()
                .filter(r -> r.caseData().language().equals("DE"))
                .count();
        if (total == 0) return;
        double pct = 100.0 * correct / total;
        assertTrue(pct >= 50.0,
                "German accuracy " + String.format("%.1f", pct) + "% below 50% threshold");
    }

    private void assertCategoryAccuracy(String category, double threshold) {
        long correct = results.values().stream()
                .filter(r -> r.caseData().category().equals(category) && r.isMatch())
                .count();
        long total = results.values().stream()
                .filter(r -> r.caseData().category().equals(category))
                .count();
        if (total == 0) return;
        double pct = 100.0 * correct / total;
        assertTrue(pct >= threshold,
                category + " accuracy " + String.format("%.1f", pct) + "% below " + threshold + "% threshold");
    }

    @AfterAll
    static void report() {
        long elapsedSec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        System.out.println();
        System.out.println("  Benchmark duration: " + elapsedSec + "s");
        System.out.println("=".repeat(80));
    }

    private static void writeReport(String model) {
        try {
            Files.createDirectories(Path.of("target"));
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(80)).append("\n");
            sb.append("  VERIFIER MODEL BENCHMARK REPORT\n");
            sb.append("  Timestamp: ").append(Instant.now()).append("\n");
            sb.append("  Model: ").append(model).append("\n");
            sb.append("=".repeat(80)).append("\n\n");
            sb.append(String.format("%-12s %-24s %-3s %-12s %-12s %-8s %-6s %s%n",
                    "ID", "CATEGORY", "LNG", "EXPECTED", "ACTUAL", "CONF", "MATCH", "REASON"));
            sb.append("-".repeat(80)).append("\n");
            for (var r : results.values()) {
                sb.append(String.format("%-12s %-24s %-3s %-12s %-12s %-6.2f  %-6s %s%n",
                        r.caseData().id(), r.caseData().category(), r.caseData().language(),
                        r.caseData().expected(), r.actualVerdict(),
                        r.confidence(), r.isMatch() ? "PASS" : "FAIL",
                        truncate(r.reason(), 80)));
            }
            sb.append("\n");
            long correct = results.values().stream().filter(TestResult::isMatch).count();
            sb.append("Overall: ").append(correct).append("/").append(results.size())
                    .append(" (").append(String.format("%.1f", 100.0 * correct / results.size())).append("%)\n");
            Files.writeString(Path.of("target/verifier-benchmark-report.txt"), sb.toString());
        } catch (IOException e) {
            System.err.println("Failed to write benchmark report: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    record TestResult(VerifierModelBenchmark.Case caseData, Verdict actualVerdict,
                      double confidence, long latencyMs, String reason) {
        boolean isMatch() { return actualVerdict == caseData.expected(); }
    }
}
