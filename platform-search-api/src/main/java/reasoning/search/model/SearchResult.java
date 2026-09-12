package reasoning.search.model;

/** A single search result with chunk reference, text, scores, provider, citation, intent, and strategy. */
public record SearchResult(
        ChunkReference chunk,
        String text,
        double score,
        double confidenceScore,
        String provider,
        CitationReference citation,
        double keywordScore,
        double vectorScore,
        double rerankScore,
        String intent,
        String retrievalStrategy
) {
    public SearchResult(
            ChunkReference chunk,
            String text,
            double score,
            double confidenceScore,
            String provider,
            CitationReference citation) {
        this(chunk, text, score, confidenceScore, provider, citation, 0.0, 0.0, 0.0, "GENERAL", provider);
    }
}
