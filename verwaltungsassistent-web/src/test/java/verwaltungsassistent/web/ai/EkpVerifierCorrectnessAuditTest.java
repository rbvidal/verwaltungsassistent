package verwaltungsassistent.web.ai;

import reasoning.ai.api.ClaimVerificationService;
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
 * Verwaltungsassistent Reasoning Correctness Audit — Verifier-only tests.
 *
 * <p>Proves each Verwaltungsassistent reasoning capability using the independent verifier
 * with controlled evidence. No infrastructure dependencies beyond Ollama.
 * No heuristics, keyword rules, or embedding shortcuts.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Verwaltungsassistent Verifier Correctness Audit")
class EkpVerifierCorrectnessAuditTest {

    @Autowired(required = false)
    private ClaimVerificationService verifier;

    @Autowired(required = false)
    private AiProviderProperties aiProperties;

    private static final StringBuilder R = new StringBuilder();
    private static Instant startTime;
    static final Map<String, String> F = new LinkedHashMap<>();

    // Controlled evidence corpus
    static final String E_VPN =
        "Remote employees must use VPN and two-factor authentication when accessing " +
        "the municipal network. Access without VPN is strictly prohibited.";

    static final String DOC_A = "Employees working remotely must use VPN to connect to " +
        "the municipal network. The VPN client is provided by the IT department.";

    static final String DOC_B = "Two-factor authentication is mandatory for all remote " +
        "access. Employees must use a hardware token or the approved authenticator app.";

    static final String DOC_C = "The IT department manages all software licenses and " +
        "renewals. Software requests must be submitted through the IT service portal.";

    static final String DOC_D = "Remote employees are explicitly NOT required to use VPN. " +
        "Direct connections to the municipal network are permitted WITHOUT any VPN or " +
        "authentication requirements. Two-factor authentication is NOT mandatory.";

    static final String DE_E = "Mitarbeiter im Homeoffice müssen für den Zugriff auf das " +
        "Verwaltungsnetz ein VPN und eine Zwei-Faktor-Authentifizierung verwenden.";

    @BeforeAll static void init() {
        startTime = Instant.now();
        R.append("=".repeat(78)).append("\n");
        R.append("  Verwaltungsassistent VERIFIER CORRECTNESS AUDIT\n");
        R.append("  ").append(Instant.now()).append("\n");
        R.append("=".repeat(78)).append("\n\n");
    }

    @BeforeEach void check() {
        Assumptions.assumeTrue(verifier != null, "ClaimVerificationService not available");
    }

    // ════════════════════════════════════════════════════════
    // 1. CLAIM MUTATION — same evidence, different claims
    // ════════════════════════════════════════════════════════

    @Test @Order(1) @DisplayName("1a. Claim mutation: ENTAILED")
    void claimEntailed() {
        ClaimVerification r = verifier.verify(
            "Remote employees must use VPN and two-factor authentication.", E_VPN);
        logCase("1a. ENTAILED", "Remote employees must use VPN and 2FA.", E_VPN,
                Verdict.ENTAILED, r);
        assertEquals(Verdict.ENTAILED, r.verdict());
        F.put("claim_mutation_entailed", "PROVEN");
    }

    @Test @Order(2) @DisplayName("1b. Claim mutation: CONTRADICTED")
    void claimContradicted() {
        ClaimVerification r = verifier.verify(
            "Remote employees do not need VPN and are exempt from authentication.", E_VPN);
        logCase("1b. CONTRADICTED", "do not need VPN + exempt", E_VPN,
                Verdict.CONTRADICTED, r);
        assertEquals(Verdict.CONTRADICTED, r.verdict());
        F.put("claim_mutation_contradicted", "PROVEN");
    }

    @Test @Order(3) @DisplayName("1c. Claim mutation: UNKNOWN")
    void claimUnknown() {
        ClaimVerification r = verifier.verify(
            "The office cafeteria serves vegetarian meals.", E_VPN);
        logCase("1c. UNKNOWN", "cafeteria meals", E_VPN, Verdict.UNKNOWN, r);
        assertEquals(Verdict.UNKNOWN, r.verdict());
        F.put("claim_mutation_unknown", "PROVEN");
    }

    @Test @Order(4) @DisplayName("1d. Claim mutation: partial match → not ENTAILED")
    void claimPartialNotEntailed() {
        ClaimVerification r = verifier.verify(
            "Remote employees may work without any authentication.", E_VPN);
        R.append(String.format("  Verdict: %s (%.2f) — %s%n%n",
                r.verdict(), r.confidence(), r.reason()));
        assertNotEquals(Verdict.ENTAILED, r.verdict(),
            "Weakened 'may work without authentication' must not be ENTAILED");
        F.put("claim_mutation_partial", "PROVEN");
    }

    @Test @Order(5) @DisplayName("1e. German entailment")
    void claimGermanEntailed() {
        ClaimVerification r = verifier.verify(
            "Mitarbeiter im Homeoffice müssen VPN und Zwei-Faktor-Authentifizierung verwenden.",
            DE_E);
        logCase("1e. German ENTAILED", "Mitarbeiter müssen VPN+2FA", DE_E,
                Verdict.ENTAILED, r);
        assertEquals(Verdict.ENTAILED, r.verdict());
        F.put("german_entailment", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 2. MULTI-DOCUMENT REASONING
    // ════════════════════════════════════════════════════════

    @Test @Order(6) @DisplayName("2. Multi-document: A+B entailed, C unknown, D contradicted")
    void multiDocumentReasoning() {
        String claim = "Remote employees must use VPN and two-factor authentication.";
        List<String> ev = List.of(DOC_A, DOC_B, DOC_C, DOC_D);
        List<Verdict> expected = List.of(
            Verdict.ENTAILED, Verdict.ENTAILED, Verdict.UNKNOWN, Verdict.CONTRADICTED);

        R.append("## 2. Multi-Document Reasoning\n");
        R.append(String.format("  Claim: %s%n%n", claim));

        List<ClaimVerification> results = verifier.verifyBatch(claim, ev);
        assertEquals(4, results.size());

        int correct = 0;
        for (int i = 0; i < results.size(); i++) {
            boolean match = results.get(i).verdict() == expected.get(i);
            if (match) correct++;
            R.append(String.format("  DOC_%c: %s (exp=%s) %.2f %s — %s%n",
                (char)('A'+i), results.get(i).verdict(), expected.get(i),
                results.get(i).confidence(), match ? "✓" : "✗",
                truncate(results.get(i).reason(), 80)));
        }

        R.append(String.format("  Accuracy: %d/4%n", correct));

        // A and B must be ENTAILED
        assertEquals(Verdict.ENTAILED, results.get(0).verdict(),
            "A (VPN) must ENTAIL VPN+2FA claim");
        assertEquals(Verdict.ENTAILED, results.get(1).verdict(),
            "B (2FA) must ENTAIL VPN+2FA claim");

        // C must NOT be support
        assertNotEquals(Verdict.ENTAILED, results.get(2).verdict(),
            "C (software licenses) must NOT entail VPN+2FA claim");

        // D must CONTRADICT
        assertEquals(Verdict.CONTRADICTED, results.get(3).verdict(),
            "D (VPN not needed) must CONTRADICT VPN+2FA claim");

        // Contradiction must be preserved alongside support
        boolean hasEnt = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED);
        boolean hasCon = results.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
        assertTrue(hasEnt && hasCon,
            "Must preserve both ENTAILED and CONTRADICTED — conflict visible");

        R.append(String.format("  Conflict preserved: %s%n%n", hasEnt && hasCon ? "YES" : "NO"));
        F.put("multi_document_reasoning", correct >= 3 ? "PROVEN" : "PARTIAL");
        F.put("multi_doc_conflict_preserved", (hasEnt && hasCon) ? "PROVEN" : "NOT PROVEN");
    }

    @Test @Order(7) @DisplayName("2b. German conflicting evidence")
    void multiDocGermanConflict() {
        String claim = "Mitarbeiter müssen für Fernzugriff ein VPN verwenden.";
        List<String> ev = List.of(
            "Mitarbeiter müssen für Fernzugriff auf das Verwaltungsnetz ein VPN verwenden.",
            "Für Fernzugriff ist kein VPN erforderlich. Direkte Verbindungen sind ohne VPN erlaubt.");

        List<ClaimVerification> results = verifier.verifyBatch(claim, ev);
        assertEquals(2, results.size());
        assertEquals(Verdict.ENTAILED, results.get(0).verdict(),
            "German evidence must ENTAIL matching claim");
        assertEquals(Verdict.CONTRADICTED, results.get(1).verdict(),
            "German evidence must CONTRADICT opposite claim");

        boolean both = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED)
                && results.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
        R.append(String.format("  German conflict preserved: %s%n%n", both ? "YES" : "NO"));
        assertTrue(both);
        F.put("multi_doc_german_conflict", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 3. GROUNDING SEMANTICS
    // ════════════════════════════════════════════════════════

    @Test @Order(8) @DisplayName("3a. All entailed → grounded=true")
    void groundingAllEntailed() {
        String claim = "Remote employees must use VPN for access.";
        List<String> ev = List.of(
            "Remote employees must use VPN when accessing the network remotely.",
            "Remote employees are required to use a VPN for access.");

        List<ClaimVerification> results = verifier.verifyBatch(claim, ev);
        boolean all = results.stream().allMatch(r -> r.verdict() == Verdict.ENTAILED);
        for (int i = 0; i < results.size(); i++) {
            R.append(String.format("  Evidence %d: %s (%.2f) — %s%n",
                    i+1, results.get(i).verdict(), results.get(i).confidence(),
                    truncate(results.get(i).reason(), 80)));
        }
        R.append(String.format("  All ENTAILED: %s → grounded=%s%n%n", all, all));
        assertTrue(all, "All evidence must ENTAIL the claim for grounded=true");
        F.put("grounding_all_entailed", "PROVEN");
    }

    @Test @Order(9) @DisplayName("3b. All unknown → grounded=false")
    void groundingAllUnknown() {
        String claim = "Employees must use two-factor authentication.";
        List<String> ev = List.of(
            "Employees must use VPN for remote access.",
            "The office cafeteria serves lunch from 11:30 to 14:00.");

        List<ClaimVerification> results = verifier.verifyBatch(claim, ev);
        boolean any = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED);
        R.append(String.format("  Any ENTAILED: %s → grounded=%s%n%n", any, !any));
        assertFalse(any, "No evidence supports 2FA — must not be falsely entailed");
        F.put("grounding_all_unknown", "PROVEN");
    }

    @Test @Order(10) @DisplayName("3c. Contradicted → grounded=false")
    void groundingContradicted() {
        ClaimVerification r = verifier.verify(
            "Remote employees do not need VPN.",
            "Remote employees must use VPN when accessing the municipal network.");
        R.append(String.format("  Verdict: %s (%.2f) → grounded=%s%n%n",
                r.verdict(), r.confidence(), r.verdict() == Verdict.CONTRADICTED ? "false" : "ERROR"));
        assertEquals(Verdict.CONTRADICTED, r.verdict());
        F.put("grounding_contradicted", "PROVEN");
    }

    @Test @Order(11) @DisplayName("3d. Conflicting evidence → conflict visible")
    void groundingConflictingVisible() {
        String claim = "The travel allowance is 24 euros.";
        List<String> ev = List.of(
            "The allowance for a full day is 24 euros.",
            "The daily travel allowance has been reduced to 12 euros effective January 1st.");

        List<ClaimVerification> results = verifier.verifyBatch(claim, ev);
        boolean hasE = results.stream().anyMatch(r -> r.verdict() == Verdict.ENTAILED);
        boolean hasC = results.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
        R.append(String.format("  ENTAILED=%s CONTRADICTED=%s → conflict %s%n%n",
                hasE, hasC, (hasE && hasC) ? "VISIBLE" : "HIDDEN"));
        assertTrue(hasE && hasC, "Conflict must be preserved, not hidden");
        F.put("grounding_conflict_visible", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 4. EVIDENCE PROVENANCE
    // ════════════════════════════════════════════════════════

    @Test @Order(12) @DisplayName("4a. Per-evidence provenance traceable")
    void evidenceProvenanceTrace() {
        String claim = "Remote employees must use VPN and two-factor authentication.";
        Map<String, String> labeled = new LinkedHashMap<>();
        labeled.put("E1", DOC_A);
        labeled.put("E2", DOC_B);
        labeled.put("E3", DOC_C);

        List<String> excerpts = new ArrayList<>(labeled.values());
        List<ClaimVerification> results = verifier.verifyBatch(claim, excerpts);

        R.append("## 4a. Evidence Provenance\n");
        int idx = 0;
        Map<String, Verdict> trace = new LinkedHashMap<>();
        for (var e : labeled.entrySet()) {
            Verdict v = results.get(idx).verdict();
            trace.put(e.getKey(), v);
            R.append(String.format("  %s → %s (%.2f)%n", e.getKey(), v, results.get(idx).confidence()));
            idx++;
        }

        assertEquals(Verdict.ENTAILED, trace.get("E1"));
        assertEquals(Verdict.ENTAILED, trace.get("E2"));
        assertNotEquals(Verdict.ENTAILED, trace.get("E3"));
        R.append(String.format("  Trace: E1=%s E2=%s E3=%s — PROVEN%n%n",
                trace.get("E1"), trace.get("E2"), trace.get("E3")));
        F.put("evidence_provenance_trace", "PROVEN");
    }

    @Test @Order(13) @DisplayName("4b. SourceCitation lacks retrieval provenance")
    void retrievalProvenanceGap() {
        R.append("## 4b. Retrieval Provenance Gap\n");
        R.append("  SourceCitation has NO provider/retrieval-path field.\n");
        R.append("  Keyword/vector/graph origin is LOST at citation construction.\n");
        R.append("  DefaultRetrievalAugmentationService hardcodes SourceType.FACTUAL.\n\n");
        F.put("retrieval_provenance", "NOT PROVEN — SourceCitation lacks provider field");
    }

    // ════════════════════════════════════════════════════════
    // 5. COVERAGE SEMANTICS
    // ════════════════════════════════════════════════════════

    @Test @Order(14) @DisplayName("5. Coverage is structural, not semantic")
    void coverageStructuralNotSemantic() {
        R.append("## 5. Coverage Semantics\n");
        R.append("  SourceDossier.coverageScore = coveredRoles / 4\n");
        R.append("  Roles matched by KEYWORD substring, not semantic analysis.\n");
        R.append("  No per-claim semantic coverage metric exists.\n");
        R.append("  Verifier produces ENTAILED/CONTRADICTED/UNKNOWN per claim\n");
        R.append("  but these are never aggregated into claimCoverage.\n\n");
        F.put("semantic_claim_coverage", "NOT PROVEN — coverage is structural role ratio");
    }

    // ════════════════════════════════════════════════════════
    // 6. INDEPENDENT VERIFICATION
    // ════════════════════════════════════════════════════════

    @Test @Order(15) @DisplayName("6. Verifier ≠ Generator — independently configurable")
    void independentVerifierConfig() {
        String gen = aiProperties.getOllama().getChatModel();
        String ver = aiProperties.getOllama().getVerifierModel();
        R.append(String.format("  Generator: %s | Verifier: %s | Independent: %s%n%n",
                gen, ver, !gen.equals(ver) ? "YES" : "SAME MODEL"));
        assertNotEquals(gen, ver, "Generator and verifier must be different models");
        F.put("independent_verification", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 7. NUMERICAL ACCURACY
    // ════════════════════════════════════════════════════════

    @Test @Order(16) @DisplayName("7. Numerical reasoning")
    void numericalReasoning() {
        ClaimVerification r1 = verifier.verify(
            "The full-day allowance is 24 euros.",
            "The allowance for a full day is 24 euros.");
        assertEquals(Verdict.ENTAILED, r1.verdict());

        ClaimVerification r2 = verifier.verify(
            "The allowance is 12 euros.",
            "The allowance is 24 euros.");
        assertEquals(Verdict.CONTRADICTED, r2.verdict());

        R.append(String.format("  Same number: %s | Different: %s — PROVEN%n%n",
                r1.verdict(), r2.verdict()));
        F.put("numerical_accuracy", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 8. CLAIM-BATCH EQUIVALENCE
    // ════════════════════════════════════════════════════════

    @Test @Order(17) @DisplayName("8. Claim-batch ≡ pairwise verdicts")
    void claimBatchEquivalence() {
        String claim = "Employees must use VPN for remote access.";
        List<String> ev = List.of(
            "Employees must use VPN when accessing the municipal network remotely.",
            "Employees do not need VPN for remote access.");

        List<ClaimVerification> batch = verifier.verifyBatch(claim, ev);
        List<ClaimVerification> pairwise = List.of(
            verifier.verify(claim, ev.get(0)),
            verifier.verify(claim, ev.get(1)));

        boolean eq = batch.get(0).verdict() == pairwise.get(0).verdict()
                  && batch.get(1).verdict() == pairwise.get(1).verdict();
        R.append(String.format("  Batch: %s/%s | Pairwise: %s/%s | Equivalent: %s%n%n",
            batch.get(0).verdict(), batch.get(1).verdict(),
            pairwise.get(0).verdict(), pairwise.get(1).verdict(), eq ? "YES" : "NO"));
        assertTrue(eq);
        F.put("claim_batch_equivalence", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 9. DOMAIN NEUTRALITY
    // ════════════════════════════════════════════════════════

    @Test @Order(18) @DisplayName("9. Domain neutrality — works on arbitrary domains")
    void domainNeutrality() {
        // Medical domain
        ClaimVerification r1 = verifier.verify(
            "Patients must fast for 12 hours before surgery.",
            "Pre-operative fasting is required for 12 hours prior to all surgical procedures.");
        assertEquals(Verdict.ENTAILED, r1.verdict(), "Must work on medical text");

        // Legal domain
        ClaimVerification r2 = verifier.verify(
            "The contract requires 30 days written notice for termination.",
            "Either party may terminate this agreement upon thirty (30) days prior written notice.");
        assertEquals(Verdict.ENTAILED, r2.verdict(), "Must work on legal text");

        R.append(String.format("  Medical: %s | Legal: %s — PROVEN%n%n",
                r1.verdict(), r2.verdict()));
        F.put("domain_neutrality", "PROVEN");
    }

    // ════════════════════════════════════════════════════════
    // 10. INFRASTRUCTURE GAPS DOCUMENTED
    // ════════════════════════════════════════════════════════

    @Test @Order(19) @DisplayName("10. Infrastructure-dependent capabilities")
    void infrastructureGaps() {
        R.append("## 10. Infrastructure-Dependent (not testable now)\n");
        R.append("  PostgreSQL/Qdrant/Neo4j unavailable. These require live infra:\n\n");
        F.put("graph_unique_contribution", "NOT PROVEN — Neo4j unavailable");
        F.put("repair_0to1_support", "NOT PROVEN — full pipeline unavailable");
        F.put("reranking_benefit", "NOT PROVEN — retrieval unavailable");
        F.put("full_e2e_grounding", "NOT PROVEN — PostgreSQL/Qdrant unavailable");
    }

    // ════════════════════════════════════════════════════════
    // FINAL REPORT
    // ════════════════════════════════════════════════════════

    @Test @Order(99) @DisplayName("FINAL: Correctness audit report")
    void finalReport() {
        R.append("=".repeat(78)).append("\n");
        R.append("  FINAL CORRECTNESS AUDIT\n");
        R.append("=".repeat(78)).append("\n\n");
        R.append(String.format("  %-42s %s%n", "PROPERTY", "STATUS"));
        R.append(String.format("  %-42s %s%n", "-".repeat(42), "-".repeat(20)));

        String[] props = {
            "Independent verification",
            "Contradiction detection",
            "Unknown detection",
            "Claim mutation (ENTAILED/CONTRA/UNK)",
            "Multi-document reasoning",
            "Multi-doc conflict preserved",
            "Claim→evidence provenance trace",
            "Retrieval provenance (keyword/vec/graph)",
            "Semantic claim coverage",
            "Grounding: all entailed",
            "Grounding: all unknown",
            "Grounding: contradicted",
            "Grounding: conflict visible",
            "Numerical accuracy",
            "Claim-batch equivalence",
            "Domain neutrality",
            "German entailment",
            "Graph unique contribution",
            "Reranking benefit",
            "Repair: 0→1 support improvement",
            "Full E2E grounding",
        };

        // Direct mapping from user-facing property names to internal keys
        Map<String, String> pmap = new LinkedHashMap<>();
        pmap.put("Independent verification", "independent_verification");
        pmap.put("Contradiction detection", "claim_mutation_contradicted");
        pmap.put("Unknown detection", "claim_mutation_unknown");
        pmap.put("Claim mutation (ENTAILED/CONTRA/UNK)", "claim_mutation_entailed");
        pmap.put("Multi-document reasoning", "multi_document_reasoning");
        pmap.put("Multi-doc conflict preserved", "multi_doc_conflict_preserved");
        pmap.put("Claim→evidence provenance trace", "evidence_provenance_trace");
        pmap.put("Retrieval provenance (keyword/vec/graph)", "retrieval_provenance");
        pmap.put("Semantic claim coverage", "semantic_claim_coverage");
        pmap.put("Grounding: all entailed", "grounding_all_entailed");
        pmap.put("Grounding: all unknown", "grounding_all_unknown");
        pmap.put("Grounding: contradicted", "grounding_contradicted");
        pmap.put("Grounding: conflict visible", "grounding_conflict_visible");
        pmap.put("Numerical accuracy", "numerical_accuracy");
        pmap.put("Claim-batch equivalence", "claim_batch_equivalence");
        pmap.put("Domain neutrality", "domain_neutrality");
        pmap.put("German entailment", "german_entailment");
        pmap.put("Graph unique contribution", "graph_unique_contribution");
        pmap.put("Reranking benefit", "reranking_benefit");
        pmap.put("Repair: 0→1 support improvement", "repair_0to1_support");
        pmap.put("Full E2E grounding", "full_e2e_grounding");

        for (var e : pmap.entrySet()) {
            String status = F.getOrDefault(e.getValue(), "NOT TESTED");
            R.append(String.format("  %-42s %s%n", e.getKey(), status));
        }

        R.append("\n").append("=".repeat(78)).append("\n");
        long sec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        R.append("  Duration: ").append(sec).append("s\n");
        R.append("=".repeat(78)).append("\n");

        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/verwaltungsassistent-correctness-audit.txt"), R.toString());
        } catch (IOException ignored) {}
        System.out.println(R.toString());
    }

    // ── helpers ──

    private void logCase(String label, String claim, String evidence,
                          Verdict expected, ClaimVerification actual) {
        R.append(String.format("## %s%n", label));
        R.append(String.format("  Evidence: %s%n", truncate(evidence, 120)));
        R.append(String.format("  Claim: %s%n", truncate(claim, 120)));
        R.append(String.format("  Expected: %s | Actual: %s (%.2f)%n",
                expected, actual.verdict(), actual.confidence()));
        R.append(String.format("  Reason: %s%n%n", actual.reason()));
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
