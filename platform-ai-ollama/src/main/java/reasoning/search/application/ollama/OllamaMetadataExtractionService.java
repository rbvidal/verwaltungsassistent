package reasoning.search.application.ollama;

import reasoning.search.api.MetadataExtractionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Ollama-based {@link MetadataExtractionService} that prompts a chat model to extract structured metadata from documents. */
public class OllamaMetadataExtractionService implements MetadataExtractionService {

    private static final Logger log = LoggerFactory.getLogger(OllamaMetadataExtractionService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient;
    private final String chatModel;
    private final String baseUrl;

    public OllamaMetadataExtractionService(String baseUrl, String chatModel) {
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public MetadataSuggestion extractMetadata(String text, String fileName) {
        String prompt = buildPrompt(text, fileName);
        String jsonResponse = callOllama(prompt);
        return parseResponse(jsonResponse);
    }

    private String buildPrompt(String text, String fileName) {
        String excerpt = text.length() > 4000 ? text.substring(0, 4000) : text;
        return """
                Analyze this document excerpt and extract structured metadata.
                Respond ONLY with a JSON object, no other text.

                File name: %s

                Document excerpt:
                ---
                %s
                ---

                Return JSON with these fields. Use null for unknown values. Be precise:
                {
                  "suggestedTitle": "descriptive title derived from document content, not file name",
                  "documentType": "one of: PDF|DOCX|TXT|HTML",
                  "domain": "one of: FINANCE|HEALTHCARE|TECHNOLOGY|MANUFACTURING|EDUCATION|ENERGY|GOVERNMENT|REAL_ESTATE|RETAIL|TELECOMMUNICATIONS|OTHER",
                  "category": "one of: REPORT|CONTRACT|CORRESPONDENCE|TECHNICAL|POLICY|RESEARCH|PRESENTATION|MANUAL|OTHER",
                  "tags": ["specific_topic_tags_not_generic_terms"],
                  "date": "most relevant date in YYYY-MM-DD format",
                  "confidence": 0.0_to_1.0
                }

                Field rules:
                - tags: use specific terms (e.g. "quarterly_report"), not generic words
                - domain: the industry or sector the document relates to
                - category: the type of document based on its content and purpose
                - confidence: 0.3 for guesswork, 0.6 for reasonable inference, 0.9 for explicit mention
                """.formatted(fileName, excerpt);
    }

    private String callOllama(String prompt) {
        Map<String, Object> request = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "stream", false,
                "options", Map.of("temperature", 0.1)
        );
        OllamaChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaChatResponse.class);
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new IllegalStateException("Ollama returned empty response for metadata extraction");
        }
        return response.message().content();
    }

    @SuppressWarnings("unchecked")
    private MetadataSuggestion parseResponse(String json) {
        String cleaned = json.trim();
        // Strip markdown code fences if present
        if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf('\n');
            int end = cleaned.lastIndexOf("```");
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start + 1, end).trim();
            }
        }
        try {
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            return new MetadataSuggestion(
                    stringOrNull(map, "suggestedTitle"),
                    stringOrNull(map, "documentType"),
                    stringOrNull(map, "domain"),
                    stringOrNull(map, "category"),
                    stringListOrEmpty(map, "tags"),
                    stringOrNull(map, "date"),
                    doubleOrZero(map, "confidence")
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse metadata extraction response: {}", cleaned, e);
            return emptySuggestion();
        }
    }

    private String stringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null && !value.toString().isBlank() ? value.toString() : null;
    }

    private List<String> stringListOrEmpty(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private double doubleOrZero(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number num) {
            return Math.max(0.0, Math.min(1.0, num.doubleValue()));
        }
        return 0.0;
    }

    private MetadataSuggestion emptySuggestion() {
        return new MetadataSuggestion(null, null, null, null, List.of(), null, 0.0);
    }

    record OllamaChatResponse(OllamaMessage message) {
    }

    record OllamaMessage(String role, String content) {
    }
}
