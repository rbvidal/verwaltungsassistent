package verwaltungsassistent.web.ai;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Expanded corpus — RUN B: LLM semantic parser (candidate).
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
@DisplayName("Expanded Semantic Corpus — RUN B (LLM)")
class ExpandedSemanticCorpusLlmTest extends AbstractExpandedSemanticCorpusTest {

    @Override
    protected String mode() {
        return "LLM";
    }

    @Override
    protected String reportFileName() {
        return "expanded-corpus-llm.txt";
    }

    @Override
    protected String corpusResource() {
        return "corpus/expanded-semantic-corpus.csv";
    }
}
