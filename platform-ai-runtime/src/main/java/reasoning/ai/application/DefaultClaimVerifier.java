package reasoning.ai.application;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.ClaimVerification.Verdict;
import reasoning.ai.model.ModelCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based claim verification. Uses an independent LLM call (separate
 * from the generating LLM) to evaluate whether evidence entails,
 * contradicts, or is neutral toward a claim.
 *
 * <p>Two strategies are supported (configured via
 * {@code platform.ai.ollama.verification-strategy}):
 * <ul>
 *   <li>{@code pairwise} — one LLM call per claim/evidence pair (baseline)</li>
 *   <li>{@code claim_batch} — one LLM call per claim with all evidence items</li>
 * </ul>
 *
 * <p>This replaces embedding-similarity-based support checking.
 * Embedding similarity measures topical relatedness, not logical
 * entailment — it cannot distinguish "VPN is required" from
 * "VPN is not required."
 *
 * <p>All verifier calls run at temperature 0.0 — the grounding verdict must
 * be deterministic: the same question with the same evidence must not flip
 * between grounded and ungrounded across runs (Ollama's default temperature
 * 0.8 made identical requests produce different verdicts).
 */
@Service
public class DefaultClaimVerifier implements ClaimVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultClaimVerifier.class);
    private static final Pattern VERDICT_PAT = Pattern.compile("\"verdict\"\\s*:\\s*\"(ENTAILED|CONTRADICTED|UNKNOWN)\"");
    private static final Pattern CONF_PAT = Pattern.compile("\"confidence\"\\s*:\\s*([0-9.]+)");
    private static final Pattern REASON_PAT = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EVIDENCE_ID_PAT = Pattern.compile("\"evidenceId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BATCH_VERDICT_PAT = Pattern.compile("\"verdict\"\\s*:\\s*\"(ENTAILED|CONTRADICTED|UNKNOWN)\"");
    private static final int MAX_EVIDENCE_CHARS = 500;
    private static final int MAX_CLAIM_CHARS = 400;

    private final ChatCompletionProvider llmProvider;
    private final AiProviderProperties properties;

    public DefaultClaimVerifier(ChatCompletionProvider llmProvider, AiProviderProperties properties) {
        this.llmProvider = llmProvider;
        this.properties = properties;
    }

    private ModelCapabilities verifierCapabilities() {
        String model = properties.getOllama().getVerifierModel();
        return new ModelCapabilities("verifier", model, 4096, false, false, true);
    }

    private boolean isClaimBatch() {
        return "claim_batch".equalsIgnoreCase(properties.getOllama().getVerificationStrategy());
    }

    // ── Single pair verification (always pairwise, unchanged) ──

    @Override
    public ClaimVerification verify(String claim, String evidenceExcerpt) {
        String prompt = buildPairwisePrompt(claim, evidenceExcerpt);
        try {
            String response = llmProvider.complete(prompt, verifierCapabilities(), 0.0);
            return parsePairwiseResponse(claim, evidenceExcerpt, response);
        } catch (Exception e) {
            log.warn("Claim verification failed: {}", e.getMessage());
            return new ClaimVerification(claim, evidenceExcerpt, Verdict.UNKNOWN, 0.0,
                    "Verification error: " + e.getMessage());
        }
    }

    // ── Batch verification (strategy-dependent) ──

    @Override
    public List<List<ClaimVerification>> verifyAllClaims(List<String> claims,
                                                         List<String> evidenceExcerpts) {
        return verifyAllClaims(claims, evidenceExcerpts, null);
    }

    @Override
    public List<List<ClaimVerification>> verifyAllClaims(List<String> claims,
                                                         List<String> evidenceExcerpts,
                                                         String question) {
        if (claims == null || claims.isEmpty()
                || evidenceExcerpts == null || evidenceExcerpts.isEmpty()) {
            return List.of();
        }
        if (!isClaimBatch()) {
            // PAIRWISE mode: keep historical one-call-per-claim-per-evidence behavior
            List<List<ClaimVerification>> results = new ArrayList<>();
            for (String claim : claims) {
                results.add(verifyBatch(claim, evidenceExcerpts));
            }
            return results;
        }
        // CLAIM_BATCH mode: ONE verifier call for all claims x all evidence
        List<String> claimIds = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) claimIds.add(claimId(i));
        List<String> evidenceIds = new ArrayList<>();
        for (int i = 0; i < evidenceExcerpts.size(); i++) evidenceIds.add(evidenceId(i));
        String prompt = buildAllClaimsPrompt(claims, evidenceExcerpts, claimIds, evidenceIds, question);
        try {
            String response = llmProvider.complete(prompt, verifierCapabilities(), 0.0);
            log.info("Claim batch RAW response ({} claims, {} evidence): {}", claims.size(),
                    evidenceExcerpts.size(), response);
            return parseAllClaimsResponse(claims, evidenceExcerpts, response);
        } catch (Exception e) {
            log.warn("All-claims batch verification failed: {}", e.getMessage());
            List<List<ClaimVerification>> fallback = new ArrayList<>();
            for (String claim : claims) {
                List<ClaimVerification> perClaim = new ArrayList<>();
                for (String excerpt : evidenceExcerpts) {
                    perClaim.add(new ClaimVerification(claim, excerpt,
                            Verdict.UNKNOWN, 0.0, "Batch verification error: " + e.getMessage()));
                }
                fallback.add(perClaim);
            }
            return fallback;
        }
    }

    @Override
    public List<ClaimVerification> verifyBatch(String claim, List<String> evidenceExcerpts) {
        if (evidenceExcerpts == null || evidenceExcerpts.isEmpty()) {
            return List.of();
        }
        if (!isClaimBatch()) {
            // PAIRWISE: one LLM call per evidence item (baseline)
            List<ClaimVerification> results = new ArrayList<>();
            for (String excerpt : evidenceExcerpts) {
                results.add(verify(claim, excerpt));
            }
            return results;
        }
        // CLAIM_BATCH: one LLM call for all evidence items
        return verifyClaimBatch(claim, evidenceExcerpts);
    }

    private List<ClaimVerification> verifyClaimBatch(String claim, List<String> evidenceExcerpts) {
        List<String> expectedIds = new ArrayList<>();
        for (int i = 0; i < evidenceExcerpts.size(); i++) {
            expectedIds.add(evidenceId(i));
        }
        String prompt = buildClaimBatchPrompt(claim, evidenceExcerpts, expectedIds);
        try {
            String response = llmProvider.complete(prompt, verifierCapabilities(), 0.0);
            List<ClaimVerification> results = parseClaimBatchResponse(claim, evidenceExcerpts, response);
            // Validate completeness: every requested ID must have a result
            List<String> returnedIds = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                String id = evidenceId(i);
                if (!results.get(i).reason().contains("No result from batch verifier")) {
                    returnedIds.add(id);
                }
            }
            List<String> missing = new ArrayList<>(expectedIds);
            missing.removeAll(returnedIds);
            if (!missing.isEmpty()) {
                log.warn("Claim batch response missing {} evidence items: {}",
                        missing.size(), String.join(", ", missing));
            }
            return results;
        } catch (Exception e) {
            log.warn("Claim batch verification failed: {}", e.getMessage());
            List<ClaimVerification> fallback = new ArrayList<>();
            for (int i = 0; i < evidenceExcerpts.size(); i++) {
                fallback.add(new ClaimVerification(claim, evidenceExcerpts.get(i),
                        Verdict.UNKNOWN, 0.0, "Batch verification error: " + e.getMessage()));
            }
            return fallback;
        }
    }

    // ── Pairwise prompt ──

    private String buildPairwisePrompt(String claim, String evidence) {
        return """
            You are a precise fact-checking verifier. Your ONLY task is to determine
            whether a CLAIM is logically supported by the provided EVIDENCE.

            Respond ONLY with a JSON object. No other text.

            EVIDENCE:
            \"""" + truncate(evidence, 800) + "\"\n\n" +
            """
            CLAIM:
            \"""" + truncate(claim, 400) + "\"\n\n" +
            """
            Determine the logical relationship:
            - ENTAILED: The evidence logically supports the claim. The claim follows from the evidence.
            - CONTRADICTED: The evidence contradicts the claim. The claim says the opposite.
            - UNKNOWN: The evidence is insufficient to determine. The claim is about different facts.

            Consider:
            - Negation: "not required" vs "required" are CONTRADICTED
            - Numbers: "12 EUR" vs "24 EUR" are CONTRADICTED if the evidence specifies the value
            - Conditions: "must" vs "may" are different; check whether the evidence states obligation
            - Partial support: if only part of the claim is supported, that is UNKNOWN

            Respond with exactly:
            {"verdict": "ENTAILED|CONTRADICTED|UNKNOWN", "confidence": 0.XX, "reason": "brief explanation"}
            """;
    }

    // ── Claim-batch prompt ──

    private String buildClaimBatchPrompt(String claim, List<String> evidenceExcerpts,
                                          List<String> expectedIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are a precise fact-checking verifier. Your task is to determine
            whether a CLAIM is logically supported by each provided EVIDENCE item.

            Evaluate each evidence item INDEPENDENTLY against the claim.
            Report each evidence item separately. Do NOT summarize across evidence items.

            Respond ONLY with a JSON object. No other text.

            """);

        sb.append("CLAIM:\n\"\"\"").append(truncate(claim, MAX_CLAIM_CHARS)).append("\"\"\"\n\n");

        sb.append("EVIDENCE ITEMS:\n");
        for (int i = 0; i < evidenceExcerpts.size(); i++) {
            sb.append("[").append(evidenceId(i)).append("] \"\"\"")
                    .append(truncate(evidenceExcerpts.get(i), MAX_EVIDENCE_CHARS))
                    .append("\"\"\"\n");
        }

        sb.append("\nYou MUST return a verdict for ALL ").append(expectedIds.size())
                .append(" evidence IDs: ").append(String.join(", ", expectedIds)).append("\n");
        sb.append("Do NOT skip any evidence item. Every ID listed above must appear in the response.\n\n");

        sb.append("""
            For each evidence item, determine:
            - ENTAILED: The evidence logically supports the claim.
            - CONTRADICTED: The evidence contradicts the claim.
            - UNKNOWN: The evidence is insufficient to determine.

            Consider: negation, contradictory statements, different numerical values,
            conditions ("must" vs "may"), exceptions, temporal qualifications, paraphrases.

            Respond with exactly:
            {
              "claim": "brief claim summary",
              "evidence": [
                {"evidenceId": "E1", "verdict": "ENTAILED|CONTRADICTED|UNKNOWN", "confidence": 0.XX, "reason": "brief explanation"},
                {"evidenceId": "E2", "verdict": "ENTAILED|CONTRADICTED|UNKNOWN", "confidence": 0.XX, "reason": "brief explanation"}
              ]
            }
            """);

        return sb.toString();
    }

    // ── All-claims prompt (single-call optimization) ──

    private String buildAllClaimsPrompt(List<String> claims, List<String> evidenceExcerpts,
                                        List<String> claimIds, List<String> evidenceIds,
                                        String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are a precise fact-checking verifier. Your task is to determine
            whether each CLAIM is logically supported by each provided EVIDENCE item.

            Evaluate each CLAIM against each EVIDENCE item INDEPENDENTLY.
            Report each claim separately. Do NOT summarize across claims.

            Respond ONLY with a JSON object. No other text.

            """);

        if (question != null && !question.isBlank()) {
            sb.append("USER QUESTION:\n\"\"\"").append(truncate(question, 400))
                    .append("\"\"\"\n\n");
        }

        sb.append("""
            CRITICAL RULES:
            - Retrieved candidates are NOT automatically evidence. A high
              retrieval similarity score does NOT mean a candidate is relevant
              to the USER QUESTION.
            - First decide whether each EVIDENCE item is actually relevant to
              the USER QUESTION. Evidence about a different topic must be
              treated as UNKNOWN, regardless of its similarity score.
            - If the available evidence is insufficient to answer the USER
              QUESTION, the correct result is an insufficient-evidence outcome
              (UNKNOWN for all claims) — never an inferred or invented answer.
            - A claim stating that the EVIDENCE LACKS information (e.g. "the
              documents contain no information about X", "no suitable legal
              basis was found") is a meta-claim about the evidence itself.
              An evidence item that simply does NOT mention the topic does
              NOT entail such a claim — mark it UNKNOWN. Only an evidence
              item that explicitly states the claimed fact may be ENTAILED.

            """);

        sb.append("CLAIMS:\n");
        for (int i = 0; i < claims.size(); i++) {
            sb.append("[").append(claimIds.get(i)).append("] \"\"\"")
                    .append(truncate(claims.get(i), MAX_CLAIM_CHARS)).append("\"\"\"\n");
        }

        sb.append("\nEVIDENCE ITEMS:\n");
        for (int i = 0; i < evidenceExcerpts.size(); i++) {
            sb.append("[").append(evidenceIds.get(i)).append("] \"\"\"")
                    .append(truncate(evidenceExcerpts.get(i), MAX_EVIDENCE_CHARS))
                    .append("\"\"\"\n");
        }

        sb.append("\nYou MUST return a result for EVERY claim ID: ")
                .append(String.join(", ", claimIds)).append("\n");
        sb.append("For each claim, list ONLY the evidence items whose verdict is ENTAILED")
                .append(" or CONTRADICTED. Evidence items not listed for a claim are")
                .append(" treated as UNKNOWN.\n");
        sb.append("Do NOT skip any claim. Do NOT invent evidence IDs.\n\n");

        sb.append("""
            For each verdict, determine:
            - ENTAILED: The evidence logically supports the claim.
            - CONTRADICTED: The evidence contradicts the claim.
            - UNKNOWN: The evidence is insufficient to determine (omit from the response).

            Consider: negation, contradictory statements, different numerical values,
            conditions ("must" vs "may"), exceptions, temporal qualifications, paraphrases.

            Respond with exactly:
            {
              "results": [
                {"claimId": "C1", "evidence": [{"evidenceId": "E2", "verdict": "ENTAILED", "confidence": 0.85, "reason": "brief explanation"}]},
                {"claimId": "C2", "evidence": []}
              ]
            }
            """);

        return sb.toString();
    }

    private List<List<ClaimVerification>> parseAllClaimsResponse(List<String> claims,
                                                                 List<String> evidenceExcerpts,
                                                                 String response) {
        List<List<ClaimVerification>> out = new ArrayList<>();
        for (int ci = 0; ci < claims.size(); ci++) {
            String block = extractClaimBlock(response, claimId(ci));
            // Reuses the single-claim parsing (evidence array, per-block scan,
            // positional fallback) with identical UNKNOWN defaulting.
            out.add(parseClaimBatchResponse(claims.get(ci), evidenceExcerpts, block));
        }
        return out;
    }

    /** Extracts the JSON fragment for one claim from a combined results response. */
    private static String extractClaimBlock(String response, String claimId) {
        Matcher m = Pattern.compile(
                "\"claimId\"\\s*:\\s*\"" + Pattern.quote(claimId) + "\"").matcher(response);
        if (!m.find()) return "";
        int start = m.start();
        int end = response.length();
        Matcher next = Pattern.compile("\"claimId\"\\s*:\\s*\"").matcher(response);
        if (next.find(start + 1)) end = next.start();
        return response.substring(start, end);
    }

    private static String claimId(int index) {
        return "C" + (index + 1);
    }

    // ── Response parsing ──

    private ClaimVerification parsePairwiseResponse(String claim, String evidence, String response) {
        Verdict verdict = Verdict.UNKNOWN;
        double confidence = 0.5;
        String reason = "";

        Matcher vm = VERDICT_PAT.matcher(response);
        if (vm.find()) {
            try { verdict = Verdict.valueOf(vm.group(1)); }
            catch (IllegalArgumentException ignored) {}
        }

        Matcher cm = CONF_PAT.matcher(response);
        if (cm.find()) {
            try { confidence = Double.parseDouble(cm.group(1)); }
            catch (NumberFormatException ignored) {}
        }

        Matcher rm = REASON_PAT.matcher(response);
        if (rm.find()) reason = rm.group(1);

        log.info("Verification: {} [{}] confidence={}", verdict, reason, String.format("%.2f", confidence));
        return new ClaimVerification(claim, evidence, verdict, confidence, reason);
    }

    private List<ClaimVerification> parseClaimBatchResponse(String claim, List<String> evidenceExcerpts,
                                                             String response) {
        List<ParsedEvidenceResult> parsed = new ArrayList<>();

        // Strategy 1: extract the entire evidence array, then split into per-item blocks
        java.util.regex.Matcher arrayMatcher = java.util.regex.Pattern.compile(
                "\"evidence\"\\s*:\\s*\\[(.*)\\]", java.util.regex.Pattern.DOTALL).matcher(response);
        if (arrayMatcher.find()) {
            String arrayContent = arrayMatcher.group(1);
            // Split on "evidenceId" boundaries — each block starts with {"evidenceId"
            String[] blocks = arrayContent.split("\\},\\s*(?=\\{)");
            for (String block : blocks) {
                block = block.trim();
                if (!block.endsWith("}")) block = block + "}";
                if (!block.startsWith("{")) block = "{" + block;
                String id = extractJsonString(block, "evidenceId");
                if (id == null) continue;
                String verdictStr = extractJsonString(block, "verdict");
                double conf = extractJsonDouble(block, "confidence");
                String reason = extractJsonString(block, "reason");

                Verdict verdict = Verdict.UNKNOWN;
                if (verdictStr != null) {
                    try { verdict = Verdict.valueOf(verdictStr); }
                    catch (IllegalArgumentException ignored) {}
                }
                parsed.add(new ParsedEvidenceResult(id, verdict, conf, reason != null ? reason : ""));
            }
        }

        // Strategy 2: fallback — scan for evidenceId blocks individually
        if (parsed.isEmpty()) {
            java.util.regex.Matcher blockMatcher = java.util.regex.Pattern.compile(
                    "\\{[^}]*\"evidenceId\"[^}]*\\}").matcher(response);
            while (blockMatcher.find()) {
                String block = blockMatcher.group();
                String id = extractJsonString(block, "evidenceId");
                String verdictStr = extractJsonString(block, "verdict");
                double conf = extractJsonDouble(block, "confidence");
                String reason = extractJsonString(block, "reason");

                Verdict verdict = Verdict.UNKNOWN;
                if (verdictStr != null) {
                    try { verdict = Verdict.valueOf(verdictStr); }
                    catch (IllegalArgumentException ignored) {}
                }
                parsed.add(new ParsedEvidenceResult(id, verdict, conf, reason != null ? reason : ""));
            }
        }

        // Strategy 3: last resort — extract verdicts in order and assign by position
        if (parsed.isEmpty()) {
            java.util.regex.Matcher vm = BATCH_VERDICT_PAT.matcher(response);
            int idx = 0;
            while (vm.find() && idx < evidenceExcerpts.size()) {
                parsed.add(new ParsedEvidenceResult(evidenceId(idx),
                        Verdict.valueOf(vm.group(1)), 0.5, ""));
                idx++;
            }
        }

        // Map results back to evidence excerpts by ID (E1→index 0, E2→index 1, ...).
        // The batch prompt contract: the model lists ONLY entailed/contradicted
        // evidence items; omitted evidence is UNKNOWN by design. A positional
        // fallback must therefore never shift a verdict to a different evidence
        // item — it is only a last resort when the response carries NO usable
        // evidence IDs at all.
        //
        // "Bekannte" IDs sind die IDs DIESES Prompts (E1..En). IDs wie E3/E4 in
        // einer Antwort zu einem 1-Item-Prompt sind Phantome: das Modell hat den
        // Verdict über gültigen Inhalt gefällt, aber die ID erfunden. Solche
        // Antworten werden positional zugeordnet, statt die Verdicts zu verwerfen.
        // Mischt das Modell echte und Phantom-IDs (z. B. E1 + E4 bei 2 Items),
        // greift der Fallback NICHT — fehlende echte IDs bleiben UNKNOWN
        // (Omissions-Kontrakt), damit ein Verdict nie auf ein falsches Item
        // verschoben wird.
        List<String> expectedIds = new ArrayList<>();
        for (int i = 0; i < evidenceExcerpts.size(); i++) {
            expectedIds.add(evidenceId(i));
        }
        boolean anyKnownId = parsed.stream()
                .anyMatch(p -> p.id != null && expectedIds.contains(p.id));
        List<ClaimVerification> results = new ArrayList<>();
        for (int i = 0; i < evidenceExcerpts.size(); i++) {
            String expectedId = evidenceId(i);
            ParsedEvidenceResult match = parsed.stream()
                    .filter(p -> expectedId.equals(p.id))
                    .findFirst().orElse(null);

            if (match != null) {
                log.info("Batch verification [{}]: {} confidence={}",
                        match.id, match.verdict, String.format("%.2f", match.confidence));
                results.add(new ClaimVerification(claim, evidenceExcerpts.get(i),
                        match.verdict, match.confidence, match.reason));
            } else if (!anyKnownId && i < parsed.size()) {
                // Response without recognizable evidence IDs → positional assignment
                match = parsed.get(i);
                log.info("Batch verification [{}→{}]: {} confidence={}",
                        expectedId, match.id, match.verdict, String.format("%.2f", match.confidence));
                results.add(new ClaimVerification(claim, evidenceExcerpts.get(i),
                        match.verdict, match.confidence, match.reason));
            } else {
                log.warn("No verification result for {}, defaulting to UNKNOWN", expectedId);
                results.add(new ClaimVerification(claim, evidenceExcerpts.get(i),
                        Verdict.UNKNOWN, 0.0, "No result from batch verifier for " + expectedId));
            }
        }

        return results;
    }

    private static String extractJsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static double extractJsonDouble(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return 0.5;
    }

    private static String evidenceId(int index) {
        return "E" + (index + 1);
    }

    private record ParsedEvidenceResult(String id, Verdict verdict, double confidence, String reason) {}

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s != null ? s : "";
    }
}
