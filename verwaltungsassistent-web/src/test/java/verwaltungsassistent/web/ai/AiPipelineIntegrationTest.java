package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the AI pipeline end-to-end through AiFacade.answer().
 *
 * <p>Verifies the full chain: retrieval → prompt building → LLM inference →
 * grounding → structured decision package. Runs against a real Ollama instance.
 * When no documents are indexed, the pipeline still executes — retrieval returns
 * empty results and the LLM responds with appropriate caveats.
 */
@SpringBootTest
class AiPipelineIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AiPipelineIntegrationTest.class);

    @Autowired
    private AiFacade aiFacade;

    @Test
    void fullPipelineShouldExecuteAndReturnStructuredResponse() {
        // Given: a realistic municipal decision question
        String question = """
                Ein Sachbearbeiter im Bauamt muss prüfen, ob für den Bau \
                eines Carports mit 25 m² Grundfläche eine Baugenehmigung \
                erforderlich ist. Das Grundstück liegt in Berlin innerhalb \
                eines Bebauungsplans. Welches Verfahren ist anzuwenden und \
                welche Unterlagen werden benötigt?""";
        AiRequest request = new AiRequest(
                question, "default", null,
                new AiConversationContext(List.of(), null, null, null,
                        UUID.randomUUID().toString()),
                5, RetrievalScope.HYBRID, UUID.randomUUID());

        // When: the pipeline executes
        Instant start = Instant.now();
        AiResponse response = aiFacade.answer(request);
        Duration latency = Duration.between(start, Instant.now());

        // Then: a structured response is returned
        assertThat(response).isNotNull();
        ReasonedAnswer answer = response.answer();
        assertThat(answer).isNotNull();
        assertThat(answer.answer()).isNotBlank();

        InferenceMetadata metadata = response.metadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.model()).isNotBlank();
        assertThat(metadata.retrievalStrategy()).isNotBlank();

        ConfidenceProfile confidence = answer.confidence();
        assertThat(confidence).isNotNull();

        // ── Diagnostics ──
        log.info("═══════════════════════════════════════════");
        log.info("  AI Pipeline Diagnostics");
        log.info("═══════════════════════════════════════════");
        log.info("  Retrieval strategy : {}", metadata.retrievalStrategy());
        log.info("  Model              : {}", metadata.model());
        log.info("  Total latency      : {} ms", latency.toMillis());
        log.info("  Grounded           : {}", answer.grounded());
        log.info("  Confidence (overall): {}", String.format("%.2f", confidence.overallConfidence()));
        log.info("  Confidence (source) : {}", String.format("%.2f", confidence.sourceConfidence()));
        log.info("  Confidence (completeness): {}", String.format("%.2f", confidence.completenessConfidence()));
        log.info("  Answer length      : {} chars", answer.answer().length());
        log.info("  Source citations   : {}", answer.sourceCitations().size());
        log.info("  Authority refs     : {}", answer.authorityReferences().size());
        log.info("  Answer preview     : {}",
                answer.answer().length() > 120
                        ? answer.answer().substring(0, 120) + "..."
                        : answer.answer());
        log.info("═══════════════════════════════════════════");

        // ── Assertions ──
        assertThat(latency.toMillis()).isLessThan(120_000); // reasonable timeout
        assertThat(answer.answer().length()).isGreaterThan(50);
    }

    @Test
    void ruleEnginePathShouldExecuteForProcurementQuestion() {
        String question = """
                Die Vergabestelle muss Büromaterial im Wert von 8.500 € \
                beschaffen. Welches Vergabeverfahren ist nach AV §55 LHO \
                Berlin anzuwenden?""";
        AiRequest request = new AiRequest(
                question, "default", null,
                new AiConversationContext(List.of(), null, null, null,
                        UUID.randomUUID().toString()),
                5, RetrievalScope.HYBRID, UUID.randomUUID());

        AiResponse response = aiFacade.answer(request);

        assertThat(response).isNotNull();
        assertThat(response.answer().answer()).isNotBlank();

        log.info("RuleEngine test — strategy: {}, answer length: {}",
                response.metadata().retrievalStrategy(),
                response.answer().answer().length());
    }

    @Test
    void hybridRetrievalPathShouldExecuteForGeneralQuestion() {
        String question = """
                Welche Regelungen gelten für Dienstreisen eines \
                Tarifbeschäftigten im öffentlichen Dienst nach TV-L?""";
        AiRequest request = new AiRequest(
                question, "default", null,
                new AiConversationContext(List.of(), null, null, null,
                        UUID.randomUUID().toString()),
                5, RetrievalScope.HYBRID, UUID.randomUUID());

        AiResponse response = aiFacade.answer(request);

        assertThat(response).isNotNull();
        assertThat(response.answer().answer()).isNotBlank();

        log.info("Hybrid retrieval test — strategy: {}, sources: {}",
                response.metadata().retrievalStrategy(),
                response.answer().sourceCitations().size());
    }
}
