package reasoning.ai.verification;

import java.util.*;

/**
 * Complete verification result containing per-stage validation findings.
 *
 * <p>Produced by {@link DecisionVerifier} after pipeline execution.
 * No LLM query is made during verification — this is pure inspection
 * of already-generated outputs.
 */
public class VerificationResult {

    private final String question;
    private final long verifiedAt;

    // Stage 1
    private final String intentPrimary;
    private final double intentConfidence;
    private final String intentSecondary;
    private final double intentSecondaryConf;
    private final boolean intentConfident;
    private final String intentMismatch;

    // Stage 2
    private final String routingStrategy;
    private final boolean routingDeterministic;
    private final boolean routingNeedsRetrieval;
    private final String routingNote;

    // Stage 3
    private final List<String> rulesFired;
    private final List<String> tablesConsulted;
    private final List<String> unusedRules;

    // Stage 4
    private final int keywordHits;
    private final int vectorHits;
    private final int graphHits;
    private final int mergedCandidates;

    // Stage 5
    private final int evidenceCount;
    private final int authorityCount;
    private final int duplicateEvidence;
    private final List<String> duplicateList;
    private final int lowScoreEvidence;
    private final List<String> lowScoreList;
    private final int totalFindings;
    private final String candidateNote;

    // Stage 6
    private final List<RerankingExplanation> rerankingExplanations;

    // Stage 7
    private final double coverage;
    private final int presentRoles;
    private final int missingRoles;
    private final List<String> missingRoleList;
    private final int pageReferences;
    private final int totalCitations;
    private final int orphanEvidence;
    private final int uniqueAuthorities;
    private final int uniqueDocuments;

    // Stage 8
    private final int promptTokens;
    private final int promptEvidenceCount;
    private final int promptRuleCount;
    private final int promptAuthorityCount;
    private final String knowledgeInjected;
    private final int compressedContextSize;
    private final List<String> missingContext;

    // Stage 9
    private final List<String> unsupportedFindings;
    private final List<String> unsupportedRecommendations;
    private final List<String> numericConflicts;
    private final List<String> invalidCitations;
    private final boolean ruleConsistent;
    private final double finalConfidence;

    // Performance
    private final long intentMs;
    private final long routingMs;
    private final long retrievalMs;
    private final long promptMs;
    private final long llmMs;
    private final long groundMs;
    private final long totalMs;

    // Attribution
    private final List<FindingAttribution> attributions;

    private VerificationResult(Builder b) {
        this.question = b.question;
        this.verifiedAt = System.currentTimeMillis();
        this.intentPrimary = b.intentPrimary;
        this.intentConfidence = b.intentConfidence;
        this.intentSecondary = b.intentSecondary;
        this.intentSecondaryConf = b.intentSecondaryConf;
        this.intentConfident = b.intentConfident;
        this.intentMismatch = b.intentMismatch;
        this.routingStrategy = b.routingStrategy;
        this.routingDeterministic = b.routingDeterministic;
        this.routingNeedsRetrieval = b.routingNeedsRetrieval;
        this.routingNote = b.routingNote;
        this.rulesFired = List.copyOf(b.rulesFired);
        this.tablesConsulted = List.copyOf(b.tablesConsulted);
        this.unusedRules = List.copyOf(b.unusedRules);
        this.keywordHits = b.keywordHits;
        this.vectorHits = b.vectorHits;
        this.graphHits = b.graphHits;
        this.mergedCandidates = b.mergedCandidates;
        this.evidenceCount = b.evidenceCount;
        this.authorityCount = b.authorityCount;
        this.duplicateEvidence = b.duplicateEvidence;
        this.duplicateList = List.copyOf(b.duplicateList);
        this.lowScoreEvidence = b.lowScoreEvidence;
        this.lowScoreList = List.copyOf(b.lowScoreList);
        this.totalFindings = b.totalFindings;
        this.candidateNote = b.candidateNote;
        this.rerankingExplanations = List.copyOf(b.rerankingExplanations);
        this.coverage = b.coverage;
        this.presentRoles = b.presentRoles;
        this.missingRoles = b.missingRoles;
        this.missingRoleList = List.copyOf(b.missingRoleList);
        this.pageReferences = b.pageReferences;
        this.totalCitations = b.totalCitations;
        this.orphanEvidence = b.orphanEvidence;
        this.uniqueAuthorities = b.uniqueAuthorities;
        this.uniqueDocuments = b.uniqueDocuments;
        this.promptTokens = b.promptTokens;
        this.promptEvidenceCount = b.promptEvidenceCount;
        this.promptRuleCount = b.promptRuleCount;
        this.promptAuthorityCount = b.promptAuthorityCount;
        this.knowledgeInjected = b.knowledgeInjected;
        this.compressedContextSize = b.compressedContextSize;
        this.missingContext = List.copyOf(b.missingContext);
        this.unsupportedFindings = List.copyOf(b.unsupportedFindings);
        this.unsupportedRecommendations = List.copyOf(b.unsupportedRecommendations);
        this.numericConflicts = List.copyOf(b.numericConflicts);
        this.invalidCitations = List.copyOf(b.invalidCitations);
        this.ruleConsistent = b.ruleConsistent;
        this.finalConfidence = b.finalConfidence;
        this.intentMs = b.intentMs;
        this.routingMs = b.routingMs;
        this.retrievalMs = b.retrievalMs;
        this.promptMs = b.promptMs;
        this.llmMs = b.llmMs;
        this.groundMs = b.groundMs;
        this.totalMs = b.totalMs;
        this.attributions = List.copyOf(b.attributions);
    }

    // ── Getters ──

    public String question() { return question; }
    public long verifiedAt() { return verifiedAt; }
    public String intentPrimary() { return intentPrimary; }
    public double intentConfidence() { return intentConfidence; }
    public String intentSecondary() { return intentSecondary; }
    public double intentSecondaryConf() { return intentSecondaryConf; }
    public boolean intentConfident() { return intentConfident; }
    public String intentMismatch() { return intentMismatch; }
    public String routingStrategy() { return routingStrategy; }
    public boolean routingDeterministic() { return routingDeterministic; }
    public boolean routingNeedsRetrieval() { return routingNeedsRetrieval; }
    public String routingNote() { return routingNote; }
    public List<String> rulesFired() { return rulesFired; }
    public List<String> tablesConsulted() { return tablesConsulted; }
    public List<String> unusedRules() { return unusedRules; }
    public int keywordHits() { return keywordHits; }
    public int vectorHits() { return vectorHits; }
    public int graphHits() { return graphHits; }
    public int mergedCandidates() { return mergedCandidates; }
    public int evidenceCount() { return evidenceCount; }
    public int authorityCount() { return authorityCount; }
    public int duplicateEvidence() { return duplicateEvidence; }
    public List<String> duplicateList() { return duplicateList; }
    public int lowScoreEvidence() { return lowScoreEvidence; }
    public List<String> lowScoreList() { return lowScoreList; }
    public int totalFindings() { return totalFindings; }
    public String candidateNote() { return candidateNote; }
    public List<RerankingExplanation> rerankingExplanations() { return rerankingExplanations; }
    public double coverage() { return coverage; }
    public int presentRoles() { return presentRoles; }
    public int missingRoles() { return missingRoles; }
    public List<String> missingRoleList() { return missingRoleList; }
    public int pageReferences() { return pageReferences; }
    public int totalCitations() { return totalCitations; }
    public int orphanEvidence() { return orphanEvidence; }
    public int uniqueAuthorities() { return uniqueAuthorities; }
    public int uniqueDocuments() { return uniqueDocuments; }
    public int promptTokens() { return promptTokens; }
    public int promptEvidenceCount() { return promptEvidenceCount; }
    public int promptRuleCount() { return promptRuleCount; }
    public int promptAuthorityCount() { return promptAuthorityCount; }
    public String knowledgeInjected() { return knowledgeInjected; }
    public int compressedContextSize() { return compressedContextSize; }
    public List<String> missingContext() { return missingContext; }
    public List<String> unsupportedFindings() { return unsupportedFindings; }
    public List<String> unsupportedRecommendations() { return unsupportedRecommendations; }
    public List<String> numericConflicts() { return numericConflicts; }
    public List<String> invalidCitations() { return invalidCitations; }
    public boolean ruleConsistent() { return ruleConsistent; }
    public double finalConfidence() { return finalConfidence; }
    public long intentMs() { return intentMs; }
    public long routingMs() { return routingMs; }
    public long retrievalMs() { return retrievalMs; }
    public long promptMs() { return promptMs; }
    public long llmMs() { return llmMs; }
    public long groundMs() { return groundMs; }
    public long totalMs() { return totalMs; }
    public List<FindingAttribution> attributions() { return attributions; }

    // ── Nested types ──

    public record RerankingExplanation(String document, double keywordScore, double semanticScore,
                                        double graphBoost, double domainBoost, double finalScore, String reason) {}

    public record FindingAttribution(String finding, List<SourceTrace> sources) {}

    public record SourceTrace(String document, String documentId, String retrievalSource,
                               double similarityScore, String rerankingExplanation, String citation) {}

    // ── Builder ──

    public static Builder builder(String question) { return new Builder(question); }

    public static class Builder {
        private final String question;
        private String intentPrimary, intentSecondary, intentMismatch, routingStrategy, routingNote;
        private double intentConfidence, intentSecondaryConf;
        private boolean intentConfident, routingDeterministic, routingNeedsRetrieval;
        private final List<String> rulesFired = new ArrayList<>(), tablesConsulted = new ArrayList<>(), unusedRules = new ArrayList<>();
        private int keywordHits, vectorHits, graphHits, mergedCandidates;
        private int evidenceCount, authorityCount, duplicateEvidence, lowScoreEvidence, totalFindings;
        private final List<String> duplicateList = new ArrayList<>(), lowScoreList = new ArrayList<>();
        private String candidateNote;
        private final List<RerankingExplanation> rerankingExplanations = new ArrayList<>();
        private double coverage;
        private int presentRoles, missingRoles, pageReferences, totalCitations, orphanEvidence, uniqueAuthorities, uniqueDocuments;
        private final List<String> missingRoleList = new ArrayList<>();
        private int promptTokens, promptEvidenceCount, promptRuleCount, promptAuthorityCount, compressedContextSize;
        private String knowledgeInjected;
        private final List<String> missingContext = new ArrayList<>();
        private final List<String> unsupportedFindings = new ArrayList<>(), unsupportedRecommendations = new ArrayList<>(), numericConflicts = new ArrayList<>(), invalidCitations = new ArrayList<>();
        private boolean ruleConsistent;
        private double finalConfidence;
        private long intentMs, routingMs, retrievalMs, promptMs, llmMs, groundMs, totalMs;
        private final List<FindingAttribution> attributions = new ArrayList<>();

        Builder(String question) { this.question = question; }

        public void putIntent(String primary, double conf, String secondary, double secConf, boolean confident, String mismatch) {
            intentPrimary = primary; intentConfidence = conf; intentSecondary = secondary;
            intentSecondaryConf = secConf; intentConfident = confident; intentMismatch = mismatch;
        }
        public void putRouting(String strategy, boolean deterministic, boolean needsRetrieval, String note) {
            routingStrategy = strategy; routingDeterministic = deterministic;
            routingNeedsRetrieval = needsRetrieval; routingNote = note;
        }
        public void putKnowledge(List<String> fired, List<String> tables, List<String> unused) {
            rulesFired.addAll(fired); tablesConsulted.addAll(tables); unusedRules.addAll(unused);
        }
        public void putRetrieval(int kw, int vec, int graph, int merged) {
            keywordHits = kw; vectorHits = vec; graphHits = graph; mergedCandidates = merged;
        }
        public void putCandidates(int ev, int auth, int dups, List<String> dupList, int low, List<String> lowList, int findings, String note) {
            evidenceCount = ev; authorityCount = auth; duplicateEvidence = dups;
            duplicateList.addAll(dupList); lowScoreEvidence = low; lowScoreList.addAll(lowList);
            totalFindings = findings; candidateNote = note;
        }
        public void putReranking(List<RerankingExplanation> explanations) {
            rerankingExplanations.addAll(explanations);
        }
        public void putEvidence(double cov, int present, int missing, List<String> missingList, int pages, int cites, int orphans, int uniqueAuth, int uniqueDocs) {
            coverage = cov; presentRoles = present; missingRoles = missing;
            missingRoleList.addAll(missingList); pageReferences = pages; totalCitations = cites;
            orphanEvidence = orphans; uniqueAuthorities = uniqueAuth; uniqueDocuments = uniqueDocs;
        }
        public void putPrompt(int tokens, int evCount, int ruleCount, int authCount, String knowledge, int compressedSize, List<String> missing) {
            promptTokens = tokens; promptEvidenceCount = evCount; promptRuleCount = ruleCount;
            promptAuthorityCount = authCount; knowledgeInjected = knowledge;
            compressedContextSize = compressedSize; missingContext.addAll(missing);
        }
        public void putLlmOutput(List<String> unsupFindings, List<String> unsupRecs, List<String> numConflicts, List<String> invCites, boolean consistent, double conf) {
            unsupportedFindings.addAll(unsupFindings); unsupportedRecommendations.addAll(unsupRecs);
            numericConflicts.addAll(numConflicts); invalidCitations.addAll(invCites);
            ruleConsistent = consistent; finalConfidence = conf;
        }
        public void putPerformance(long i, long r, long ret, long pr, long l, long g, long t) {
            intentMs = i; routingMs = r; retrievalMs = ret; promptMs = pr; llmMs = l; groundMs = g; totalMs = t;
        }
        public void putAttribution(List<FindingAttribution> a) { attributions.addAll(a); }

        public VerificationResult build() { return new VerificationResult(this); }
    }
}
