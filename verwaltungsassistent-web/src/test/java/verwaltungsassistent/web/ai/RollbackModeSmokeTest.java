package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.DecisionStrategy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rollback smoke test: overrides the new default with
 * {@code platform.ai.ollama.semantic-intent.enabled=false} (the equivalent of
 * the SEMANTIC_INTENT_ENABLED=false environment override) and verifies the
 * legacy regex path is restored and still works.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = verwaltungsassistent.web.VerwaltungsassistentApplication.class,
    properties = {
        "platform.neo4j.uri=bolt://localhost:7687",
        "platform.neo4j.username=neo4j",
        "platform.neo4j.password=password",
        "platform.ai.ollama.base-url=http://localhost:11434",
        "platform.ai.ollama.chat-model=qwen2.5:14b",
        "platform.ai.ollama.verifier-model=qwen2.5:7b",
        "platform.ai.ollama.verification-strategy=claim_batch",
        "platform.ai.ollama.semantic-intent.enabled=false",
        "platform.ai.ollama.embedding-model=nomic-embed-text",
        "platform.ai.ollama.embedding-dimension=768"
    }
)
@TestPropertySource(properties = {
    "platform.search.qdrant.enabled=true",
    "platform.search.qdrant.collection=mda_chunks",
    "platform.search.qdrant.vector-dimension=768",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Rollback-Mode Smoke (regex parser via override)")
class RollbackModeSmokeTest {

    @Autowired private AiFacade aiFacade;
    @Autowired private SemanticIntentParser semanticIntentParser;
    @Autowired private DecisionRouter decisionRouter;

    @Test @Order(1)
    @DisplayName("0. Parser selection: regex parser restored by override")
    void regexParserIsRestored() {
        assertInstanceOf(RegexSemanticIntentParser.class, semanticIntentParser,
            "semantic-intent.enabled=false must restore the regex parser");
    }

    @Test @Order(2)
    @DisplayName("1. DE travel through legacy regex path")
    void deTravelLegacy() {
        var routing = decisionRouter.route(
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?");
        assertEquals(DecisionStrategy.RULE_ENGINE, routing.strategy());
        assertNotNull(routing.decision());
        assertTrue(routing.decision().decision().contains("Tagegeld: 12"));

        AiResponse response = aiFacade.answer(new AiRequest(
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
            null, null, null, 15));
        assertNotNull(response.answer().answer());
        System.out.println("ROLLBACK SMOKE: regex parser | " + routing.decision().decision()
            + " | answer length=" + response.answer().answer().length());
    }
}
