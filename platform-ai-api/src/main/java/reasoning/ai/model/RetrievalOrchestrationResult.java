package reasoning.ai.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of a retrieval orchestration run, containing the retrieval context
 * and full explainability metadata about how the retrieval was performed.
 */
public record RetrievalOrchestrationResult(
        RetrievalContext retrievalContext,

        // Explainability metadata
        String intent,
        String selectedStrategy,
        String providerName,
        String promptTemplateId,
        int promptTemplateVersion,
        String modelName,
        Instant retrievalStartedAt,
        Instant retrievalCompletedAt,
        int keywordResultCount,
        int vectorResultCount,
        int graphNodeCount,
        int totalChunkCount,
        int totalSourceCount,
        String fusionMethod,
        boolean rerankingApplied,
        String rerankingProvider,
        Map<String, Object> evaluationScores,
        List<String> traceLog
) {
    public RetrievalOrchestrationResult {
        evaluationScores = evaluationScores != null ? Map.copyOf(evaluationScores) : Map.of();
        traceLog = traceLog != null ? List.copyOf(traceLog) : List.of();
    }

    /** Returns a human-readable summary of the retrieval decisions. */
    public String explain() {
        return String.format(
                "Intent: %s | Strategy: %s | Provider: %s | Model: %s | Prompt: %s/v%d | " +
                "Retrievers: keyword=%d vector=%d graph=%d | Sources: %d | " +
                "Fusion: %s | Reranking: %s (%s) | Duration: %dms",
                intent, selectedStrategy, providerName, modelName,
                promptTemplateId, promptTemplateVersion,
                keywordResultCount, vectorResultCount, graphNodeCount,
                totalSourceCount, fusionMethod,
                rerankingApplied ? "yes" : "no", rerankingProvider,
                retrievalCompletedAt.toEpochMilli() - retrievalStartedAt.toEpochMilli());
    }

    /** Returns a builder for fluent construction. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private RetrievalContext retrievalContext;
        private String intent = "unknown";
        private String selectedStrategy = "HYBRID";
        private String providerName = "unknown";
        private String promptTemplateId = "unknown";
        private int promptTemplateVersion;
        private String modelName = "unknown";
        private Instant retrievalStartedAt = Instant.now();
        private Instant retrievalCompletedAt = Instant.now();
        private int keywordResultCount;
        private int vectorResultCount;
        private int graphNodeCount;
        private int totalChunkCount;
        private int totalSourceCount;
        private String fusionMethod = "weighted-linear";
        private boolean rerankingApplied;
        private String rerankingProvider = "none";
        private Map<String, Object> evaluationScores = Map.of();
        private List<String> traceLog = new java.util.ArrayList<>();

        public Builder retrievalContext(RetrievalContext ctx) { this.retrievalContext = ctx; return this; }
        public Builder intent(String v) { this.intent = v; return this; }
        public Builder selectedStrategy(String v) { this.selectedStrategy = v; return this; }
        public Builder providerName(String v) { this.providerName = v; return this; }
        public Builder promptTemplateId(String v) { this.promptTemplateId = v; return this; }
        public Builder promptTemplateVersion(int v) { this.promptTemplateVersion = v; return this; }
        public Builder modelName(String v) { this.modelName = v; return this; }
        public Builder retrievalStartedAt(Instant v) { this.retrievalStartedAt = v; return this; }
        public Builder retrievalCompletedAt(Instant v) { this.retrievalCompletedAt = v; return this; }
        public Builder keywordResultCount(int v) { this.keywordResultCount = v; return this; }
        public Builder vectorResultCount(int v) { this.vectorResultCount = v; return this; }
        public Builder graphNodeCount(int v) { this.graphNodeCount = v; return this; }
        public Builder totalChunkCount(int v) { this.totalChunkCount = v; return this; }
        public Builder totalSourceCount(int v) { this.totalSourceCount = v; return this; }
        public Builder fusionMethod(String v) { this.fusionMethod = v; return this; }
        public Builder rerankingApplied(boolean v) { this.rerankingApplied = v; return this; }
        public Builder rerankingProvider(String v) { this.rerankingProvider = v; return this; }
        public Builder evaluationScores(Map<String, Object> v) { this.evaluationScores = v; return this; }
        public Builder traceLog(List<String> v) { this.traceLog = v; return this; }
        public Builder addTrace(String msg) { this.traceLog.add(msg); return this; }

        public RetrievalOrchestrationResult build() {
            return new RetrievalOrchestrationResult(
                    retrievalContext, intent, selectedStrategy, providerName,
                    promptTemplateId, promptTemplateVersion, modelName,
                    retrievalStartedAt, retrievalCompletedAt,
                    keywordResultCount, vectorResultCount, graphNodeCount,
                    totalChunkCount, totalSourceCount, fusionMethod,
                    rerankingApplied, rerankingProvider, evaluationScores,
                    List.copyOf(traceLog));
        }
    }
}
