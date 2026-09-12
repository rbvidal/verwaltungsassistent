package reasoning.search.application.qdrant;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Manages the Qdrant vector collection lifecycle, ensuring the configured collection exists on startup. */
@Component
public class QdrantCollectionManager {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionManager.class);

    private final RestClient restClient;
    private final QdrantProperties properties;

    public QdrantCollectionManager(QdrantProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** Ensures the configured collection exists (idempotent) once at startup. */
    @PostConstruct
    public void ensureCollectionExists() {
        try {
            Map<String, Object> collectionInfo = restClient.get()
                    .uri("/collections/{collection}", properties.collection())
                    .retrieve()
                    .body(Map.class);
            if (collectionInfo != null) {
                log.info("Qdrant collection '{}' already exists", properties.collection());
                return;
            }
        } catch (Exception e) {
            log.info("Qdrant collection '{}' not found, creating...", properties.collection());
        }
        try {
            createCollection();
        } catch (Exception e) {
            log.warn("Qdrant collection '{}' could not be initialized at {}. Vector search remains unavailable until Qdrant is reachable.",
                    properties.collection(), properties.baseUrl(), e);
        }
    }

    private void createCollection() {
        Map<String, Object> request = Map.of(
                "vectors", Map.of(
                        "size", properties.vectorDimension(),
                        "distance", "Cosine"
                )
        );
        restClient.put()
                .uri("/collections/{collection}", properties.collection())
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Qdrant collection '{}' created with dimension={}", properties.collection(), properties.vectorDimension());
    }
}
