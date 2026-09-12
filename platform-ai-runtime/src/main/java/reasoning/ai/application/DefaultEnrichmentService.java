package reasoning.ai.application;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.EnrichmentService;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.EnrichmentContext;
import reasoning.ai.model.ModelCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default semantic enrichment implementation.
 * Uses a combination of regex-based entity extraction and optional LLM-based enrichment.
 * When a ChatCompletionProvider is available, uses the LLM for higher-quality extraction.
 * Otherwise falls back to regex pattern matching.
 */
@Service
public class DefaultEnrichmentService implements EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEnrichmentService.class);

    // Common entity patterns
    private static final Pattern ORGANIZATION = Pattern.compile(
            "\\b([A-Z][a-z]*(?:\\s+(?:&|and|of|the|in|on|at|for|to)\\s+)?[A-Z][a-z]*){1,6}\\b\\s*(?:Inc\\.?|Corp\\.?|LLC|Ltd\\.?|GmbH|AG|SA|PLC|Group|Company|Organization|Agency|Commission|Authority|Institute|University|College)");
    private static final Pattern PERSON = Pattern.compile(
            "\\b(?:Mr\\.|Mrs\\.|Ms\\.|Dr\\.|Prof\\.)\\s+[A-Z][a-z]+\\s+[A-Z][a-z]+\\b");
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{2}/\\d{2}/\\d{4}\\b|\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}\\b");
    private static final Pattern MONEY = Pattern.compile(
            "\\b(?:\\$|EUR|USD|€|£)\\s*[\\d,]+(?:\\.\\d{2})?\\b|\\b[\\d,]+(?:\\.\\d{2})?\\s*(?:dollars|euros?|USD|EUR)\\b", Pattern.CASE_INSENSITIVE);

    private static final int MAX_TEXT_LENGTH = 8000;

    private final Optional<ChatCompletionProvider> llmProvider;
    private final AiProviderProperties aiProperties;

    public DefaultEnrichmentService(Optional<ChatCompletionProvider> llmProvider,
                                    AiProviderProperties aiProperties) {
        this.llmProvider = llmProvider;
        this.aiProperties = aiProperties;
    }

    @Override
    public EnrichmentContext enrich(String documentId, String text) {
        EnrichmentContext ctx = new EnrichmentContext(documentId);
        if (text == null || text.isBlank()) return ctx;

        String sample = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;

        // If LLM is available, use it for high-quality extraction
        if (llmProvider.isPresent() && llmProvider.get().isAvailable()) {
            try {
                enrichWithLLM(ctx, documentId, sample);
                // If LLM produced no entities and no concepts, fall back to regex
                if (ctx.getEntities().isEmpty() && ctx.getConcepts().isEmpty()) {
                    log.debug("LLM enrichment produced no results, falling back to regex");
                    enrichWithRegex(ctx, documentId, sample);
                }
            } catch (Exception e) {
                log.warn("LLM enrichment failed, falling back to regex: {}", e.getMessage());
                enrichWithRegex(ctx, documentId, sample);
            }
        } else {
            enrichWithRegex(ctx, documentId, sample);
        }

        log.debug("Enriched document {}: {} entities, {} concepts, {} relationships",
                documentId, ctx.getEntities().size(), ctx.getConcepts().size(), ctx.getRelationships().size());
        return ctx;
    }

    private void enrichWithLLM(EnrichmentContext ctx, String documentId, String text) {
        String prompt = buildEnrichmentPrompt(text);
        String response = llmProvider.get().complete(prompt, new ModelCapabilities("enrichment", aiProperties.getOllama().getChatModel(), 4096, false, false, true));
        parseLLMResponse(ctx, response);
    }

    private void enrichWithRegex(EnrichmentContext ctx, String documentId, String text) {
        // Extract organizations
        Matcher m = ORGANIZATION.matcher(text);
        Set<String> seenOrgs = new HashSet<>();
        while (m.find()) {
            String name = m.group().trim();
            if (seenOrgs.add(name.toLowerCase()) && name.length() > 3) {
                ctx.addEntity(new EnrichmentContext.ExtractedEntity(
                        name, "ORGANIZATION", 0.7, List.of(m.group()), extractContext(text, m.start())));
            }
        }

        // Extract persons
        m = PERSON.matcher(text);
        Set<String> seenPersons = new HashSet<>();
        while (m.find()) {
            String name = m.group().trim();
            if (seenPersons.add(name.toLowerCase())) {
                ctx.addEntity(new EnrichmentContext.ExtractedEntity(
                        name, "PERSON", 0.8, List.of(m.group()), extractContext(text, m.start())));
            }
        }

        // Extract dates as temporal concepts
        m = DATE.matcher(text);
        Set<String> seenDates = new HashSet<>();
        while (m.find() && seenDates.size() < 20) {
            String date = m.group().trim();
            if (seenDates.add(date)) {
                ctx.addConcept(new EnrichmentContext.ExtractedConcept(
                        "Date: " + date, "temporal", 0.9, List.of()));
            }
        }

        // Extract monetary values
        m = MONEY.matcher(text);
        int moneyCount = 0;
        while (m.find() && moneyCount < 10) {
            ctx.addConcept(new EnrichmentContext.ExtractedConcept(
                    "Amount: " + m.group().trim(), "financial", 0.85, List.of()));
            moneyCount++;
        }
    }

    private void parseLLMResponse(EnrichmentContext ctx, String response) {
        if (response == null || response.isBlank()) return;

        // Strip markdown code fences if present
        String json = response.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).strip();
            }
        }

        try {
            // Extract entities using find() instead of split() — the split approach
            // loses entity block boundaries when the response has preamble text.
            Pattern entityPattern = Pattern.compile(
                    "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]+)\"");
            Matcher entityMatcher = entityPattern.matcher(json);
            while (entityMatcher.find()) {
                String name = entityMatcher.group(1);
                String type = entityMatcher.group(2);
                if (name != null && !name.isBlank()) {
                    ctx.addEntity(new EnrichmentContext.ExtractedEntity(
                            name, type != null ? type : "unknown", 0.8, List.of(name), ""));
                }
            }

            // Extract concepts using find()
            Pattern conceptPattern = Pattern.compile(
                    "\"label\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"domain\"\\s*:\\s*\"([^\"]+)\"");
            Matcher conceptMatcher = conceptPattern.matcher(json);
            while (conceptMatcher.find()) {
                String label = conceptMatcher.group(1);
                String domain = conceptMatcher.group(2);
                if (label != null && !label.isBlank()) {
                    ctx.addConcept(new EnrichmentContext.ExtractedConcept(
                            label, domain != null ? domain : "general", 0.7, List.of()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse LLM enrichment response: {}", e.getMessage());
        }
    }

    private String buildEnrichmentPrompt(String text) {
        return """
            Extract structured information from the following document text.
            Return a JSON object with two arrays:
            - "entities": objects with "name", "type" (ORGANIZATION, PERSON, TECHNOLOGY, REGULATION, PROJECT), and "mentions"
            - "concepts": objects with "label", "domain", and "confidence"
            - "relationships": objects with "sourceId", "targetId", "type" (REFERENCES, PART_OF, RELATED_TO), and "evidence"

            Focus on:
            - Organizations, companies, agencies
            - People mentioned
            - Technologies, products, platforms
            - Regulations, standards, policies
            - Key concepts and topics
            - Relationships between entities

            TEXT:
            %s
            """.formatted(text);
    }

    private static String extractContext(String text, int position) {
        int start = Math.max(0, position - 40);
        int end = Math.min(text.length(), position + 80);
        return text.substring(start, end).replace('\n', ' ');
    }
}
