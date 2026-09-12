package reasoning.search.application.qdrant;

import reasoning.search.api.EmbeddingProvider;
import reasoning.search.api.VectorSearchProvider;
import reasoning.common.model.DocumentFileType;
import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.CitationReference;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchFilter;
import reasoning.search.model.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Qdrant-based {@link VectorSearchProvider} that indexes chunks with embeddings and performs vector similarity search. */
@Component
@ConditionalOnProperty(name = "platform.search.qdrant.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantVectorSearchProvider implements VectorSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorSearchProvider.class);

    /**
     * Pre-merge candidate depth of the vector channel. The search fetches
     * several times the requested page/fenster size so that answer passages
     * inside large law documents (the Fristen-Passage deep in a statute whose
     * chunks all score in a narrow similarity band) survive to the merge —
     * the fusion, reranking and the evidence gates downstream decide on a
     * deeper pool, exactly like the keyword channel's generous fetch. Qdrant
     * searches over a few hundred points are cheap; the final windows stay in
     * the layers that consume the candidates.
     */
    private static final int VECTOR_CANDIDATE_DEPTH_MULTIPLIER = 6;
    private static final int VECTOR_CANDIDATE_MAX = 500;

    private final RestClient restClient;
    private final QdrantProperties properties;
    private final EmbeddingProvider embeddingProvider;

    public QdrantVectorSearchProvider(QdrantProperties properties, EmbeddingProvider embeddingProvider) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public List<RetrievalCandidate> search(SearchQuery query) {
        float[] queryVector = embeddingProvider.embed(query.query());
        int limit = candidateFetchLimit(query.size());

        Map<String, Object> request = buildSearchRequest(queryVector, limit, query.filter());

        QdrantSearchResponse response = restClient.post()
                .uri("/collections/{collection}/points/search", properties.collection())
                .body(request)
                .retrieve()
                .body(QdrantSearchResponse.class);

        if (response == null || response.result == null) {
            return List.of();
        }

        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (QdrantScoredPoint point : response.result) {
            if (point.payload == null) {
                continue;
            }
            float score = point.score;
            String excerpt = point.payload.text();
            if (excerpt != null && excerpt.length() > 240) {
                excerpt = excerpt.substring(0, 240);
            }
            candidates.add(new RetrievalCandidate(
                    new ChunkReference(
                            UUID.fromString(point.payload.chunkId()),
                            UUID.fromString(point.payload.documentId()),
                            point.payload.documentVersion(),
                            point.payload.title(),
                            new ChunkPosition(point.payload.pageNumber(), null, point.payload.chunkIndex(), null, null),
                            parseDocType(point.payload.documentType())
                    ),
                    point.payload.text(),
                    0.0,
                    score,
                    score,
                    score,
                    "qdrant",
                    new CitationReference(
                            UUID.fromString(point.payload.documentId()),
                            UUID.fromString(point.payload.chunkId()),
                            point.payload.documentVersion(),
                            point.payload.title(),
                            point.payload.pageNumber(),
                            null,
                            null,
                            excerpt,
                            point.payload.publishedAt(),
                            point.payload.validFrom(),
                            point.payload.validUntil(),
                            point.payload.supersededAt()
                    )
            ));
        }
        return candidates;
    }

    /** Candidate depth for a requested page size (see {@link #VECTOR_CANDIDATE_DEPTH_MULTIPLIER}). */
    static int candidateFetchLimit(int pageSize) {
        return Math.min(Math.max(pageSize, 10) * VECTOR_CANDIDATE_DEPTH_MULTIPLIER,
                VECTOR_CANDIDATE_MAX);
    }

    @Override
    public void index(DocumentChunk chunk, float[] embedding) {
        Map<String, Object> point = Map.of(
                "id", chunk.id().toString(),
                "vector", embedding,
                "payload", toPayload(chunk)
        );

        Map<String, Object> request = Map.of("points", List.of(point));

        restClient.put()
                .uri("/collections/{collection}/points", properties.collection())
                .body(request)
                .retrieve()
                .toBodilessEntity();

        log.debug("Indexed chunk {} into Qdrant", chunk.id());
    }

    @Override
    public void indexBatch(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("Chunks and embeddings must have same size");
        }
        if (chunks.isEmpty()) {
            return;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            points.add(Map.of(
                    "id", chunks.get(i).id().toString(),
                    "vector", embeddings.get(i),
                    "payload", toPayload(chunks.get(i))
            ));
        }
        restClient.put()
                .uri("/collections/{collection}/points", properties.collection())
                .body(Map.of("points", points))
                .retrieve()
                .toBodilessEntity();

        log.debug("Indexed {} chunks into Qdrant", chunks.size());
    }

    @Override
    public void deleteByDocument(UUID documentId) {
        Map<String, Object> filter = Map.of(
                "must", List.of(
                        Map.of("key", "documentId", "match", Map.of("value", documentId.toString()))
                )
        );
        restClient.post()
                .uri("/collections/{collection}/points/delete", properties.collection())
                .body(Map.of("filter", filter))
                .retrieve()
                .toBodilessEntity();

        log.debug("Deleted Qdrant points for document {}", documentId);
    }

    /**
     * Deletes ALL points of the collection (Qdrant removes every point when
     * no filter is given). Used by the administrative index purge so that
     * orphaned vectors from replaced uploads are removed as well.
     */
    @Override
    public void deleteAll() {
        // Qdrant's points/delete requires a PointsSelector variant; an empty
        // filter matches every point of the collection.
        restClient.post()
                .uri("/collections/{collection}/points/delete", properties.collection())
                .body(Map.of("filter", Map.of()))
                .retrieve()
                .toBodilessEntity();

        log.info("Deleted all Qdrant points of collection {}", properties.collection());
    }

    /**
     * Builds the Qdrant search request. When the caller restricted retrieval
     * to specific documents, the vector search is scoped via the documentId
     * payload filter BEFORE Qdrant applies its top-K limit — otherwise a case
     * document ranking below the global cutoff would never surface. Without
     * a document filter the request is identical to the global search.
     */
    static Map<String, Object> buildSearchRequest(float[] vector, int limit, SearchFilter filter) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("vector", vector);
        request.put("limit", limit);
        request.put("with_payload", true);
        if (filter != null && filter.documentIds() != null && !filter.documentIds().isEmpty()) {
            List<String> ids = filter.documentIds().stream().map(UUID::toString).toList();
            request.put("filter", Map.of("must", List.of(
                    Map.of("key", "documentId", "match", Map.of("any", ids)))));
        }
        return request;
    }

    private QdrantPayload toPayload(DocumentChunk chunk) {
        return new QdrantPayload(
                chunk.id().toString(),
                chunk.documentId().toString(),
                chunk.documentVersion(),
                chunk.text(),
                chunk.metadata().title(),
                chunk.metadata().documentType() != null ? chunk.metadata().documentType().name() : null,
                chunk.metadata().category(),
                chunk.metadata().tags() != null ? String.join(",", chunk.metadata().tags()) : null,
                chunk.metadata().source(),
                chunk.metadata().tenantId(),
                chunk.metadata().documentCreatedAt() != null ? chunk.metadata().documentCreatedAt().toString() : null,
                chunk.position().chunkIndex(),
                chunk.position().pageNumber(),
                chunk.metadata().publishedAt(),
                chunk.metadata().validFrom(),
                chunk.metadata().validUntil(),
                chunk.metadata().supersededAt()
        );
    }

    record QdrantPayload(
            String chunkId,
            String documentId,
            int documentVersion,
            String text,
            String title,
            String documentType,
            String category,
            String tags,
            String source,
            String tenantId,
            String documentCreatedAt,
            int chunkIndex,
            Integer pageNumber,
            java.time.LocalDate publishedAt,
            java.time.LocalDate validFrom,
            java.time.LocalDate validUntil,
            java.time.LocalDate supersededAt
    ) {
    }

    record QdrantSearchResponse(List<QdrantScoredPoint> result) {
    }

    record QdrantScoredPoint(String id, float score, QdrantPayload payload) {
    }

    private static DocumentFileType parseDocType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return DocumentFileType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
