package reasoning.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for the AI pipeline — prompt size, evidence limits,
 * retry behavior, coverage thresholds, and domain boost factors.
 */
@ConfigurationProperties(prefix = "platform.ai.pipeline")
public class AiPipelineProperties {

    /** Maximum number of unique evidence sources (documents) in the prompt. */
    private int maxEvidenceSources = 4;

    /** Maximum number of paragraphs (excerpt chunks) per evidence source. */
    private int maxParagraphsPerSource = 3;

    /** Maximum total prompt length in characters. */
    private int maxPromptLength = 3800;

    /** Maximum excerpt length per evidence item in characters. */
    private int maxExcerptLength = 500;

    /** Whether automatic retry on evidence coverage failure is enabled. */
    private boolean retryEnabled = false;

    /** Minimum confidence score for an evidence item to be considered "covered". */
    private double coverageThreshold = 0.6;

    /** Domain boost factor for matching domain documents (0.0 to 1.0). */
    private double domainBoostFactor = 0.35;

    /** Maximum domain-mismatched documents allowed in retrieval results. */
    private int maxMismatchedDocuments = 0;

    public int getMaxEvidenceSources() { return maxEvidenceSources; }
    public void setMaxEvidenceSources(int v) { this.maxEvidenceSources = v; }
    public int getMaxParagraphsPerSource() { return maxParagraphsPerSource; }
    public void setMaxParagraphsPerSource(int v) { this.maxParagraphsPerSource = v; }
    public int getMaxPromptLength() { return maxPromptLength; }
    public void setMaxPromptLength(int v) { this.maxPromptLength = v; }
    public int getMaxExcerptLength() { return maxExcerptLength; }
    public void setMaxExcerptLength(int v) { this.maxExcerptLength = v; }
    public boolean isRetryEnabled() { return retryEnabled; }
    public void setRetryEnabled(boolean v) { this.retryEnabled = v; }
    public double getCoverageThreshold() { return coverageThreshold; }
    public void setCoverageThreshold(double v) { this.coverageThreshold = v; }
    public double getDomainBoostFactor() { return domainBoostFactor; }
    public void setDomainBoostFactor(double v) { this.domainBoostFactor = v; }
    public int getMaxMismatchedDocuments() { return maxMismatchedDocuments; }
    public void setMaxMismatchedDocuments(int v) { this.maxMismatchedDocuments = v; }
}
