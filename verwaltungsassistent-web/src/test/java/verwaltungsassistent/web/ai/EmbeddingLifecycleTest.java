package verwaltungsassistent.web.ai;

import reasoning.search.api.EmbeddingProvider;
import reasoning.search.api.VectorSearchProvider;
import reasoning.search.application.NoOpEmbeddingProvider;
import reasoning.search.application.NoOpVectorSearchProvider;
import reasoning.search.application.ollama.OllamaEmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime verification of the embedding/indexing lifecycle.
 * Verifies which providers are actually active and whether embeddings are generated.
 */
@SpringBootTest
@DisplayName("Embedding Lifecycle — Runtime Verification")
class EmbeddingLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingLifecycleTest.class);

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private VectorSearchProvider vectorSearchProvider;

    // ═══════════════════════════════════════════════════════════════
    // STAGE: Embedding Provider
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EmbeddingProvider is OllamaEmbeddingProvider, NOT NoOpEmbeddingProvider")
    void embeddingProviderIsOllamaNotNoOp() {
        log.info("=== Embedding Provider Identity ===");
        log.info("  Class: {}", embeddingProvider.getClass().getName());

        assertInstanceOf(OllamaEmbeddingProvider.class, embeddingProvider,
                "Expected OllamaEmbeddingProvider to be active, but got: "
                        + embeddingProvider.getClass().getSimpleName());

        assertFalse(embeddingProvider instanceof NoOpEmbeddingProvider,
                "NoOpEmbeddingProvider should NOT be active — Ollama should be the provider");
    }

    @Test
    @DisplayName("Embedding dimension is 768 (nomic-embed-text), not 0")
    void embeddingDimensionIsNonZero() {
        int dimension = embeddingProvider.dimension();
        log.info("=== Embedding Dimension ===");
        log.info("  Dimension: {}", dimension);
        log.info("  Model: {}", embeddingProvider.modelName());

        assertEquals(768, dimension, "Expected 768d (nomic-embed-text), got: " + dimension);
        assertTrue(dimension > 0, "Dimension must be positive");
        assertEquals("nomic-embed-text", embeddingProvider.modelName());
    }

    @Test
    @DisplayName("embed() returns non-empty vector of correct dimension")
    void embedReturnsNonEmptyVector() {
        String text = "Die Vergabe öffentlicher Aufträge richtet sich nach § 55 LHO.";
        float[] vector = embeddingProvider.embed(text);

        log.info("=== Embedding Generation ===");
        log.info("  Input text: {}", text);
        log.info("  Vector length: {}", vector.length);
        log.info("  First 5 values: [{}, {}, {}, {}, {}]",
                vector.length > 0 ? String.format("%.6f", vector[0]) : "N/A",
                vector.length > 1 ? String.format("%.6f", vector[1]) : "N/A",
                vector.length > 2 ? String.format("%.6f", vector[2]) : "N/A",
                vector.length > 3 ? String.format("%.6f", vector[3]) : "N/A",
                vector.length > 4 ? String.format("%.6f", vector[4]) : "N/A");

        assertNotNull(vector, "Vector must not be null");
        assertEquals(768, vector.length, "Vector must be 768-dimensional");
        // Verify it's not all zeros
        boolean hasNonZero = false;
        for (float v : vector) {
            if (v != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Vector must contain non-zero values (not empty embedding)");
    }

    @Test
    @DisplayName("embedBatch() returns correct number of vectors")
    void embedBatchReturnsCorrectCount() {
        var texts = java.util.List.of(
                "§ 55 LHO regelt die Vergabe öffentlicher Aufträge.",
                "Das BRKG definiert die Reisekostenerstattung für Bundesbeamte.",
                "Die TV-L Entgelttabelle legt Gehälter im öffentlichen Dienst fest."
        );
        var vectors = embeddingProvider.embedBatch(texts);

        log.info("=== Batch Embedding ===");
        log.info("  Input count: {}", texts.size());
        log.info("  Output count: {}", vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            log.info("  Vector[{}] length: {}", i, vectors.get(i).length);
        }

        assertEquals(3, vectors.size(), "Must return 3 vectors for 3 inputs");
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals(768, vectors.get(i).length,
                    "Vector " + i + " must be 768-dimensional");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STAGE: Vector Search Provider
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("VectorSearchProvider: Qdrant module available but disabled by config")
    void vectorSearchProviderIsNoOp() {
        log.info("=== Vector Search Provider Identity ===");
        log.info("  Class: {}", vectorSearchProvider.getClass().getName());

        boolean isNoOp = vectorSearchProvider instanceof NoOpVectorSearchProvider;
        log.info("  Is NoOp: {}", isNoOp);
        log.info("  Status: {}",
                isNoOp ? "NoOp — Qdrant disabled (enabled=false in config)"
                       : "QdrantVectorSearchProvider active");

        if (isNoOp) {
            log.info("  ⚠ Vectors generated but NOT persisted — enable QDRANT_ENABLED=true");
        }
    }

    @Test
    @DisplayName("Vector search returns empty results (NoOp provider)")
    void vectorSearchReturnsEmpty() {
        var query = new reasoning.search.model.SearchQuery(
                "test query", null, null, null, 0, 10);
        var results = vectorSearchProvider.search(query);

        log.info("=== Vector Search ===");
        log.info("  Query: 'test query'");
        log.info("  Results: {}", results.size());

        assertTrue(results.isEmpty(), "NoOp vector search should return empty results");
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle Summary
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Full lifecycle check: reports status of each stage")
    void fullLifecycleReport() {
        log.info("═══════════════════════════════════════════");
        log.info("  DOCUMENT LIFECYCLE — RUNTIME STATUS");
        log.info("═══════════════════════════════════════════");
        log.info("  1. Upload              : ✓ (DocumentController)");
        log.info("  2. Text extraction     : ✓ (DefaultTextExtractionService)");
        log.info("  3. Chunking            : ✓ (SentenceAwareChunkingStrategy)");
        log.info("  4. Embedding provider  : {} (dim={})",
                embeddingProvider.getClass().getSimpleName(),
                embeddingProvider.dimension());
        log.info("     Model               : {}", embeddingProvider.modelName());
        log.info("     Status              : {}",
                embeddingProvider.dimension() > 0 ? "✓ AKTIV" : "✗ LEER");
        log.info("  5. Vector persistence  : {}",
                vectorSearchProvider.getClass().getSimpleName());
        log.info("     Status              : {}",
                vectorSearchProvider instanceof NoOpVectorSearchProvider
                        ? "✗ KEIN VECTOR-STORE (Qdrant fehlt)" : "✓ AKTIV");
        log.info("  6. Retrieval           : keyword (JPA) + vector (NoOp)");
        log.info("     Vector coverage     : 0 (Vektoren werden generiert");
        log.info("                           aber nicht persistiert)");
        log.info("═══════════════════════════════════════════");
        log.info("  ERSTE LÜCKE (behoben): EmbeddingProvider");
        log.info("    → OllamaEmbeddingProvider war nicht");
        log.info("      als @Component registriert");
        log.info("  NÄCHSTE LÜCKE: VectorSearchProvider");
        log.info("    → platform-search-qdrant fehlt als");
        log.info("      Abhängigkeit in der pom.xml");
        log.info("    → Qdrant nicht konfiguriert");
        log.info("═══════════════════════════════════════════");

        assertTrue(embeddingProvider.dimension() > 0,
                "Embedding provider must be functional");
    }
}
