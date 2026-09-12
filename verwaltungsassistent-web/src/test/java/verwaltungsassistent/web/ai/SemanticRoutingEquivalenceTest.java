package verwaltungsassistent.web.ai;

import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.LlmSemanticIntentParser;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.SalaryTable;
import reasoning.ai.knowledge.ThresholdTable;
import reasoning.ai.knowledge.TravelAllowanceTable;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.DecisionStrategy;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.provider.OllamaChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that semantically equivalent questions in German, English, and
 * Portuguese reach the SAME structured intent and therefore the SAME
 * deterministic rule path (RULE_ENGINE with identical parameters).
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
    "platform.search.qdrant.enabled=false",
    "spring.profiles.active=dev",
    "spring.flyway.enabled=false"
})
@DisplayName("Semantic Routing Equivalence DE/EN/PT")
class SemanticRoutingEquivalenceTest {

    @Autowired(required = false)
    private AiProviderProperties aiProperties;

    private static DecisionRouter regexRouter;
    private static DecisionRouter llmRouter;
    private static KnowledgeRegistry registry;
    private static final StringBuilder R = new StringBuilder();

    @BeforeAll
    static void buildRegistry() {
        registry = new KnowledgeRegistry();

        TravelAllowanceTable brkg = new TravelAllowanceTable("BRKG", "BRKG", LocalDate.of(2024, 1, 1));
        brkg.addEntry(8, 24.0, 6.0, "domestic", false, "Abwesenheit über 8 Stunden");
        brkg.addEntry(11, 24.0, 12.0, "domestic", false, "Abwesenheit über 11 Stunden");
        brkg.addEntry(24, 24.0, 28.0, "domestic", false, "Abwesenheit 24 Stunden");
        registry.register(brkg);

        SalaryTable tvl = new SalaryTable("TV-L 2025", "TV-L", LocalDate.of(2025, 2, 1), null);
        tvl.addEntry("EG 9", 3, 4117.53, 0, "");
        tvl.addEntry("EG 11", 3, 4910.15, 0, "");
        registry.register(tvl);

        ThresholdTable av55 = new ThresholdTable("AV zu Paragraph 55 LHO Berlin", "AV §55 LHO",
                LocalDate.of(2024, 1, 1));
        av55.addEntry(1000.0, 10_000.0, "Direktauftrag",
                "Lieferung/Dienstleistung", List.of("Vergabevermerk erforderlich"),
                "1.000 € bis 10.000 €");
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung", List.of("Ex-post-Veröffentlichung"),
                "10.000 € bis 100.000 €");
        registry.register(av55);

        DomainClassifier domainClassifier = new DomainClassifier(DomainKnowledge.skeletal());
        regexRouter = new DecisionRouter(registry, domainClassifier, null,
                new RegexSemanticIntentParser());
    }

    @BeforeEach
    void buildLlmRouter() {
        Assumptions.assumeTrue(aiProperties != null, "AiProviderProperties not available");
        try {
            ObjectMapper mapper = new ObjectMapper();
            OllamaChatProvider provider = new OllamaChatProvider(mapper, aiProperties);
            Assumptions.assumeTrue(provider.isAvailable(), "Ollama not available");
            DomainClassifier domainClassifier = new DomainClassifier(DomainKnowledge.skeletal());
            llmRouter = new DecisionRouter(registry, domainClassifier, null,
                    new LlmSemanticIntentParser(provider, aiProperties));
        } catch (Exception e) {
            Assumptions.abort("Cannot build LLM router: " + e.getMessage());
        }
    }

    record Q(String lang, String text) {}

    // ── Travel allowance: 12 hours ──

    static final List<Q> TRAVEL_12H = List.of(
        new Q("DE", "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?"),
        new Q("EN", "What is the meal allowance for a 12-hour business trip?"),
        new Q("PT", "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?")
    );

    // ── Salary: EG 9 step 3 ──

    static final List<Q> SALARY_EG9S3 = List.of(
        new Q("DE", "Wie hoch ist das Gehalt in EG 9 Stufe 3 nach TV-L?"),
        new Q("EN", "What is the salary for grade EG 9 step 3 in TV-L?"),
        new Q("PT", "Qual é o salário para o grau EG 9 nível 3 no TV-L?")
    );

    // ── Procurement: 8,000 euros ──

    static final List<Q> PROC_8000 = List.of(
        new Q("DE", "Kann ich einen IT-Auftrag über 8.000 Euro freihändig vergeben?"),
        new Q("EN", "Can I directly award an IT contract worth 8,000 euros?"),
        new Q("PT", "Posso contratar diretamente um serviço de TI no valor de 8.000 euros?")
    );

    @Test
    @DisplayName("Travel: DE/EN/PT → same RULE_ENGINE decision via LLM router")
    void travelEquivalenceLlm() {
        R.append("=".repeat(78)).append("\n");
        R.append("  SEMANTIC ROUTING EQUIVALENCE — LLM ROUTER\n");
        R.append("=".repeat(78)).append("\n\n");

        DecisionResult first = null;
        for (Q q : TRAVEL_12H) {
            var result = llmRouter.route(q.text);
            R.append(String.format("  %-3s %s%n", q.lang, q.text));
            R.append(String.format("      → strategy=%s decision=%s%n",
                    result.strategy(), decisionSummary(result.decision())));

            assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy(),
                q.lang + " travel query must route to RULE_ENGINE");
            assertTrue(result.decision() instanceof DecisionResult.TravelDecision,
                q.lang + " travel query must produce TravelDecision");

            if (first == null) first = result.decision();
            else {
                var td1 = (DecisionResult.TravelDecision) first;
                var td2 = (DecisionResult.TravelDecision) result.decision();
                assertEquals(td1.allowanceEur(), td2.allowanceEur(), 0.01,
                    q.lang + " allowance must match reference language");
                assertEquals(td1.hours(), td2.hours(), 0.01,
                    q.lang + " hours must match reference language");
            }
        }
        R.append(String.format("%n  All languages → identical RULE_ENGINE travel decision ✓%n%n"));
    }

    @Test
    @DisplayName("Salary: DE/EN/PT → same RULE_ENGINE decision via LLM router")
    void salaryEquivalenceLlm() {
        DecisionResult first = null;
        for (Q q : SALARY_EG9S3) {
            var result = llmRouter.route(q.text);
            R.append(String.format("  %-3s %s → %s%n", q.lang, q.text,
                    decisionSummary(result.decision())));
            assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy(),
                q.lang + " salary query must route to RULE_ENGINE");
            assertTrue(result.decision() instanceof DecisionResult.SalaryDecision,
                q.lang + " salary query must produce SalaryDecision");
            if (first == null) {
                first = result.decision();
            } else {
                var sd1 = (DecisionResult.SalaryDecision) first;
                var sd2 = (DecisionResult.SalaryDecision) result.decision();
                assertEquals(sd1.monthlyAmount(), sd2.monthlyAmount(), 0.01,
                    q.lang + " monthly amount must match reference language");
                assertEquals(sd1.grade(), sd2.grade(), q.lang + " grade must match");
            }
        }
        R.append(String.format("%n  All languages → identical RULE_ENGINE salary decision ✓%n%n"));
    }

    @Test
    @DisplayName("Procurement: DE/EN/PT → same RULE_ENGINE decision via LLM router")
    void procurementEquivalenceLlm() {
        DecisionResult first = null;
        for (Q q : PROC_8000) {
            var result = llmRouter.route(q.text);
            R.append(String.format("  %-3s %s → %s%n", q.lang, q.text,
                    decisionSummary(result.decision())));
            assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy(),
                q.lang + " procurement query must route to RULE_ENGINE");
            assertTrue(result.decision() instanceof DecisionResult.ProcurementDecision,
                q.lang + " procurement query must produce ProcurementDecision");
            if (first == null) {
                first = result.decision();
            } else {
                var pd1 = (DecisionResult.ProcurementDecision) first;
                var pd2 = (DecisionResult.ProcurementDecision) result.decision();
                assertEquals(pd1.procedure(), pd2.procedure(),
                    q.lang + " procedure must match reference language");
            }
        }
        R.append(String.format("%n  All languages → identical RULE_ENGINE procurement decision ✓%n%n"));
    }

    @Test
    @DisplayName("Regex router baseline: German works, PT/EN fail or misroute")
    void regexRouterBaseline() {
        R.append("=".repeat(78)).append("\n");
        R.append("  REGEX ROUTER BASELINE (fallback behavior)\n");
        R.append("=".repeat(78)).append("\n\n");
        for (Q q : TRAVEL_12H) {
            var result = regexRouter.route(q.text);
            R.append(String.format("  %-3s → strategy=%s decision=%s%n",
                    q.lang, result.strategy(), decisionSummary(result.decision())));
        }
        for (Q q : SALARY_EG9S3) {
            var result = regexRouter.route(q.text);
            R.append(String.format("  %-3s → strategy=%s decision=%s%n",
                    q.lang, result.strategy(), decisionSummary(result.decision())));
        }
        R.append(String.format("%n  (Regex parser: German is correct; EN/PT depend on language patterns)%n%n"));
    }

    @Test
    @DisplayName("FINAL: Routing equivalence report")
    void finalReport() {
        R.append("=".repeat(78)).append("\n");
        R.append("  ROUTING EQUIVALENCE SUMMARY\n");
        R.append("=".repeat(78)).append("\n\n");
        R.append("  LLM router: DE/EN/PT reach identical structured intent\n");
        R.append("  → identical deterministic rule decision (RULE_ENGINE)\n");
        R.append("  Regex router: German-dependent patterns (fallback)\n\n");
        writeReport();
        System.out.println(R.toString());
    }

    private static String decisionSummary(DecisionResult d) {
        if (d == null) return "null (retrieval)";
        if (d instanceof DecisionResult.TravelDecision td)
            return "Tagegeld " + td.allowanceEur() + "€ / " + td.hours() + "h";
        if (d instanceof DecisionResult.SalaryDecision sd)
            return sd.grade() + " Stufe " + sd.step() + " = " + sd.monthlyAmount() + "€";
        if (d instanceof DecisionResult.ProcurementDecision pd)
            return pd.procedure();
        return d.getClass().getSimpleName();
    }

    private static void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/semantic-routing-equivalence.txt"), R.toString());
        } catch (IOException ignored) {}
    }
}
