package reasoning.ai.verification;

import reasoning.ai.model.AiResponse;

import java.util.*;

/**
 * Result of a repair attempt by the {@link DecisionRepairEngine}.
 */
public class RepairResult {

    private final String question;
    private final boolean activated;
    private final String reason;
    private final boolean passed;
    private final double originalConfidence;
    private final double finalConfidence;
    private final AiResponse repairedResponse;
    private final List<RepairStep> steps;
    private final List<RepairCycle> cycles;

    private RepairResult(Builder b) {
        this.question = b.question;
        this.activated = b.activated;
        this.reason = b.reason;
        this.passed = b.passed;
        this.originalConfidence = b.originalConfidence;
        this.finalConfidence = b.finalConfidence;
        this.repairedResponse = b.repairedResponse;
        this.steps = List.copyOf(b.steps);
        this.cycles = List.copyOf(b.cycles);
    }

    public String question() { return question; }
    public boolean activated() { return activated; }
    public String reason() { return reason; }
    public boolean passed() { return passed; }
    public double originalConfidence() { return originalConfidence; }
    public double finalConfidence() { return finalConfidence; }
    public double confidenceGain() { return finalConfidence - originalConfidence; }
    public AiResponse repairedResponse() { return repairedResponse; }
    public List<RepairStep> steps() { return steps; }
    public List<RepairCycle> cycles() { return cycles; }

    public record RepairStep(String stage, String action, long latencyMs) {}
    public record RepairCycle(int cycle, double confidenceAfter, double confidenceBefore) {}

    public static Builder builder(String question) { return new Builder(question); }

    public static class Builder {
        private final String question;
        private boolean activated, passed;
        private String reason;
        private double originalConfidence, finalConfidence;
        private AiResponse repairedResponse;
        private final List<RepairStep> steps = new ArrayList<>();
        private final List<RepairCycle> cycles = new ArrayList<>();

        Builder(String q) { this.question = q; }

        public Builder activated(boolean v) { activated = v; return this; }
        public Builder reason(String r) { reason = r; return this; }
        public Builder passed(boolean v) { passed = v; return this; }
        public Builder originalConfidence(double v) { originalConfidence = v; return this; }
        public Builder finalConfidence(double v) { finalConfidence = v; return this; }
        public Builder repairedResponse(AiResponse r) { repairedResponse = r; return this; }
        public Builder addStep(String stage, String action, long latencyMs) {
            steps.add(new RepairStep(stage, action, latencyMs)); return this;
        }
        public Builder addCycle(int cycle, double after, double before) {
            cycles.add(new RepairCycle(cycle, after, before)); return this;
        }
        public RepairResult build() { return new RepairResult(this); }
    }
}
