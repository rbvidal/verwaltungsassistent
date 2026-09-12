package verwaltungsassistent.web.service;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ModelProvider;
import reasoning.ai.model.ModelCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Direkter LLM-Dialog für den Tab „Allgemeine Fragen" — bewusst getrennt von
 * der kommunalen Assistenten-Pipeline: kein Retrieval, kein Grounding, kein
 * Verifier, keine Belege. Es wird dieselbe konfigurierte Modell-Infrastruktur
 * (ChatCompletionProvider/ModelProvider) wie für den Assistenten verwendet;
 * die Antwort ist eine direkte Modellantwort mit minimaler Systemanweisung.
 */
@Service
public class GeneralChatService {

    private static final Logger log = LoggerFactory.getLogger(GeneralChatService.class);

    /** Maximal mitgesendete Verlaufseinträge (älteste zuerst). */
    public static final int MAX_HISTORY_TURNS = 10;

    private static final String SYSTEM_PROMPT = """
            Du bist ein allgemeiner Assistent für Mitarbeitende der öffentlichen Verwaltung.
            Beantworte allgemeine Fragen direkt, verständlich und sachlich.

            Regeln:
            - Antworte in der Sprache der Frage; Deutsch ist die natürliche Standardsprache.
            - Du hast KEINEN Zugriff auf die Dokumentensammlung, die Verwaltungsunterlagen
              oder die Wissensbasis dieser Anwendung.
            - Behaupte niemals, dass eine Antwort anhand von Anwendungsdokumenten geprüft,
              verifiziert oder mit Quellen belegt wurde.
            - Erfinde keine Quellenangaben.
            - Wenn eine Frage nicht sinnvoll beantwortet werden kann, sage das ehrlich.
            """;

    private final ChatCompletionProvider chatCompletionProvider;
    private final ModelProvider modelProvider;

    public GeneralChatService(ChatCompletionProvider chatCompletionProvider,
                              ModelProvider modelProvider) {
        this.chatCompletionProvider = chatCompletionProvider;
        this.modelProvider = modelProvider;
    }

    /** One conversation turn (role + content) kept in the HTTP session. */
    public record ChatTurn(String role, String content) {}

    /** Signals that the LLM is unavailable or the request failed. */
    public static class ChatUnavailableException extends RuntimeException {
        public ChatUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Sends the conversation history (plus the pending user turn) directly to
     * the configured LLM and returns the model's answer. No retrieval, no
     * grounding, no verification.
     */
    public String answer(List<ChatTurn> history, String userQuestion) {
        if (chatCompletionProvider == null || !chatCompletionProvider.isAvailable()) {
            throw new ChatUnavailableException("Sprachmodell nicht verfügbar", null);
        }
        String prompt = buildPrompt(history, userQuestion);
        try {
            ModelCapabilities capabilities = modelProvider.capabilities(null);
            String response = chatCompletionProvider.complete(prompt, capabilities);
            if (response == null || response.isBlank()) {
                throw new ChatUnavailableException("Leere Antwort des Sprachmodells", null);
            }
            return response.trim();
        } catch (ChatUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("General chat request failed: {}", e.getMessage());
            throw new ChatUnavailableException("Antwort fehlgeschlagen", e);
        }
    }

    private static String buildPrompt(List<ChatTurn> history, String userQuestion) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n\n— Verlauf —\n");
        List<ChatTurn> context = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;
        for (ChatTurn turn : context) {
            sb.append(turn.role().equals("user") ? "Frage: " : "Antwort: ")
              .append(turn.content()).append("\n\n");
        }
        sb.append("Frage: ").append(userQuestion).append("\n\nAntwort:");
        return sb.toString();
    }
}
