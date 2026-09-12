package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.DecisionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded German demo workflow validation (Prompt 6): the six scenarios of
 * the September 10 German demonstration, each executed once against live
 * infrastructure. Captures route, decision, citations, verification status
 * and wall latency.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("German demo workflow (Prompt 6)")
class GermanDemoWorkflowTest {

    @Autowired private AiFacade aiFacade;
    @Autowired private DecisionRouter decisionRouter;

    private record Scenario(String id, String route, String decision,
                            int citations, boolean grounded, long wallMs) {}

    private Scenario run(String id, String question) {
        long t0 = System.currentTimeMillis();
        var routing = decisionRouter.route(question);
        AiResponse response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
        long wall = System.currentTimeMillis() - t0;
        String decision = routing.decision() != null ? routing.decision().decision() : "none";
        int citations = response.answer().sourceCitations().size();
        boolean grounded = response.answer().grounded();
        String answer = response.answer().answer();
        System.out.printf("DEMO %s | route=%s | decision=%s | citations=%d | grounded=%s | wall=%dms%n",
            id, routing.strategy(), decision, citations, grounded, wall);
        System.out.printf("  answer[0..200]: %s%n",
            answer == null ? "null" : answer.substring(0, Math.min(200, answer.length())));
        return new Scenario(id, routing.strategy().name(), decision, citations, grounded, wall);
    }

    @Test @Order(1) @DisplayName("1. German travel allowance -> deterministic rule")
    void travelAllowance() {
        Scenario s = run("TRAVEL",
            "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?");
        assertEquals(DecisionStrategy.RULE_ENGINE.name(), s.route());
        assertTrue(s.decision().contains("Tagegeld: 12"), "decision was: " + s.decision());
    }

    @Test @Order(2) @DisplayName("2. German salary -> deterministic rule")
    void salary() {
        Scenario s = run("SALARY", "Wie hoch ist das Gehalt für EG 9b Stufe 3?");
        assertEquals(DecisionStrategy.RULE_ENGINE.name(), s.route());
        assertTrue(s.decision().contains("4117.53"), "decision was: " + s.decision());
    }

    @Test @Order(3) @DisplayName("3. German procurement -> deterministic rule")
    void procurement() {
        Scenario s = run("PROCUREMENT", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?");
        assertEquals(DecisionStrategy.RULE_ENGINE.name(), s.route());
        assertTrue(s.decision().contains("Direktauftrag"), "decision was: " + s.decision());
    }

    @Test @Order(4) @DisplayName("4. German retrieval question -> hybrid retrieval with evidence")
    void retrieval() {
        Scenario s = run("RETRIEVAL",
            "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), s.route());
        assertTrue(s.citations() > 0, "retrieval answer must carry citations");
    }

    @Test @Order(5) @DisplayName("5. Ambiguous German question -> must not force a rule")
    void ambiguous() {
        Scenario s = run("AMBIGUOUS", "Was gilt bei 12?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), s.route(),
            "ambiguous question must not trigger a deterministic rule");
    }

    @Test @Order(6) @DisplayName("6. Unsupported German question -> safe fallback")
    void unsupported() {
        Scenario s = run("UNSUPPORTED",
            "Welche Regeln gelten für den Kauf von Quantencomputern in der Berliner Verwaltung?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), s.route(),
            "unsupported question must not trigger a deterministic rule");
    }
}
