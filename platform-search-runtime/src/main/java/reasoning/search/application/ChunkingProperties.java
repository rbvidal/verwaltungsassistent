package reasoning.search.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for document text chunking. */
@ConfigurationProperties(prefix = "platform.search.chunking")
public class ChunkingProperties {

    /** Target maximum characters per chunk. */
    private int maxChunkSize = 500;

    /** Number of characters to overlap between adjacent chunks. */
    private int overlap = 50;

    /** Chunking strategy: SENTENCE (split at sentence boundaries) or FIXED (split at exact size). */
    private Strategy strategy = Strategy.SENTENCE;

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public void setMaxChunkSize(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public enum Strategy {
        SENTENCE,
        FIXED
    }
}
