package reasoning.search.application;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditRequestContext;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.ChunkRepository;
import reasoning.search.api.GraphSearchProvider;
import reasoning.search.api.HybridRetrievalService;
import reasoning.search.api.IndexChunkCommand;
import reasoning.search.api.QueryIntentClassifier;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.QueryIntent;
import reasoning.search.model.ChunkMetadata;
import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkType;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchFilter;
import reasoning.search.model.SearchMode;
import reasoning.search.model.SearchQuery;
import reasoning.search.model.SearchRequestContext;
import reasoning.search.model.SearchResult;
import reasoning.search.model.SearchResultPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Implementation of both {@link SearchFacade} and {@link ChunkManagementService} with hybrid retrieval and audit. */
@Service
public class SearchService implements SearchFacade, ChunkManagementService {

    private static final int MAX_PAGE_SIZE = 100;

    private final HybridRetrievalService retrievalService;
    private final ChunkRepository chunks;
    private final SearchAuditPublisher auditPublisher;
    private final QueryIntentClassifier intentClassifier;
    private final GraphSearchProvider graphSearchProvider;

    public SearchService(HybridRetrievalService retrievalService, ChunkRepository chunks,
                         SearchAuditPublisher auditPublisher, QueryIntentClassifier intentClassifier,
                         GraphSearchProvider graphSearchProvider) {
        this.retrievalService = retrievalService;
        this.chunks = chunks;
        this.auditPublisher = auditPublisher;
        this.intentClassifier = intentClassifier;
        this.graphSearchProvider = graphSearchProvider;
    }

    @Override
    @Transactional
    public SearchResultPage search(SearchQuery query) {
        SearchQuery normalized = normalize(query);

        List<RetrievalCandidate> candidates = retrievalService.retrieve(normalized);

        QueryIntent intent = intentClassifier.classify(normalized.query());

        int from = Math.min(normalized.page() * normalized.size(), candidates.size());
        int to = Math.min(from + normalized.size(), candidates.size());
        List<SearchResult> results = candidates.subList(from, to).stream()
                .map(candidate -> new SearchResult(
                        candidate.chunk(),
                        candidate.text(),
                        candidate.rankingScore(),
                        candidate.confidenceScore(),
                        candidate.provider(),
                        candidate.citation(),
                        candidate.keywordScore(),
                        candidate.vectorScore(),
                        candidate.provider().contains("reranked") ? candidate.rankingScore() : 0.0,
                        intent.intent(),
                        normalized.mode().name()))
                .toList();
        int totalPages = candidates.isEmpty() ? 0 : (int) Math.ceil((double) candidates.size() / normalized.size());
        auditPublisher.emit(
                normalized.context().actorId(),
                normalized.context().tenantId(),
                AuditEventType.SEARCH_EXECUTED,
                normalized.context().requestId(),
                Map.of(
                        "mode", normalized.mode().name(),
                        "query", normalized.query().length() > 200
                                ? normalized.query().substring(0, 200) + "…" : normalized.query(),
                        "queryLength", Integer.toString(normalized.query().length()),
                        "resultCount", Integer.toString(results.size()),
                        "candidateCount", Integer.toString(candidates.size()),
                        "intent", intent.intent()));
        String effectiveStrategy = normalized.mode().name();
        if (normalized.mode() == SearchMode.HYBRID
                && graphSearchProvider != null && graphSearchProvider.isAvailable()) {
            effectiveStrategy = "HYBRID_GRAPH";
        }
        return new SearchResultPage(results, normalized.page(), normalized.size(), candidates.size(), totalPages, effectiveStrategy);
    }

    @Override
    @Transactional
    public DocumentChunk indexChunk(IndexChunkCommand command) {
        requireText(command.text(), "Chunk text is required");
        requireText(command.title(), "Title is required");
        if (command.documentId() == null) {
            throw new IllegalArgumentException("Document id is required");
        }
        if (command.documentVersion() <= 0) {
            throw new IllegalArgumentException("Document version must be positive");
        }
        if (command.documentType() == null) {
            throw new IllegalArgumentException("Document type is required");
        }
        DocumentChunk chunk = new DocumentChunk(
                UUID.randomUUID(),
                command.documentId(),
                command.documentVersion(),
                command.chunkType() == null ? ChunkType.TEXT : command.chunkType(),
                command.text().trim(),
                new ChunkPosition(command.pageNumber(), command.sectionIndex(), Math.max(command.chunkIndex(), 0), command.startOffset(), command.endOffset()),
                new ChunkMetadata(
                        command.title().trim(),
                        command.documentType(),
                        trimToNull(command.category()),
                        normalizeTags(command.tags()),
                        trimToNull(command.source()),
                        trimToNull(command.tenantId()),
                        command.documentCreatedAt(),
                        command.attributes(),
                        trimToNull(command.embeddingReference()),
                        command.publishedAt(),
                        command.validFrom(),
                        command.validUntil(),
                        command.supersededAt()),
                null,
                null);
        return chunks.save(chunk);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentChunk getChunk(UUID chunkId) {
        if (chunkId == null) {
            throw new IllegalArgumentException("Chunk id is required");
        }
        return chunks.findById(chunkId).orElseThrow(() -> new IllegalArgumentException("Chunk not found"));
    }

    @Override
    @Transactional
    public int deleteByDocumentId(UUID documentId) {
        return chunks.deleteByDocumentId(documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentChunk> findChunks(SearchFilter filter, int page, int size) {
        return chunks.find(normalize(filter), Math.max(page, 0), normalizeSize(size));
    }

    private SearchQuery normalize(SearchQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Search query is required");
        }
        requireText(query.query(), "Search query text is required");
        AuditRequestContext auditContext = AuditRequestContext.current();
        SearchRequestContext context = query.context() == null
                ? new SearchRequestContext(null, null, auditContext.correlationId(), auditContext.requestId())
                : new SearchRequestContext(
                        query.context().actorId(),
                        query.context().tenantId(),
                        hasText(query.context().correlationId()) ? query.context().correlationId() : auditContext.correlationId(),
                        hasText(query.context().requestId()) ? query.context().requestId() : auditContext.requestId());
        return new SearchQuery(
                query.query().trim(),
                query.mode() == null ? SearchMode.HYBRID : query.mode(),
                normalize(query.filter()),
                context,
                Math.max(query.page(), 0),
                normalizeSize(query.size()));
    }

    private SearchFilter normalize(SearchFilter filter) {
        if (filter == null) {
            return new SearchFilter(Set.of(), null, null, null, null, null, null, null, List.of());
        }
        return new SearchFilter(
                filter.documentIds(),
                filter.documentType(),
                trimToNull(filter.category()),
                trimToNull(filter.tag()),
                trimToNull(filter.source()),
                trimToNull(filter.tenantId()),
                filter.createdFrom(),
                filter.createdTo(),
                filter.metadata());
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) {
            return Set.of();
        }
        return tags.stream()
                .filter(this::hasText)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
