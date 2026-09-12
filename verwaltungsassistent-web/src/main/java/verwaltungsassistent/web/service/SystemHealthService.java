package verwaltungsassistent.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Kompakter Systemstatus für die Mitarbeiter-Oberfläche (Phase 2D.12) — KEIN
 * Monitoring-Framework. Der Status prüft die tatsächlich relevanten
 * Laufzeit-Abhängigkeiten über dieselben Erreichbarkeits-Mechanismen wie die
 * Infrastruktur-Seite der Administration:
 *
 * <ul>
 *   <li>PostgreSQL (Datenbank): reale Verbindung über den DataSource-Pool;</li>
 *   <li>Qdrant (Vektorspeicher): HTTP-Antwort auf die Collections-API;</li>
 *   <li>Ollama (KI-Modell): HTTP-Antwort auf /api/tags;</li>
 *   <li>Neo4j (Graph-Datenbank): HTTP-Antwort des Neo4j-HTTP-Endpunkts —
 *       optionale Komponente (Anreicherung), blockiert den Status nicht.</li>
 * </ul>
 *
 * <p>Die Mitarbeiterin sieht nur OK/Fehler (grün/rot) mit einem klaren
 * Hinweis, welche Komponente nicht erreichbar ist; technische Details
 * (Adressen) bleiben der Administration vorbehalten.</p>
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String qdrantBaseUrl;
    private final String neo4jUri;
    private final String ollamaBaseUrl;

    public SystemHealthService(JdbcTemplate jdbcTemplate,
                               @Value("${ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.ollamaBaseUrl = ollamaBaseUrl != null ? ollamaBaseUrl : "http://localhost:11434";
        this.qdrantBaseUrl = "http://"
                + System.getProperty("platform.search.qdrant.host", "localhost") + ":"
                + System.getProperty("platform.search.qdrant.rest-port", "6333");
        this.neo4jUri = System.getProperty("platform.neo4j.uri", "bolt://localhost:7687");
    }

    /** Eine geprüfte Laufzeit-Komponente (required = blockiert den Gesamtstatus). */
    public record Component(String key, String label, boolean ok, boolean required, String detail) {}

    /** Gesamtstatus: ok = alle required-Komponenten erreichbar. */
    public record SystemStatus(boolean ok, List<Component> components) {}

    public SystemStatus status() {
        List<Component> components = new ArrayList<>();
        components.add(new Component("database", "Datenbank",
                databaseReachable(), true, "PostgreSQL"));
        components.add(new Component("vector", "Vektorspeicher",
                httpOk(qdrantBaseUrl + "/collections"), true, qdrantBaseUrl + "/dashboard"));
        components.add(new Component("aiModel", "KI-Modell",
                httpOk(ollamaBaseUrl + "/api/tags"), true, ollamaBaseUrl));
        // Neo4j ist nur Anreicherung — ein Ausfall degradiert die Suche, macht
        // das System aber nicht funktionsunfähig (kein falscher Rot-Zustand).
        components.add(new Component("graph", "Graph-Datenbank",
                httpOk(neo4jHttpEndpoint()), false, neo4jHttpEndpoint()));
        boolean ok = components.stream()
                .filter(Component::required)
                .allMatch(Component::ok);
        return new SystemStatus(ok, components);
    }

    /** PostgreSQL: reale Datenbank-Verbindung über den bestehenden DataSource-Pool. */
    private boolean databaseReachable() {
        try {
            return jdbcTemplate.queryForObject("SELECT 1", Integer.class) != null;
        } catch (Exception e) {
            log.debug("Datenbank nicht erreichbar: {}", e.getMessage());
            return false;
        }
    }

    private boolean httpOk(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 500;
        } catch (Exception e) {
            log.debug("HTTP-Prüfung {} fehlgeschlagen: {}", url, e.getMessage());
            return false;
        }
    }

    private String neo4jHttpEndpoint() {
        return "http://" + neo4jUri.replaceFirst("^bolt://", "").replace(":7687", ":7474");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
}
