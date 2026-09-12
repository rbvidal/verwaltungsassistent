package verwaltungsassistent.web.config;

import reasoning.search.api.ChunkingStrategy;
import reasoning.search.application.ChunkingProperties;
import reasoning.search.application.FixedSizeChunkingStrategy;
import reasoning.search.application.SentenceAwareChunkingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkingConfig {

    /**
     * Respektiert die konfigurierte Strategie (ChunkingProperties, Standard
     * SENTENCE) statt hart FIXED zu erzwingen. Festes 500-Zeichen-Schneiden
     * zerteilte Normsätze (z. B. „(1) Wer eine Wohnung bezieht, hat sich
     * innerhalb von zwei Wochen … anzumelden.") mitten im Satz — die
     * beantwortende Passage eines Gesetzes kam dadurch zerstückelt in die
     * Evidenz (siehe BMG § 17, Chunks 63/64) und die Antwort orientierte sich
     * an der falschen Norm.
     */
    @Bean
    public ChunkingStrategy chunkingStrategy(ChunkingProperties chunkingProperties) {
        return switch (chunkingProperties.getStrategy()) {
            case FIXED -> new FixedSizeChunkingStrategy(chunkingProperties);
            case SENTENCE -> new SentenceAwareChunkingStrategy(chunkingProperties);
        };
    }
}
