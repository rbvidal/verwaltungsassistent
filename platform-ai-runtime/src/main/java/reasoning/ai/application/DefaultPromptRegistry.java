package reasoning.ai.application;

import reasoning.ai.api.PromptRegistry;
import reasoning.ai.model.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory prompt registry seeded with platform prompt templates across all supported categories.
 *
 * <p>Categories include RETRIEVAL, SUMMARIZATION, EXTRACTION, CLASSIFICATION, EVALUATION,
 * REASONING, SYSTEM, GRAPH, and SEARCH — covering the full range of AI operations
 * the platform performs.
 */
@Component
public class DefaultPromptRegistry implements PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptRegistry.class);

    private final Map<String, List<PromptTemplate>> prompts = new ConcurrentHashMap<>();

    public DefaultPromptRegistry() {
        registerDefaultPrompts();
    }

    @Override
    public Optional<PromptTemplate> get(String qualifiedId) {
        int slash = qualifiedId.lastIndexOf("/v");
        if (slash < 0) return Optional.empty();
        String promptId = qualifiedId.substring(0, slash);
        try {
            int version = Integer.parseInt(qualifiedId.substring(slash + 2));
            return getVersions(promptId).stream()
                    .filter(p -> p.getVersion() == version)
                    .findFirst();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PromptTemplate> getLatest(String promptId) {
        return getVersions(promptId).stream()
                .max(Comparator.comparingInt(PromptTemplate::getVersion));
    }

    @Override
    public List<String> listPromptIds() {
        return List.copyOf(prompts.keySet());
    }

    @Override
    public List<PromptTemplate> getVersions(String promptId) {
        return prompts.getOrDefault(promptId, List.of());
    }

    /** Returns all prompts in a given category. */
    public List<PromptTemplate> findByCategory(PromptTemplate.Category category) {
        return prompts.values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getCategory() == category)
                .collect(Collectors.toList());
    }

    @Override
    public void register(PromptTemplate template) {
        prompts.computeIfAbsent(template.getId(), k -> new ArrayList<>()).add(template);
        log.debug("Registered prompt: {}", template.getQualifiedId());
    }

    private void registerDefaultPrompts() {
        // ── RETRIEVAL category ──
        register(new PromptTemplate("rag-answer", 1, PromptTemplate.Category.RETRIEVAL,
                "RAG-grounded answer generation for document intelligence queries",
                """
                You are an AI assistant analyzing real documents.
                Base your answer ONLY on the retrieved context below.
                Cite specific sources using bracket notation [1], [2], etc.
                If the context does not contain enough information, say so explicitly.

                RETRIEVED CONTEXT:
                {{context}}

                QUESTION:
                {{question}}

                INSTRUCTIONS:
                - Cite sources for every factual claim
                - Distinguish between direct evidence and inference
                - Flag any temporal inconsistencies
                - If information is missing, state what additional documents would help
                """,
                List.of("context", "question"), "text", List.of("*"), 0.3,
                List.of(new PromptTemplate.Example(
                        Map.of("context", "Document A states: ...", "question", "What is the key finding?"),
                        "[1] The key finding is ...")),
                Map.of("type", "rag", "domain", "general")));

        // ── EXTRACTION category ──
        register(new PromptTemplate("entity-extraction", 1, PromptTemplate.Category.EXTRACTION,
                "Extract structured entities and concepts from document text",
                """
                Extract structured information from the following document text.
                Return a JSON object with:
                - "entities": objects with "name", "type" (ORGANIZATION, PERSON, TECHNOLOGY, REGULATION, PROJECT)
                - "concepts": objects with "label", "domain", "confidence"
                - "relationships": objects with "sourceId", "targetId", "type", "evidence"

                TEXT:
                {{text}}
                """,
                List.of("text"), "json", List.of("*"), 0.1,
                List.of(), Map.of("type", "extraction", "domain", "general")));

        register(new PromptTemplate("concept-extraction", 1, PromptTemplate.Category.EXTRACTION,
                "Extract domain concepts and classify them",
                """
                Analyze the following text and identify key concepts.
                For each concept provide: label, domain, confidence (0-1), and related entities.

                TEXT:
                {{text}}

                Return JSON array of concept objects.
                """,
                List.of("text"), "json", List.of("*"), 0.1,
                List.of(), Map.of("type", "extraction")));

        // ── SUMMARIZATION category ──
        register(new PromptTemplate("document-summary", 1, PromptTemplate.Category.SUMMARIZATION,
                "Summarize a document's key points and findings",
                """
                Summarize the following document. Include:
                - Main topic (1 sentence)
                - Key findings (bullet points)
                - Important dates and entities
                - Overall significance (1 sentence)

                DOCUMENT TEXT:
                {{text}}
                """,
                List.of("text"), "text", List.of("*"), 0.3,
                List.of(), Map.of("type", "summarization")));

        // ── EVALUATION category ──
        register(new PromptTemplate("rerank-evaluation", 1, PromptTemplate.Category.EVALUATION,
                "Score document excerpts for relevance to a query",
                """
                You are a relevance scoring engine.
                For each of the following document excerpts, score how relevant it is to the query.
                Score from 0 (completely irrelevant) to 10 (directly answers the query).

                QUERY:
                {{query}}

                EXCERPTS:
                {{excerpts}}

                Return one line per excerpt in format: index=score
                """,
                List.of("query", "excerpts"), "scored-list", List.of("*"), 0.1,
                List.of(), Map.of("type", "evaluation")));

        register(new PromptTemplate("faithfulness-check", 1, PromptTemplate.Category.EVALUATION,
                "Check if an answer is faithful to its source documents",
                """
                Compare the following AI-generated answer against its source documents.
                Identify any claims that ARE NOT supported by the sources.
                Return JSON: {"faithful": true/false, "unsupported_claims": [...]}

                SOURCES:
                {{sources}}

                ANSWER:
                {{answer}}
                """,
                List.of("sources", "answer"), "json", List.of("*"), 0.1,
                List.of(), Map.of("type", "evaluation")));

        // ── SYSTEM category ──
        register(new PromptTemplate("intent-classification", 1, PromptTemplate.Category.SYSTEM,
                "Classify a user query by intent for routing",
                """
                Classify the following query into exactly one of these intents:
                GENERAL, CONTRACT, FINANCE, COMPLIANCE, PROCEDURE, COMMUNICATION, INDEX_INSPECTION

                QUERY: {{query}}
                Return only the intent label.
                """,
                List.of("query"), "label", List.of("*"), 0.0,
                List.of(), Map.of("type", "system", "domain", "routing")));

        // ── GRAPH category ──
        register(new PromptTemplate("graph-relation-extraction", 1, PromptTemplate.Category.GRAPH,
                "Extract entity relationships for knowledge graph population",
                """
                From the following text, extract relationships between entities.
                Return JSON array of relationships: [{"source": "...", "target": "...", "type": "...", "evidence": "..."}]

                Relationship types: REFERENCES, PART_OF, RELATED_TO, DEPENDS_ON, IMPLEMENTS, USES, MENTIONS, BELONGS_TO

                TEXT:
                {{text}}
                """,
                List.of("text"), "json", List.of("*"), 0.1,
                List.of(), Map.of("type", "graph", "domain", "enrichment")));

        log.info("Prompt registry initialized with {} prompts across {} categories",
                prompts.values().stream().mapToInt(List::size).sum(),
                prompts.values().stream().flatMap(List::stream)
                        .map(PromptTemplate::getCategory).distinct().count());
    }
}
