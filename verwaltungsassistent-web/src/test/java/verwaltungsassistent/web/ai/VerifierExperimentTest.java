package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Prompt 13 experiment: does the independent verifier reject irrelevant
 * evidence? The verifier model is NOT pinned here — it follows the
 * configured default (14B) or the OLLAMA_VERIFIER_MODEL environment
 * variable, so the same class runs the 7B baseline and the 14B experiment.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Prompt 13: verifier experiment")
class VerifierExperimentTest {

    @Autowired private AiFacade aiFacade;
    @Autowired private DecisionRouter decisionRouter;

    private void run(String id, String question) {
        long t0 = System.currentTimeMillis();
        var routing = decisionRouter.route(question);
        AiResponse response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
        long wall = System.currentTimeMillis() - t0;
        String decision = routing.decision() != null ? routing.decision().decision() : "none";
        String answer = response.answer().answer();
        System.out.printf("EXP %s | route=%s | decision=%s | citations=%d | grounded=%s | wall=%dms%n",
            id, routing.strategy(), decision, response.answer().sourceCitations().size(),
            response.answer().grounded(), wall);
        System.out.printf("  Q: %s%n", question);
        System.out.printf("  A: %s%n", answer == null ? "null"
            : answer.substring(0, Math.min(600, answer.length())).replace("\n", " | "));
    }

    @Test @Order(1) @DisplayName("Problematic: Wohnungsgeberbestätigung")
    void anmeldung() {
        run("ANMELDUNG", "Eine Person will sich anmelden, hat aber keine Wohnungsgeberbestätigung.");
    }

    @Test @Order(2) @DisplayName("Positive control: travel")
    void travel() {
        run("TRAVEL", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?");
    }

    @Test @Order(3) @DisplayName("Positive control: procurement")
    void procurement() {
        run("PROCUREMENT", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?");
    }

    @Test @Order(4) @DisplayName("Positive control: building")
    void building() {
        run("BUILDING", "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?");
    }

    @Test @Order(5) @DisplayName("Negative control: Personalausweis")
    void personalausweis() {
        run("AUSWEIS", "Wie beantragt man einen Personalausweis?");
    }
}
