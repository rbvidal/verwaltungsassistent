package reasoning.ai.provider;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ModelCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Chat completion provider backed by Ollama's HTTP API. */
@Component
@ConditionalOnProperty(name = "platform.ai.ollama.base-url")
public class OllamaChatProvider implements ChatCompletionProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatProvider.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AiProviderProperties properties;

    public OllamaChatProvider(ObjectMapper objectMapper, AiProviderProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public boolean isAvailable() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getOllama().getBaseUrl()))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Ollama availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String complete(String prompt, ModelCapabilities capabilities) {
        return complete(prompt, capabilities, null);
    }

    @Override
    public String complete(String prompt, ModelCapabilities capabilities, Double temperature) {
        String model = capabilities.model();
        if (model == null || model.isBlank()) {
            model = properties.getOllama().getChatModel();
            log.info("No model in capabilities, falling back to configured default: {}", model);
        }
        if (model == null || model.isBlank()) {
            return "No model configured. Set OLLAMA_CHAT_MODEL or platform.ai.ollama.chat-model.";
        }

        String baseUrl = properties.getOllama().getBaseUrl();
        String uri = baseUrl + "/api/chat";
        String requestBody;

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("stream", false);
            // RunPod/Qwen deployment tuning (demo): reasoning models (qwen3.5)
            // answer far faster with the thinking phase disabled; keep_alive=-1
            // pins the loaded model in VRAM between requests.
            root.put("think", false);
            root.put("keep_alive", -1);
            if (temperature != null) {
                root.putObject("options").put("temperature", temperature);
            }
            ArrayNode messages = root.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", prompt);

            requestBody = objectMapper.writeValueAsString(root);

            log.info("Ollama request: POST {} | model={} | promptLength={} chars",
                    uri, model, prompt.length());

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(parseTimeout(properties.getOllama().getRequestTimeout()))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Ollama response: HTTP {} | bodyLength={}",
                    response.statusCode(),
                    response.body() != null ? response.body().length() : 0);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = response.body() != null ? response.body() : "";
                log.error("Ollama request failed: HTTP {} | uri={} | model={} | response={}",
                        response.statusCode(), uri, model,
                        responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);

                // Diagnose 404: model not found
                if (response.statusCode() == 404) {
                    String diagnosis = diagnoseModelNotFound(baseUrl, model);
                    return "Provider error (HTTP 404): Model '" + model + "' not found at " + uri
                            + ". " + diagnosis;
                }

                return "Provider error (HTTP " + response.statusCode() + ")"
                        + (responseBody.length() < 200 ? ": " + responseBody : ".");
            }

            JsonNode node = objectMapper.readTree(response.body());
            JsonNode content = node.path("message").path("content");
            if (content.isMissingNode() || !content.isTextual()) {
                log.error("Ollama unexpected response format: {}", response.body());
                return "Provider returned unexpected response format.";
            }
            return content.asText();
        } catch (Exception e) {
            log.error("Ollama request failed: uri={} | model={} | error={}", uri, model, e.getMessage(), e);
            return "Provider request failed: " + e.getMessage();
        }
    }

    /** Checks whether the model is actually known to Ollama and returns a diagnostic. */
    private String diagnoseModelNotFound(String baseUrl, String model) {
        try {
            var listRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());
            if (listResponse.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(listResponse.body());
                JsonNode models = node.path("models");
                if (models.isArray()) {
                    var names = new java.util.ArrayList<String>();
                    for (JsonNode m : models) {
                        String name = m.path("name").asText();
                        if (!name.isEmpty()) names.add(name);
                    }
                    if (names.isEmpty()) {
                        return "No models are installed in Ollama. Pull one with: ollama pull " + model;
                    }
                    return "Available models: " + String.join(", ", names)
                            + ". Pull the model with: ollama pull " + model;
                }
            }
        } catch (Exception ignored) {
            log.debug("Could not query /api/tags for diagnosis", ignored);
        }
        return "Is the model pulled? Try: ollama pull " + model;
    }

    private static Duration parseTimeout(String s) {
        if (s == null || s.isBlank()) return Duration.ofSeconds(120);
        s = s.strip().toLowerCase();
        try {
            if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(120);
        }
    }
}
