package reasoning.ai.governance;

import reasoning.ai.model.*;
import reasoning.ai.verification.RepairResult;
import reasoning.ai.verification.VerificationResult;

import java.time.Instant;
import java.util.*;

/**
 * Immutable snapshot of the complete pipeline state at a specific point.
 *
 * <p>Every repair cycle creates a new snapshot. The original decision
 * is always preserved. Snapshots are compared by the DecisionJudge.
 */
public final class DecisionSnapshot implements Comparable<DecisionSnapshot> {

    private final String id;
    private final Instant timestamp;
    private final String pipelineStage; // "original", "repair-cycle-1", "repair-cycle-2"
    private final String question;
    private final AiResponse decision;
    private final VerificationResult verification;
    private final RepairResult repair;
    // Derived metrics (pre-computed for immutability)
    private final double confidence;
    private final int evidenceCount;
    private final int authorityCount;
    private final int supportedFindings;
    private final int unsupportedFindings;
    private final int totalFindings;
    private final double coverage;
    private final long latencyMs;
    private final int keywordHits;
    private final int vectorHits;
    private final int graphHits;
    private final int mergedCandidates;
    private final int promptTokens;
    private final List<String> knowledgeTablesUsed;
    private final List<String> rulesTriggered;
    private final String model;
    private final String strategy;
    private final List<SourceCitation> citations;

    private DecisionSnapshot(Builder b) {
        this.id = b.id;
        this.timestamp = b.timestamp;
        this.pipelineStage = b.pipelineStage;
        this.question = b.question;
        this.decision = b.decision;
        this.verification = b.verification;
        this.repair = b.repair;
        this.confidence = b.confidence;
        this.evidenceCount = b.evidenceCount;
        this.authorityCount = b.authorityCount;
        this.supportedFindings = b.supportedFindings;
        this.unsupportedFindings = b.unsupportedFindings;
        this.totalFindings = b.totalFindings;
        this.coverage = b.coverage;
        this.latencyMs = b.latencyMs;
        this.keywordHits = b.keywordHits;
        this.vectorHits = b.vectorHits;
        this.graphHits = b.graphHits;
        this.mergedCandidates = b.mergedCandidates;
        this.promptTokens = b.promptTokens;
        this.knowledgeTablesUsed = List.copyOf(b.knowledgeTablesUsed);
        this.rulesTriggered = List.copyOf(b.rulesTriggered);
        this.model = b.model;
        this.strategy = b.strategy;
        this.citations = List.copyOf(b.citations);
    }

    // ── Getters ──

    public String id() { return id; }
    public Instant timestamp() { return timestamp; }
    public String pipelineStage() { return pipelineStage; }
    public String question() { return question; }
    public AiResponse decision() { return decision; }
    public VerificationResult verification() { return verification; }
    public RepairResult repair() { return repair; }
    public double confidence() { return confidence; }
    public int evidenceCount() { return evidenceCount; }
    public int authorityCount() { return authorityCount; }
    public int supportedFindings() { return supportedFindings; }
    public int unsupportedFindings() { return unsupportedFindings; }
    public int totalFindings() { return totalFindings; }
    public double coverage() { return coverage; }
    public long latencyMs() { return latencyMs; }
    public int keywordHits() { return keywordHits; }
    public int vectorHits() { return vectorHits; }
    public int graphHits() { return graphHits; }
    public int mergedCandidates() { return mergedCandidates; }
    public int promptTokens() { return promptTokens; }
    public List<String> knowledgeTablesUsed() { return knowledgeTablesUsed; }
    public List<String> rulesTriggered() { return rulesTriggered; }
    public String model() { return model; }
    public String strategy() { return strategy; }
    public List<SourceCitation> citations() { return citations; }
    public boolean isRepaired() { return pipelineStage != null && pipelineStage.contains("repair"); }

    /** Score used for comparison — higher is better */
    public double qualityScore() {
        double score = confidence * 0.25 + coverage * 0.20;
        if (totalFindings > 0) score += ((double) supportedFindings / totalFindings) * 0.20;
        if (unsupportedFindings == 0) score += 0.15;
        if (evidenceCount >= 2) score += 0.10;
        if (authorityCount >= 1) score += 0.10;
        return Math.min(1.0, score);
    }

    @Override
    public int compareTo(DecisionSnapshot o) {
        return Double.compare(o.qualityScore(), this.qualityScore());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecisionSnapshot that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return "DecisionSnapshot{id=" + id + ", stage=" + pipelineStage
                + ", confidence=" + String.format("%.2f", confidence)
                + ", score=" + String.format("%.2f", qualityScore()) + "}";
    }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private Instant timestamp = Instant.now();
        private String pipelineStage = "original";
        private String question;
        private AiResponse decision;
        private VerificationResult verification;
        private RepairResult repair;
        private double confidence;
        private int evidenceCount, authorityCount, supportedFindings, unsupportedFindings, totalFindings;
        private double coverage;
        private long latencyMs;
        private int keywordHits, vectorHits, graphHits, mergedCandidates, promptTokens;
        private final List<String> knowledgeTablesUsed = new ArrayList<>();
        private final List<String> rulesTriggered = new ArrayList<>();
        private String model, strategy;
        private final List<SourceCitation> citations = new ArrayList<>();

        public Builder id(String v) { id = v; return this; }
        public Builder timestamp(Instant v) { timestamp = v; return this; }
        public Builder stage(String v) { pipelineStage = v; return this; }
        public Builder question(String v) { question = v; return this; }
        public Builder decision(AiResponse v) { decision = v; return this; }
        public Builder verification(VerificationResult v) { verification = v; return this; }
        public Builder repair(RepairResult v) { repair = v; return this; }
        public Builder confidence(double v) { confidence = v; return this; }
        public Builder evidenceCount(int v) { evidenceCount = v; return this; }
        public Builder authorityCount(int v) { authorityCount = v; return this; }
        public Builder supportedFindings(int v) { supportedFindings = v; return this; }
        public Builder unsupportedFindings(int v) { unsupportedFindings = v; return this; }
        public Builder totalFindings(int v) { totalFindings = v; return this; }
        public Builder coverage(double v) { coverage = v; return this; }
        public Builder latencyMs(long v) { latencyMs = v; return this; }
        public Builder keywordHits(int v) { keywordHits = v; return this; }
        public Builder vectorHits(int v) { vectorHits = v; return this; }
        public Builder graphHits(int v) { graphHits = v; return this; }
        public Builder mergedCandidates(int v) { mergedCandidates = v; return this; }
        public Builder promptTokens(int v) { promptTokens = v; return this; }
        public Builder knowledgeTables(List<String> v) { knowledgeTablesUsed.addAll(v); return this; }
        public Builder rulesTriggered(List<String> v) { rulesTriggered.addAll(v); return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder strategy(String v) { strategy = v; return this; }
        public Builder citations(List<SourceCitation> v) { citations.addAll(v); return this; }

        /** Populate metrics from an AI response. */
        public Builder fromResponse(AiResponse response) {
            ReasonedAnswer a = response.answer();
            InferenceMetadata m = response.metadata();
            decision(response);
            confidence(a.confidence() != null ? a.confidence().overallConfidence() : 0);
            evidenceCount(a.sourceCitations() != null ? a.sourceCitations().size() : 0);
            authorityCount(a.authorityReferences() != null ? a.authorityReferences().size() : 0);
            if (a.findingHierarchy() != null) {
                FindingHierarchy fh = a.findingHierarchy();
                totalFindings = fh.primaryFindings().size() + fh.secondaryFindings().size()
                        + fh.proceduralFindings().size() + fh.supportingFindings().size();
                supportedFindings = fh.primaryFindings().size();
                unsupportedFindings = totalFindings - supportedFindings;
            }
            coverage(a.sourceDossier() != null ? a.sourceDossier().coverageScore() : 0);
            model(m.model());
            strategy(m.retrievalStrategy());
            if (a.sourceCitations() != null) citations(a.sourceCitations());
            return this;
        }

        /** Populate metrics from a verification result. */
        public Builder fromVerification(VerificationResult vr) {
            verification(vr);
            if (vr != null) {
                keywordHits(vr.keywordHits());
                vectorHits(vr.vectorHits());
                graphHits(vr.graphHits());
                mergedCandidates(vr.mergedCandidates());
                promptTokens(vr.promptTokens());
                knowledgeTables(vr.tablesConsulted());
                rulesTriggered(vr.rulesFired());
                supportedFindings(vr.totalFindings() - vr.unsupportedFindings().size());
                unsupportedFindings(vr.unsupportedFindings().size());
                totalFindings(vr.totalFindings());
            }
            return this;
        }

        public DecisionSnapshot build() { return new DecisionSnapshot(this); }
    }
}
