package reasoning.search.application.ollama;

import reasoning.search.api.RerankingProvider;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Ollama-based cross-encoder {@link RerankingProvider} using a chat model to score relevance of candidate excerpts. */
public class OllamaRerankingProvider implements RerankingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaRerankingProvider.class);

    private final RestClient restClient;
    private final String model;

    public OllamaRerankingProvider(String baseUrl, String model) {
        this.model = model;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public List<RetrievalCandidate> rerank(SearchQuery query, List<RetrievalCandidate> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }

        // ── Domain-aware pre-boost ──
        String domain = detectDomain(query.query());
        log.info("RERANK Domain detected: '{}' for query: {}", domain,
                query.query().length() > 60 ? query.query().substring(0, 60) + "..." : query.query());
        List<RetrievalCandidate> boosted = applyDomainBoost(candidates, domain);

        // Rerank the full candidate window the caller asked for (query.size()).
        // Keeps the previous 15-candidate floor so callers with smaller sizes
        // are not silently narrowed; the widened temporal-overscan window from
        // the retrieval path is what makes displaced valid candidates rerankable.
        int topN = Math.min(boosted.size(), Math.max(15, query.size()));
        List<RetrievalCandidate> toRerank = boosted.subList(0, topN);
        List<RetrievalCandidate> rest = boosted.subList(topN, boosted.size());

        try {
            String prompt = buildRerankPrompt(query.query(), toRerank);
            String response = callOllama(prompt);
            Map<Integer, Double> scores = parseScores(response, toRerank.size());

            List<RetrievalCandidate> rescored = new ArrayList<>();
            for (int i = 0; i < toRerank.size(); i++) {
                RetrievalCandidate c = toRerank.get(i);
                double rerankScore = scores.getOrDefault(i, c.rankingScore());
                double finalScore = 0.2 * c.rankingScore() + 0.8 * rerankScore;
                rescored.add(new RetrievalCandidate(
                        c.chunk(), c.text(), c.keywordScore(), c.vectorScore(),
                        finalScore,
                        c.confidenceScore(), "reranked", c.citation()));
            }
            rescored.sort(Comparator.comparingDouble(RetrievalCandidate::rankingScore).reversed());
            rescored.addAll(rest);
            return rescored;
        } catch (Exception e) {
            log.warn("Reranking failed, returning original order: {}", e.getMessage());
            return boosted;
        }
    }

    /** Detects the administrative domain from query terms for domain-aware boosting. */
    private String detectDomain(String query) {
        String lower = query.toLowerCase();

        // Procurement — strongest signal
        if (containsAny(lower, "beschaffung", "vergabe", "ausschreibung", "lieferung",
                "rahmenvertrag", "direktauftrag", "vergabeverfahren", "beschaffen",
                "einkauf", "lieferant", "angebot", "auftragswert", "schwellenwert",
                "vergabevermerk", "angebotsvergleich", "verhandlungsvergabe",
                "beschrankte ausschreibung", "offentliche ausschreibung",
                "vergaberecht", "gwb", "vgv", "uvgo", "vob", "berlavg")) {
            return "procurement";
        }

        // Building — strongest signal
        if (containsAny(lower, "bauantrag", "baugenehmigung", "garage", "carport",
                "abstandsflache", "abstandsflachen", "bebauungsplan", "baugenehmigungsverfahren",
                "bauordnungsrecht", "bauvorlageberechtigung", "baulast",
                "einfamilienhaus", "wohngebaude", "grenzbebauung", "geschossflachenzahl",
                "grundflachenzahl", "geschosswohnungsbau", "baunvo", "bauvorlv",
                "bauo", "baugb", "erschliessung", "nutzungsanderung",
                "bauvoranfrage", "vorbescheid", "teilungsgenehmigung")) {
            return "building";
        }

        // HR — strongest signal
        if (containsAny(lower, "urlaub", "tv-l", "tv-l", "tvö", "dienstreise",
                "arbeitszeit", "entgeltgruppe", "tarifvertrag", "personalrat",
                "kundigung", "befristung", "teilzeit", "elternzeit",
                "reisekosten", "trennungsgeld", "umzugskosten",
                "beurteilung", "beforderung", "stellenausschreibung",
                "homeoffice", "mobiles arbeiten", "dienstvereinbarung")) {
            return "hr";
        }

        return "general";
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    /** Boosts candidates whose category matches the detected domain. */
    private List<RetrievalCandidate> applyDomainBoost(List<RetrievalCandidate> candidates, String domain) {
        if ("general".equals(domain)) return candidates;

        List<RetrievalCandidate> boosted = new ArrayList<>();
        for (RetrievalCandidate c : candidates) {
            String category = extractCategory(c);
            double boost = computeDomainBoost(category, domain);
            double newScore = Math.min(1.0, c.rankingScore() * (1.0 + boost));
            boosted.add(new RetrievalCandidate(
                    c.chunk(), c.text(), c.keywordScore(), c.vectorScore(),
                    newScore, c.confidenceScore(), c.provider(), c.citation()));
        }
        boosted.sort(Comparator.comparingDouble(RetrievalCandidate::rankingScore).reversed());
        return boosted;
    }

    private String extractCategory(RetrievalCandidate c) {
        // citation title often contains the document title which reveals the category
        if (c.citation() != null && c.citation().title() != null) {
            String title = c.citation().title().toLowerCase();
            if (containsAny(title, "bau", "baugb", "baunvo", "bauvorlv", "abstands")) return "building";
            if (containsAny(title, "vergabe", "beschaffung", "gwb", "vgv", "uvgo", "berlavg", "vob")) return "procurement";
            if (containsAny(title, "tv-l", "urlaub", "reisekosten", "arbeitszeit", "entgelt", "brkg", "lrkg", "mobile"))
                return "hr";
        }
        // fall back to chunk metadata
        if (c.chunk() != null && c.chunk().documentType() != null) {
            return c.chunk().documentType().name().toLowerCase();
        }
        return "unknown";
    }

    private double computeDomainBoost(String category, String domain) {
        if (category.equals(domain)) return 0.35;   // Strong boost for matching domain
        if ("unknown".equals(category)) return 0.0;  // No boost for unknown
        return -0.50;                                 // Penalize non-matching (increased from -0.15)
    }

    private String buildRerankPrompt(String query, List<RetrievalCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rate how relevant each document excerpt is to this query.\n\n");
        sb.append("Query: ").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String excerpt = candidates.get(i).text();
            if (excerpt.length() > 600) {
                excerpt = excerpt.substring(0, 600);
            }
            sb.append("[").append(i).append("] ").append(excerpt).append("\n\n");
        }
        sb.append("Respond ONLY with scores line by line, format: index=score\n");
        sb.append("Score from 0 (completely irrelevant) to 10 (highly relevant).\n");
        sb.append("Example: 0=8 1=3 2=9");
        return sb.toString();
    }

    private String callOllama(String prompt) {
        Map<String, Object> request = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false,
                "options", Map.of("temperature", 0.0)
        );
        OllamaChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaChatResponse.class);
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new IllegalStateException("Ollama returned empty reranking response");
        }
        return response.message().content();
    }

    private Map<Integer, Double> parseScores(String response, int count) {
        Map<Integer, Double> scores = new java.util.HashMap<>();
        for (String line : response.split("[\\n;]")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            try {
                int index = Integer.parseInt(line.substring(0, eq).trim());
                double score = Double.parseDouble(line.substring(eq + 1).trim()) / 10.0;
                scores.put(index, Math.max(0.0, Math.min(1.0, score)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (scores.isEmpty()) {
            String cleaned = response.replaceAll("[^0-9,.\\s=]", "").trim();
            String[] parts = cleaned.split("[,\\s]+");
            for (int i = 0; i < Math.min(parts.length, count); i++) {
                try {
                    scores.put(i, Math.max(0.0, Math.min(1.0, Double.parseDouble(parts[i]) / 10.0)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return scores;
    }

    record OllamaChatResponse(OllamaMessage message) {}
    record OllamaMessage(String role, String content) {}
}
