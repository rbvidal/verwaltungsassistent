package reasoning.ai.analytics;

import java.time.Instant;
import java.util.*;

/**
 * Immutable metrics for one complete pipeline execution.
 * Captures every stage, timing, and outcome for aggregation.
 */
public final class PipelineRunMetrics {

    private final String executionId;
    private final Instant timestamp;
    private final String domain;
    private final String question;
    // Routing
    private final String routeStrategy;
    private final boolean routeDeterministic;
    // Retrieval
    private final int keywordHits, vectorHits, graphHits, mergedCandidates;
    private final long retrievalLatencyMs;
    // Evidence
    private final int evidenceCount, authorityCount, totalCitations, pageRefs;
    private final double evidenceCoverage;
    // Prompt
    private final int promptTokens, promptRuleCount;
    private final long promptLatencyMs;
    // Generation
    private final String model;
    private final double confidence;
    private final long llmLatencyMs;
    private final int findingCount, supportedFindings, unsupportedFindings;
    // Verification
    private final boolean verificationPassed;
    private final int verificationFailures;
    private final String intentPrimary;
    private final double intentConfidence;
    // Repair
    private final boolean repairActivated;
    private final boolean repairSucceeded;
    private final String repairReason;
    private final double confidenceGain;
    private final long repairLatencyMs;
    // Governance
    private final boolean repairedSelected;
    private final double originalScore, candidateScore;
    // Knowledge
    private final List<String> knowledgeTables;
    private final List<String> rulesTriggered;
    // Overall
    private final long totalLatencyMs;
    private final String outcome; // "success", "repaired", "failed"
    // Stage breakdown
    private final List<StageMetrics> stages;

    private PipelineRunMetrics(Builder b) {
        this.executionId = b.executionId;
        this.timestamp = b.timestamp;
        this.domain = b.domain;
        this.question = b.question;
        this.routeStrategy = b.routeStrategy;
        this.routeDeterministic = b.routeDeterministic;
        this.keywordHits = b.keywordHits;
        this.vectorHits = b.vectorHits;
        this.graphHits = b.graphHits;
        this.mergedCandidates = b.mergedCandidates;
        this.retrievalLatencyMs = b.retrievalLatencyMs;
        this.evidenceCount = b.evidenceCount;
        this.authorityCount = b.authorityCount;
        this.totalCitations = b.totalCitations;
        this.pageRefs = b.pageRefs;
        this.evidenceCoverage = b.evidenceCoverage;
        this.promptTokens = b.promptTokens;
        this.promptRuleCount = b.promptRuleCount;
        this.promptLatencyMs = b.promptLatencyMs;
        this.model = b.model;
        this.confidence = b.confidence;
        this.llmLatencyMs = b.llmLatencyMs;
        this.findingCount = b.findingCount;
        this.supportedFindings = b.supportedFindings;
        this.unsupportedFindings = b.unsupportedFindings;
        this.verificationPassed = b.verificationPassed;
        this.verificationFailures = b.verificationFailures;
        this.intentPrimary = b.intentPrimary;
        this.intentConfidence = b.intentConfidence;
        this.repairActivated = b.repairActivated;
        this.repairSucceeded = b.repairSucceeded;
        this.repairReason = b.repairReason;
        this.confidenceGain = b.confidenceGain;
        this.repairLatencyMs = b.repairLatencyMs;
        this.repairedSelected = b.repairedSelected;
        this.originalScore = b.originalScore;
        this.candidateScore = b.candidateScore;
        this.knowledgeTables = List.copyOf(b.knowledgeTables);
        this.rulesTriggered = List.copyOf(b.rulesTriggered);
        this.totalLatencyMs = b.totalLatencyMs;
        this.outcome = b.outcome;
        this.stages = List.copyOf(b.stages);
    }

    // ── Getters (all fields) ──
    public String executionId() { return executionId; }
    public Instant timestamp() { return timestamp; }
    public String domain() { return domain; }
    public String question() { return question; }
    public String routeStrategy() { return routeStrategy; }
    public boolean routeDeterministic() { return routeDeterministic; }
    public int keywordHits() { return keywordHits; }
    public int vectorHits() { return vectorHits; }
    public int graphHits() { return graphHits; }
    public int mergedCandidates() { return mergedCandidates; }
    public long retrievalLatencyMs() { return retrievalLatencyMs; }
    public int evidenceCount() { return evidenceCount; }
    public int authorityCount() { return authorityCount; }
    public int totalCitations() { return totalCitations; }
    public int pageRefs() { return pageRefs; }
    public double evidenceCoverage() { return evidenceCoverage; }
    public int promptTokens() { return promptTokens; }
    public int promptRuleCount() { return promptRuleCount; }
    public long promptLatencyMs() { return promptLatencyMs; }
    public String model() { return model; }
    public double confidence() { return confidence; }
    public long llmLatencyMs() { return llmLatencyMs; }
    public int findingCount() { return findingCount; }
    public int supportedFindings() { return supportedFindings; }
    public int unsupportedFindings() { return unsupportedFindings; }
    public boolean verificationPassed() { return verificationPassed; }
    public int verificationFailures() { return verificationFailures; }
    public String intentPrimary() { return intentPrimary; }
    public double intentConfidence() { return intentConfidence; }
    public boolean repairActivated() { return repairActivated; }
    public boolean repairSucceeded() { return repairSucceeded; }
    public String repairReason() { return repairReason; }
    public double confidenceGain() { return confidenceGain; }
    public long repairLatencyMs() { return repairLatencyMs; }
    public boolean repairedSelected() { return repairedSelected; }
    public double originalScore() { return originalScore; }
    public double candidateScore() { return candidateScore; }
    public List<String> knowledgeTables() { return knowledgeTables; }
    public List<String> rulesTriggered() { return rulesTriggered; }
    public long totalLatencyMs() { return totalLatencyMs; }
    public String outcome() { return outcome; }
    public List<StageMetrics> stages() { return stages; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String executionId = UUID.randomUUID().toString();
        private Instant timestamp = Instant.now();
        private String domain = "unknown", question = "";
        private String routeStrategy = "unknown";
        private boolean routeDeterministic;
        private int keywordHits, vectorHits, graphHits, mergedCandidates;
        private long retrievalLatencyMs;
        private int evidenceCount, authorityCount, totalCitations, pageRefs;
        private double evidenceCoverage;
        private int promptTokens, promptRuleCount;
        private long promptLatencyMs;
        private String model = "unknown";
        private double confidence;
        private long llmLatencyMs;
        private int findingCount, supportedFindings, unsupportedFindings;
        private boolean verificationPassed;
        private int verificationFailures;
        private String intentPrimary;
        private double intentConfidence;
        private boolean repairActivated, repairSucceeded;
        private String repairReason;
        private double confidenceGain;
        private long repairLatencyMs;
        private boolean repairedSelected;
        private double originalScore, candidateScore;
        private final List<String> knowledgeTables = new ArrayList<>();
        private final List<String> rulesTriggered = new ArrayList<>();
        private long totalLatencyMs;
        private String outcome = "unknown";
        private final List<StageMetrics> stages = new ArrayList<>();

        public Builder executionId(String v) { executionId = v; return this; }
        public Builder timestamp(Instant v) { timestamp = v; return this; }
        public Builder domain(String v) { domain = v; return this; }
        public Builder question(String v) { question = v; return this; }
        public Builder routeStrategy(String v) { routeStrategy = v; return this; }
        public Builder routeDeterministic(boolean v) { routeDeterministic = v; return this; }
        public Builder keywordHits(int v) { keywordHits = v; return this; }
        public Builder vectorHits(int v) { vectorHits = v; return this; }
        public Builder graphHits(int v) { graphHits = v; return this; }
        public Builder mergedCandidates(int v) { mergedCandidates = v; return this; }
        public Builder retrievalLatencyMs(long v) { retrievalLatencyMs = v; return this; }
        public Builder evidenceCount(int v) { evidenceCount = v; return this; }
        public Builder authorityCount(int v) { authorityCount = v; return this; }
        public Builder totalCitations(int v) { totalCitations = v; return this; }
        public Builder pageRefs(int v) { pageRefs = v; return this; }
        public Builder evidenceCoverage(double v) { evidenceCoverage = v; return this; }
        public Builder promptTokens(int v) { promptTokens = v; return this; }
        public Builder promptRuleCount(int v) { promptRuleCount = v; return this; }
        public Builder promptLatencyMs(long v) { promptLatencyMs = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder confidence(double v) { confidence = v; return this; }
        public Builder llmLatencyMs(long v) { llmLatencyMs = v; return this; }
        public Builder findingCount(int v) { findingCount = v; return this; }
        public Builder supportedFindings(int v) { supportedFindings = v; return this; }
        public Builder unsupportedFindings(int v) { unsupportedFindings = v; return this; }
        public Builder verificationPassed(boolean v) { verificationPassed = v; return this; }
        public Builder verificationFailures(int v) { verificationFailures = v; return this; }
        public Builder intentPrimary(String v) { intentPrimary = v; return this; }
        public Builder intentConfidence(double v) { intentConfidence = v; return this; }
        public Builder repairActivated(boolean v) { repairActivated = v; return this; }
        public Builder repairSucceeded(boolean v) { repairSucceeded = v; return this; }
        public Builder repairReason(String v) { repairReason = v; return this; }
        public Builder confidenceGain(double v) { confidenceGain = v; return this; }
        public Builder repairLatencyMs(long v) { repairLatencyMs = v; return this; }
        public Builder repairedSelected(boolean v) { repairedSelected = v; return this; }
        public Builder originalScore(double v) { originalScore = v; return this; }
        public Builder candidateScore(double v) { candidateScore = v; return this; }
        public Builder knowledgeTables(List<String> v) { knowledgeTables.addAll(v); return this; }
        public Builder rulesTriggered(List<String> v) { rulesTriggered.addAll(v); return this; }
        public Builder totalLatencyMs(long v) { totalLatencyMs = v; return this; }
        public Builder outcome(String v) { outcome = v; return this; }
        public Builder addStage(StageMetrics v) { stages.add(v); return this; }
        public PipelineRunMetrics build() { return new PipelineRunMetrics(this); }
    }
}
