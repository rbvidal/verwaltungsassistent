package reasoning.ai.application;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.Domain;
import reasoning.ai.model.ModelCapabilities;
import reasoning.ai.model.StructuredIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based semantic intent parser (experimental).
 *
 * <p>Uses the existing verifier model (default: qwen2.5:7b) to extract
 * structured parameters from natural-language questions. Language-neutral
 * — works with German, English, Portuguese, etc.
 *
 * <p>Enabled via {@code platform.ai.ollama.semantic-intent.enabled=true}.
 * When disabled, {@link RegexSemanticIntentParser} is used instead.
 */
public class LlmSemanticIntentParser implements SemanticIntentParser {

    private static final Logger log = LoggerFactory.getLogger(LlmSemanticIntentParser.class);
    private static final Pattern HOURS_JSON = Pattern.compile("\"hours\"\\s*:\\s*([0-9.]+)");
    private static final Pattern DISTANCE_JSON = Pattern.compile("\"distanceKm\"\\s*:\\s*([0-9.]+)");
    private static final Pattern AMOUNT_JSON = Pattern.compile("\"amountEur\"\\s*:\\s*([0-9.]+)");
    private static final Pattern GRADE_JSON = Pattern.compile("\"salaryGrade\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STEP_JSON = Pattern.compile("\"salaryStep\"\\s*:\\s*([0-9]+)");
    private static final Pattern INTENT_JSON = Pattern.compile("\"intentType\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern QUERY_TYPE_JSON = Pattern.compile("\"queryType\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern OVERNIGHT_JSON = Pattern.compile("\"overnight\"\\s*:\\s*(true|false)");
    private static final Pattern DOMAIN_JSON = Pattern.compile("\"domain\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LANGUAGE_JSON = Pattern.compile("\"language\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern GRADE_TOKEN = Pattern.compile("(?i)eg\\s*(\\d+[a-z]?)");
    private static final Pattern BARE_GRADE = Pattern.compile("(?i)(\\d+[a-z]?)");

    private final ChatCompletionProvider llmProvider;
    private final AiProviderProperties properties;

    public LlmSemanticIntentParser(ChatCompletionProvider llmProvider, AiProviderProperties properties) {
        this.llmProvider = llmProvider;
        this.properties = properties;
    }

    @Override
    public StructuredIntent parse(String question) {
        ModelCapabilities caps = new ModelCapabilities(
                "verifier", properties.getOllama().getVerifierModel(), 2048, false, false, true);
        String prompt = buildPrompt(question);

        try {
            // Deterministic extraction: greedy decoding removes the sampling
            // variance that previously dropped parameters (e.g. salaryStep).
            String response = llmProvider.complete(prompt, caps, 0.0);
            return parseResponse(question, response);
        } catch (Exception e) {
            log.warn("LLM semantic intent extraction failed: {}", e.getMessage());
            // Fall back to empty intent — caller should use regex fallback
            return StructuredIntent.empty(question);
        }
    }

    private String buildPrompt(String question) {
        String domainList = String.join(", ", Domain.allClassifiable().stream()
                .map(Domain::name).sorted().toList());
        if (domainList.isBlank()) domainList = "GENERAL";
        return """
            Extract structured parameters from this question. Return ONLY a JSON object.

            Do NOT answer the question. Do NOT provide values — only extract what is in the question.

            QUESTION: \"""" + question + "\"\n\n" +
            """
            Extract these fields if present (omit if absent):
            - language: ISO 639-1 code of the question's language — exactly one of "de",
              "en", "pt", "fr". Judge by the wording of the question itself (e.g. "What is"
              -> en, "Was ist" -> de, "Quel est" -> fr, "Qual é" -> pt). Proper nouns like
              cities or institutions do not change the language. Never return null.
            - domain: the domain/category of the question — MUST be one of: """ + domainList + """
              (use GENERAL if none matches)
            - DOMAIN SCOPES: TRAVEL covers business travel expense reimbursement only
              (Dienstreise, Tagegeld, Kilometergeld, Übernachtung bei Dienstreisen). Travel
              documents such as a passport (Reisepass) or Ausweisdokumente are NOT TRAVEL.
              BUILDING covers construction permits and building law only — a construction
              company or builder (Bauunternehmen) is not BUILDING. HR covers employee
              salary/pay-scale topics (Besoldung, TV-L) — citizen services are not HR.
              PROCUREMENT covers public procurement (Vergabe) only. If no scope fits, use GENERAL.
            - intentType: "TRAVEL_ALLOWANCE" | "SALARY_LOOKUP" | "PROCUREMENT_THRESHOLD" | "GENERAL"
            - hours: numeric duration in hours
            - distanceKm: numeric distance in kilometers
            - amountEur: numeric monetary amount in EUR
            - salaryGrade: the grade token only, e.g. "EG 9b"
            - salaryStep: salary step number
            - queryType: "INCREASE" if asking about change/increase, otherwise omit
            - overnight: true if overnight stay is mentioned, otherwise omit

            SEMANTIC RULES:
            - The presence of a number alone is NOT evidence of intent. Only set
              intentType to TRAVEL_ALLOWANCE, SALARY_LOOKUP or PROCUREMENT_THRESHOLD
              when the meaning of the question actually expresses that intent. If the
              question is ambiguous or does not clearly express one of these intents,
              use intentType "GENERAL".
            - amountEur requires a currency reference (EUR, €, euros) or clear monetary
              context in the question.
            - hours requires a duration context (hours, Stunden, horas, heures, ...).
            - Daily travel allowances ("Tagegeld", "per diem", "diária", "indemnité de
              repas", "indemnité de déplacement") are TRAVEL_ALLOWANCE, not salary.
            - Language follows the question's own wording (articles, verbs, prepositions),
              not numbers, currency symbols, proper nouns or domain terms like "EG 9b".

            NUMBER FORMAT: interpret numbers according to the language of the question
            and the context. Distinguish thousands separators from decimal separators —
            the same characters have different meanings in different languages
            (e.g. "8.000,50" in German, "8,000.50" in English and "8 000,50" in French
            all mean 8000.50). Always output plain numbers without separators (e.g. 8000.5).

            Respond with exactly:
            {"language": "...", "domain": "...", "intentType": "...", "hours": N, ...}
            """;
    }

    private StructuredIntent parseResponse(String question, String response) {
        Map<String, Object> params = new LinkedHashMap<>();
        String intent = StructuredIntent.INTENT_GENERAL;
        Domain domain = null;

        Matcher domainM = DOMAIN_JSON.matcher(response);
        if (domainM.find()) {
            String domainName = domainM.group(1).trim().toUpperCase();
            // Only accept domains that are actually registered by the application
            for (Domain registered : Domain.allClassifiable()) {
                if (registered.name().equalsIgnoreCase(domainName)) {
                    domain = registered;
                    break;
                }
            }
            if (domain == null && !"GENERAL".equals(domainName)) {
                log.warn("LLM returned unregistered domain '{}' — ignored", domainName);
            }
        }

        Matcher intentM = INTENT_JSON.matcher(response);
        if (intentM.find()) intent = intentM.group(1);

        Matcher h = HOURS_JSON.matcher(response);
        if (h.find()) {
            try { params.put("hours", Double.parseDouble(h.group(1))); }
            catch (NumberFormatException ignored) {}
        }

        Matcher d = DISTANCE_JSON.matcher(response);
        if (d.find()) {
            try { params.put("distanceKm", Double.parseDouble(d.group(1))); }
            catch (NumberFormatException ignored) {}
        }

        Matcher a = AMOUNT_JSON.matcher(response);
        if (a.find()) {
            try { params.put("amountEur", Double.parseDouble(a.group(1))); }
            catch (NumberFormatException ignored) {}
        }

        Matcher g = GRADE_JSON.matcher(response);
        if (g.find()) params.put("salaryGrade", normalizeGrade(g.group(1)));

        Matcher s = STEP_JSON.matcher(response);
        if (s.find()) {
            try { params.put("salaryStep", Integer.parseInt(s.group(1))); }
            catch (NumberFormatException ignored) {}
        }

        Matcher qt = QUERY_TYPE_JSON.matcher(response);
        if (qt.find()) params.put("queryType", qt.group(1));

        Matcher ov = OVERNIGHT_JSON.matcher(response);
        if (ov.find()) params.put("overnight", Boolean.parseBoolean(ov.group(1)));

        String language = null;
        Matcher lm = LANGUAGE_JSON.matcher(response);
        if (lm.find()) language = normalizeLanguage(lm.group(1));

        log.info("LLM semantic intent: language={} domain={} intent={} params={}",
                language, domain, intent, params);
        return new StructuredIntent(question, domain, intent, params, language);
    }

    /** Reduces the LLM's grade field to the registered grade-token schema ("EG 9b"). */
    private static String normalizeGrade(String raw) {
        String trimmed = raw != null ? raw.trim() : "";
        Matcher withEg = GRADE_TOKEN.matcher(trimmed);
        if (withEg.find()) return "EG " + withEg.group(1);
        Matcher bare = BARE_GRADE.matcher(trimmed);
        if (bare.matches()) return "EG " + bare.group(1);
        return trimmed;
    }

    /**
     * Normalizes the LLM-returned language value to an ISO 639-1 code from the
     * supported answer-language set (de/en/pt/fr), or {@code null} when
     * unrecognized. Tolerates region codes ("en-US") and common display names
     * ("English") the model occasionally returns instead of the bare code.
     */
    static String normalizeLanguage(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.length() > 1 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
            if (v.isEmpty()) return null;
        }
        v = v.toLowerCase();
        int dash = v.indexOf('-');
        if (dash > 0) v = v.substring(0, dash).trim();
        return switch (v) {
            case "en", "english" -> "en";
            case "pt", "portuguese", "português" -> "pt";
            case "fr", "french", "français" -> "fr";
            case "de", "german", "deutsch" -> "de";
            default -> null;
        };
    }
}
