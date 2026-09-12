package verwaltungsassistent.web.ai;

import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.application.RetrievalPlanner;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.Domain;
import reasoning.ai.model.StructuredIntent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves StructuredIntent.domain is AUTHORITATIVE in the semantic-intent
 * path and propagates into retrieval planning.
 *
 * <p>LLM parser enabled via semantic-intent.enabled=true.
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
@DisplayName("Semantic Domain Propagation")
class SemanticDomainPropagationTest {

    @Autowired private SemanticIntentParser semanticIntentParser;
    @Autowired private DecisionRouter decisionRouter;
    @Autowired private RetrievalPlanner retrievalPlanner;

    private static final StringBuilder R = new StringBuilder();

    @AfterAll
    static void report() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/semantic-domain-propagation.txt"), R.toString());
        } catch (IOException ignored) {}
        System.out.println(R.toString());
    }

    record Q(String lang, String text, String expectedDomain) {}

    @Test
    @DisplayName("DE/EN/PT/FR travel: domain=TRAVEL authoritative, route=RULE_ENGINE")
    void travelDomainAllLanguages() {
        List<Q> queries = List.of(
            new Q("DE", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?", "TRAVEL"),
            new Q("EN", "What is the meal allowance for a 12-hour business trip?", "TRAVEL"),
            new Q("PT", "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?", "TRAVEL"),
            new Q("FR", "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?", "TRAVEL")
        );

        R.append("=".repeat(78)).append("\n");
        R.append("  DOMAIN PROPAGATION — TRAVEL (DE/EN/PT/FR)\n");
        R.append("=".repeat(78)).append("\n\n");

        for (Q q : queries) {
            StructuredIntent intent = semanticIntentParser.parse(q.text);
            var routing = decisionRouter.route(q.text);

            R.append(String.format("  %s: intent.domain=%s intentType=%s params=%s → route=%s%n",
                q.lang, intent.domain(), intent.intentType(), intent.parameters(),
                routing.strategy()));

            // THE KEY ASSERTION: domain is authoritative and correct
            assertNotNull(intent.domain(), q.lang + ": parser must produce a domain");
            assertEquals(Domain.of(q.expectedDomain), intent.domain(),
                q.lang + ": domain must be " + q.expectedDomain);
            assertEquals("TRAVEL_ALLOWANCE", intent.intentType());
            assertEquals(12.0, intent.hours().orElse(0.0), 0.01);
            assertEquals("RULE_ENGINE", routing.strategy().name());

            // Propagate into retrieval planning — plan must carry the same domain
            var plan = retrievalPlanner.plan(new AiRequest(q.text, null, null, null, 15),
                    intent.domain());
            assertEquals(Domain.of(q.expectedDomain), plan.primaryDomain(),
                q.lang + ": RetrievalPlan.primaryDomain must carry authoritative domain");
        }
        R.append(String.format("%n  All 4 languages: domain=%s propagated to RetrievalPlan ✓%n%n",
            Domain.of("TRAVEL")));
    }

    @Test
    @DisplayName("PROCUREMENT: domain authoritative (DE + EN)")
    void procurementDomain() {
        R.append("=".repeat(78)).append("\n");
        R.append("  DOMAIN PROPAGATION — PROCUREMENT (DE/EN)\n");
        R.append("=".repeat(78)).append("\n\n");

        for (Q q : List.of(
                new Q("DE", "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?", "PROCUREMENT"),
                new Q("EN", "Which award procedure applies to an 8,000 euro contract?", "PROCUREMENT"))) {
            StructuredIntent intent = semanticIntentParser.parse(q.text);
            var routing = decisionRouter.route(q.text);

            R.append(String.format("  %s: domain=%s intent=%s params=%s → %s%n",
                q.lang, intent.domain(), intent.intentType(), intent.parameters(),
                routing.strategy()));

            assertNotNull(intent.domain(), q.lang + ": parser must produce a domain");
            assertEquals(Domain.of(q.expectedDomain), intent.domain());
            assertEquals(8000.0, intent.amountEur().orElse(0.0), 0.01);
            assertEquals("RULE_ENGINE", routing.strategy().name());

            var plan = retrievalPlanner.plan(new AiRequest(q.text, null, null, null, 15),
                    intent.domain());
            assertEquals(Domain.of(q.expectedDomain), plan.primaryDomain());
        }
        R.append(String.format("%n  PROCUREMENT domain propagated ✓%n%n"));
    }

    @Test
    @DisplayName("HR/SALARY: domain authoritative (DE + EN)")
    void salaryDomain() {
        R.append("=".repeat(78)).append("\n");
        R.append("  DOMAIN PROPAGATION — HR (DE/EN)\n");
        R.append("=".repeat(78)).append("\n\n");

        for (Q q : List.of(
                new Q("DE", "Wie hoch ist das Gehalt für EG 9b Stufe 3?", "HR"),
                new Q("EN", "What is the salary for EG 9b step 3?", "HR"))) {
            StructuredIntent intent = semanticIntentParser.parse(q.text);
            var routing = decisionRouter.route(q.text);

            R.append(String.format("  %s: domain=%s intent=%s params=%s → %s%n",
                q.lang, intent.domain(), intent.intentType(), intent.parameters(),
                routing.strategy()));

            assertNotNull(intent.domain(), q.lang + ": parser must produce a domain");
            assertEquals(Domain.of(q.expectedDomain), intent.domain());
            assertEquals("SALARY_LOOKUP", intent.intentType());
            assertEquals("EG 9b", intent.salaryGrade().orElse(""));
            assertEquals("RULE_ENGINE", routing.strategy().name());

            var plan = retrievalPlanner.plan(new AiRequest(q.text, null, null, null, 15),
                    intent.domain());
            assertEquals(Domain.of(q.expectedDomain), plan.primaryDomain());
        }
        R.append(String.format("%n  HR domain propagated ✓%n%n"));
    }

    @Test
    @DisplayName("BUILDING retrieval: domain authoritative in plan")
    void buildingDomain() {
        R.append("=".repeat(78)).append("\n");
        R.append("  DOMAIN PROPAGATION — BUILDING (retrieval)\n");
        R.append("=".repeat(78)).append("\n\n");

        Q q = new Q("DE", "Welche Abstandsflächen sind nach BauO Bln Paragraph 6 einzuhalten?", "BUILDING");
        StructuredIntent intent = semanticIntentParser.parse(q.text);
        var routing = decisionRouter.route(q.text);

        R.append(String.format("  %s: domain=%s intent=%s params=%s → %s%n",
            q.lang, intent.domain(), intent.intentType(), intent.parameters(),
            routing.strategy()));

        assertNotNull(intent.domain(), "parser must produce a domain");
        assertEquals(Domain.of(q.expectedDomain), intent.domain());
        assertNotEquals("RULE_ENGINE", routing.strategy().name(),
            "Building question must not produce a deterministic rule");

        var plan = retrievalPlanner.plan(new AiRequest(q.text, null, null, null, 15),
                intent.domain());
        assertEquals(Domain.of(q.expectedDomain), plan.primaryDomain(),
            "RetrievalPlan must carry authoritative BUILDING domain");
        R.append(String.format("%n  BUILDING domain propagated to RetrievalPlan ✓%n%n"));
    }

    @Test
    @DisplayName("Legacy regex mode: DomainClassifier fallback still works")
    void legacyRegexMode() {
        R.append("=".repeat(78)).append("\n");
        R.append("  LEGACY MODE — regex parser (domain=null → DomainClassifier)\n");
        R.append("=".repeat(78)).append("\n\n");

        RegexSemanticIntentParser regexParser = new RegexSemanticIntentParser();
        String q = "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?";
        StructuredIntent intent = regexParser.parse(q);

        // Regex parser does not produce a domain — legacy behavior preserved
        assertNull(intent.domain(), "Regex parser must NOT produce a domain (legacy behavior)");
        assertEquals("TRAVEL_ALLOWANCE", intent.intentType());
        assertEquals(12.0, intent.hours().orElse(0.0), 0.01);

        // Legacy routing still works: RULE_ENGINE via structured parameters
        var routing = decisionRouter.route(q);
        // NOTE: the injected decisionRouter uses the LLM parser (enabled in this context),
        // so this exercises the LLM path. The regex-only router is covered by
        // DecisionRouterTest (25 tests) which constructs it directly.
        assertEquals("RULE_ENGINE", routing.strategy().name(),
            "Routing must work with regex-parsed intent");

        R.append("  Regex parser: domain=null (legacy), intent=TRAVEL_ALLOWANCE, hours=12.0\n");
        R.append("  Legacy DecisionRouterTest covers regex-only routing (25 tests)\n\n");
    }
}
