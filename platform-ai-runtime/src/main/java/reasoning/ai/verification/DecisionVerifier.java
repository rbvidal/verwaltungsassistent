package reasoning.ai.verification;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.RetrievalAugmentationService;
import reasoning.ai.application.*;
import reasoning.ai.config.AiProviderProperties;
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
 * Post-hoc verification of every pipeline stage.
 *
 * <p>This verifier consumes the outputs of all pipeline stages and
 * validates each one. It does NOT query the LLM again. It does NOT
 * bypass or replace any existing pipeline component.
 *
 * <p>Verification stages:
 * <ol>
 *   <li>Intent Classification — domain consistency check</li>
 *   <li>Routing — strategy correctness</li>
 *   <li>Structured Knowledge — rule/table usage</li>
 *   <li>Hybrid Retrieval — keyword/vector/graph contribution</li>
 *   <li>Candidate Generation — dedup, conflicts, missing evidence</li>
 *   <li>Reranking — top document explanation</li>
 *   <li>Evidence Package — coverage, diversity, citations</li>
 *   <li>Prompt Builder — size, counts, missing context</li>
 *   <li>LLM Output — hallucinations, unsupported claims</li>
 * </ol>
 */
@Component
public class DecisionVerifier {

    private static final Logger log = LoggerFactory.getLogger(DecisionVerifier.class);

    private final SearchFacade searchFacade;
    private final KnowledgeRegistry knowledgeRegistry;
    private final DomainClassifier domainClassifier;
    private final PipelineProfiler profiler;
    private final AiProviderProperties aiProperties;

    public DecisionVerifier(SearchFacade searchFacade,
                            KnowledgeRegistry knowledgeRegistry,
                            DomainClassifier domainClassifier,
                            PipelineProfiler profiler,
                            AiProviderProperties aiProperties) {
        this.searchFacade = searchFacade;
        this.knowledgeRegistry = knowledgeRegistry;
        this.domainClassifier = domainClassifier;
        this.profiler = profiler;
        this.aiProperties = aiProperties;
    }

    /**
     * Verifies an executed pipeline by inspecting all available outputs.
     *
     * @param request   the original AI request
     * @param response  the complete AI response
     * @return full verification result with per-stage findings
     */
    public VerificationResult verify(AiRequest request, AiResponse response) {
        ReasonedAnswer answer = response.answer();
        InferenceMetadata metadata = response.metadata();

        VerificationResult.Builder v = VerificationResult.builder(request.question());

        // ── Stage 1: Intent Classification ──
        verifyIntent(request.question(), metadata, answer, v);

        // ── Stage 2: Routing ──
        verifyRouting(metadata, v);

        // ── Stage 3: Structured Knowledge ──
        verifyKnowledge(request.question(), answer, metadata, v);

        // ── Stage 4: Hybrid Retrieval ──
        verifyRetrieval(request.question(), answer, v);

        // ── Stage 5: Candidate Generation ──
        verifyCandidates(answer, v);

        // ── Stage 6: Reranking ──
        verifyReranking(answer, v);

        // ── Stage 7: Evidence Package ──
        verifyEvidence(answer, v);

        // ── Stage 8: Prompt Builder ──
        verifyPrompt(answer, metadata, v);

        // ── Stage 9: LLM Output ──
        verifyLlmOutput(answer, v);

        // ── Performance ──
        verifyPerformance(v);

        // ── Retrieval Attribution ──
        buildAttribution(answer, v);

        return v.build();
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 1: Intent Classification
    // ═══════════════════════════════════════════════════════════

    private void verifyIntent(String question, InferenceMetadata metadata,
                               ReasonedAnswer answer, VerificationResult.Builder v) {
        DomainClassifier.DomainResult domain = domainClassifier.classify(question);

        // Check domain consistency with triggered rules
        String strategy = metadata.retrievalStrategy();
        List<String> issues = new ArrayList<>();

        if ("RULE_ENGINE".equals(strategy)) {
            // Check that the domain matches the triggered rules
            if ("procurement".equals(domain.primary().name().toLowerCase())
                    && answer.authorityReferences().stream().noneMatch(
                        a -> a.entryTitle() != null
                            && a.entryTitle().toLowerCase().contains("av"))) {
                issues.add("RULE_ENGINE triggered but procurement authority not cited");
            }
        }

        v.putIntent(domain.primary().name(), domain.primaryConfidence(),
                domain.secondary() != null ? domain.secondary().name() : null,
                domain.secondaryConfidence(),
                domain.isConfident(),
                issues.isEmpty() ? null : String.join("; ", issues));
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 2: Routing
    // ═══════════════════════════════════════════════════════════

    private void verifyRouting(InferenceMetadata metadata, VerificationResult.Builder v) {
        String strategy = metadata.retrievalStrategy();
        boolean deterministic = "RULE_ENGINE".equals(strategy);
        boolean hybrid = "HYBRID_RETRIEVAL".equals(strategy);
        boolean graph = "GRAPH_REASONING".equals(strategy);

        List<String> notes = new ArrayList<>();
        if (deterministic) {
            notes.add("Deterministic path — retrieval skipped, LLM explains only");
        } else if (hybrid) {
            notes.add("Hybrid retrieval — full retrieval + evidence + LLM reasoning");
        } else if (graph) {
            notes.add("Graph reasoning — Neo4j-enhanced retrieval");
        }

        v.putRouting(strategy, deterministic, hybrid || graph,
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 3: Structured Knowledge
    // ═══════════════════════════════════════════════════════════

    private void verifyKnowledge(String question, ReasonedAnswer answer,
                                  InferenceMetadata metadata, VerificationResult.Builder v) {
        List<String> rulesFired = new ArrayList<>();
        List<String> tablesConsulted = new ArrayList<>();
        List<String> unusedRules = new ArrayList<>();

        // Build a list of ALL registered structured tables for reference
        List<String> tablesAvailable = new ArrayList<>();
        knowledgeRegistry.salaryTables().forEach(t ->
            tablesAvailable.add("SalaryTable(" + t.payScale() + ") entries=" + t.size()));
        knowledgeRegistry.travelTables().forEach(t ->
            tablesAvailable.add("TravelAllowanceTable(" + t.regulation() + ") entries=" + t.size()));
        knowledgeRegistry.thresholdTables().forEach(t ->
            tablesAvailable.add("ThresholdTable(" + t.regulation() + ") entries=" + t.size()));

        // Only report tables that were ACTUALLY consulted for this decision.
        // For RULE_ENGINE: the confidence explanation names the source table.
        // For retrieval paths: no structured knowledge tables were consulted.
        String strategy = metadata.retrievalStrategy();
        if ("RULE_ENGINE".equals(strategy) && answer.confidence() != null) {
            String explanation = answer.confidence().explanation();
            if (explanation != null) {
                // Check ALL registered tables — not specific municipal ones
                for (TravelAllowanceTable t : knowledgeRegistry.travelTables()) {
                    if (explanation.contains(t.regulation())) {
                        tablesConsulted.add("TravelAllowanceTable(" + t.regulation() + ") entries=" + t.size());
                    }
                }
                for (SalaryTable t : knowledgeRegistry.salaryTables()) {
                    if (explanation.contains(t.payScale()) || explanation.contains("Entgelttabelle")) {
                        tablesConsulted.add("SalaryTable(" + t.payScale() + ") entries=" + t.size());
                    }
                }
                for (ThresholdTable t : knowledgeRegistry.thresholdTables()) {
                    if (explanation.contains(t.regulation()) || explanation.contains("Wertgrenze")) {
                        tablesConsulted.add("ThresholdTable(" + t.regulation() + ") entries=" + t.size());
                    }
                }
            }
        }

        // Detect which structured knowledge rules fired based on consulted tables.
        // This is generic: any registered table that was consulted = a rule that fired.
        // Tables that are registered but NOT consulted = unused rules.
        for (SalaryTable t : knowledgeRegistry.salaryTables()) {
            if (tablesConsulted.stream().anyMatch(c -> c.contains(t.payScale()))) {
                rulesFired.add("salary-query (" + t.payScale() + ")");
            } else {
                unusedRules.add("salary-query (" + t.payScale() + " loaded but not triggered)");
            }
        }
        for (TravelAllowanceTable t : knowledgeRegistry.travelTables()) {
            if (tablesConsulted.stream().anyMatch(c -> c.contains(t.regulation()))) {
                rulesFired.add("travel-expense (" + t.regulation() + ")");
            } else {
                unusedRules.add("travel-expense (" + t.regulation() + " loaded but not triggered)");
            }
        }
        for (ThresholdTable t : knowledgeRegistry.thresholdTables()) {
            if (tablesConsulted.stream().anyMatch(c -> c.contains(t.regulation()))) {
                rulesFired.add("procurement-threshold (" + t.regulation() + ")");
            } else {
                unusedRules.add("procurement-threshold (" + t.regulation() + " loaded but not triggered)");
            }
        }

        // Append available tables note to unused rules for full transparency
        if (!"RULE_ENGINE".equals(strategy) && !tablesAvailable.isEmpty()) {
            unusedRules.add("Structured tables available (not used for retrieval path): "
                    + String.join(", ", tablesAvailable));
        }

        v.putKnowledge(rulesFired, tablesConsulted, unusedRules);
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 4: Hybrid Retrieval
    // ═══════════════════════════════════════════════════════════

    private void verifyRetrieval(String question, ReasonedAnswer answer,
                                  VerificationResult.Builder v) {
        // Run validation searches (not re-querying LLM — just validating search)
        var keywordResults = runValidationSearch(question, SearchMode.KEYWORD, 10);
        var vectorResults = runValidationSearch(question, SearchMode.SEMANTIC, 10);

        int keywordHits = keywordResults.size();
        int vectorHits = vectorResults.size();

        // Graph search is through Neo4j — check if we have graph search capability
        int graphHits = 0;
        try {
            var graphResults = runValidationSearch(question, SearchMode.GRAPH, 5);
            graphHits = graphResults.size();
        } catch (Exception ignored) {}

        // Merged count (deduplicated across sources)
        Set<String> allDocIds = new LinkedHashSet<>();
        keywordResults.forEach(r -> allDocIds.add(r.chunk().documentId().toString()));
        vectorResults.forEach(r -> allDocIds.add(r.chunk().documentId().toString()));

        v.putRetrieval(keywordHits, vectorHits, graphHits, allDocIds.size());
    }

    private List<SearchResult> runValidationSearch(String question, SearchMode mode, int size) {
        try {
            SearchQuery query = new SearchQuery(question, mode,
                    new SearchFilter(null, null, null, null, null, null, null, null, List.of()),
                    new SearchRequestContext("verifier", null, null, null), 0, size);
            return searchFacade.search(query).results();
        } catch (Exception e) {
            log.debug("Validation search failed for mode {}: {}", mode, e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 5: Candidate Generation
    // ═══════════════════════════════════════════════════════════

    private void verifyCandidates(ReasonedAnswer answer, VerificationResult.Builder v) {
        int evidenceCount = answer.sourceCitations() != null ? answer.sourceCitations().size() : 0;
        int authorityCount = answer.authorityReferences() != null ? answer.authorityReferences().size() : 0;

        // Detect duplicates
        Set<String> seenDocs = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        if (answer.sourceCitations() != null) {
            for (SourceCitation sc : answer.sourceCitations()) {
                String docId = sc.documentId().toString();
                if (!seenDocs.add(docId)) {
                    duplicates.add(sc.title() != null ? sc.title() : docId);
                }
            }
        }

        // Detect low-score evidence
        List<String> lowScoreEvidence = new ArrayList<>();
        if (answer.sourceCitations() != null) {
            for (SourceCitation sc : answer.sourceCitations()) {
                if (sc.confidenceScore() < 0.15) {
                    lowScoreEvidence.add(sc.title() != null ? sc.title() : sc.documentId().toString());
                }
            }
        }

        int totalFindings = countFindings(answer.findingHierarchy());

        v.putCandidates(evidenceCount, authorityCount, duplicates.size(),
                duplicates, lowScoreEvidence.size(), lowScoreEvidence,
                totalFindings, totalFindings > evidenceCount ? "Weniger Belege als Feststellungen — mögliche unbelegte Aussagen" : null);
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 6: Reranking
    // ═══════════════════════════════════════════════════════════

    private void verifyReranking(ReasonedAnswer answer, VerificationResult.Builder v) {
        if (answer.sourceCitations() == null || answer.sourceCitations().isEmpty()) {
            v.putReranking(List.of());
            return;
        }

        // Explain why top documents won (based on scores)
        List<VerificationResult.RerankingExplanation> explanations = new ArrayList<>();
        List<SourceCitation> sorted = new ArrayList<>(answer.sourceCitations());
        sorted.sort(Comparator.comparingDouble(SourceCitation::confidenceScore).reversed());

        int limit = Math.min(3, sorted.size());
        for (int i = 0; i < limit; i++) {
            SourceCitation sc = sorted.get(i);
            double score = sc.confidenceScore();
            String reason = score >= 0.7 ? "Hohe Relevanz — starke keyword/vector Übereinstimmung"
                    : score >= 0.4 ? "Moderate Relevanz — teilweise Übereinstimmung"
                    : "Niedrige Relevanz — schwache keyword/vector Übereinstimmung";
            explanations.add(new VerificationResult.RerankingExplanation(
                    sc.title() != null ? sc.title() : sc.documentId().toString(),
                    score, score, 0.0, 0.0, score, reason));
        }

        v.putReranking(explanations);
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 7: Evidence Package
    // ═══════════════════════════════════════════════════════════

    private void verifyEvidence(ReasonedAnswer answer, VerificationResult.Builder v) {
        SourceDossier dossier = answer.sourceDossier();
        double coverage = dossier != null ? dossier.coverageScore() : 0;
        List<String> presentRoles = dossier != null ? dossier.presentRoles() : List.of();
        List<String> missingRoles = dossier != null ? dossier.missingRoles() : List.of();

        // Count page references
        long pageRefs = 0;
        long totalCitations = answer.sourceCitations() != null ? answer.sourceCitations().size() : 0;
        if (answer.sourceCitations() != null) {
            pageRefs = answer.sourceCitations().stream()
                    .filter(sc -> sc.pageNumber() != null && sc.pageNumber() > 0).count();
        }

        // Orphan evidence: citations not referenced by any finding
        int orphanCount = 0;
        if (answer.findingHierarchy() != null && answer.sourceCitations() != null) {
            Set<String> citedInFindings = new LinkedHashSet<>();
            for (FindingElement f : answer.findingHierarchy().primaryFindings()) {
                if (f.governingReferences() != null) citedInFindings.addAll(f.governingReferences());
            }
            for (SourceCitation sc : answer.sourceCitations()) {
                if (!citedInFindings.contains(sc.title())
                        && !citedInFindings.contains(sc.documentId().toString())) {
                    orphanCount++;
                }
            }
        }

        // Authority diversity
        Set<String> uniqueAuthorities = new LinkedHashSet<>();
        if (answer.authorityReferences() != null) {
            answer.authorityReferences().stream()
                    .map(AuthorityReference::entryTitle)
                    .filter(Objects::nonNull)
                    .forEach(uniqueAuthorities::add);
        }

        // Document diversity
        Set<String> uniqueDocs = new LinkedHashSet<>();
        if (answer.sourceCitations() != null) {
            answer.sourceCitations().stream()
                    .map(sc -> sc.documentId().toString())
                    .forEach(uniqueDocs::add);
        }

        v.putEvidence(coverage, presentRoles.size(), missingRoles.size(),
                missingRoles, (int) pageRefs, (int) totalCitations,
                orphanCount, uniqueAuthorities.size(), uniqueDocs.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 8: Prompt Builder
    // ═══════════════════════════════════════════════════════════

    private void verifyPrompt(ReasonedAnswer answer, InferenceMetadata metadata,
                               VerificationResult.Builder v) {
        int evidenceCount = answer.sourceCitations() != null ? answer.sourceCitations().size() : 0;
        int authorityCount = answer.authorityReferences() != null ? answer.authorityReferences().size() : 0;
        int ruleCount = metadata.referencedChunkIds() != null ? metadata.referencedChunkIds().size() : 0;

        // Estimate prompt size based on token count (rough estimate: 4 chars per token)
        int estimatedTokens = 0;
        if (answer.answer() != null) estimatedTokens += answer.answer().length() / 4;
        if (answer.sourceCitations() != null) {
            for (SourceCitation sc : answer.sourceCitations()) {
                if (sc.excerpt() != null) estimatedTokens += sc.excerpt().length() / 4;
            }
        }
        estimatedTokens += 500; // system prompt overhead

        // Detect missing context
        List<String> missingContext = new ArrayList<>();
        if (evidenceCount == 0) missingContext.add("Keine Belege im Prompt enthalten");
        if (authorityCount == 0) missingContext.add("Keine Vorschriften referenziert");
        if (metadata.model() == null) missingContext.add("Modell nicht spezifiziert");

        v.putPrompt(estimatedTokens, evidenceCount, ruleCount, authorityCount,
                "structured-knowledge-injected", 0, missingContext);
    }

    // ═══════════════════════════════════════════════════════════
    // Stage 9: LLM Output
    // ═══════════════════════════════════════════════════════════

    private void verifyLlmOutput(ReasonedAnswer answer, VerificationResult.Builder v) {
        List<String> unsupportedFindings = new ArrayList<>();
        List<String> unsupportedRecommendations = new ArrayList<>();
        List<String> numericConflicts = new ArrayList<>();
        List<String> invalidCitations = new ArrayList<>();

        // Check findings without citations
        if (answer.findingHierarchy() != null) {
            for (FindingElement f : answer.findingHierarchy().primaryFindings()) {
                if ((f.governingReferences() == null || f.governingReferences().isEmpty())
                        && (f.relatedReferences() == null || f.relatedReferences().isEmpty())) {
                    unsupportedFindings.add(f.label());
                }
            }
        }

        // Check recommendation without evidence
        if (answer.answer() != null && !answer.answer().isBlank()
                && (answer.sourceCitations() == null || answer.sourceCitations().isEmpty())
                && (answer.authorityReferences() == null || answer.authorityReferences().isEmpty())) {
            unsupportedRecommendations.add("Empfehlung ohne Belege oder Vorschriften");
        }

        // Check citation validity
        if (answer.sourceCitations() != null) {
            for (SourceCitation sc : answer.sourceCitations()) {
                if (sc.title() == null || sc.title().isBlank()) {
                    invalidCitations.add("Beleg ohne Titel (ID: " + sc.documentId() + ")");
                }
                if (sc.excerpt() == null || sc.excerpt().isBlank()) {
                    invalidCitations.add("Beleg '" + (sc.title() != null ? sc.title() : sc.documentId())
                            + "' ohne Auszug/Excerpt");
                }
            }
        }

        // Check rule consistency
        boolean ruleConsistent = answer.grounded();

        v.putLlmOutput(unsupportedFindings, unsupportedRecommendations,
                numericConflicts, invalidCitations, ruleConsistent,
                answer.confidence() != null ? answer.confidence().overallConfidence() : 0);
    }

    // ═══════════════════════════════════════════════════════════
    // Performance
    // ═══════════════════════════════════════════════════════════

    private void verifyPerformance(VerificationResult.Builder v) {
        Map<String, PipelineProfiler.StageTiming> profile = profiler.getCurrentProfile();
        long intentMs = ms(profile, "intent");
        long routingMs = ms(profile, "routing");
        long retrievalMs = ms(profile, "retrieval");
        long promptMs = ms(profile, "prompt");
        long llmMs = ms(profile, "llm");
        long groundMs = ms(profile, "ground");
        long totalMs = profiler.totalMs();

        v.putPerformance(intentMs, routingMs, retrievalMs, promptMs, llmMs, groundMs, totalMs);
    }

    // ═══════════════════════════════════════════════════════════
    // Retrieval Attribution
    // ═══════════════════════════════════════════════════════════

    private void buildAttribution(ReasonedAnswer answer, VerificationResult.Builder v) {
        List<VerificationResult.FindingAttribution> attributions = new ArrayList<>();

        if (answer.findingHierarchy() != null) {
            for (FindingElement f : answer.findingHierarchy().primaryFindings()) {
                List<VerificationResult.SourceTrace> traces = new ArrayList<>();

                // For each finding, trace back through the sources
                if (f.governingReferences() != null && answer.sourceCitations() != null) {
                    for (String ref : f.governingReferences()) {
                        for (SourceCitation sc : answer.sourceCitations()) {
                            if (ref.equals(sc.title()) || ref.equals(sc.documentId().toString())) {
                                traces.add(new VerificationResult.SourceTrace(
                                        sc.title() != null ? sc.title() : sc.documentId().toString(),
                                        sc.documentId().toString(),
                                        "hybrid", // source retrieval type
                                        sc.confidenceScore(),
                                        "Top-" + (sc.confidenceScore() >= 0.7 ? "Rank" : "Mid"),
                                        sc.excerpt() != null ? sc.excerpt() : ""));
                            }
                        }
                    }
                }

                attributions.add(new VerificationResult.FindingAttribution(
                        f.label(), traces));
            }
        }

        v.putAttribution(attributions);
    }

    // ── Helpers ──

    private long ms(Map<String, PipelineProfiler.StageTiming> p, String stage) {
        PipelineProfiler.StageTiming t = p.get(stage);
        return t != null ? t.ms() : 0;
    }

    private int countFindings(FindingHierarchy fh) {
        if (fh == null) return 0;
        return fh.primaryFindings().size() + fh.secondaryFindings().size()
                + fh.proceduralFindings().size() + fh.supportingFindings().size();
    }

    private boolean containsAny(String text, String... terms) {
        for (String t : terms) if (text.contains(t)) return true;
        return false;
    }
}
