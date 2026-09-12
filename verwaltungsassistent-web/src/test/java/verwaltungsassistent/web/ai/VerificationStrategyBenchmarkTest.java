package verwaltungsassistent.web.ai;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.benchmark.VerifierModelBenchmark;
import reasoning.ai.benchmark.VerifierModelBenchmark.MultiEvidenceCase;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares PAIRWISE vs CLAIM_BATCH verification strategies.
 *
 * <p>Runs the full 27-case single-evidence corpus AND the 6 multi-evidence
 * cases through both strategies. Measures call count, latency, accuracy.
 *
 * <p>Output: {@code target/verification-strategy-comparison.txt}
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
        "platform.ai.ollama.embedding-model=nomic-embed-text",
        "platform.ai.ollama.embedding-dimension=768"
    }
)
@TestPropertySource(properties = {
    "platform.search.qdrant.enabled=false",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@DisplayName("Verification Strategy Comparison")
class VerificationStrategyBenchmarkTest {

    @Autowired
    private ClaimVerificationService claimVerifier;

    @Autowired
    private AiProviderProperties aiProperties;

    private static final List<VerifierModelBenchmark.Case> SINGLE = VerifierModelBenchmark.all();
    private static final List<MultiEvidenceCase> MULTI = VerifierModelBenchmark.multiEvidenceCases();
    private static final StringBuilder R = new StringBuilder();
    private static Instant startTime;

    @BeforeAll
    static void init() {
        startTime = Instant.now();
    }

    @BeforeEach
    void checkAvailable() {
        Assumptions.assumeTrue(claimVerifier != null, "ClaimVerificationService not available");
    }

    // ═══════════════════════════════════════════════════════════════
    // PAIRWISE BASELINE
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("PAIRWISE: Single-evidence corpus")
    void pairwiseSingleEvidence() {
        setStrategy("pairwise");
        CountingVerifier cv = new CountingVerifier(claimVerifier);
        StrategyResult result = runSingleCorpus(cv, "PAIRWISE");

        R.append("=".repeat(80)).append("\n");
        R.append("  STRATEGY COMPARISON: PAIRWISE vs CLAIM_BATCH\n");
        R.append("  Model: ").append(aiProperties.getOllama().getVerifierModel()).append("\n");
        R.append("=".repeat(80)).append("\n\n");
        R.append("## PAIRWISE — Single Evidence Corpus (27 cases)\n\n");
        appendResult(result);
        R.append(String.format("  LLM calls: %d (verify: %d, verifyBatch: %d)%n%n",
                cv.verifyCalls.get() + cv.verifyBatchCalls.get(),
                cv.verifyCalls.get(), cv.verifyBatchCalls.get()));

        assertTrue(result.accuracy() >= 80.0, "PAIRWISE accuracy too low: " + result.accuracy());
    }

    @Test
    @Order(2)
    @DisplayName("PAIRWISE: Multi-evidence cases")
    void pairwiseMultiEvidence() {
        setStrategy("pairwise");
        CountingVerifier cv = new CountingVerifier(claimVerifier);
        MultiResult result = runMultiCorpus(cv, "PAIRWISE");

        R.append("## PAIRWISE — Multi-Evidence Cases (6 cases, ").append(MULTI.size()).append(" claims)\n\n");
        appendMultiResult(result);
        R.append(String.format("  LLM calls: %d (verify: %d, verifyBatch: %d)%n",
                cv.verifyCalls.get() + cv.verifyBatchCalls.get(),
                cv.verifyCalls.get(), cv.verifyBatchCalls.get()));
        R.append(String.format("  Evidence items per claim: avg %.1f%n",
                MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).average().orElse(0)));
        R.append(String.format("  With pairwise: %d evidence items → %d LLM calls%n%n",
                MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).sum(),
                cv.verifyCalls.get()));

        // Multi-evidence per-category checks
        for (MultiEvidenceCase c : MULTI) {
            List<ClaimVerification> results = claimVerifier.verifyBatch(c.claim(), c.evidenceExcerpts());
            for (int i = 0; i < results.size(); i++) {
                Verdict expected = c.expectedVerdicts().get(i);
                Verdict actual = results.get(i).verdict();
                if (c.category().equals("conflicting")) {
                    // For conflicting cases: both verdicts matter — contradiction must not be hidden
                    boolean hasEntailment = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED);
                    boolean hasContradiction = results.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
                    R.append(String.format("  %s conflicting: entailment=%s contradiction=%s%n",
                            c.id(), hasEntailment ? "YES" : "NO", hasContradiction ? "YES" : "NO"));
                    assertTrue(hasEntailment, c.id() + ": conflicting case must preserve ENTAILED evidence");
                    assertTrue(hasContradiction, c.id() + ": conflicting case must preserve CONTRADICTED evidence");
                }
            }
        }
        R.append("\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // CLAIM_BATCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("CLAIM_BATCH: Single-evidence corpus")
    void claimBatchSingleEvidence() {
        setStrategy("claim_batch");
        CountingVerifier cv = new CountingVerifier(claimVerifier);
        StrategyResult result = runSingleCorpus(cv, "CLAIM_BATCH");

        R.append("## CLAIM_BATCH — Single Evidence Corpus (27 cases)\n\n");
        appendResult(result);
        R.append(String.format("  LLM calls: %d (verifyBatch: %d)%n%n",
                cv.verifyCalls.get() + cv.verifyBatchCalls.get(), cv.verifyBatchCalls.get()));

        assertTrue(result.accuracy() >= 80.0, "CLAIM_BATCH accuracy too low: " + result.accuracy());
    }

    @Test
    @Order(4)
    @DisplayName("CLAIM_BATCH: Multi-evidence cases")
    void claimBatchMultiEvidence() {
        setStrategy("claim_batch");
        CountingVerifier cv = new CountingVerifier(claimVerifier);
        MultiResult result = runMultiCorpus(cv, "CLAIM_BATCH");

        R.append("## CLAIM_BATCH — Multi-Evidence Cases (6 cases, ").append(MULTI.size()).append(" claims)\n\n");
        appendMultiResult(result);
        int totalEvidence = MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).sum();
        R.append(String.format("  LLM calls: %d (verifyBatch: %d)%n",
                cv.verifyCalls.get() + cv.verifyBatchCalls.get(), cv.verifyBatchCalls.get()));
        R.append(String.format("  Evidence items per claim: avg %.1f%n",
                MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).average().orElse(0)));
        R.append(String.format("  With claim_batch: %d evidence items → %d LLM calls%n",
                totalEvidence, cv.verifyBatchCalls.get()));
        R.append(String.format("  Call reduction: %d → %d (%.0f%%)%n%n",
                totalEvidence, cv.verifyBatchCalls.get(),
                100.0 * (totalEvidence - cv.verifyBatchCalls.get()) / totalEvidence));

        // Check conflicting evidence preservation
        for (MultiEvidenceCase c : MULTI) {
            List<ClaimVerification> results = claimVerifier.verifyBatch(c.claim(), c.evidenceExcerpts());
            for (int i = 0; i < results.size(); i++) {
                Verdict expected = c.expectedVerdicts().get(i);
                Verdict actual = results.get(i).verdict();
                if (c.category().equals("conflicting")) {
                    boolean hasEntailment = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED);
                    boolean hasContradiction = results.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
                    R.append(String.format("  %s conflicting: entailment=%s contradiction=%s%n",
                            c.id(), hasEntailment ? "YES" : "NO", hasContradiction ? "YES" : "NO"));
                    assertTrue(hasEntailment, c.id() + ": claim_batch must preserve ENTAILED evidence");
                    assertTrue(hasContradiction, c.id() + ": claim_batch must preserve CONTRADICTED evidence");
                }
            }
        }
        R.append("\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // Prompt-size effect test (1, 2, 4, 8 evidence items)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Prompt-size effect: 1/2/4/8 evidence items")
    void promptSizeEffect() {
        setStrategy("claim_batch");
        R.append("## Prompt-Size Effect on Claim-Batch Accuracy\n\n");

        String claim = "Employees must use VPN for remote access.";
        String entailedEvidence = "Employees must use VPN when accessing the municipal network remotely.";
        String fillerEvidence = "The office is open Monday through Friday from 8:00 to 18:00.";

        int[] sizes = {1, 2, 4, 8};
        for (int size : sizes) {
            List<String> evidence = new ArrayList<>();
            evidence.add(entailedEvidence);
            for (int i = 1; i < size; i++) {
                evidence.add(fillerEvidence);
            }

            long t0 = System.currentTimeMillis();
            List<ClaimVerification> results = claimVerifier.verifyBatch(claim, evidence);
            long elapsed = System.currentTimeMillis() - t0;

            boolean primaryCorrect = results.get(0).verdict() == Verdict.ENTAILED;
            long unknownCount = results.stream().filter(r -> r.verdict() == Verdict.UNKNOWN).count();
            boolean parseOk = results.size() == size; // all evidence items got a result

            R.append(String.format("  %d evidence: primary=%s unknowns=%d/%d parse=%s latency=%dms%n",
                    size, primaryCorrect ? "CORRECT" : "WRONG",
                    unknownCount, size, parseOk ? "OK" : "FAIL", elapsed));
        }
        R.append("\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // Comparison summary
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("PAIRWISE vs CLAIM_BATCH summary")
    void comparisonSummary() {
        setStrategy("pairwise");
        CountingVerifier pw = new CountingVerifier(claimVerifier);
        StrategyResult pwResult = runSingleCorpus(pw, "PAIRWISE");
        MultiResult pwMulti = runMultiCorpus(pw, "PAIRWISE");

        setStrategy("claim_batch");
        CountingVerifier cb = new CountingVerifier(claimVerifier);
        StrategyResult cbResult = runSingleCorpus(cb, "CLAIM_BATCH");
        MultiResult cbMulti = runMultiCorpus(cb, "CLAIM_BATCH");

        int totalEvidenceMulti = MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).sum();

        R.append("=".repeat(80)).append("\n");
        R.append("  FINAL COMPARISON\n");
        R.append("=".repeat(80)).append("\n\n");
        R.append(String.format("  %-30s %12s %12s%n", "Metric", "PAIRWISE", "CLAIM_BATCH"));
        R.append(String.format("  %-30s %12s %12s%n", "-".repeat(30), "-".repeat(12), "-".repeat(12)));
        R.append(String.format("  %-30s %12d %12d%n", "Single-evidence LLM calls",
                pw.verifyCalls.get() + pw.verifyBatchCalls.get(),
                cb.verifyCalls.get() + cb.verifyBatchCalls.get()));
        R.append(String.format("  %-30s %12d %12d%n", "Multi-evidence LLM calls",
                pwMulti.totalCalls(), cbMulti.totalCalls()));
        R.append(String.format("  %-30s %12d %12d%n", "Total LLM calls (all)",
                pw.verifyCalls.get() + pw.verifyBatchCalls.get() + pwMulti.totalCalls(),
                cb.verifyCalls.get() + cb.verifyBatchCalls.get() + cbMulti.totalCalls()));
        R.append(String.format("  %-30s %11.1f%% %11.1f%%%n", "Single-evidence accuracy",
                pwResult.accuracy(), cbResult.accuracy()));
        R.append(String.format("  %-30s %11.1f%% %11.1f%%%n", "Multi-evidence accuracy",
                pwMulti.accuracy(), cbMulti.accuracy()));
        R.append(String.format("  %-30s %9d ms %9d ms%n", "Single-evidence total time",
                pwResult.totalMs(), cbResult.totalMs()));
        R.append(String.format("  %-30s %9d ms %9d ms%n", "Multi-evidence total time",
                pwMulti.totalMs(), cbMulti.totalMs()));

        // Call reduction calculation
        int pairwiseTotal = pw.verifyCalls.get() + pw.verifyBatchCalls.get() + pwMulti.totalCalls();
        int batchTotal = cb.verifyCalls.get() + cb.verifyBatchCalls.get() + cbMulti.totalCalls();
        double reduction = 100.0 * (pairwiseTotal - batchTotal) / Math.max(1, pairwiseTotal);
        R.append(String.format("  %-30s %12s %11.0f%%%n", "Call reduction", "-", reduction));

        R.append(String.format("%n  Multi-evidence: %d claims × %.1f avg evidence = %d total items%n",
                MULTI.size(),
                MULTI.stream().mapToInt(c -> c.evidenceExcerpts().size()).average().orElse(0),
                totalEvidenceMulti));
        R.append(String.format("  Pairwise: %d items → %d calls (1 per item)%n",
                totalEvidenceMulti, totalEvidenceMulti));
        R.append(String.format("  Claim-batch: %d items → %d calls (1 per claim)%n",
                totalEvidenceMulti, cbMulti.totalCalls()));
        R.append(String.format("  Savings: %d calls eliminated%n",
                totalEvidenceMulti - cbMulti.totalCalls()));

        R.append("\n").append("=".repeat(80)).append("\n");
        long elapsedSec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        R.append("  Total benchmark duration: ").append(elapsedSec).append("s\n");
        R.append("=".repeat(80)).append("\n");

        writeReport();
        System.out.println(R.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void setStrategy(String strategy) {
        aiProperties.getOllama().setVerificationStrategy(strategy);
    }

    private StrategyResult runSingleCorpus(CountingVerifier cv, String label) {
        int correct = 0;
        long totalMs = 0;
        Map<String, int[]> catStats = new LinkedHashMap<>();

        for (VerifierModelBenchmark.Case c : SINGLE) {
            long t0 = System.currentTimeMillis();
            ClaimVerification result;
            try {
                result = cv.verify(c.claim(), c.evidence());
            } catch (Exception e) {
                continue;
            }
            long elapsed = System.currentTimeMillis() - t0;
            totalMs += elapsed;

            if (result.verdict() == c.expected()) correct++;
            catStats.computeIfAbsent(c.category(), k -> new int[2])[result.verdict() == c.expected() ? 0 : 1]++;
        }

        return new StrategyResult(label, correct, SINGLE.size(), totalMs, catStats);
    }

    private MultiResult runMultiCorpus(CountingVerifier cv, String label) {
        int correct = 0;
        int total = 0;
        int totalCalls = 0;
        long totalMs = 0;

        for (MultiEvidenceCase c : MULTI) {
            long t0 = System.currentTimeMillis();
            List<ClaimVerification> results;
            try {
                results = cv.verifyBatch(c.claim(), c.evidenceExcerpts());
                totalCalls++;
            } catch (Exception e) {
                continue;
            }
            totalMs += System.currentTimeMillis() - t0;

            for (int i = 0; i < results.size() && i < c.expectedVerdicts().size(); i++) {
                if (results.get(i).verdict() == c.expectedVerdicts().get(i)) correct++;
                total++;
            }
        }

        return new MultiResult(label, correct, total, totalMs, totalCalls);
    }

    private void appendResult(StrategyResult r) {
        R.append(String.format("  Accuracy: %d/%d (%.1f%%)%n", r.correct(), r.total(), r.accuracy()));
        R.append(String.format("  Total time: %,d ms%n", r.totalMs()));
        R.append(String.format("  Avg latency: %,.0f ms/call%n",
                r.totalMs() / (double) Math.max(1, r.total())));
        for (var e : r.categoryStats().entrySet()) {
            int[] s = e.getValue();
            R.append(String.format("    %-28s %d/%d (%.0f%%)%n",
                    e.getKey(), s[0], s[0] + s[1],
                    100.0 * s[0] / Math.max(1, s[0] + s[1])));
        }
        R.append("\n");
    }

    private void appendMultiResult(MultiResult r) {
        R.append(String.format("  Per-evidence accuracy: %d/%d (%.1f%%)%n",
                r.correct(), r.total(), r.accuracy()));
        R.append(String.format("  Total time: %,d ms%n", r.totalMs()));
        R.append(String.format("  Avg latency: %,.0f ms/claim%n",
                r.totalMs() / (double) Math.max(1, r.totalCalls())));
        R.append(String.format("  Total LLM calls: %d%n", r.totalCalls()));
    }

    private void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/verification-strategy-comparison.txt"), R.toString());
        } catch (IOException e) {
            System.err.println("Failed to write report: " + e.getMessage());
        }
        System.out.println(R.toString());
    }

    // ── Data classes ──

    record StrategyResult(String label, int correct, int total, long totalMs,
                          Map<String, int[]> categoryStats) {
        double accuracy() { return 100.0 * correct / Math.max(1, total); }
    }

    record MultiResult(String label, int correct, int total, long totalMs, int totalCalls) {
        double accuracy() { return 100.0 * correct / Math.max(1, total); }
    }

    /**
     * Wraps a ClaimVerificationService to count LLM calls.
     * verifyBatch is the method used by DefaultGroundingService.
     */
    static class CountingVerifier implements ClaimVerificationService {
        final ClaimVerificationService delegate;
        final AtomicInteger verifyCalls = new AtomicInteger();
        final AtomicInteger verifyBatchCalls = new AtomicInteger();

        CountingVerifier(ClaimVerificationService delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClaimVerification verify(String claim, String evidenceExcerpt) {
            verifyCalls.incrementAndGet();
            return delegate.verify(claim, evidenceExcerpt);
        }

        @Override
        public List<ClaimVerification> verifyBatch(String claim, List<String> evidenceExcerpts) {
            verifyBatchCalls.incrementAndGet();
            return delegate.verifyBatch(claim, evidenceExcerpts);
        }
    }
}
