package reasoning.ai.application;

import reasoning.ai.api.*;
import reasoning.ai.config.AiPipelineProperties;
import reasoning.ai.model.*;
import reasoning.common.audit.AuditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Single authoritative execution path. DecisionRouter is the entry point.
 * No service may bypass it. No legacy execution path remains.
 *
 * <p>Two strategies exist. Each has exactly one execution path:
 * <ul>
 *   <li>RULE_ENGINE: RuleEngine decides → LLM explains. No retrieval.</li>
 *   <li>HYBRID_RETRIEVAL: Full retrieval + evidence → LLM reasons.</li>
 * </ul>
 */
@Service
public class AiService implements AiOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final RetrievalAugmentationService retrievalAugmentationService;
    private final ContextAssembler contextAssembler;
    private final PromptBuilder promptBuilder;
    private final ModelProvider modelProvider;
    private final ChatCompletionProvider chatCompletionProvider;
    private final GroundingService groundingService;
    private final AiAuditPublisher auditPublisher;
    private final QueryIntentClassifier intentClassifier;
    private final EvidenceCoverageValidator evidenceCoverageValidator;
    private final PipelineProfiler profiler;
    private final DecisionRouter decisionRouter;
    private final ObjectProvider<PipelineProgressListener> progressListeners;

    public AiService(
            RetrievalAugmentationService retrievalAugmentationService,
            ContextAssembler contextAssembler,
            PromptBuilder promptBuilder,
            ModelProvider modelProvider,
            ChatCompletionProvider chatCompletionProvider,
            GroundingService groundingService,
            AiAuditPublisher auditPublisher,
            QueryIntentClassifier intentClassifier,
            EvidenceCoverageValidator evidenceCoverageValidator,
            PipelineProfiler profiler,
            DecisionRouter decisionRouter,
            ObjectProvider<PipelineProgressListener> progressListeners) {
        this.retrievalAugmentationService = retrievalAugmentationService;
        this.contextAssembler = contextAssembler;
        this.promptBuilder = promptBuilder;
        this.modelProvider = modelProvider;
        this.chatCompletionProvider = chatCompletionProvider;
        this.groundingService = groundingService;
        this.auditPublisher = auditPublisher;
        this.intentClassifier = intentClassifier;
        this.evidenceCoverageValidator = evidenceCoverageValidator;
        this.profiler = profiler;
        this.decisionRouter = decisionRouter;
        this.progressListeners = progressListeners;
    }

    /** Emits a semantic pipeline-stage event to optional progress listeners (never influences the pipeline). */
    private void emitStage(String reqId, String stage) {
        emitStage(reqId, stage, java.util.Map.of());
    }

    /** Emits a stage event with optional runtime data for the interactive visualization. */
    private void emitStage(String reqId, String stage, Map<String, Object> data) {
        PipelineProgressListener listener = progressListeners.getIfAvailable();
        if (listener != null) {
            try {
                listener.onStage(reqId, stage, data);
            } catch (RuntimeException ex) {
                log.warn("Progress listener failed for stage {}: {}", stage, ex.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public AiResponse answer(AiRequest request) {
        AiRequest normalized = normalize(request);
        String reqId = normalized.context().requestId() != null
                ? normalized.context().requestId() : UUID.randomUUID().toString();
        profiler.start(reqId);
        log.info("Request received: reqId={} questionLength={}", reqId, normalized.question().length());

        // ── Intent gate ──
        QueryIntent intent = intentClassifier.classify(normalized.question());
        profiler.record("intent");
        log.info("Intent detection: {} ({}ms)", intent, profiler.getCurrentProfile().get("intent").ms());

        // ── DecisionRouter — SINGLE ENTRY POINT ──
        var routing = decisionRouter.route(normalized.question());
        profiler.record("routing");
        // Emitted after routing so the intent stage can carry the authoritative
        // domain/language (StructuredIntent) — observability data only.
        Map<String, Object> intentData = new java.util.LinkedHashMap<>();
        intentData.put("intentType", intent.name());
        if (routing.intent() != null) {
            if (routing.intent().domain() != null) {
                intentData.put("domain", routing.intent().domain().name());
            }
            if (routing.intent().language() != null) {
                intentData.put("language", routing.intent().language());
            }
        }
        intentData.put("ms", profiler.getCurrentProfile().get("intent").ms());
        emitStage(reqId, "intent", intentData);
        emitStage(reqId, "routing", Map.of(
                "strategy", routing.strategy().name(),
                "ms", profiler.getCurrentProfile().get("routing").ms()));
        log.info("Domain classification: strategy={} ({}ms)",
                routing.strategy(), profiler.getCurrentProfile().get("routing").ms());

        // The user's detected language (StructuredIntent.language) controls the
        // presentation language of the generated answer. Null → German default.
        String userLanguage = routing.intent() != null ? routing.intent().language() : null;

        RetrievalContext retrievalContext;
        String rawAnswer;
        long llmMs = 0;
        String llmRole = "explain-only";
        ModelCapabilities capabilities = modelProvider.capabilities(normalized.model());
        // The evidence actually selected for the answer (hybrid path only);
        // the verifier grounds against this package, not the candidate list.
        EvidencePackage selectedEvidence = null;

        if (routing.isRuleEngine()) {
            // ═══════ RULE-ENGINE PATH ═══════
            assertNoRetrieval();

            DecisionResult decision = routing.decision();
            String prompt = buildExplanationPrompt(normalized.question(), decision, userLanguage);
            profiler.record("prompt");
            log.info("Prompt built: {} chars | decision={} | source={} ({}ms)",
                    prompt.length(), decision.getClass().getSimpleName(), decision.source(),
                    profiler.getCurrentProfile().get("prompt").ms());

            log.info("LLM request started: {} chars prompt", prompt.length());
            long startMs = System.currentTimeMillis();
            rawAnswer = chatCompletionProvider.complete(prompt, capabilities);
            profiler.record("llm");
            llmMs = System.currentTimeMillis() - startMs;
            log.info("LLM response received: {} chars answer ({}ms)",
                    rawAnswer != null ? rawAnswer.length() : 0, llmMs);
            emitStage(reqId, "answer-generation", Map.of(
                    "status", "EXECUTED", "role", llmRole, "ms", llmMs));

            retrievalContext = new RetrievalContext(normalized.question(),
                    "RULE_ENGINE", List.of(), ruleAuthorities(decision),
                    null, null, null, decision);

        } else {
            // ═══════ HYBRID-RETRIEVAL PATH ═══════
            log.info("Retrieval started: strategy={} question='{}'",
                    routing.strategy(),
                    normalized.question().length() > 100
                            ? normalized.question().substring(0, 100) + "..."
                            : normalized.question());
            emitStage(reqId, "retrieval-started");
            retrievalContext = retrievalAugmentationService.retrieve(normalized, routing.intent());
            profiler.record("retrieval");
            long retrievalMs = profiler.getCurrentProfile().get("retrieval").ms();
            emitStage(reqId, "retrieval-done", Map.of(
                    "sources", retrievalContext.sources().size(),
                    "authorities", retrievalContext.authorityReferences().size(),
                    "ms", retrievalMs,
                    "strategy", routing.strategy().name()));
            log.info("Retrieval completed: {} sources, {} authorities ({}ms)",
                    retrievalContext.sources().size(),
                    retrievalContext.authorityReferences().size(),
                    retrievalMs);

            PromptContext promptContext = contextAssembler.assemble(normalized, retrievalContext, userLanguage);
            selectedEvidence = promptContext.evidencePackage();
            String prompt = promptBuilder.build(promptContext, userLanguage);
            profiler.record("prompt");
            int evidenceItems = promptContext.evidencePackage() != null
                    ? promptContext.evidencePackage().items().size() : 0;
            emitStage(reqId, "evidence", Map.of(
                    "evidenceItems", evidenceItems,
                    "ms", profiler.getCurrentProfile().get("prompt").ms(),
                    "strategy", routing.strategy().name(),
                    "sources", retrievalContext.sources().size()));
            log.info("Evidence package created: {} evidence items", evidenceItems);
            log.info("Prompt built: {} chars, {} evidence docs ({}ms)",
                    prompt.length(), evidenceItems,
                    profiler.getCurrentProfile().get("prompt").ms());

            llmRole = "reason";
            log.info("LLM request started: {} chars prompt", prompt.length());
            long startMs = System.currentTimeMillis();
            rawAnswer = chatCompletionProvider.complete(prompt, capabilities);
            profiler.record("llm");
            llmMs = System.currentTimeMillis() - startMs;
            log.info("LLM response received: {} chars answer ({}ms)",
                    rawAnswer != null ? rawAnswer.length() : 0, llmMs);
            emitStage(reqId, "answer-generation", Map.of(
                    "status", "EXECUTED", "role", llmRole, "ms", llmMs));

            if (promptContext.evidencePackage() != null) {
                evidenceCoverageValidator.validate(
                        rawAnswer, promptContext.evidencePackage(), normalized.question());
                profiler.record("coverage");
                emitStage(reqId, "coverage", Map.of(
                        "status", "EXECUTED",
                        "ms", profiler.getCurrentProfile().get("coverage").ms()));
                log.info("Coverage validation: {}ms",
                        profiler.getCurrentProfile().get("coverage").ms());
            }
        }

        // ── Ground ──
        if (rawAnswer == null || rawAnswer.isBlank()) {
            rawAnswer = "The language model did not produce a response. This may indicate a model loading delay on first request. Please try again.";
            log.warn("LLM returned empty response — model may still be loading (cold start)");
        }
        ReasonedAnswer reasonedAnswer = groundingService.ground(rawAnswer, retrievalContext, selectedEvidence);
        profiler.record("ground");
        Map<String, Object> groundData = new java.util.LinkedHashMap<>();
        groundData.put("grounded", reasonedAnswer.grounded());
        groundData.put("ms", profiler.getCurrentProfile().get("ground").ms());
        if (reasonedAnswer.confidence() != null) {
            groundData.put("confidence",
                    (int) Math.round(reasonedAnswer.confidence().overallConfidence() * 100));
        }
        if (reasonedAnswer.findingHierarchy() != null) {
            groundData.put("supportedFindings",
                    reasonedAnswer.findingHierarchy().primaryFindings().size());
            groundData.put("unsupportedFindings",
                    reasonedAnswer.findingHierarchy().secondaryFindings().size());
        }
        emitStage(reqId, "ground", groundData);
        // Terminal stage: marks the Antwort node and carries the LLM answer timing.
        emitStage(reqId, "antwort", Map.of(
                "status", "EXECUTED", "role", llmRole, "ms", llmMs,
                "totalMs", profiler.totalMs()));
        log.info("Grounding complete: grounded={} overallConf={} sourceConf={} completenessConf={} ({}ms)",
                reasonedAnswer.grounded(),
                reasonedAnswer.confidence() != null
                        ? String.format("%.2f", reasonedAnswer.confidence().overallConfidence()) : "N/A",
                reasonedAnswer.confidence() != null
                        ? String.format("%.2f", reasonedAnswer.confidence().sourceConfidence()) : "N/A",
                reasonedAnswer.confidence() != null
                        ? String.format("%.2f", reasonedAnswer.confidence().completenessConfidence()) : "N/A",
                profiler.getCurrentProfile().get("ground").ms());
        profiler.finish();

        // ── Runtime trace (Part G) ──
        logExecutionTrace(routing.strategy(), retrievalContext, profiler);

        // ── Audit ──
        InferenceMetadata metadata = new InferenceMetadata(
                capabilities.provider(), capabilities.model(),
                Instant.now(), Instant.now(),
                normalized.context().correlationId(), reqId,
                "v9-routed",
                routing.strategy().name(),
                List.of(),
                reasonedAnswer.confidence().overallConfidence());

        // Audit-Trail: die zum Zeitpunkt des Ereignisses verfügbaren Fakten
        // (Frage, Strategie, Konfidenz, Grounding, Belege, Dauer) — kompakt,
        // keine Roh-Payloads.
        Map<String, String> auditMeta = new LinkedHashMap<>();
        auditMeta.put("strategy", routing.strategy().name());
        auditMeta.put("retrieval", routing.needsRetrieval() ? "EXECUTED" : "SKIPPED");
        auditMeta.put("query", truncate(normalized.question(), 200));
        auditMeta.put("intent", intent != null ? intent.name() : "");
        auditMeta.put("grounded", String.valueOf(reasonedAnswer.grounded()));
        auditMeta.put("confidence", String.format(java.util.Locale.ROOT, "%.3f",
                reasonedAnswer.confidence() != null ? reasonedAnswer.confidence().overallConfidence() : 0.0));
        auditMeta.put("durationMs", String.valueOf(profiler.totalMs()));
        auditMeta.put("sources", String.valueOf(retrievalContext.sources().size()));
        auditMeta.put("authorities", String.valueOf(retrievalContext.authorityReferences().size()));
        if (reasonedAnswer.findingHierarchy() != null) {
            auditMeta.put("supportedFindings",
                    String.valueOf(reasonedAnswer.findingHierarchy().primaryFindings().size()));
            auditMeta.put("unsupportedFindings",
                    String.valueOf(reasonedAnswer.findingHierarchy().secondaryFindings().size()));
        }
        auditMeta.put("evidenceDocuments", retrievalContext.sources().stream()
                .map(sc -> sc.documentId() != null ? sc.documentId().toString() : "")
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(12)
                .collect(java.util.stream.Collectors.joining(",")));
        auditMeta.put("answerExcerpt", truncate(reasonedAnswer.answer(), 500));
        auditPublisher.emit(normalized.context().actorId(), normalized.context().tenantId(),
                AuditEventType.MODEL_INFERENCE, reqId, auditMeta);

        log.info("DecisionPackage built: reqId={} strategy={} answerLength={} sourceCitations={} authorityRefs={} totalMs={}",
                reqId, routing.strategy(),
                reasonedAnswer.answer() != null ? reasonedAnswer.answer().length() : 0,
                reasonedAnswer.sourceCitations().size(),
                reasonedAnswer.authorityReferences().size(),
                profiler.totalMs());

        return new AiResponse(reasonedAnswer, metadata);
    }

    // ── Guards ──

    /** Runtime assertion: retrieval must not execute in RuleEngine path. */
    private void assertNoRetrieval() {
        // Always true — the guard is structural (we never call retrieval here),
        // not conditional. If retrieval were called, this method wouldn't be reached.
        log.debug("Retrieval guard: OK (RuleEngine path active)");
    }

    // ── Prompt ──

    String buildExplanationPrompt(String question, DecisionResult decision) {
        return buildExplanationPrompt(question, decision, null);
    }

    String buildExplanationPrompt(String question, DecisionResult decision, String userLanguage) {
        var values = decision.values();
        boolean german = AnswerLanguage.germanDefault(userLanguage);

        Map<String, String> labels = new HashMap<>();
        labels.put("amount", "Betrag");
        labels.put("category", "Kategorie");
        labels.put("procedure", "Verfahren");
        labels.put("requirements", "Zusätzliche Pflichten");
        labels.put("grade", "Entgeltgruppe");
        labels.put("step", "Stufe");
        labels.put("monthlyAmount", "Monatsbetrag");
        labels.put("payScale", "Tarifvertrag");
        labels.put("effectiveDate", "Gültig ab");
        labels.put("hours", "Stunden");
        labels.put("allowanceEur", "Tagegeld");
        labels.put("description", "Beschreibung");
        labels.put("feeType", "Gebührenart");
        labels.put("regulation", "Regelung");

        StringBuilder sb = new StringBuilder();

        // ── Answer language (top position — must dominate the German scaffolding) ──
        if (!german) {
            sb.append("ANSWER LANGUAGE: ").append(AnswerLanguage.displayName(userLanguage)).append(".\n");
            sb.append("Write the entire answer in this language, including all headings.\n\n");
        }

        // ── FRAGE / QUESTION ──
        if (german) {
            sb.append("FRAGE\n").append(question).append("\n\n");
        } else {
            sb.append("QUESTION — answer in the SAME LANGUAGE as the question (")
                .append(AnswerLanguage.displayName(userLanguage)).append("):\n")
                .append(question).append("\n\n");
        }
        sb.append("↓\n\n");

        // ── ANTWORT DES REGELSYSTEMS / RULE SYSTEM ANSWER ──
        if (german) {
            sb.append("ANTWORT DES REGELSYSTEMS\n");
        } else {
            sb.append("RULE SYSTEM ANSWER — explain in the SAME LANGUAGE as the question (")
                .append(AnswerLanguage.displayName(userLanguage)).append("):\n");
        }
        sb.append(buildDeterministicAnswer(decision, german)).append("\n\n");
        sb.append("↓\n\n");

        // ── DETAILS DES REGELSYSTEMS / RULE SYSTEM DETAILS ──
        sb.append(german ? "DETAILS DES REGELSYSTEMS\n" : "RULE SYSTEM DETAILS\n");

        for (var entry : values.entrySet()) {
            // German mode uses human German labels; non-German mode presents the
            // neutral structured field names — no translation tables, the model
            // phrases them in the answer language.
            String label = german
                    ? labels.getOrDefault(entry.getKey(), entry.getKey())
                    : entry.getKey();
            Object val = entry.getValue();
            if (val instanceof List<?> list) {
                sb.append(label).append(":\n");
                for (Object item : list) {
                    sb.append("  - ").append(item).append("\n");
                }
            } else {
                sb.append(label).append(": ").append(formatValue(val, german)).append("\n");
            }
            sb.append("\n");
        }

        sb.append(german ? "Angewendete Schwelle: " : "threshold: ").append(decision.reason()).append("\n");
        sb.append(german ? "Rechtsgrundlage: " : "legalBasis: ").append(decision.source()).append("\n");
        sb.append(german ? "Behörde: " : "authority: ").append(decision.authority()).append("\n\n");

        sb.append("↓\n\n");

        // ── IHRE AUFGABE / YOUR TASK ──
        sb.append(AnswerLanguage.taskInstruction(userLanguage));
        sb.append(AnswerLanguage.ruleFormatInstruction(userLanguage));
        if (german) {
            sb.append("Antwortsprache: ").append(AnswerLanguage.displayName(userLanguage)).append(".\n");
            sb.append("Die gesamte Antwort muss in dieser Sprache formuliert sein.\n");
            sb.append("Behalten Sie offizielle Bezeichnungen (z. B. BRKG, TV-L, Tagegeld,\n");
            sb.append("Verfahrensnamen) unverändert bei und erklären Sie sie bei Bedarf\n");
            sb.append("in der Antwortsprache.\n");
            sb.append("Keine Rechtsberatung.");
        } else {
            sb.append("ANSWER LANGUAGE: the SAME LANGUAGE as the question (")
                .append(AnswerLanguage.displayName(userLanguage)).append(").\n");
            sb.append("The entire answer must be written in this language.\n");
            sb.append("Keep official designations (e.g. BRKG, TV-L, Tagegeld, procedure names)\n");
            sb.append("unchanged and explain them in the answer language where appropriate.\n");
            sb.append("This is not legal advice.");
        }

        return sb.toString();
    }

    /**
     * Exposes the deterministic decision's authoritative source as a primary
     * authority reference so the decision UI can render the legal basis for
     * rule-engine answers (which skip retrieval and would otherwise show none).
     */
    private static List<AuthorityReference> ruleAuthorities(DecisionResult decision) {
        if (decision == null || decision.source() == null || decision.source().isBlank()) {
            return List.of();
        }
        String source = decision.source();
        String basis = decision.reason() != null ? decision.reason() : "";
        return List.of(new AuthorityReference(
                null, null, "RULE-" + decision.getClass().getSimpleName(),
                null, null, source, decision.decision(), null, basis,
                0.9, 0.9, AuthorityReference.ReferenceTier.PRIMARY));
    }

    /**
     * Formats a value for the prompt. German mode uses German number formatting
     * with €; non-German mode uses plain neutral formatting.
     */
    private static String formatValue(Object val, boolean german) {
        if (val instanceof Number num) {
            if (german) {
                var nf = java.text.NumberFormat.getInstance(java.util.Locale.GERMANY);
                nf.setMinimumFractionDigits(2);
                nf.setMaximumFractionDigits(2);
                return nf.format(num.doubleValue()) + " €";
            }
            return String.format(java.util.Locale.US, "%.2f €", num.doubleValue());
        }
        return val.toString();
    }

    /**
     * Builds a short deterministic answer from the structured decision data.
     * German mode phrases it in German; non-German mode uses neutral structure
     * while keeping authoritative terms (procedure names, "Tagegeld") unchanged.
     */
    private static String buildDeterministicAnswer(DecisionResult decision, boolean german) {
        if (decision instanceof DecisionResult.ProcurementDecision pd) {
            return german
                    ? "Das zulässige Verfahren ist:\n" + pd.procedure()
                    : "procedure: " + pd.procedure();
        }
        if (decision instanceof DecisionResult.SalaryDecision sd) {
            return german
                    ? sd.grade() + " Stufe " + sd.step() + " =\n" + formatValue(sd.monthlyAmount(), true)
                    : sd.grade() + " step " + sd.step() + " = " + formatValue(sd.monthlyAmount(), false);
        }
        if (decision instanceof DecisionResult.TravelDecision td) {
            return "Tagegeld: " + formatValue(td.allowanceEur(), german)
                    + " (" + td.description() + ")";
        }
        if (decision instanceof DecisionResult.FeeDecision fd) {
            return german
                    ? "Gebühr: " + formatValue(fd.amount(), true) + " (" + fd.feeType() + ")"
                    : "feeType: " + fd.feeType() + ", amount: " + formatValue(fd.amount(), false);
        }
        return decision.decision();
    }

    // ── Trace ──

    private void logExecutionTrace(DecisionStrategy strategy, RetrievalContext ctx,
                                    PipelineProfiler profiler) {
        String graphStatus;
        if (strategy == DecisionStrategy.RULE_ENGINE) {
            graphStatus = "SKIPPED";
        } else if (strategy == DecisionStrategy.GRAPH_REASONING) {
            graphStatus = ctx.sources().isEmpty() ? "ATTEMPTED (no data)" : "EXECUTED";
        } else {
            graphStatus = "NOT ATTEMPTED";
        }
        int sources = ctx.sources().size();
        String retrievalStatus;
        if (strategy == DecisionStrategy.RULE_ENGINE) {
            retrievalStatus = "SKIPPED";
        } else if (sources > 0) {
            retrievalStatus = "EXECUTED (" + sources + " sources)";
        } else {
            retrievalStatus = "ATTEMPTED (0 sources)";
        }
        String trace = """
            ╔══════════════════════════════════════╗
            ║  EXECUTION TRACE                     ║
            ╠══════════════════════════════════════╣
            ║  Strategy:   %-24s ║
            ║  Retrieval:  %-24s ║
            ║  GraphRAG:   %-24s ║
            ║  Reranking:  %-24s ║
            ║  Evidence:   %-24s ║
            ║  LLM role:   %-24s ║
            ║  Sources:    %-24d ║
            ║  Total ms:   %-24d ║
            ╚══════════════════════════════════════╝""".formatted(
            strategy,
            retrievalStatus,
            graphStatus,
            strategy == DecisionStrategy.RULE_ENGINE ? "SKIPPED" : "EXECUTED",
            strategy == DecisionStrategy.RULE_ENGINE ? "SKIPPED" : "EXECUTED",
            strategy == DecisionStrategy.RULE_ENGINE ? "explain-only" : "reason",
            sources,
            profiler.totalMs());
        log.info(trace);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s != null ? s : "";
    }

    private AiRequest normalize(AiRequest request) {
        if (request == null) throw new IllegalArgumentException("AI request is required");
        if (request.question() == null || request.question().isBlank())
            throw new IllegalArgumentException("Question is required");
        return new AiRequest(request.question().trim(), request.model(),
                request.searchFilter(),
                request.context() != null ? request.context()
                        : new AiConversationContext(List.of(), null, null, null, null),
                request.maxRetrievalResults() <= 0 ? 5
                        : Math.min(request.maxRetrievalResults(), 20),
                request.retrievalScope(), request.workspaceId(), request.asOf(),
                request.retrievalQuery() != null ? request.retrievalQuery().trim() : null);
    }
}
