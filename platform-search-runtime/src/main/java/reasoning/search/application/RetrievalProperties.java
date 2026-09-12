package reasoning.search.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for hybrid retrieval fusion. */
@ConfigurationProperties(prefix = "platform.search.retrieval")
public class RetrievalProperties {

    /** Weight for keyword search results in fusion (0.0–1.0). */
    private double keywordWeight = 0.20;

    /** Weight for vector search results in fusion (0.0–1.0). */
    private double vectorWeight = 0.60;

    /** Weight for confidence score in fusion (0.0–1.0). */
    private double confidenceWeight = 0.20;

    /**
     * Scales raw {@code ts_rank_cd} values (typically 0.05–0.15 on this
     * corpus) into the score band the hybrid fusion weights and citation
     * tier thresholds (0.15/0.45) expect; capped at 1.0.
     */
    private double keywordRankScale = 4.0;

    public double getKeywordWeight() {
        return keywordWeight;
    }

    public void setKeywordWeight(double keywordWeight) {
        this.keywordWeight = keywordWeight;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public double getConfidenceWeight() {
        return confidenceWeight;
    }

    public void setConfidenceWeight(double confidenceWeight) {
        this.confidenceWeight = confidenceWeight;
    }

    public double getKeywordRankScale() {
        return keywordRankScale;
    }

    public void setKeywordRankScale(double keywordRankScale) {
        this.keywordRankScale = keywordRankScale;
    }
}
