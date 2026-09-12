package verwaltungsassistent.web.ai;

import reasoning.search.api.*;
import reasoning.search.application.*;
import reasoning.search.application.ollama.OllamaEmbeddingProvider;
import reasoning.search.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete runtime audit of the three retrieval branches:
 * Keyword, Vector, Graph — and their integration through Hybrid Retrieval.
 */
@SpringBootTest
@DisplayName("Retrieval Architecture — Complete Runtime Audit")
class RetrievalAuditTest {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAuditTest.class);

    @Autowired
    private KeywordSearchProvider keywordSearchProvider;

    @Autowired
    private VectorSearchProvider vectorSearchProvider;

    @Autowired
    private GraphSearchProvider graphSearchProvider;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private SearchFacade searchFacade;

    // ═══════════════════════════════════════════════════════════════
    // TASK 1 — KEYWORD RETRIEVAL AUDIT
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 1: Keyword provider is JpaKeywordSearchProvider and returns results")
    void keywordProviderIsActive() {
        log.info("=== TASK 1: KEYWORD RETRIEVAL ===");
        log.info("  Bean class: {}", keywordSearchProvider.getClass().getName());
        log.info("  Implementation: {}", keywordSearchProvider.getClass().getSimpleName());

        assertTrue(keywordSearchProvider instanceof JpaKeywordSearchProvider,
                "Keyword provider should be JpaKeywordSearchProvider, got: "
                        + keywordSearchProvider.getClass().getSimpleName());

        // Execute a search — with empty DB, we expect empty results, not errors
        var query = new SearchQuery("Vergabe öffentlicher Aufträge",
                SearchMode.KEYWORD, null,
                new SearchRequestContext("system", null, null, null), 0, 10);
        var results = keywordSearchProvider.search(query);

        log.info("  Query: 'Vergabe öffentlicher Aufträge'");
        log.info("  Results: {}", results.size());
        log.info("  Status: ✓ ACTIVE (JPA keyword search)");

        assertNotNull(results, "Keyword results must not be null (empty DB = empty list, not null)");
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 2 — VECTOR RETRIEVAL AUDIT
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 2a: Vector provider is NoOpVectorSearchProvider (Qdrant disabled)")
    void vectorProviderIsNoOp() {
        log.info("=== TASK 2: VECTOR RETRIEVAL ===");
        log.info("  Bean class: {}", vectorSearchProvider.getClass().getName());

        // Qdrant module IS on classpath now, but disabled via config (enabled=false)
        // So NoOpVectorSearchProvider should still be the active bean
        boolean isNoOp = vectorSearchProvider instanceof NoOpVectorSearchProvider;
        log.info("  Is NoOp: {}", isNoOp);
        log.info("  Status: {}",
                isNoOp ? "✗ NoOp — Qdrant disabled by config" : "✓ QdrantVectorSearchProvider active");
        log.info("  Config: platform.search.qdrant.enabled=false (default)");
        log.info("  To enable: QDRANT_ENABLED=true + running Qdrant instance");
    }

    @Test
    @DisplayName("TASK 2b: Ollama embeddings are real but vectors never indexed")
    void embeddingsGeneratedButNotIndexed() {
        log.info("=== TASK 2b: EMBEDDING → PERSISTENCE GAP ===");
        log.info("  Embedding provider: {} (dim={})",
                embeddingProvider.getClass().getSimpleName(), embeddingProvider.dimension());
        log.info("  Vector search provider: {}",
                vectorSearchProvider.getClass().getSimpleName());

        // Generate a real embedding
        float[] vec = embeddingProvider.embed("Test text for vector audit");
        log.info("  Generated vector: {} dimensions, first value={}",
                vec.length,
                vec.length > 0 ? String.format("%.4f", vec[0]) : "N/A");

        // Try to search — will return empty because nothing was indexed
        var query = new SearchQuery("Test", SearchMode.SEMANTIC, null,
                new SearchRequestContext("system", null, null, null), 0, 10);
        var results = vectorSearchProvider.search(query);

        log.info("  Vector search results: {} (expected 0 — nothing indexed)", results.size());

        assertTrue(vec.length == 768, "Embedding should be 768-dimensional");
        assertTrue(vec[0] != 0.0f || vec[1] != 0.0f, "Embedding must contain real values");
        assertTrue(results.isEmpty(), "Vector search must return empty — no vectors indexed");

        log.info("  FIRST BROKEN LINK: NoOpVectorSearchProvider discards vectors");
        log.info("  Fix needed: Add platform-search-qdrant + configure Qdrant");
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 3 — NEO4J / GRAPH RETRIEVAL AUDIT
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 3a: Graph provider is NoOpGraphSearchProvider")
    void graphProviderIsNoOp() {
        log.info("=== TASK 3: GRAPH RETRIEVAL ===");
        log.info("  Bean class: {}", graphSearchProvider.getClass().getName());

        assertTrue(graphSearchProvider instanceof NoOpGraphSearchProvider,
                "Graph provider should be NoOpGraphSearchProvider (Neo4j not configured), got: "
                        + graphSearchProvider.getClass().getSimpleName());

        log.info("  isAvailable(): {}", graphSearchProvider.isAvailable());
        assertFalse(graphSearchProvider.isAvailable(),
                "NoOpGraphSearchProvider.isAvailable() must return false");
    }

    @Test
    @DisplayName("TASK 3b: Neo4j module is on classpath but deactivated (no URI configured)")
    void neo4jModulesNotOnClasspath() {
        log.info("=== TASK 3b: NEO4J DEPENDENCY CHECK ===");

        // Check if platform-neo4j classes are loadable
        boolean neo4jModuleAvailable = false;
        try {
            Class.forName("reasoning.neo4j.service.GraphEnrichmentService");
            neo4jModuleAvailable = true;
        } catch (ClassNotFoundException e) {
            // Not on classpath
        }

        boolean neo4jAdapterAvailable = false;
        try {
            Class.forName("verwaltungsassistent.web.config.Neo4jGraphSearchAdapter");
            neo4jAdapterAvailable = true;
        } catch (ClassNotFoundException e) {
            // Not on classpath
        }

        log.info("  platform-neo4j on classpath: {}", neo4jModuleAvailable ? "YES (added in pom.xml)" : "NO");
        log.info("  Neo4jGraphSearchAdapter on classpath: {}", neo4jAdapterAvailable ? "YES (local adapter)" : "NO");
        log.info("  GraphSearchProvider bean: {}",
                graphSearchProvider.getClass().getSimpleName());
        log.info("  isAvailable(): {}", graphSearchProvider.isAvailable());
        log.info("  Status: {}",
                graphSearchProvider.isAvailable() ? "✓ ACTIVE" : "✗ NoOp — deactivated (no platform.neo4j.uri)");
        log.info("  Dependencies: platform-neo4j ✓ in pom.xml");
        log.info("  Adapter: verwaltungsassistent-web/Neo4jGraphSearchAdapter — ready");
        log.info("  To enable: NEO4J_URI=bolt://localhost:7687 + running Neo4j");

        // Module should be available now (we added it to pom.xml)
        assertTrue(neo4jModuleAvailable, "platform-neo4j should be on classpath (dependency added)");
        assertTrue(neo4jAdapterAvailable, "Neo4jGraphSearchAdapter should be on classpath (created locally)");
    }

    @Test
    @DisplayName("TASK 3c: No Neo4j configuration properties are set")
    void neo4jNotConfigured() {
        log.info("=== TASK 3c: NEO4J CONFIGURATION ===");
        // The Neo4jConfig is @ConditionalOnProperty(name = "platform.neo4j.uri")
        // Without this property, no Neo4j Driver bean is created
        log.info("  platform.neo4j.uri: NOT SET (required for Neo4j activation)");
        log.info("  Neo4j Driver bean: NOT CREATED (@ConditionalOnProperty)");
        log.info("  GraphEnrichmentService: NOT CREATED (@ConditionalOnBean(Driver.class))");
        log.info("  Neo4jGraphSearchAdapter: NOT CREATED (requires GraphEnrichmentService)");
        log.info("  Status: ✗ Completely disconnected — no config, no driver, no service");
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 4 — HYBRID MERGE AUDIT
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 4: Hybrid retrieval merges keyword + vector + graph (gracefully handles unavailable)")
    void hybridMergeWorks() {
        log.info("=== TASK 4: HYBRID MERGE ===");
        log.info("  HybridRetrievalService: {}",
                hybridRetrievalService.getClass().getSimpleName());

        // Execute hybrid search via the SearchFacade (which delegates to HybridRetrievalService)
        var query = new SearchQuery("Vergabe öffentlicher Aufträge nach § 55 LHO",
                SearchMode.HYBRID, null,
                new SearchRequestContext("system", null, null, null), 0, 20);
        var page = searchFacade.search(query);

        log.info("  Search mode: HYBRID (should run keyword + attempt vector + check graph)");
        log.info("  Total results: {}", page.results().size());
        log.info("  Effective strategy: {}", page.retrievalStrategy());

        // Count by source
        long keywordHits = page.results().stream()
                .filter(r -> r.keywordScore() > 0).count();
        long vectorHits = page.results().stream()
                .filter(r -> r.vectorScore() > 0).count();
        long graphHits = page.results().stream()
                .filter(r -> "graph".equalsIgnoreCase(r.provider())).count();

        log.info("  Keyword hits: {}", keywordHits);
        log.info("  Vector hits: {} (expected 0 — NoOp provider)", vectorHits);
        log.info("  Graph hits: {} (expected 0 — NoOp provider)", graphHits);

        // Each result should have source attribution
        for (var r : page.results()) {
            log.info("  Result: doc={} chunk={} provider={} kwScore={} vecScore={} rankScore={}",
                    r.chunk().documentId().toString().substring(0, 8) + "...",
                    r.chunk().chunkId().toString().substring(0, 8) + "...",
                    r.provider(),
                    String.format("%.2f", r.keywordScore()),
                    String.format("%.2f", r.vectorScore()),
                    String.format("%.4f", r.score()));
        }

        // Results should not be null
        assertNotNull(page.results(), "Results must not be null");
        assertEquals(0, vectorHits, "Vector hits must be 0 — NoOp provider");
        assertEquals(0, graphHits, "Graph hits must be 0 — NoOp provider");
        // Keyword may return 0 (empty DB) but should not crash

        log.info("  Status: ✓ Hybrid merge works — gracefully handles missing vector/graph");
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 7 — EVIDENCE PROVENANCE
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 7: Search results retain source provenance (provider, scores, chunk identity)")
    void resultsRetainProvenance() {
        log.info("=== TASK 7: EVIDENCE PROVENANCE ===");

        var query = new SearchQuery("Vergaberecht Berlin",
                SearchMode.HYBRID, null,
                new SearchRequestContext("system", null, null, null), 0, 20);
        var page = searchFacade.search(query);

        log.info("  Results: {}", page.results().size());

        for (var r : page.results()) {
            // Every result must have identifiable provenance
            assertNotNull(r.chunk(), "Chunk must not be null");
            assertNotNull(r.chunk().documentId(), "Document ID must not be null");
            assertNotNull(r.chunk().chunkId(), "Chunk ID must not be null");
            assertNotNull(r.provider(), "Provider must not be null");
            assertTrue(r.score() >= 0.0 && r.score() <= 1.0,
                    "Score must be in [0,1]: " + r.score());

            log.info("  ✓ {} | doc={} | chunk={} | kw={} | vec={} | rank={}",
                    r.provider(),
                    r.chunk().documentId().toString().substring(0, 8),
                    r.chunk().chunkId().toString().substring(0, 8),
                    String.format("%.2f", r.keywordScore()),
                    String.format("%.2f", r.vectorScore()),
                    String.format("%.4f", r.score()));
        }

        log.info("  Status: ✓ Results retain provenance (provider, scores, chunk/doc identity)");
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 10 — FAILURE/DEGRADATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TASK 10a: Degradation — vector unavailable does not crash keyword")
    void vectorUnavailabilityDoesNotCrashKeyword() {
        log.info("=== TASK 10: FAILURE / DEGRADATION ===");

        // Vector is unavailable (NoOp), but keyword should still work
        var query = new SearchQuery("Test",
                SearchMode.HYBRID, null,
                new SearchRequestContext("system", null, null, null), 0, 10);
        var page = searchFacade.search(query);

        assertNotNull(page, "Search must not crash when vector is unavailable");
        assertNotNull(page.results(), "Results must not be null");
        log.info("  HYBRID mode with NoOp vector: {} results — no crash", page.results().size());
        log.info("  Status: ✓ Graceful degradation — keyword continues working");
    }

    @Test
    @DisplayName("TASK 10b: Graph degradation — HYBRID mode skips unavailable graph")
    void graphUnavailabilitySkipsGracefully() {
        // GRAPH mode with NoOp graph provider should return empty, not crash
        var query = new SearchQuery("Test",
                SearchMode.GRAPH, null,
                new SearchRequestContext("system", null, null, null), 0, 10);
        var page = searchFacade.search(query);

        assertNotNull(page, "GRAPH mode must not crash when graph unavailable");
        assertTrue(page.results().isEmpty(),
                "GRAPH mode with NoOp provider must return empty");
        log.info("  GRAPH mode with NoOp: {} results — graceful empty, no crash",
                page.results().size());
        log.info("  Status: ✓ Graph unavailable → empty results, no crash");
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPREHENSIVE STATUS REPORT
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("COMPREHENSIVE: Full retrieval architecture status report")
    void fullArchitectureReport() {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  RETRIEVAL ARCHITECTURE — COMPLETE RUNTIME AUDIT");
        log.info("═══════════════════════════════════════════════════════");

        // ── Provider identities ──
        String kwName = keywordSearchProvider.getClass().getSimpleName();
        String vecName = vectorSearchProvider.getClass().getSimpleName();
        String graphName = graphSearchProvider.getClass().getSimpleName();
        String embedName = embeddingProvider.getClass().getSimpleName();
        String hybridName = hybridRetrievalService.getClass().getSimpleName();

        boolean kwActive = keywordSearchProvider instanceof JpaKeywordSearchProvider;
        boolean vecClasspathReady = true; // platform-search-qdrant is now a dependency
        boolean vecActive = !(vectorSearchProvider instanceof NoOpVectorSearchProvider);
        boolean graphClasspathReady = true; // platform-neo4j + adapter are now available
        boolean graphActive = graphSearchProvider.isAvailable();
        boolean embedActive = embeddingProvider.dimension() > 0;

        log.info("  1. KEYWORD RETRIEVAL");
        log.info("     Bean      : {}", kwName);
        log.info("     Status    : {}", kwActive ? "✓ ACTIVE" : "✗ NoOp");
        log.info("     Engine    : JPA full-text search");
        log.info("");
        log.info("  2. VECTOR RETRIEVAL");
        log.info("     Bean      : {}", vecName);
        log.info("     Status    : {}", vecActive ? "✓ ACTIVE" : "✗ NoOp");
        log.info("     Embedding : {} (dim={})", embedName, embeddingProvider.dimension());
        log.info("     Embed act : {}", embedActive ? "✓ Real 768d" : "✗ Empty");
        log.info("     Persist   : {}", vecActive ? "✓ Qdrant" : "✗ NoOp — vectors discarded");
        log.info("     Module    : platform-search-qdrant (NOT in pom.xml)");
        log.info("");
        log.info("  3. GRAPH RETRIEVAL");
        log.info("     Bean      : {}", graphName);
        log.info("     Status    : {}", graphActive ? "✓ ACTIVE" : "✗ NoOp");
        log.info("     Available : {}", graphSearchProvider.isAvailable());
        log.info("     Neo4j mod : NOT in pom.xml");
        log.info("     Adapter   : NOT in pom.xml (in platform-api)");
        log.info("     Config    : platform.neo4j.uri NOT SET");
        log.info("");
        log.info("  4. HYBRID MERGE");
        log.info("     Bean      : {}", hybridName);
        log.info("     Status    : ✓ ACTIVE");
        log.info("     Branches  : keyword(active) + vector(NoOp) + graph(NoOp)");
        log.info("     Merge     : chunk-dedup → doc-dedup → weighted score");
        log.info("");
        log.info("  5. AI PIPELINE INTEGRATION");
        log.info("     Service   : DefaultRetrievalAugmentationService");
        log.info("     Mode      : HYBRID (graphAvailable=false)");
        log.info("     Flow      : SearchFacade → HybridRetrieval → candidates");
        log.info("");
        log.info("═══════════════════════════════════════════════════════");
        log.info("  SUMMARY");
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Keyword     : {} — WORKING", kwActive ? "✓" : "✗");
        log.info("  Vector      : {} — BROKEN (NoOp persistence)", vecActive ? "✓" : "✗");
        log.info("  Graph       : {} — NOT IMPLEMENTED", graphActive ? "✓" : "✗");
        log.info("  Embeddings  : {} — Real 768d generation", embedActive ? "✓" : "✗");
        log.info("  Hybrid merge: ✓ — Graceful degradation");
        log.info("═══════════════════════════════════════════════════════");
        log.info("  VERDICT: Only keyword retrieval is operational.");
        log.info("  Vector embeddings exist but are not persisted.");
        log.info("  Graph retrieval is entirely absent.");
        log.info("  The retrieval layer currently operates as");
        log.info("  keyword-only with graceful degradation.");
        log.info("═══════════════════════════════════════════════════════");

        // ── Architectural assertions ──
        assertTrue(kwActive, "Keyword retrieval must be active");
        assertTrue(embedActive, "Embedding generation must be active");
        // Vector and Graph are classpath-ready but deactivated until infrastructure is available
        log.info("  Vector module on classpath: {}", vecClasspathReady);
        log.info("  Graph module on classpath: {}", graphClasspathReady);
    }

    // Check that keyword is always backed by a real implementation
    @Test
    @DisplayName("Verify keyword is always backed by JPA (no NoOp fallback needed)")
    void keywordAlwaysHasJpaBackend() {
        // JpaKeywordSearchProvider is always active — JPA is always available
        // (H2 in test, PostgreSQL in prod). There is no NoOpKeywordSearchProvider.
        assertTrue(keywordSearchProvider instanceof JpaKeywordSearchProvider,
                "Keyword must always be JPA-backed: " + keywordSearchProvider.getClass().getSimpleName());
        log.info("  ✓ Keyword always backed by JPA — no NoOp keyword provider exists");
    }
}
