package reasoning.ai.governance;

import java.time.Instant;
import java.util.*;

/**
 * Complete reasoning chain from question to final decision.
 *
 * <p>Every node references its corresponding runtime object.
 * This is the audit trail — not a second reasoning pass.
 */
public final class DecisionLineage {

    private final String id;
    private final Instant timestamp;
    private final String question;

    // Pipeline stages
    private final LineageNode intentClassification;
    private final LineageNode routing;
    private final LineageNode structuredKnowledge;
    private final LineageNode retrieval;
    private final LineageNode candidates;
    private final LineageNode reranking;
    private final LineageNode evidencePackage;
    private final LineageNode prompt;
    private final LineageNode llmGeneration;
    private final LineageNode verification;
    private final LineageNode repair;
    private final LineageNode judging;
    private final LineageNode finalDecision;

    // Snapshots
    private final DecisionSnapshot originalSnapshot;
    private final DecisionSnapshot finalSnapshot;
    private final DecisionComparison comparison;

    private DecisionLineage(Builder b) {
        this.id = b.id;
        this.timestamp = b.timestamp;
        this.question = b.question;
        this.intentClassification = b.intentClassification;
        this.routing = b.routing;
        this.structuredKnowledge = b.structuredKnowledge;
        this.retrieval = b.retrieval;
        this.candidates = b.candidates;
        this.reranking = b.reranking;
        this.evidencePackage = b.evidencePackage;
        this.prompt = b.prompt;
        this.llmGeneration = b.llmGeneration;
        this.verification = b.verification;
        this.repair = b.repair;
        this.judging = b.judging;
        this.finalDecision = b.finalDecision;
        this.originalSnapshot = b.originalSnapshot;
        this.finalSnapshot = b.finalSnapshot;
        this.comparison = b.comparison;
    }

    // ── Getters ──

    public String id() { return id; }
    public Instant timestamp() { return timestamp; }
    public String question() { return question; }
    public LineageNode intentClassification() { return intentClassification; }
    public LineageNode routing() { return routing; }
    public LineageNode structuredKnowledge() { return structuredKnowledge; }
    public LineageNode retrieval() { return retrieval; }
    public LineageNode candidates() { return candidates; }
    public LineageNode reranking() { return reranking; }
    public LineageNode evidencePackage() { return evidencePackage; }
    public LineageNode prompt() { return prompt; }
    public LineageNode llmGeneration() { return llmGeneration; }
    public LineageNode verification() { return verification; }
    public LineageNode repair() { return repair; }
    public LineageNode judging() { return judging; }
    public LineageNode finalDecision() { return finalDecision; }
    public DecisionSnapshot originalSnapshot() { return originalSnapshot; }
    public DecisionSnapshot finalSnapshot() { return finalSnapshot; }
    public DecisionComparison comparison() { return comparison; }

    /** Returns all non-null nodes in pipeline order. */
    public List<LineageNode> allNodes() {
        List<LineageNode> nodes = new ArrayList<>();
        addIfNotNull(nodes, intentClassification);
        addIfNotNull(nodes, routing);
        addIfNotNull(nodes, structuredKnowledge);
        addIfNotNull(nodes, retrieval);
        addIfNotNull(nodes, candidates);
        addIfNotNull(nodes, reranking);
        addIfNotNull(nodes, evidencePackage);
        addIfNotNull(nodes, prompt);
        addIfNotNull(nodes, llmGeneration);
        addIfNotNull(nodes, verification);
        addIfNotNull(nodes, repair);
        addIfNotNull(nodes, judging);
        addIfNotNull(nodes, finalDecision);
        return nodes;
    }

    private static void addIfNotNull(List<LineageNode> list, LineageNode node) {
        if (node != null) list.add(node);
    }

    /**
     * A single node in the decision lineage.
     */
    public record LineageNode(
            String stage,
            String label,
            int order,
            Map<String, Object> details,
            List<LineageNode> children,
            long latencyMs,
            String status // "ok", "warning", "error", "repaired"
    ) {
        public LineageNode {
            details = details == null ? Map.of() : Map.copyOf(details);
            children = children == null ? List.of() : List.copyOf(children);
        }

        public static LineageNode of(String stage, String label, int order, Map<String, Object> details, long latencyMs) {
            return new LineageNode(stage, label, order, details, List.of(), latencyMs, "ok");
        }

        public static LineageNode withStatus(String stage, String label, int order, Map<String, Object> details, long latencyMs, String status) {
            return new LineageNode(stage, label, order, details, List.of(), latencyMs, status);
        }
    }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private Instant timestamp = Instant.now();
        private String question;
        private LineageNode intentClassification, routing, structuredKnowledge, retrieval, candidates,
                reranking, evidencePackage, prompt, llmGeneration, verification, repair, judging, finalDecision;
        private DecisionSnapshot originalSnapshot, finalSnapshot;
        private DecisionComparison comparison;

        public Builder id(String v) { id = v; return this; }
        public Builder timestamp(Instant v) { timestamp = v; return this; }
        public Builder question(String v) { question = v; return this; }
        public Builder intent(LineageNode v) { intentClassification = v; return this; }
        public Builder routing(LineageNode v) { this.routing = v; return this; }
        public Builder knowledge(LineageNode v) { structuredKnowledge = v; return this; }
        public Builder retrieval(LineageNode v) { this.retrieval = v; return this; }
        public Builder candidates(LineageNode v) { this.candidates = v; return this; }
        public Builder reranking(LineageNode v) { this.reranking = v; return this; }
        public Builder evidence(LineageNode v) { evidencePackage = v; return this; }
        public Builder prompt(LineageNode v) { this.prompt = v; return this; }
        public Builder llm(LineageNode v) { llmGeneration = v; return this; }
        public Builder verification(LineageNode v) { this.verification = v; return this; }
        public Builder repair(LineageNode v) { this.repair = v; return this; }
        public Builder judging(LineageNode v) { this.judging = v; return this; }
        public Builder finalDecision(LineageNode v) { this.finalDecision = v; return this; }
        public Builder originalSnapshot(DecisionSnapshot v) { originalSnapshot = v; return this; }
        public Builder finalSnapshot(DecisionSnapshot v) { finalSnapshot = v; return this; }
        public Builder comparison(DecisionComparison v) { comparison = v; return this; }

        public DecisionLineage build() { return new DecisionLineage(this); }
    }
}
