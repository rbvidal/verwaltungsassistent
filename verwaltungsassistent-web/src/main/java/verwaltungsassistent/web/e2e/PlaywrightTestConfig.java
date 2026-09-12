package verwaltungsassistent.web.e2e;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DecisionRouter.RoutingResult;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.model.DecisionStrategy;
import reasoning.ai.model.Domain;
import reasoning.ai.model.StructuredIntent;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.EmbeddingProvider;
import reasoning.search.api.GraphSearchProvider;
import reasoning.search.api.VectorSearchProvider;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Map;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * E2E-Testkonfiguration (playwright-Profil):
 * <ul>
 *   <li><b>Uhr:</b> {@link TestClock} (@Primary) — die Bearbeitungszeit-
 *       Messung (CaseWorkStateService) läuft über diese veränderbare Uhr;
 *       keine echten Wartezeiten im Browser-Test.</li>
 *   <li><b>KI:</b> {@link DeterministicAiFacade} (@Primary) hinter der
 *       bestehenden {@link AiFacade}-Schnittstelle — kein Ollama, keine
 *       Nichtdeterminismen; die vollständige Workflow-Kette bleibt echt.</li>
 *   <li><b>Suche/Embedding:</b> No-Op-Provider — die Dokument-Ingestion
 *       läuft deterministisch ohne Vektor-Embedding gegen Qdrant/Ollama.</li>
 * </ul>
 * Produktionsprofile bleiben unverändert.
 */
@Configuration
@Profile("playwright")
public class PlaywrightTestConfig {

    @Bean
    @Primary
    public TestClock playwrightTestClock() {
        return new TestClock(Instant.now());
    }

    @Bean
    @Primary
    public AiFacade deterministicAiFacade() {
        return new DeterministicAiFacade();
    }

    /**
     * Die E-Mail-Analyse ruft den DecisionRouter DIREKT auf (semantische
     * Intent-Klassifikation) — ohne Stub würde jede E-Mail-Analyse eine echte
     * LLM-Intent-Extraktion (Ollama) auslösen. Der Stub liefert eine feste,
     * deterministische Klassifikation (GEWERBE/GENERAL) hinter derselben
     * Router-Schnittstelle.
     */
    @Bean
    @Primary
    public DecisionRouter e2eDecisionRouter(KnowledgeRegistry registry,
                                            DomainClassifier domainClassifier,
                                            GraphSearchProvider graphSearchProvider,
                                            SemanticIntentParser semanticIntentParser) {
        return new DecisionRouter(registry, domainClassifier, graphSearchProvider,
                semanticIntentParser) {
            @Override
            public RoutingResult route(String question) {
                StructuredIntent intent = new StructuredIntent(question, Domain.of("GEWERBE"),
                        "GENERAL", Map.of(), "de");
                return new RoutingResult(DecisionStrategy.HYBRID_RETRIEVAL, null,
                        "Deterministische E2E-Intentklassifikation", intent);
            }
        };
    }

    @Bean
    @Primary
    public EmbeddingProvider e2eNoOpEmbeddingProvider() {
        return new EmbeddingProvider() {
            @Override
            public float[] embed(String text) {
                return new float[0];
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(t -> new float[0]).toList();
            }

            @Override
            public int dimension() {
                return 0;
            }

            @Override
            public String modelName() {
                return "e2e-noop";
            }
        };
    }

    /**
     * Die Dokument-Anreicherung (strukturierte Wissensextraktion → Neo4j)
     * würde bei jedem Upload eine echte LLM-Extraktion auslösen — im E2E
     * wird sie über "Graph nicht verfügbar" sauber übersprungen
     * (EnrichmentHook prüft {@code graph.isAvailable()} VOR dem LLM-Aufruf).
     */
    @Bean
    @Primary
    public GraphEnrichmentService e2eNoOpGraphEnrichmentService(ObjectProvider<Driver> drivers) {
        return new GraphEnrichmentService(drivers) {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    @Bean
    @Primary
    public VectorSearchProvider e2eNoOpVectorSearchProvider() {
        return new VectorSearchProvider() {
            @Override
            public List<RetrievalCandidate> search(SearchQuery query) {
                return List.of();
            }

            @Override
            public void index(DocumentChunk chunk, float[] embedding) {
                // No-Op: keine Vektor-Indizierung im E2E-Profil.
            }

            @Override
            public void deleteByDocument(UUID documentId) {
                // No-Op.
            }

            @Override
            public void deleteAll() {
                // No-Op.
            }
        };
    }
}
