package reasoning.ai.application;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.api.GroundingService;
import reasoning.ai.model.*;
import reasoning.common.text.GermanTermVariants;
import reasoning.search.api.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Grounds a raw AI answer against retrieved evidence.
 *
 * <p>For retrieval answers: parses the LLM output into claims, embeds
 * each claim and each evidence excerpt, and computes cosine similarity
 * to determine semantic support. Lexical overlap acts as a fast-path
 * pre-filter before embedding.
 *
 * <p>For rule-engine answers: grounded by the authoritative structured
 * knowledge table itself.
 *
 * <p>A citation alone does NOT establish support.
 * Word overlap alone does NOT establish support.
 * Semantic similarity between the actual claim text and the actual
 * evidence excerpt is the primary support signal.
 */
@Service
public class DefaultGroundingService implements GroundingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultGroundingService.class);
    /** Max evidence excerpts passed to the claim-batch verifier (reliability bound). */
    static final int MAX_VERIFIER_EVIDENCE = 5;

    // Cosine similarity threshold for considering a claim supported by evidence.
    // 0.55 works well for cross-lingual domain text (nomic-embed-text);
    // random unrelated text pairs typically score 0.2-0.3 with this model.
    private final EmbeddingProvider embeddingProvider;
    private final ClaimVerificationService claimVerifier;

    public DefaultGroundingService(EmbeddingProvider embeddingProvider,
                                    ClaimVerificationService claimVerifier) {
        this.embeddingProvider = embeddingProvider;
        this.claimVerifier = claimVerifier;
    }

    @Override
    public ReasonedAnswer ground(String rawAnswer, RetrievalContext retrievalContext) {
        return ground(rawAnswer, retrievalContext, null);
    }

    @Override
    public ReasonedAnswer ground(String rawAnswer, RetrievalContext retrievalContext,
                                 EvidencePackage evidencePackage) {
        if ("RULE_ENGINE".equals(retrievalContext.retrievalStrategy())
                && retrievalContext.structuredDecision() != null) {
            return groundStructured(rawAnswer, retrievalContext.structuredDecision(),
                    retrievalContext.authorityReferences());
        }

        if (retrievalContext.sources().isEmpty() && retrievalContext.authorityReferences().isEmpty()) {
            return new ReasonedAnswer(INSUFFICIENT_EVIDENCE_ANSWER,
                    List.of(), List.of(), null, null,
                    ConfidenceProfile.none(), false, 0.0, false);
        }

        List<SourceCitation> attributed = reattribute(rawAnswer, retrievalContext.sources());

        double sourceConfidence = attributed.stream()
                .mapToDouble(SourceCitation::confidenceScore).average().orElse(0.0);
        double authorityConfidence = retrievalContext.authorityReferences().stream()
                .mapToDouble(a -> a.tier() == AuthorityReference.ReferenceTier.PRIMARY ? 0.9 : 0.5)
                .average().orElse(0.0);
        double completenessConf = retrievalContext.sourceDossier() != null
                ? retrievalContext.sourceDossier().coverageScore() : 0.0;

        // Parse claims and evaluate semantic support. The verifier sees the
        // evidence actually selected for the answer (the evidence package),
        // not the full retrieval candidate list.
        ClaimEvaluation eval = evaluateClaims(rawAnswer, attributed, evidencePackage,
                retrievalContext.query());
        FindingHierarchy findings = eval.findings;
        // Semantic confidence scales with the fraction of claims actually
        // supported by evidence: any-supported must not equal fully-supported.
        // Full coverage → 0.8, no supported claim → 0.3, partial → in between.
        double semanticConf = findings.primaryFindings().isEmpty()
                ? 0.3 : 0.3 + 0.5 * eval.claimCoverage;
        // Overall confidence: weighted sum of the four dimensions shown in the
        // decision UI (cases/decision-fragments.html). Keep weights in sync.
        double overallConf = Math.min(1.0,
                sourceConfidence * 0.2 + authorityConfidence * 0.25 + semanticConf * 0.25 + completenessConf * 0.3);

        ConfidenceProfile confProfile = new ConfidenceProfile(
                sourceConfidence, semanticConf, authorityConfidence, completenessConf, overallConf,
                "Source=" + String.format("%.2f", sourceConfidence)
                        + " Semantic=" + String.format("%.2f", semanticConf)
                        + " Authority=" + String.format("%.2f", authorityConfidence)
                        + " Completeness=" + String.format("%.2f", completenessConf));

        // ── Deterministic final evidence status ──────────────────────────────
        // The LLM (answer drafting, claim verifier) may vary between runs.
        // The FINAL green/yellow/insufficient decision must therefore not
        // depend on stochastic per-claim verdicts or on the exact drafted
        // claim set. It is computed from stable artifacts only:
        //   1. retrieved evidence (excerpts, confidence, document metadata)
        //   2. specific question terms (deterministic anchor rule)
        //   3. the KURZANTWORT core answer, whose lexical support is checked
        //      deterministically against the anchored evidence.
        // Anything the LLM adds in ENTSCHEIDUNG/VERFAHREN/… cannot change the
        // status; genuine contradictions remain hard blockers.
        // Deterministic final status: grounded iff question-anchored evidence
        // is present (stable artifacts: excerpt/title carry a specific
        // question term). The drafted answer text and any stochastic claim
        // verdicts do NOT participate — the same case + corpus + retrieval
        // always yields the same status.
        List<SourceCitation> anchoredEvidence = anchoredEvidence(retrievalContext, attributed);
        boolean anchoredEvidenceAvailable = !anchoredEvidence.isEmpty();
        boolean grounded = anchoredEvidenceAvailable;

        String presentedAnswer = rawAnswer;
        List<SourceCitation> presentedCitations = attributed;
        List<AuthorityReference> presentedAuthorities = retrievalContext.authorityReferences();
        // Fail closed when the retrieved evidence is not anchored to the
        // question at all (irrelevant documents must never produce a grounded
        // answer, e.g. Beglaubigung/Melderegister hits for a Spielplatz case).
        if (!anchoredEvidenceAvailable) {
            log.info("Deterministic gate rejected the answer (no anchored evidence, "
                            + "anchored={}) — presenting insufficient-evidence outcome",
                    anchoredEvidenceAvailable);
            presentedAnswer = INSUFFICIENT_EVIDENCE_ANSWER;
            presentedCitations = List.of();
            presentedAuthorities = List.of();
            findings = new FindingHierarchy(List.of(), List.of(), List.of(), List.of(), List.of());
            confProfile = ConfidenceProfile.insufficientEvidence();
            grounded = false;
        }

        return new ReasonedAnswer(presentedAnswer, presentedCitations, presentedAuthorities,
                findings, retrievalContext.sourceDossier(), confProfile, grounded,
                eval.claimCoverage, eval.conflictPresent);
    }

    /**
     * Retrieved evidence that is RELEVANT to the question: excerpt OR document
     * title carries a specific question term (German variants). The title is a
     * stable document-level artifact and guards against chunk-selection
     * variance. No confidence threshold: every retrieved item counts, so the
     * decision does not depend on ranking scores. Deterministic for identical
     * question + corpus + retrieval.
     */
    private List<SourceCitation> anchoredEvidence(RetrievalContext context,
                                                  List<SourceCitation> attributed) {
        List<String> specific = DefaultRetrievalAugmentationService.specificTerms(
                context.query(), stopWords);
        List<SourceCitation> pool = attributed != null && !attributed.isEmpty()
                ? attributed : context.sources();
        List<SourceCitation> result = new ArrayList<>();
        if (specific.isEmpty()) {
            return pool;
        }
        List<Set<String>> variantSets = specific.stream()
                .map(GermanTermVariants::of)
                .toList();
        for (SourceCitation s : pool) {
            String text = s.excerpt() != null ? s.excerpt() : "";
            String title = s.title() != null ? s.title() : "";
            if (DefaultRetrievalAugmentationService.containsAnyVariant(text, variantSets)
                    || DefaultRetrievalAugmentationService.containsAnyVariant(title, variantSets)) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * The KURZANTWORT is the direct answer to the question and the only
     * drafted text that participates in the deterministic status decision.
     * It is supported when at least one anchored evidence excerpt shares
     * substantive vocabulary with it (robust overlap: >=2 tokens or one
     * token of length >= 7).
     */
    private boolean coreClaimSupportedBy(String rawAnswer, List<SourceCitation> anchoredEvidence) {
        String core = coreClaimText(rawAnswer);
        if (core == null) return false;
        for (SourceCitation s : anchoredEvidence) {
            // Evidence excerpts may vary between runs (chunk selection);
            // the DOCUMENT TITLE is a stable artifact of the retrieved
            // document and counts as support as well. Both are checked with
            // the same deterministic lexical rule.
            if (lexicallySupports(core, s.excerpt())
                    || (s.title() != null && lexicallySupports(core, s.title()))) {
                return true;
            }
        }
        return false;
    }

    /** Text of the KURZANTWORT section, or the whole cleaned answer as fallback. */
    private String coreClaimText(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) return null;
        String[] markers = {"KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE",
                "VERFAHREN", "NÄCHSTER SCHRITT"};
        int idx = rawAnswer.indexOf("KURZANTWORT");
        if (idx >= 0) {
            int start = idx + "KURZANTWORT".length();
            int end = rawAnswer.length();
            for (String nextMarker : markers) {
                if ("KURZANTWORT".equals(nextMarker)) continue;
                int nextIdx = rawAnswer.indexOf(nextMarker, start);
                if (nextIdx >= 0 && nextIdx < end) end = nextIdx;
            }
            String core = rawAnswer.substring(start, end).trim()
                    .replaceAll("\\*\\*|__", "").replaceAll("\\n+", " ").trim();
            return core.length() > 10 ? core : null;
        }
        String cleaned = rawAnswer.replaceAll("\\*\\*|__", "").trim();
        return cleaned.length() > 20 ? cleaned : null;
    }

    /** True when a CONTRADICTED finding exists whose label refers to the core answer text. */
    private boolean contradictionOnCore(String rawAnswer, FindingHierarchy findings) {
        if (rawAnswer == null || findings == null) return false;
        String core = coreClaimText(rawAnswer);
        if (core == null) return false;
        for (FindingElement f : findings.secondaryFindings()) {
            String label = f.label();
            if (label == null) continue;
            // Only genuine CONTRADICTED findings carry the "Claim: " marker
            // (deterministically lexically filtered); unsupported findings
            // must not act as contradictions.
            if (!label.startsWith("Claim: ")) continue;
            String claimPart = label.substring("Claim: ".length());
            if (lexicallyOverlapping(claimPart, core, 5)) return true;
        }
        return false;
    }

    // ── Claim evaluation with embedding-based semantic support ──

    /** User-facing outcome when the evidence cannot support an answer (German demo). */
    static final String INSUFFICIENT_EVIDENCE_ANSWER =
            "Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.";

    private record ClaimEvaluation(FindingHierarchy findings, double claimCoverage,
                                   boolean conflictPresent, boolean evidenceSufficient) {}

    /**
     * Control claim verified alongside the answer claims: ENTAILED when the
     * combined evidence is sufficient to answer the user question. When the
     * verifier does not entail it, the answer must not be presented with
     * citations — the fail-closed outcome applies even if meta-claims about
     * the evidence ("the documents contain no information about X") received
     * spurious ENTAILED verdicts.
     */
    static final String SUFFICIENCY_CLAIM =
            "The available evidence is sufficient to answer the user's question.";

    private ClaimEvaluation evaluateClaims(String rawAnswer, List<SourceCitation> attributed,
                                           EvidencePackage evidencePackage, String question) {
        List<ClaimCandidate> candidates = extractClaimSections(rawAnswer);
        if (candidates.isEmpty()) {
            String cleaned = rawAnswer.replaceAll("\\*\\*|__", "").trim();
            if (cleaned.length() > 20) {
                String text = cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
                candidates = List.of(new ClaimCandidate(null, text));
            }
        }
        if (candidates.isEmpty()) {
            return new ClaimEvaluation(
                new FindingHierarchy(List.of(), List.of(), List.of(), List.of(), List.of()),
                0.0, false, false);
        }

        // Parallel arrays keep the claim text for the verifier/findings while
        // remembering which claim came from a purely structural answer section
        // (VERFAHREN / NÄCHSTER SCHRITT). Unsupported verdicts on such
        // workflow scaffolding (e.g. "Kein Verfahren erforderlich") are not
        // substantive evidence gaps and must not block the grounded state;
        // contradictions there remain hard blockers.
        List<String> claims = new ArrayList<>();
        boolean[] structural = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            claims.add(candidates.get(i).text());
            structural[i] = candidates.get(i).structural();
        }

        // Verifier evidence window: the EVIDENCE SELECTED for the answer (the
        // evidence package the prompt was built from), bounded to the top-N
        // items by confidence. Retrieval candidates that were ranked but NOT
        // selected (e.g. topical-but-irrelevant BMG hits) must not pollute the
        // verification verdict. Without a package (callers without an
        // assembled prompt context) the top-N attributed sources remain the
        // fallback. The claim-batch prompt evaluates every claim against every
        // excerpt; with large evidence sets the model's JSON output becomes
        // unreliable (truncated/invalid → all-UNKNOWN → spurious fail-closed).
        // The presented citations stay complete — only the verifier input is
        // bounded.
        List<String> verifierExcerpts;
        List<String> verifierLabels;
        List<EvidenceItem> packageItems = evidencePackage != null ? evidencePackage.items() : List.of();
        if (!packageItems.isEmpty()) {
            List<EvidenceItem> usableItems = packageItems.stream()
                    .filter(it -> it.excerpt() != null && it.excerpt().length() > 30)
                    .sorted(java.util.Comparator.comparingDouble(EvidenceItem::confidence).reversed())
                    .limit(MAX_VERIFIER_EVIDENCE)
                    .toList();
            verifierExcerpts = usableItems.stream().map(EvidenceItem::excerpt).toList();
            verifierLabels = usableItems.stream()
                    .map(it -> it.documentTitle() != null ? it.documentTitle() : it.documentId().toString())
                    .toList();
        } else {
            List<SourceCitation> usableEvidence = attributed.stream()
                    .filter(sc -> sc.excerpt() != null && sc.excerpt().length() > 30)
                    .sorted(java.util.Comparator.comparingDouble(SourceCitation::confidenceScore).reversed())
                    .limit(MAX_VERIFIER_EVIDENCE)
                    .toList();
            verifierExcerpts = usableEvidence.stream().map(SourceCitation::excerpt).toList();
            verifierLabels = usableEvidence.stream()
                    .map(sc -> sc.title() != null ? sc.title() : sc.documentId().toString())
                    .toList();
        }

        if (verifierExcerpts.isEmpty()) {
            List<FindingElement> unsupported = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) {
                unsupported.add(makeFinding(claims.get(i), i, 0, List.of()));
            }
            return new ClaimEvaluation(
                new FindingHierarchy(List.of(), unsupported, List.of(), List.of(), List.of()),
                0.0, false, false);
        }

        List<FindingElement> supported = new ArrayList<>();
        List<FindingElement> contradicted = new ArrayList<>();
        List<FindingElement> unsupported = new ArrayList<>();

        // The sufficiency control claim is verified alongside the answer
        // claims; its verdict gates the final presentation.
        List<String> verifierClaims = new ArrayList<>(claims);
        verifierClaims.add(SUFFICIENCY_CLAIM);

        List<String> excerpts = verifierExcerpts;
        List<List<ClaimVerification>> allBatchResults;
        try {
            // One verifier round for ALL claims (single-call optimization,
            // Prompt 4) — the verifier keeps its independent role and now
            // receives the user question to judge evidence relevance.
            allBatchResults = claimVerifier.verifyAllClaims(verifierClaims, excerpts, question);
        } catch (Exception e) {
            log.warn("Claim batch verification error: {}", e.getMessage());
            allBatchResults = List.of();
        }

        // Deterministic evidence gate. The LLM verifier is used ONLY to flag
        // potential CONTRADICTIONS (hard blockers); the final
        // supported/unsupported classification — and therefore the green /
        // yellow status — is computed by a deterministic lexical rule over
        // stable artifacts (normalized claim tokens vs. retrieved evidence
        // excerpts). A stochastic per-claim entailment verdict must never
        // decide the administrative status: the same case + corpus + app
        // version yields the same status on every run.
        for (int ci = 0; ci < claims.size(); ci++) {
            String claim = claims.get(ci);
            List<String> contradictingRefs = new ArrayList<>();
            if (ci < allBatchResults.size()) {
                List<ClaimVerification> batchResults = allBatchResults.get(ci);
                for (int ei = 0; ei < batchResults.size() && ei < verifierLabels.size(); ei++) {
                    ClaimVerification result = batchResults.get(ei);
                    if (result.verdict() == ClaimVerification.Verdict.CONTRADICTED
                            && lexicallyOverlapping(claim, verifierExcerpts.get(ei), 5)) {
                        contradictingRefs.add(verifierLabels.get(ei));
                    }
                }
            }

            List<String> supportingRefs = new ArrayList<>();
            for (int ei = 0; ei < verifierExcerpts.size(); ei++) {
                if (lexicallySupports(claim, verifierExcerpts.get(ei))) {
                    supportingRefs.add(verifierLabels.get(ei));
                }
            }

            if (!contradictingRefs.isEmpty()) {
                String label = "Claim: " + (claim.length() > 100 ? claim.substring(0, 100) + "..." : claim);
                // The verifier's reason() is generated in English by the
                // LLM — it must not leak into the German UI. The German
                // claim label carries the finding; the English verdict
                // explanation stays internal.
                contradicted.add(new FindingElement(label, FindingRole.PRIMARY_FINDING,
                        1.0, contradictingRefs, List.of(), null));
            } else if (!supportingRefs.isEmpty()) {
                // Deterministically supported: the claim shares substantive
                // vocabulary with retrieved evidence excerpts.
                supported.add(makeFinding(claim, ci, 1.0, supportingRefs));
            } else if (!structural[ci]) {
                // Unsupported substantive claim — stays a blocker for the
                // grounded state (never for pure VERFAHREN/NÄCHSTER-SCHRITT
                // scaffolding).
                unsupported.add(makeFinding(claim, ci, 0, List.of()));
            }
        }

        log.info("Deterministic claim support: {} supported, {} contradicted, {} unsupported claims",
                supported.size(), contradicted.size(), unsupported.size());
        // primaryFindings = supported, secondaryFindings = unsupported+contradicted
        List<FindingElement> allUnsupported = new ArrayList<>(unsupported);
        allUnsupported.addAll(contradicted);
        FindingHierarchy hierarchy = new FindingHierarchy(supported, allUnsupported, List.of(), List.of(), List.of());

        // Deterministic claim coverage over the substantive claims.
        int substantiveClaims = 0;
        for (int ci = 0; ci < claims.size(); ci++) {
            if (!structural[ci]) substantiveClaims++;
        }
        int totalFindings = supported.size() + contradicted.size() + unsupported.size();
        double claimCoverage = substantiveClaims > 0
                ? (double) (supported.size() + contradicted.size()) / substantiveClaims : 0.0;
        boolean conflictPresent = !contradicted.isEmpty();
        boolean evidenceSufficient = !supported.isEmpty();

        return new ClaimEvaluation(hierarchy, claimCoverage, conflictPresent, evidenceSufficient);
    }

    /**
     * Deterministic lexical support: the claim is supported when the evidence
     * excerpt shares at least two substantive tokens with the claim, or one
     * substantive token of length >= 7 (specific term). Tokenization is the
     * deterministic German-aware tokenizer of this service (stop words,
     * Unicode letters), so identical claims + identical excerpts always yield
     * the identical verdict.
     */
    private boolean lexicallySupports(String claim, String excerpt) {
        if (claim == null || excerpt == null || claim.isBlank() || excerpt.isBlank()) {
            return false;
        }
        Set<String> claimTokens = tokenize(claim);
        Set<String> excerptTokens = tokenize(excerpt);
        int shared = 0;
        int maxSharedLen = 0;
        for (String t : claimTokens) {
            if (matchesAny(t, excerptTokens)) {
                shared++;
                maxSharedLen = Math.max(maxSharedLen, t.length());
            }
        }
        return shared >= 2 || (shared >= 1 && maxSharedLen >= 7);
    }

    /**
     * Deterministic morphological matching: identical token or a shared stem
     * (common prefix >= 5 chars on tokens of length >= 6) covers German
     * inflections (Minderjährige/Minderjährigen, Elternteil/Eltern, …)
     * without any stochastic component.
     */
    private static boolean matchesAny(String token, Set<String> candidates) {
        if (candidates.contains(token)) return true;
        for (String c : candidates) {
            if (token.length() >= 6 && c.length() >= 6) {
                int max = Math.min(token.length(), c.length());
                int p = 0;
                while (p < max && token.charAt(p) == c.charAt(p)) p++;
                if (p >= 5) return true;
            }
        }
        return false;
    }

    /** Weaker overlap used to filter verifier contradiction flags (>=1 token of length >= 5). */
    private boolean lexicallyOverlapping(String claim, String excerpt, int minLen) {
        if (claim == null || excerpt == null || claim.isBlank() || excerpt.isBlank()) {
            return false;
        }
        Set<String> claimTokens = tokenize(claim);
        Set<String> excerptTokens = tokenize(excerpt);
        for (String t : claimTokens) {
            if (t.length() >= minLen && excerptTokens.contains(t)) {
                return true;
            }
        }
        return false;
    }

    // ── Evidence anchor (mirrors the retrieval-layer anchorEvidence rule) ──

    /**
     * True when at least one retrieved source's EXCERPT (chunk text) carries a
     * SPECIFIC question term (question tokens excluding generic vocabulary).
     * Mirrors {@link DefaultRetrievalAugmentationService#anchorEvidence} (which
     * also matches chunk text only), so the presentation gate distinguishes
     * "evidence anchored to the question" from "evidence unrelated in any
     * lexical way". A title-only match never anchors: a document is not a
     * Beleg merely because its heading resembles the question.
     */
    private boolean anchorEvidencePresent(RetrievalContext context) {
        List<String> specific = DefaultRetrievalAugmentationService.specificTerms(
                context.query(), stopWords);
        if (specific.isEmpty()) {
            return true;
        }
        List<Set<String>> variantSets = specific.stream()
                .map(GermanTermVariants::of)
                .toList();
        for (SourceCitation source : context.sources()) {
            String text = source.excerpt() != null ? source.excerpt() : "";
            if (DefaultRetrievalAugmentationService.containsAnyVariant(text, variantSets)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when at least one SUPPORTED (entailed) finding references evidence
     * whose excerpt carries a specific question term. An entailment signal on
     * irrelevant documents (title or topic overlap only) must not satisfy the
     * relevance gate — only evidence that lexically touches the question can
     * carry a grounded answer. Without specific question terms the gate stays
     * open (nothing to anchor against).
     */
    private boolean hasAnchoredSupportedFinding(List<FindingElement> supported,
                                                RetrievalContext context) {
        List<String> specific = DefaultRetrievalAugmentationService.specificTerms(
                context.query(), stopWords);
        if (specific.isEmpty()) {
            return !supported.isEmpty();
        }
        List<Set<String>> variantSets = specific.stream()
                .map(GermanTermVariants::of)
                .toList();
        for (FindingElement finding : supported) {
            for (String ref : finding.governingReferences()) {
                for (SourceCitation source : context.sources()) {
                    String label = source.title() != null ? source.title()
                            : source.documentId() != null ? source.documentId().toString() : null;
                    if (label == null || !label.equals(ref)) {
                        continue;
                    }
                    String text = source.excerpt() != null ? source.excerpt() : "";
                    if (DefaultRetrievalAugmentationService.containsAnyVariant(text, variantSets)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Tokenization ──
    // Stop words are configurable per language via application properties.
    // Municipal app provides German stop words in application.yml:
    //   platform.ai.grounding.stop-words: und,die,der,das,...
    // Default is empty — the core does not assume any specific language.
    // Tokenization uses Unicode letter class \\p{L} for language neutrality.
    private Set<String> stopWords = Set.of();
    public void setStopWords(Set<String> words) { this.stopWords = Set.copyOf(words); }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String w : text.toLowerCase().split("\\s+")) {
            String cleaned = w.replaceAll("[^\\p{L}0-9]", "");
            if (cleaned.length() > 3 && !stopWords.contains(cleaned)) tokens.add(cleaned);
        }
        return tokens;
    }

    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / (a.size() + b.size() - intersection.size());
    }

    // ── Grounded determination ──

    /**
     * A retrieval answer is grounded when:
     * 1. Overall confidence exceeds the noise floor
     * 2. At least one citation has meaningful confidence
     * 3. At least one claim is supported by evidence
     * 4. NO claims are unsupported (secondaryFindings empty)
     *
     * <p>An answer with one supported claim AND one unsupported claim
     * is NOT grounded — the unsupported claim represents a potential
     * hallucination or fabrication.
     */
    private boolean determineGrounded(List<SourceCitation> attributed, double overallConf,
                                       FindingHierarchy findings) {
        if (attributed.isEmpty()) return false;
        if (overallConf < 0.20) return false;

        long withMeaningfulScore = attributed.stream()
                .filter(sc -> sc.confidenceScore() >= 0.25).count();
        if (withMeaningfulScore == 0) return false;

        if (findings == null || findings.primaryFindings().isEmpty()) return false;

        // All material claims must be supported — any unsupported claim
        // means the answer is not fully grounded
        return findings.secondaryFindings().isEmpty();
    }

    // ── Claim extraction ──

    /**
     * One extracted answer claim together with the heading section it came
     * from. VERFAHREN and NÄCHSTER SCHRITT are structural workflow sections:
     * an unsupported verdict there (e.g. "Kein Verfahren erforderlich") is
     * not a missing-evidence signal and must not veto grounding.
     */
    private record ClaimCandidate(String section, String text) {
        boolean structural() {
            return "VERFAHREN".equals(section) || "NÄCHSTER SCHRITT".equals(section);
        }
    }

    private static List<ClaimCandidate> extractClaimSections(String rawAnswer) {
        List<ClaimCandidate> sections = new ArrayList<>();
        if (rawAnswer == null || rawAnswer.isBlank()) return sections;
        String[] markers = {"KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE",
                "VERFAHREN", "NÄCHSTER SCHRITT"};
        String remaining = rawAnswer;
        for (String marker : markers) {
            int idx = remaining.indexOf(marker);
            if (idx >= 0) {
                int start = idx + marker.length();
                int end = remaining.length();
                for (String nextMarker : markers) {
                    int nextIdx = remaining.indexOf(nextMarker, start);
                    if (nextIdx >= 0 && nextIdx < end) end = nextIdx;
                }
                String section = remaining.substring(start, end).trim()
                        .replaceAll("\\*\\*", "").replaceAll("__", "")
                        .replaceAll("\\n+", " ").trim();
                if (section.length() > 20) sections.add(new ClaimCandidate(marker, section));
            }
        }
        return sections;
    }

    private static FindingElement makeFinding(String claim, int idx, double score,
                                               List<String> refs) {
        FindingRole role = idx == 0 ? FindingRole.PRIMARY_FINDING : FindingRole.SUPPORTING_FINDING;
        // The label stays concise for the collapsed view; the description
        // carries the complete claim text so the expanded view is not capped.
        String label = claim.length() > 120 ? claim.substring(0, 120) + "..." : claim;
        return new FindingElement(label, role, score, refs, List.of(), claim);
    }

    // ── Source reattribution ──

    private List<SourceCitation> reattribute(String answer, List<SourceCitation> sources) {
        Set<String> answerTokens = tokenize(answer);
        double maxOrigScore = sources.stream()
                .mapToDouble(SourceCitation::confidenceScore).max().orElse(0.0);
        List<SourceCitation> rescored = new ArrayList<>();
        for (SourceCitation s : sources) {
            double overlap = jaccardSimilarity(answerTokens,
                    tokenize(s.excerpt() != null ? s.excerpt() : ""));
            double boost;
            if (s.sourceType() == SourceCitation.SourceType.AUTHORITATIVE) {
                boost = s.confidenceScore() * (1.0 + overlap * 1.0 + 0.15);
            } else {
                boost = s.confidenceScore() * (1.0 + overlap * 2.0);
            }
            boost = Math.min(1.0, boost);
            SourceCitation.SourceTier tier;
            if (s.sourceType() == SourceCitation.SourceType.AUTHORITATIVE) {
                tier = (boost >= maxOrigScore * 0.5 || boost >= 0.3)
                        ? SourceCitation.SourceTier.PRIMARY
                        : (boost >= maxOrigScore * 0.3 || boost >= 0.15)
                        ? SourceCitation.SourceTier.SUPPORTING
                        : SourceCitation.SourceTier.BACKGROUND;
            } else {
                tier = (boost >= maxOrigScore * 1.1 || boost >= 0.45)
                        ? SourceCitation.SourceTier.PRIMARY
                        : (boost >= maxOrigScore * 0.5 || boost >= 0.15)
                        ? SourceCitation.SourceTier.SUPPORTING
                        : SourceCitation.SourceTier.BACKGROUND;
            }
            rescored.add(new SourceCitation(
                    s.documentId(), s.chunkId(), s.documentVersion(), s.title(),
                    s.pageNumber(), s.startOffset(), s.endOffset(), s.excerpt(),
                    boost, tier, s.sourceType()));
        }
        rescored.sort(Comparator.comparingDouble(SourceCitation::confidenceScore).reversed());
        if (!rescored.isEmpty() && rescored.getFirst().tier() != SourceCitation.SourceTier.PRIMARY) {
            SourceCitation top = rescored.getFirst();
            rescored.set(0, new SourceCitation(
                    top.documentId(), top.chunkId(), top.documentVersion(), top.title(),
                    top.pageNumber(), top.startOffset(), top.endOffset(), top.excerpt(),
                    top.confidenceScore(), SourceCitation.SourceTier.PRIMARY, top.sourceType()));
        }
        return rescored;
    }

    // ── Structured grounding ──

    private ReasonedAnswer groundStructured(String rawAnswer, DecisionResult decision,
                                            List<AuthorityReference> authorities) {
        double conf = Math.min(1.0, decision.confidence());
        ConfidenceProfile profile = new ConfidenceProfile(
                conf, conf, conf, conf, conf,
                "Structured knowledge: " + decision.source());
        return new ReasonedAnswer(rawAnswer, List.of(), authorities,
                null, null, profile, true, 1.0, false);
    }
}
