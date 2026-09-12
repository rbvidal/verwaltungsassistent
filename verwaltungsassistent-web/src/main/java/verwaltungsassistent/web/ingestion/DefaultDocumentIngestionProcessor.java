package verwaltungsassistent.web.ingestion;

import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentIngestionProcessor;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentVersion;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.IndexChunkCommand;
import reasoning.search.api.IndexingOrchestrationService;
import reasoning.search.model.ChunkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class DefaultDocumentIngestionProcessor implements DocumentIngestionProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentIngestionProcessor.class);
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 150;

    private final DocumentFacade documents;
    private final ChunkManagementService chunks;
    private final TextExtractionService textExtractionService;
    private final ObjectProvider<IndexingOrchestrationService> indexingOrchestrator;
    private final ObjectProvider<EnrichmentHook> enrichmentHook;

    public DefaultDocumentIngestionProcessor(
            DocumentFacade documents,
            ChunkManagementService chunks,
            TextExtractionService textExtractionService,
            ObjectProvider<IndexingOrchestrationService> indexingOrchestrator,
            ObjectProvider<EnrichmentHook> enrichmentHook) {
        this.documents = documents;
        this.chunks = chunks;
        this.textExtractionService = textExtractionService;
        this.indexingOrchestrator = indexingOrchestrator;
        this.enrichmentHook = enrichmentHook;
    }

    @Override
    public void ingest(UUID documentId) {
        // A document deleted while its ingestion job was still running must
        // not be indexed afterwards — otherwise chunks/vectors of a deleted
        // document would reappear as orphans.
        try {
            reasoning.document.model.Document doc =
                    documents.getDocument(documentId, "system");
            if (doc.status() == reasoning.common.model.DocumentStatus.DELETED) {
                log.info("Document {} is deleted — skipping indexing", documentId);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not verify status of document {} before indexing: {}", documentId, e.getMessage());
        }
        IndexingOrchestrationService orchestrator = indexingOrchestrator.getIfAvailable();
        if (orchestrator != null) {
            log.info("Using full indexing pipeline (embedding + Qdrant) for document {}", documentId);
            orchestrator.indexDocument(documentId);
            runEnrichment(documentId);
            return;
        }
        log.info("No IndexingOrchestrationService — keyword-only indexing for document {}", documentId);
        ingestKeywordOnly(documentId);
    }

    private void runEnrichment(UUID documentId) {
        EnrichmentHook hook = enrichmentHook.getIfAvailable();
        if (hook == null) return;
        try {
            Document document = documents.getDocument(documentId, "system");
            DocumentVersion version = document.versions().stream()
                    .filter(v -> v.versionNumber() == document.currentVersion())
                    .findFirst().orElse(null);
            if (version != null) {
                String text = textExtractionService.extractText(document.metadata().type(), version);
                hook.enrich(document.id().toString(), document.metadata().title(), text);
            }
        } catch (Exception e) {
            log.warn("Enrichment failed for document {}: {}", documentId, e.getMessage());
        }
    }

    private void ingestKeywordOnly(UUID documentId) {
        log.info("Starting keyword-only ingestion for document {}", documentId);
        try {
            Document document = documents.getDocument(documentId, "system");
            log.info("Document loaded: id={}, type={}, version={}", document.id(), document.metadata().type(), document.currentVersion());
            DocumentVersion version = document.versions().stream()
                    .filter(v -> v.versionNumber() == document.currentVersion())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Document has no current version"));
            log.info("Version found: provider={}, key={}", version.storageProvider(), version.storageKey());

            String extractedText = textExtractionService.extractText(document.metadata().type(), version);
            log.info("Extracted text length: {} chars", extractedText.length());
            if (extractedText.isBlank()) {
                log.warn("No extractable text for document {}", documentId);
                return;
            }

        EnrichmentHook hook = enrichmentHook.getIfAvailable();
        if (hook != null) {
            try {
                hook.enrich(document.id().toString(), document.metadata().title(), extractedText);
            } catch (Exception e) {
                log.warn("Enrichment failed for document {}: {}", documentId, e.getMessage());
            }
        }

        List<String> textChunks = chunkText(extractedText);
        log.info("Indexing {} chunks for document {} (keyword-only)", textChunks.size(), documentId);
        for (int i = 0; i < textChunks.size(); i++) {
            chunks.indexChunk(new IndexChunkCommand(
                    document.id(),
                    version.versionNumber(),
                    ChunkType.TEXT,
                    textChunks.get(i),
                    null, null, i, null, null,
                    document.metadata().title(),
                    document.metadata().type(),
                    document.metadata().category(),
                    document.metadata().tags(),
                    "upload",
                    document.tenantId(),
                    document.createdAt(),
                    List.of(),
                    null,
                    document.publishedAt(),
                    document.validFrom(),
                    document.validUntil(),
                    document.supersededAt()
            ));
        }
        log.info("Keyword-only ingestion complete for document {}: {} chunks indexed", documentId, textChunks.size());
        } catch (Exception e) {
            log.error("Ingestion failed for document {}: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("Ingestion failed", e);
        }
    }

    private List<String> chunkText(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < normalized.length()) {
            int end = Math.min(index + CHUNK_SIZE, normalized.length());
            String slice = normalized.substring(index, end).trim();
            if (!slice.isBlank()) chunks.add(slice);
            if (end == normalized.length()) break;
            index = Math.max(0, end - CHUNK_OVERLAP);
        }
        return chunks;
    }
}
