package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.LlmSemanticIntentParser;
import reasoning.ai.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the NEW production default: the LLM semantic parser must be
 * active WITHOUT any semantic-intent property override (application.yml now
 * defaults to enabled=true).
 *
 * <p>Runs the 11-question smoke matrix through the full production pipeline
 * (DecisionRouter → RULE_ENGINE/HYBRID_RETRIEVAL → 14B → grounding/verification)
 * against the real infrastructure (Qdrant + Neo4j + Ollama).
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
        // NOTE: no semantic-intent property here — the application.yml default
        // (enabled=true) must select the LLM parser.
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
@DisplayName("Default-Mode Smoke (LLM parser from application.yml default)")
class DefaultModeSmokeTest {

    @Autowired private AiFacade aiFacade;
    @Autowired private SemanticIntentParser semanticIntentParser;
    @Autowired private DecisionRouter decisionRouter;

    @Test @Order(1)
    @DisplayName("0. Parser selection: LLM parser is the default primary")
    void llmParserIsDefault() {
        assertInstanceOf(LlmSemanticIntentParser.class, semanticIntentParser,
            "application.yml default must select the LLM semantic parser");
    }

    private void expectRule(String id, String question, String decisionFragment) {
        var routing = decisionRouter.route(question);
        assertNotNull(routing.intent(), id + ": intent expected");
        assertNotNull(routing.intent().domain(), id + ": domain required by gate");
        assertEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(), id);
        assertNotNull(routing.decision(), id);
        assertTrue(routing.decision().decision().contains(decisionFragment),
            id + ": decision '" + routing.decision().decision()
                + "' must contain '" + decisionFragment + "'");

        AiResponse response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
        assertNotNull(response.answer().answer(), id + ": answer expected");
        System.out.println("SMOKE " + id + ": RULE_ENGINE | intent=" + routing.intent().intentType()
            + " domain=" + routing.intent().domain() + " params=" + routing.intent().parameters()
            + " lang=" + routing.intent().language()
            + " | " + routing.decision().decision());
    }

    private void expectNoRule(String id, String question) {
        var routing = decisionRouter.route(question);
        assertNotEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
            id + ": must NOT force a deterministic answer");
        AiResponse response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
        System.out.println("SMOKE " + id + ": " + routing.strategy() + " | intent="
            + (routing.intent() != null ? routing.intent().intentType() : "n/a")
            + " domain=" + (routing.intent() != null ? routing.intent().domain() : "n/a")
            + " params=" + (routing.intent() != null ? routing.intent().parameters() : "{}")
            + " | citations=" + response.answer().sourceCitations().size());
    }

    @Test @Order(2) @DisplayName("1. DE travel")
    void deTravel() {
        expectRule("DE-TRAVEL", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?", "Tagegeld: 12");
    }

    @Test @Order(3) @DisplayName("2. EN travel")
    void enTravel() {
        expectRule("EN-TRAVEL", "What is the meal allowance for a 12-hour business trip?", "Tagegeld: 12");
    }

    @Test @Order(4) @DisplayName("3. PT travel")
    void ptTravel() {
        expectRule("PT-TRAVEL", "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?", "Tagegeld: 12");
    }

    @Test @Order(5) @DisplayName("4. FR travel")
    void frTravel() {
        expectRule("FR-TRAVEL", "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?", "Tagegeld: 12");
    }

    @Test @Order(6) @DisplayName("5. DE salary")
    void deSalary() {
        expectRule("DE-SALARY", "Wie hoch ist das Gehalt für EG 9b Stufe 3?", "4117.53");
    }

    @Test @Order(7) @DisplayName("6. EN salary")
    void enSalary() {
        expectRule("EN-SALARY", "What is the salary for EG 9b step 5?", "4480.00");
    }

    @Test @Order(8) @DisplayName("7. DE procurement")
    void deProcurement() {
        expectRule("DE-PROCUREMENT", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?", "Direktauftrag");
    }

    @Test @Order(9) @DisplayName("8. EN procurement (comma thousands)")
    void enProcurement() {
        expectRule("EN-PROCUREMENT", "Which procurement procedure applies to a contract of 8,000 euros?", "Direktauftrag");
    }

    @Test @Order(10) @DisplayName("9. Ambiguous — CRITICAL SAFETY TEST")
    void ambiguous() {
        // Even if the LLM produces TRAVEL_ALLOWANCE + hours + domain=null,
        // the domain-coherence gate must prevent RULE_ENGINE / Tagegeld.
        expectNoRule("AMBIGUOUS", "What applies at 12?");
    }

    @Test @Order(11) @DisplayName("10. Unsupported")
    void unsupported() {
        expectNoRule("UNSUPPORTED", "Welche Vorschriften gelten für Quantencomputer-Beschaffung in der Berliner Verwaltung?");
    }

    @Test @Order(12) @DisplayName("11. Retrieval (building)")
    void retrieval() {
        var routing = decisionRouter.route("Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE, routing.strategy());
        AiResponse response = aiFacade.answer(new AiRequest(
            "Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?",
            null, null, null, 15));
        assertTrue(response.answer().sourceCitations().size() > 0,
            "retrieval path must produce citations");
        System.out.println("SMOKE RETRIEVAL: " + routing.strategy() + " | citations="
            + response.answer().sourceCitations().size()
            + " | grounded=" + response.answer().grounded());
    }
}
