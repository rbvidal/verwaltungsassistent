package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Bounded latency profile for the German retrieval scenario (Prompt 3).
 *
 * <p>Run 1 is cold when the Ollama models were unloaded beforehand
 * ({@code ollama stop}); runs 2-5 are warm. Stage-level timings are captured
 * by the application's own PipelineProfiler and stage logs — this test only
 * records wall-clock totals per run.
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
@DisplayName("German retrieval latency profile (Prompt 3)")
class GermanRetrievalLatencyProfileTest {

    private static final String QUESTION =
        "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?";

    @Autowired private AiFacade aiFacade;

    @Test
    void profileGermanRetrievalLatency() {
        for (int i = 1; i <= 5; i++) {
            long t0 = System.currentTimeMillis();
            AiResponse response = aiFacade.answer(new AiRequest(QUESTION, null, null, null, 15));
            long wall = System.currentTimeMillis() - t0;
            int citations = response.answer().sourceCitations().size();
            String answer = response.answer().answer();
            System.out.printf("PROFILE-RUN %d | wall=%dms | citations=%d | answerLen=%d%n",
                i, wall, citations, answer == null ? 0 : answer.length());
        }
    }
}
