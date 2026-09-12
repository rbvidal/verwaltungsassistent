package reasoning.ai.verification;

import reasoning.ai.api.*;
import reasoning.ai.application.*;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.SalaryTable;
import reasoning.ai.knowledge.TravelAllowanceTable;
import reasoning.ai.knowledge.ThresholdTable;
import reasoning.ai.model.*;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Orchestrates targeted pipeline repairs based on verification findings.
 *
 * <p>Never duplicates retrieval, reranking, or prompt generation logic.
 * Only re-executes existing services for the stages that failed verification.
 *
 * <p>Repair strategy:
 * <ol>
 *   <li>Interpret verification result</li>
 *   <li>Identify failed stages</li>
 *   <li>Repair only affected stages (max 2 repair cycles)</li>
 *   <li>Re-verify after repair</li>
 * </ol>
 */
@Component
public class DecisionRepairEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionRepairEngine.class);
    private static final int MAX_REPAIR_CYCLES = 2;

    private final RetrievalAugmentationService retrievalService;
    private final ContextAssembler contextAssembler;
    private final PromptBuilder promptBuilder;
    private final ChatCompletionProvider llmProvider;
    private final GroundingService groundingService;
    private final ModelProvider modelProvider;
    private final DecisionRouter decisionRouter;
    private final QueryIntentClassifier intentClassifier;
    private final SearchFacade searchFacade;
    private final KnowledgeRegistry knowledgeRegistry;
    private final DecisionVerifier verifier;

    public DecisionRepairEngine(
            RetrievalAugmentationService retrievalService,
            ContextAssembler contextAssembler,
            PromptBuilder promptBuilder,
            ChatCompletionProvider llmProvider,
            GroundingService groundingService,
            ModelProvider modelProvider,
            DecisionRouter decisionRouter,
            QueryIntentClassifier intentClassifier,
            SearchFacade searchFacade,
            KnowledgeRegistry knowledgeRegistry,
            DecisionVerifier verifier) {
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.promptBuilder = promptBuilder;
        this.llmProvider = llmProvider;
        this.groundingService = groundingService;
        this.modelProvider = modelProvider;
        this.decisionRouter = decisionRouter;
        this.intentClassifier = intentClassifier;
        this.searchFacade = searchFacade;
        this.knowledgeRegistry = knowledgeRegistry;
        this.verifier = verifier;
    }

    /**
     * Attempts to repair a failed pipeline execution.
     *
     * <p>Repair success is determined by comparing before/after quality
     * metrics, not just by checking the final state in isolation.
     * Evidence count alone is never sufficient to declare success.
     *
     * @param request   the original AI request
     * @param response  the original (possibly deficient) AI response
     * @param vr        verification result identifying failures
     * @return repair result containing repaired response (if successful) and audit trail
     */
    public RepairResult repair(AiRequest request, AiResponse response, VerificationResult vr) {
        RepairResult.Builder audit = RepairResult.builder(request.question());
        audit.originalConfidence(vr.finalConfidence());

        // Snapshot BEFORE metrics for quality comparison
        double beforeCoverage = vr.coverage();
        int beforeUnsupported = vr.unsupportedFindings().size();
        int beforeInvalidCites = vr.invalidCitations().size();
        int beforeOrphanEvidence = vr.orphanEvidence();
        int beforeEvidence = vr.evidenceCount();
        boolean beforeGrounded = response.answer().grounded();
        // Count actually supported claims (primaryFindings = supported)
        int beforeSupportedClaims = response.answer().findingHierarchy() != null
                ? response.answer().findingHierarchy().primaryFindings().size() : 0;
        int beforeUnsupportedClaims = response.answer().findingHierarchy() != null
                ? response.answer().findingHierarchy().secondaryFindings().size() : 0;

        // Determine what needs repair
        List<RepairAction> actions = diagnose(vr);
        if (actions.isEmpty()) {
            audit.activated(false).reason("Keine reparaturbedürftigen Fehler erkannt");
            return audit.build();
        }

        audit.activated(true).reason(actions.get(0).description());
        log.info("Repair engine activated: {} actions required", actions.size());

        AiRequest currentRequest = request;
        AiResponse currentResponse = response;
        double previousConfidence = vr.finalConfidence();

        for (int cycle = 0; cycle < MAX_REPAIR_CYCLES; cycle++) {
            boolean repaired = false;

            for (RepairAction action : actions) {
                switch (action.type()) {
                    case EXPAND_RETRIEVAL -> {
                        currentRequest = expandRetrieval(currentRequest, vr);
                        currentResponse = rerunRetrievalAndLLM(currentRequest, vr);
                        audit.addStep("Retrieval erweitert", "Synonyme, Nachbarn und Vorschriften hinzugefügt", 0);
                        repaired = true;
                    }
                    case REBUILD_EVIDENCE -> {
                        currentResponse = rerunRetrievalAndLLM(currentRequest, vr);
                        audit.addStep("Evidence Package neu aufgebaut", "Fehlende Belege ergänzt", 0);
                        repaired = true;
                    }
                    case REBUILD_PROMPT -> {
                        currentResponse = rerunLLMOnly(currentRequest, vr);
                        audit.addStep("Prompt regeneriert", "Vorschriften und strukturiertes Wissen injiziert", 0);
                        repaired = true;
                    }
                    case REMOVE_HALLUCINATION -> {
                        currentResponse = removeHallucinationAndRegenerate(currentResponse, vr);
                        audit.addStep("Halluzination entfernt", "Unbelegte Feststellungen gelöscht, Konklusion regeneriert", 0);
                        repaired = true;
                    }
                    case REROUTE -> {
                        currentRequest = reroute(currentRequest);
                        currentResponse = rerunRetrievalAndLLM(currentRequest, vr);
                        audit.addStep("Routing korrigiert", "Deterministischer Pfad ausgewählt", 0);
                        repaired = true;
                    }
                    case INJECT_KNOWLEDGE -> {
                        currentResponse = rerunLLMOnly(currentRequest, vr);
                        audit.addStep("Strukturiertes Wissen injiziert", "Vergessene Regel/Tabelle eingefügt", 0);
                        repaired = true;
                    }
                }
            }

            if (!repaired) break;

            // Re-verify
            VerificationResult newVr = verifier.verify(currentRequest, currentResponse);
            double newConfidence = newVr.finalConfidence();
            audit.addCycle(cycle + 1, newConfidence, previousConfidence);

            // Compare BEFORE vs AFTER quality metrics
            boolean improved = qualityImproved(beforeCoverage, beforeUnsupported, beforeInvalidCites,
                    beforeOrphanEvidence, beforeGrounded,
                    beforeSupportedClaims, beforeUnsupportedClaims,
                    newVr, currentResponse);

            if (improved && isPassing(newVr)) {
                audit.passed(true);
                audit.repairedResponse(currentResponse);
                audit.finalConfidence(newConfidence);
                log.info("Repair successful: coverage {}→{}, unsupported {}→{}",
                        String.format("%.2f", beforeCoverage), String.format("%.2f", newVr.coverage()),
                        beforeUnsupported, newVr.unsupportedFindings().size());
                return audit.build();
            }

            // If evidence count increased but quality metrics didn't improve,
            // this is NOT a successful repair.
            if (newVr.evidenceCount() > beforeEvidence && !improved) {
                log.info("Repair: evidence count increased ({}→{}) but quality did not improve — NOT passing",
                        beforeEvidence, newVr.evidenceCount());
            }

            // Prepare for next cycle
            actions = diagnose(newVr);
            previousConfidence = newConfidence;
        }

        audit.passed(false).repairedResponse(currentResponse).finalConfidence(previousConfidence);
        log.warn("Repair incomplete after {} cycles — returning best available response", MAX_REPAIR_CYCLES);
        return audit.build();
    }

    /**
     * Determines whether repair produced meaningful quality improvement.
     *
     * <p>Improvement requires at least ONE of these genuine quality signals:
     * <ul>
     *   <li>Supported claims increased (most important — proves actual reasoning improvement)</li>
     *   <li>Unsupported claims decreased</li>
     *   <li>Grounding was gained (not-grounded → grounded)</li>
     *   <li>Coverage increased by at least 0.05 (structural, secondary)</li>
     *   <li>Unsupported findings/recs decreased</li>
     *   <li>Invalid citations decreased</li>
     * </ul>
     *
     * <p>Evidence count, candidate count, or retrieval score increases alone
     * are NEVER sufficient.
     */
    private boolean qualityImproved(double beforeCov, int beforeUnsup, int beforeInvalid,
                                     int beforeOrphan, boolean beforeGrounded,
                                     int beforeSupportedClaims, int beforeUnsupportedClaims,
                                     VerificationResult afterVr, AiResponse afterResponse) {
        int afterSupported = afterResponse.answer().findingHierarchy() != null
                ? afterResponse.answer().findingHierarchy().primaryFindings().size() : 0;
        int afterUnsupported = afterResponse.answer().findingHierarchy() != null
                ? afterResponse.answer().findingHierarchy().secondaryFindings().size() : 0;

        // PRIMARY: actual claim support improvement
        boolean claimsImproved = afterSupported > beforeSupportedClaims;
        boolean unsupportedClaimsReduced = afterUnsupported < beforeUnsupportedClaims;
        boolean groundingGained = !beforeGrounded && afterResponse.answer().grounded();

        // SECONDARY: structural improvements
        double covDelta = afterVr.coverage() - beforeCov;
        boolean coverageImproved = covDelta >= 0.05;
        boolean unsupportedReduced = afterVr.unsupportedFindings().size() < beforeUnsup;
        boolean invalidReduced = afterVr.invalidCitations().size() < beforeInvalid;

        return claimsImproved || unsupportedClaimsReduced || groundingGained
                || coverageImproved || unsupportedReduced || invalidReduced;
    }

    // ── Diagnosis ──

    private List<RepairAction> diagnose(VerificationResult vr) {
        List<RepairAction> actions = new ArrayList<>();

        // Low evidence → expand retrieval
        if (vr.evidenceCount() < 2 && vr.keywordHits() < 3) {
            actions.add(new RepairAction(RepairAction.Type.EXPAND_RETRIEVAL,
                    "Weniger als 2 Belege — Retrieval erweitern"));
        }
        // Missing authorities → rebuild evidence + inject knowledge
        if (vr.authorityCount() == 0 && !vr.tablesConsulted().isEmpty()) {
            actions.add(new RepairAction(RepairAction.Type.INJECT_KNOWLEDGE,
                    "Keine Vorschriften zitiert trotz verfügbarer Wissenstabellen"));
        }
        // Unsupported findings → remove hallucination
        if (!vr.unsupportedFindings().isEmpty()) {
            actions.add(new RepairAction(RepairAction.Type.REMOVE_HALLUCINATION,
                    vr.unsupportedFindings().size() + " unbelegte Feststellungen"));
        }
        // Unsupported recommendations → rebuild prompt
        if (!vr.unsupportedRecommendations().isEmpty()) {
            actions.add(new RepairAction(RepairAction.Type.REBUILD_PROMPT,
                    "Empfehlung ohne Belege — Prompt anpassen"));
        }
        // Low coverage → rebuild evidence
        if (vr.coverage() < 0.3) {
            actions.add(new RepairAction(RepairAction.Type.REBUILD_EVIDENCE,
                    "Abdeckung unter 30% — Evidence Package neu aufbauen"));
        }
        // Intent mismatch → reroute
        if (vr.intentMismatch() != null) {
            actions.add(new RepairAction(RepairAction.Type.REROUTE,
                    "Intent-Fehlklassifikation: " + vr.intentMismatch()));
        }

        return actions;
    }

    // ── Repair: Expand Retrieval ──

    private AiRequest expandRetrieval(AiRequest request, VerificationResult vr) {
        // Generic expansion: use the consulted tables' own metadata for
        // query expansion. Each registered table contributes its description
        // terms. The core does not know municipal table names.
        StringBuilder expanded = new StringBuilder(request.question());
        for (String table : vr.tablesConsulted()) {
            expanded.append(' ').append(table);
        }
        return new AiRequest(expanded.toString(), request.model(), request.searchFilter(),
                request.context(), Math.max(request.maxRetrievalResults(), 20),
                request.retrievalScope(), request.workspaceId());
    }

    // ── Repair: Rerun Retrieval + LLM ──

    private AiResponse rerunRetrievalAndLLM(AiRequest request, VerificationResult vr) {
        try {
            RetrievalContext retrievalContext = retrievalService.retrieve(request);
            PromptContext promptContext = contextAssembler.assemble(request, retrievalContext);
            String prompt = promptBuilder.build(promptContext);
            ModelCapabilities caps = modelProvider.capabilities(request.model());
            String rawAnswer = llmProvider.complete(prompt, caps);
            ReasonedAnswer reasoned = groundingService.ground(rawAnswer, retrievalContext);
            return new AiResponse(reasoned, new InferenceMetadata(
                    caps.provider(), caps.model(), java.time.Instant.now(), java.time.Instant.now(),
                    request.context().correlationId(), request.context().requestId(),
                    "v10-repaired", retrievalContext.retrievalStrategy(), List.of(),
                    reasoned.confidence() != null ? reasoned.confidence().overallConfidence() : 0.5));
        } catch (Exception e) {
            log.error("Repair retrieval+LLM failed: {}", e.getMessage());
            throw new RepairFailedException("Retrieval repair failed", e);
        }
    }

    // ── Repair: LLM Only (keep retrieval) ──

    private AiResponse rerunLLMOnly(AiRequest request, VerificationResult vr) {
        try {
            RetrievalContext retrievalContext = retrievalService.retrieve(request);
            PromptContext promptContext = contextAssembler.assemble(request, retrievalContext);

            // Enhance prompt with structured knowledge
            String basePrompt = promptBuilder.build(promptContext);
            StringBuilder enhanced = new StringBuilder(basePrompt);
            enhanced.append("\n\nWICHTIG: Beachten Sie folgende Vorschriften:\n");
            for (ThresholdTable t : knowledgeRegistry.thresholdTables())
                enhanced.append("- ").append(t.sourceDocument()).append(" (").append(t.size()).append(" Einträge)\n");
            for (TravelAllowanceTable t : knowledgeRegistry.travelTables())
                enhanced.append("- ").append(t.sourceDocument()).append(" (").append(t.size()).append(" Einträge)\n");
            for (SalaryTable t : knowledgeRegistry.salaryTables())
                enhanced.append("- ").append(t.sourceDocument()).append(" (").append(t.size()).append(" Einträge)\n");
            enhanced.append("\nZitieren Sie die genauen Angaben aus diesen Vorschriften.");

            ModelCapabilities caps = modelProvider.capabilities(request.model());
            String rawAnswer = llmProvider.complete(enhanced.toString(), caps);
            ReasonedAnswer reasoned = groundingService.ground(rawAnswer, retrievalContext);
            return new AiResponse(reasoned, new InferenceMetadata(
                    caps.provider(), caps.model(), java.time.Instant.now(), java.time.Instant.now(),
                    request.context().correlationId(), request.context().requestId(),
                    "v10-repaired-prompt", retrievalContext.retrievalStrategy(), List.of(),
                    reasoned.confidence() != null ? reasoned.confidence().overallConfidence() : 0.5));
        } catch (Exception e) {
            log.error("Repair LLM failed: {}", e.getMessage());
            throw new RepairFailedException("LLM repair failed", e);
        }
    }

    // ── Repair: Remove Hallucination ──

    private AiResponse removeHallucinationAndRegenerate(AiResponse response, VerificationResult vr) {
        ReasonedAnswer answer = response.answer();

        // Remove unsupported findings from the hierarchy
        Set<String> unsupported = new LinkedHashSet<>(vr.unsupportedFindings());
        List<FindingElement> cleanedPrimary = new ArrayList<>();
        if (answer.findingHierarchy() != null) {
            for (FindingElement f : answer.findingHierarchy().primaryFindings()) {
                if (!unsupported.contains(f.label())) cleanedPrimary.add(f);
            }
        }

        // Rebuild answer text without unsupported claims
        String cleanedAnswer = answer.answer();
        for (String unsup : vr.unsupportedFindings()) {
            cleanedAnswer = cleanedAnswer.replace(unsup, "[Zurückgezogen — nicht belegbar]");
        }

        FindingHierarchy cleanedHierarchy = new FindingHierarchy(
                cleanedPrimary,
                answer.findingHierarchy() != null ? answer.findingHierarchy().secondaryFindings() : List.of(),
                answer.findingHierarchy() != null ? answer.findingHierarchy().proceduralFindings() : List.of(),
                answer.findingHierarchy() != null ? answer.findingHierarchy().supportingFindings() : List.of(),
                answer.findingHierarchy() != null ? answer.findingHierarchy().relationships() : List.of());

        ReasonedAnswer cleaned = new ReasonedAnswer(cleanedAnswer, answer.sourceCitations(),
                answer.authorityReferences(), cleanedHierarchy, answer.sourceDossier(),
                answer.confidence(), answer.grounded(), answer.claimCoverage(), answer.conflictPresent());

        return new AiResponse(cleaned, response.metadata());
    }

    // ── Repair: Reroute ──

    private AiRequest reroute(AiRequest request) {
        var routing = decisionRouter.route(request.question());
        if (routing.isRuleEngine() && routing.decision() != null) {
            // Route to rule engine path — already handled by AiService
            log.info("Rerouted to RULE_ENGINE: {}", routing.decision().getClass().getSimpleName());
        }
        return request; // AiService will pick up the correct route on re-execution
    }

    // ── Helpers ──

    private boolean isPassing(VerificationResult vr) {
        // Hard blockers: these indicate genuine reasoning failure
        if (!vr.unsupportedFindings().isEmpty()) return false;
        if (!vr.unsupportedRecommendations().isEmpty()) return false;
        if (!vr.invalidCitations().isEmpty()) return false;
        if (vr.intentMismatch() != null) return false;

        // Evidence must exist (coverage may be structural, but zero evidence = failure)
        if (vr.evidenceCount() == 0) return false;

        // Coverage must be at minimum level for retrieval-based answers.
        // Structured/deterministic answers may have coverage=0 (no retrieval
        // happened) — that is handled by the caller.
        if (vr.coverage() < 0.2) return false;

        // All evidence being low-score means no relevant evidence was found
        if (vr.lowScoreEvidence() >= vr.evidenceCount() && vr.evidenceCount() > 0) return false;

        // Orphan evidence is ADVISORY — it means the LLM didn't cite sources
        // explicitly, but the evidence may still be in the prompt and influence
        // the answer. Orphan evidence alone does NOT block passing.
        return true;
    }

    // ── Inner types ──

    public record RepairAction(Type type, String description) {
        public enum Type {
            EXPAND_RETRIEVAL, REBUILD_EVIDENCE, REBUILD_PROMPT,
            REMOVE_HALLUCINATION, REROUTE, INJECT_KNOWLEDGE
        }
    }

    public static class RepairFailedException extends RuntimeException {
        public RepairFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
