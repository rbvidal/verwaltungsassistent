package verwaltungsassistent.web.ai;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.DecisionStrategy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that the final generated answer follows the user's
 * detected language (StructuredIntent.language) while the authoritative
 * deterministic decision remains unchanged.
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
@DisplayName("Answer Language E2E (user language controls presentation)")
class AnswerLanguageE2ETest {

    @Autowired private AiFacade aiFacade;
    @Autowired private DecisionRouter decisionRouter;

    private static final StringBuilder REPORT = new StringBuilder();

    private record Observed(String language, String route, String decision, String answer) {}

    private Observed ask(String id, String question) {
        var routing = decisionRouter.route(question);
        String lang = routing.intent() != null ? routing.intent().language() : null;
        AiResponse response = aiFacade.answer(new AiRequest(question, null, null, null, 15));
        String answer = response.answer().answer();
        String decision = routing.decision() != null ? routing.decision().decision() : "none";
        REPORT.append("=".repeat(78)).append("\n");
        REPORT.append(id).append(" | detected lang=").append(lang)
            .append(" | route=").append(routing.strategy())
            .append(" | decision=").append(decision).append("\n");
        REPORT.append("Q: ").append(question).append("\n");
        REPORT.append("A: ").append(answer == null ? "null" : answer.substring(0, Math.min(400, answer.length()))).append("\n\n");
        return new Observed(lang, routing.strategy().name(), decision, answer);
    }

    private void assertAnswerLanguage(String id, String answer, String... markers) {
        for (String m : markers) {
            if (answer != null && answer.toLowerCase().contains(m.toLowerCase())) return;
        }
        fail(id + ": answer contains none of the language markers " + Arrays.toString(markers)
            + "\nAnswer: " + answer);
    }

    private void assertRule(String id, Observed o, String decisionFragment) {
        assertEquals(DecisionStrategy.RULE_ENGINE.name(), o.route(), id + ": expected RULE_ENGINE");
        assertTrue(o.decision().contains(decisionFragment),
            id + ": decision '" + o.decision() + "' must contain '" + decisionFragment + "'");
    }

    // ── Step 4: travel in four languages ──

    @Test @Order(1) @DisplayName("DE travel → German answer")
    void deTravel() {
        Observed o = ask("DE-TRAVEL", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?");
        assertEquals("de", o.language());
        assertRule("DE-TRAVEL", o, "Tagegeld: 12");
        assertAnswerLanguage("DE-TRAVEL", o.answer(), "Verpflegungspauschale", "Tagegeld", "Dienstreise");
    }

    @Test @Order(2) @DisplayName("EN travel → English answer")
    void enTravel() {
        Observed o = ask("EN-TRAVEL", "What is the meal allowance for a 12-hour business trip?");
        assertEquals("en", o.language());
        assertRule("EN-TRAVEL", o, "Tagegeld: 12");
        assertAnswerLanguage("EN-TRAVEL", o.answer(), "allowance", "business trip", "meal");
    }

    @Test @Order(3) @DisplayName("PT travel → Portuguese answer")
    void ptTravel() {
        Observed o = ask("PT-TRAVEL", "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?");
        assertEquals("pt", o.language());
        assertRule("PT-TRAVEL", o, "Tagegeld: 12");
        assertAnswerLanguage("PT-TRAVEL", o.answer(), "subsídio", "alimentação", "viagem", "diária");
    }

    @Test @Order(4) @DisplayName("FR travel → French answer")
    void frTravel() {
        Observed o = ask("FR-TRAVEL", "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?");
        assertEquals("fr", o.language());
        assertRule("FR-TRAVEL", o, "Tagegeld: 12");
        assertAnswerLanguage("FR-TRAVEL", o.answer(), "indemnité", "repas", "voyage");
    }

    // ── Step 5: salary multilingual ──

    @Test @Order(5) @DisplayName("EN salary → English answer, value unchanged")
    void enSalary() {
        Observed o = ask("EN-SALARY", "What is the salary for EG 9b step 5?");
        assertEquals("en", o.language());
        assertRule("EN-SALARY", o, "4480.00");
        assertAnswerLanguage("EN-SALARY", o.answer(), "salary", "earn", "monthly", "grade");
    }

    @Test @Order(6) @DisplayName("PT salary → Portuguese answer, value unchanged")
    void ptSalary() {
        Observed o = ask("PT-SALARY", "Qual é o salário para EG 9b nível 5?");
        assertEquals("pt", o.language());
        assertRule("PT-SALARY", o, "4480.00");
        assertAnswerLanguage("PT-SALARY", o.answer(), "salário", "nível", "faixa");
    }

    @Test @Order(7) @DisplayName("FR salary → French answer, value unchanged")
    void frSalary() {
        Observed o = ask("FR-SALARY", "Quel est le salaire pour EG 9b échelon 5 ?");
        assertEquals("fr", o.language());
        assertRule("FR-SALARY", o, "4480.00");
        assertAnswerLanguage("FR-SALARY", o.answer(), "salaire", "échelon", "traitement");
    }

    // ── Step 5: procurement multilingual ──

    @Test @Order(8) @DisplayName("EN procurement → English answer, procedure unchanged")
    void enProcurement() {
        Observed o = ask("EN-PROCUREMENT", "Which procurement procedure applies to a contract of 8,000 euros?");
        assertEquals("en", o.language());
        assertRule("EN-PROCUREMENT", o, "Direktauftrag");
        assertAnswerLanguage("EN-PROCUREMENT", o.answer(), "procedure", "procurement", "contract", "award");
    }

    @Test @Order(9) @DisplayName("DE procurement → German answer")
    void deProcurement() {
        Observed o = ask("DE-PROCUREMENT", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?");
        assertEquals("de", o.language());
        assertRule("DE-PROCUREMENT", o, "Direktauftrag");
        assertAnswerLanguage("DE-PROCUREMENT", o.answer(), "Vergabeart", "Auftrag", "Verfahren");
    }

    @Test @Order(10) @DisplayName("FR procurement → French answer, procedure unchanged")
    void frProcurement() {
        Observed o = ask("FR-PROCUREMENT", "Quelle procédure de passation s'applique pour un marché de 8 000 euros ?");
        assertEquals("fr", o.language());
        assertRule("FR-PROCUREMENT", o, "Direktauftrag");
        assertAnswerLanguage("FR-PROCUREMENT", o.answer(), "procédure", "marché", "passation");
    }

    // ── Step 6: retrieval answer language, citations intact ──

    @Test @Order(11) @DisplayName("EN retrieval → English answer, citations intact")
    void enRetrieval() {
        Observed o = ask("EN-RETRIEVAL", "What building permit do I need for a single-family house in Berlin?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), o.route());
        AiResponse response = aiFacade.answer(new AiRequest(
            "What building permit do I need for a single-family house in Berlin?", null, null, null, 15));
        assertTrue(response.answer().sourceCitations().size() > 0, "citations must remain intact");
        assertTrue(response.answer().sourceCitations().stream()
                .allMatch(c -> c.title() != null && !c.title().isBlank()),
            "citation titles (source text) must remain intact");
        assertAnswerLanguage("EN-RETRIEVAL", o.answer(), "permit", "building", "house", "application");
    }

    // ── Step 7: ambiguous / unsupported stay safe, language-aware ──

    @Test @Order(12) @DisplayName("Ambiguous EN → no forced rule, English answer if any")
    void ambiguousEn() {
        Observed o = ask("AMBIGUOUS-EN", "What applies at 12?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), o.route(),
            "ambiguous question must not force a deterministic rule");
        assertAnswerLanguage("AMBIGUOUS-EN", o.answer(), "knowledge base", "contains no", "documents", "the question");
    }

    @Test @Order(13) @DisplayName("Unsupported PT → safe, Portuguese answer if any")
    void unsupportedPt() {
        Observed o = ask("UNSUPPORTED-PT", "Que regras se aplicam à compra de computadores quânticos na administração de Berlim?");
        assertNotEquals(DecisionStrategy.RULE_ENGINE.name(), o.route(),
            "unsupported question must not force a deterministic rule");
        assertAnswerLanguage("UNSUPPORTED-PT", o.answer(), "informações", "documentos", "encontradas", "base de conhecimento", "não contém");
    }

    @AfterAll
    static void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/answer-language-e2e.txt"), REPORT.toString());
        } catch (IOException ignored) {}
    }
}
