package verwaltungsassistent.web.ai;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * RUN B of the production-path comparison: LLM semantic path.
 *
 * <p>{@code platform.ai.ollama.semantic-intent.enabled=true} — the
 * experimental LLM parser (qwen2.5:7b). Everything else identical to Run A.
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
        "platform.ai.ollama.semantic-intent.enabled=true",
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
@DisplayName("Semantic Intent Production Comparison — RUN B (LLM)")
class SemanticIntentProductionComparisonLlmTest
        extends AbstractSemanticIntentProductionComparisonTest {

    @Override
    protected String mode() {
        return "LLM";
    }

    @Override
    protected String reportFileName() {
        return "comparison-llm.txt";
    }
}
