package reasoning.search.application;

import reasoning.document.api.DocumentFacade;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentVersion;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.ChunkingStrategy;
import reasoning.search.api.EmbeddingProvider;
import reasoning.search.api.IndexChunkCommand;
import reasoning.search.api.IndexingOrchestrationService;
import reasoning.search.api.VectorSearchProvider;
import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkType;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.MetadataFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Orchestrates document indexing by extracting text, chunking, generating embeddings, and storing in the vector DB. */
@Component
@ConditionalOnBean(EmbeddingProvider.class)
public class DefaultIndexingOrchestrationService implements IndexingOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultIndexingOrchestrationService.class);

    private final DocumentFacade documents;
    private final TextExtractionService textExtractionService;
    private final ChunkingStrategy chunkingStrategy;
    private final ChunkManagementService chunkManagementService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorSearchProvider vectorSearchProvider;
    private final MeterRegistry meterRegistry;

    public DefaultIndexingOrchestrationService(
            DocumentFacade documents,
            TextExtractionService textExtractionService,
            ChunkingStrategy chunkingStrategy,
            ChunkManagementService chunkManagementService,
            EmbeddingProvider embeddingProvider,
            VectorSearchProvider vectorSearchProvider,
            MeterRegistry meterRegistry
    ) {
        this.documents = documents;
        this.textExtractionService = textExtractionService;
        this.chunkingStrategy = chunkingStrategy;
        this.chunkManagementService = chunkManagementService;
        this.embeddingProvider = embeddingProvider;
        this.vectorSearchProvider = vectorSearchProvider;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void indexDocument(UUID documentId) {
        Document document = documents.getDocument(documentId, "system");
        String docType = document.metadata().type() != null ? document.metadata().type().name() : "UNKNOWN";
        DocumentVersion version = document.versions().stream()
                .filter(v -> v.versionNumber() == document.currentVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Document has no current version"));

        // Phase 1: Text extraction
        log.debug("Starting text extraction for document {}", documentId);
        Timer.Sample extractionSample = Timer.start(meterRegistry);
        String extractedText = textExtractionService.extractText(document.metadata().type(), version);
        extractionSample.stop(Timer.builder("ingestion.extraction.duration")
                .description("Text extraction duration per document")
                .tag("document_type", docType)
                .register(meterRegistry));
        log.debug("Text extraction complete for document {} ({} chars)", documentId, extractedText.length());

        // Phase 2: Chunking
        log.debug("Starting chunking for document {}", documentId);
        Timer.Sample chunkSample = Timer.start(meterRegistry);
        List<DocumentChunk> rawChunks = chunkingStrategy.chunk(
                document.id(), version.versionNumber(), document.metadata().title(), extractedText);
        chunkSample.stop(Timer.builder("ingestion.chunking.duration")
                .description("Text chunking duration per document")
                .tag("document_type", docType)
                .register(meterRegistry));
        log.debug("Chunking complete for document {} ({} chunks)", documentId, rawChunks.size());

        if (rawChunks.isEmpty()) {
            log.warn("No chunks produced for document {}", documentId);
            return;
        }

        // Phase 3: Embedding generation
        log.debug("Starting embedding generation for document {} ({} chunks)", documentId, rawChunks.size());
        Timer.Sample embedSample = Timer.start(meterRegistry);
        List<String> chunkTexts = rawChunks.stream().map(DocumentChunk::text).toList();
        List<float[]> embeddings = embeddingProvider.embedBatch(chunkTexts);
        embedSample.stop(Timer.builder("ingestion.embedding.duration")
                .description("Embedding generation duration per document")
                .tag("document_type", docType)
                .register(meterRegistry));
        log.debug("Embedding generation complete for document {} ({} vectors)", documentId, embeddings.size());

        // Phase 4: Chunk persistence + vector indexing
        log.debug("Starting indexing for document {} ({} chunks)", documentId, rawChunks.size());
        Timer.Sample indexSample = Timer.start(meterRegistry);
        List<DocumentChunk> enrichedChunks = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i++) {
            DocumentChunk raw = rawChunks.get(i);
            DocumentChunk enriched = persistChunk(document, version, raw, i);
            enrichedChunks.add(enriched);
        }

        vectorSearchProvider.indexBatch(enrichedChunks, embeddings);
        indexSample.stop(Timer.builder("ingestion.indexing.duration")
                .description("Chunk persistence and vector indexing duration per document")
                .tag("document_type", docType)
                .register(meterRegistry));
        log.debug("Indexing complete for document {}", documentId);

        // Pipeline-level counters
        Counter.builder("ingestion.documents.total")
                .description("Total number of documents ingested")
                .tag("document_type", docType)
                .register(meterRegistry)
                .increment();
        Counter.builder("ingestion.chunks.total")
                .description("Total number of chunks created")
                .tag("document_type", docType)
                .register(meterRegistry)
                .increment(enrichedChunks.size());

        log.info("Indexed {} chunks for document {} ({}d vectors)", enrichedChunks.size(), documentId, embeddingProvider.dimension());
    }

    @Override
    public void reindexDocument(UUID documentId) {
        deleteDocumentChunks(documentId);
        indexDocument(documentId);
    }

    @Override
    public void deleteDocumentChunks(UUID documentId) {
        chunkManagementService.deleteByDocumentId(documentId);
        vectorSearchProvider.deleteByDocument(documentId);
    }

    private DocumentChunk persistChunk(Document document, DocumentVersion version, DocumentChunk raw, int chunkIndex) {
        ChunkPosition position = raw.position();
        String embedRef = embeddingProvider.modelName() + ":" + embeddingProvider.dimension() + "d";
        log.info("persistChunk: embeddingRef={} doc={} chunk={}", embedRef, document.metadata().title(), chunkIndex);

        // Extract § references from chunk text for citation metadata
        List<MetadataFilter> attributes = extractLegalAttributes(raw.text());

        return chunkManagementService.indexChunk(new IndexChunkCommand(
                document.id(),
                version.versionNumber(),
                raw.type() != null ? raw.type() : ChunkType.TEXT,
                raw.text(),
                position.pageNumber(),
                position.sectionIndex(),
                chunkIndex,
                position.startOffset(),
                position.endOffset(),
                document.metadata().title(),
                document.metadata().type(),
                document.metadata().category(),
                document.metadata().tags(),
                "upload",
                document.tenantId(),
                document.createdAt(),
                attributes,
                embedRef,
                document.publishedAt(),
                document.validFrom(),
                document.validUntil(),
                document.supersededAt()
        ));
    }

    private static final Pattern SECTION_REF = Pattern.compile("§\\s*(\\d+[a-z]?)");
    private static final Pattern CLAUSE_REF = Pattern.compile("\\((\\d+[a-z]?)\\)");
    private static final Pattern ARTICLE_REF = Pattern.compile("Art\\.\\s*(\\d+[a-z]?)");

    private List<MetadataFilter> extractLegalAttributes(String chunkText) {
        List<MetadataFilter> attrs = new ArrayList<>();
        Matcher sm = SECTION_REF.matcher(chunkText);
        if (sm.find()) {
            attrs.add(new MetadataFilter("section_ref", sm.group(1)));
        }
        Matcher cm = CLAUSE_REF.matcher(chunkText);
        if (cm.find()) {
            attrs.add(new MetadataFilter("clause_ref", cm.group(1)));
        }
        Matcher am = ARTICLE_REF.matcher(chunkText);
        if (am.find()) {
            attrs.add(new MetadataFilter("article_ref", am.group(1)));
        }
        return attrs;
    }
}
