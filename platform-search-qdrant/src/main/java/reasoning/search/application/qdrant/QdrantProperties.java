package reasoning.search.application.qdrant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for Qdrant vector search (host, port, collection name, vector dimension). */
@ConfigurationProperties(prefix = "platform.search.qdrant")
public record QdrantProperties(String host, int restPort, String collection, int vectorDimension) {
    public QdrantProperties {
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (restPort <= 0) {
            restPort = 6333;
        }
        if (collection == null || collection.isBlank()) {
            collection = "enterprise_ai_chunks";
        }
        if (vectorDimension <= 0) {
            vectorDimension = 768;
        }
    }

    public String baseUrl() {
        return "http://" + host + ":" + restPort;
    }
}
