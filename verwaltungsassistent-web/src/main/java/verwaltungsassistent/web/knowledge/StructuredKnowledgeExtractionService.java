package verwaltungsassistent.web.knowledge;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ModelCapabilities;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentVersion;
import reasoning.search.api.ChunkingStrategy;
import reasoning.search.model.DocumentChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Extracts structured knowledge candidates from one document version via the
 * existing LLM infrastructure, validates them, and persists the outcomes.
 *
 * <p>Never called synchronously from ingestion — driven by the scheduled
 * {@link KnowledgeExtractionWorker}. Every item carries provenance back to
 * its document/version/chunk; nothing reaches ACTIVE without the validation
 * gate.
 */
@Service
public class StructuredKnowledgeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(StructuredKnowledgeExtractionService.class);

    private static final String EXTRACTED_BY = "llm-extraction";

    private final DocumentFacade documents;
    private final TextExtractionService textExtractionService;
    private final ChunkingStrategy chunkingStrategy;
    private final ChatCompletionProvider chatCompletionProvider;
    private final AiProviderProperties properties;
    private final StructuredKnowledgeStore store;
    private final KnowledgeItemValidator validator;
    private final ObjectMapper objectMapper;

    public StructuredKnowledgeExtractionService(
            DocumentFacade documents,
            TextExtractionService textExtractionService,
            ChunkingStrategy chunkingStrategy,
            ChatCompletionProvider chatCompletionProvider,
            AiProviderProperties properties,
            StructuredKnowledgeStore store,
            KnowledgeItemValidator validator,
            ObjectMapper objectMapper) {
        this.documents = documents;
        this.textExtractionService = textExtractionService;
        this.chunkingStrategy = chunkingStrategy;
        this.chatCompletionProvider = chatCompletionProvider;
        this.properties = properties;
        this.store = store;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /** Extracts and persists validated knowledge for the current version of a document. */
    public ExtractionRun extract(UUID documentId) {
        Document document = documents.getDocument(documentId, "system");
        DocumentVersion version = document.versions().stream()
                .filter(v -> v.versionNumber() == document.currentVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Document has no current version"));

        String text = textExtractionService.extractText(document.metadata().type(), version);
        List<DocumentChunk> chunks = chunkingStrategy.chunk(
                document.id(), version.versionNumber(), document.metadata().title(), text);

        List<StructuredKnowledgeItem> allItems = new ArrayList<>();
        Counters counters = new Counters();
        boolean documentValidityKnown = document.validFrom() != null || document.validUntil() != null;
        for (DocumentChunk chunk : chunks) {
            String response = callExtraction(document, chunk);
            List<ExtractedCandidate> parsed = parse(response);

            // Build items for this chunk, then validate them in ONE batched
            // claim-batch entailment call (validator.validateBatch). Malformed
            // candidates are rejected in the deterministic phase and never
            // enter the entailment batch.
            List<StructuredKnowledgeItem> chunkItems = new ArrayList<>();
            for (ExtractedCandidate c : parsed) {
                StructuredKnowledgeItem item = toItem(c, document, chunk);
                if (item == null) {
                    counters.rejected++;
                    continue;
                }
                chunkItems.add(item);
            }
            if (!chunkItems.isEmpty()) {
                List<KnowledgeItemValidator.Outcome> outcomes =
                        validator.validateBatch(chunkItems, documentValidityKnown);
                for (int i = 0; i < chunkItems.size(); i++) {
                    persist(chunkItems.get(i), outcomes.get(i), allItems, counters);
                }
            }
        }

        // A newer version replaces the knowledge of previous versions — old items
        // become SUPERSEDED (retained for audit), never silently deleted.
        if (version.versionNumber() > 1) {
            store.supersedeBySource(document.id(), version.versionNumber() - 1, EXTRACTED_BY);
        }

        log.info("Knowledge extraction for {}@v{}: {} items ({} ACTIVE, {} CANDIDATE, {} REJECTED) from {} chunks",
                document.id(), version.versionNumber(), allItems.size(), counters.active,
                counters.candidates, counters.rejected, chunks.size());
        return new ExtractionRun(allItems, counters.active, counters.candidates, counters.rejected);
    }

    private String callExtraction(Document document, DocumentChunk chunk) {
        String prompt = KnowledgeExtractionPrompt.build(
                document.metadata().title(), document.metadata().type() != null
                        ? document.metadata().type().name() : "UNKNOWN", chunk.text());
        ModelCapabilities capabilities = new ModelCapabilities(
                "extraction", properties.getOllama().getVerifierModel(), 4096, false, false, true);
        try {
            return chatCompletionProvider.complete(prompt, capabilities, 0.0);
        } catch (Exception e) {
            log.warn("Extraction LLM call failed for chunk {}: {}", chunk.id(), e.getMessage());
            return "{\"items\":[]}";
        }
    }

    /**
     * Strips a Markdown code-fence wrapper (```json … ``` or ``` … ```) around
     * the LLM's JSON output when present. Everything else is left untouched:
     * after this boundary normalization the response must still be strict JSON.
     */
    static String normalizeFences(String response) {
        if (response == null || response.isBlank()) {
            return response == null ? "" : response.trim();
        }
        String s = response.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return ""; // only a fence marker, nothing else
        }
        s = s.substring(firstNewline + 1);
        int closingFence = s.lastIndexOf("```");
        if (closingFence >= 0) {
            s = s.substring(0, closingFence);
        }
        return s.trim();
    }

    /** Strict parse: malformed or non-object responses yield no candidates (never partial fabrication). */
    List<ExtractedCandidate> parse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(normalizeFences(response));
            if (root == null || !root.has("items") || !root.get("items").isArray()) {
                log.warn("Extraction response lacks items array — treated as no candidates");
                return List.of();
            }
            List<ExtractedCandidate> result = new ArrayList<>();
            for (JsonNode node : root.get("items")) {
                if (!node.has("kind") || !node.has("domain") || !node.has("key") || !node.has("payload")) {
                    log.warn("Candidate missing required fields — skipped");
                    continue;
                }
                result.add(new ExtractedCandidate(
                        node.get("kind").asText(),
                        node.get("domain").asText(),
                        node.get("key").asText(),
                        node.get("payload"),
                        dateOrNull(node, "effectiveFrom"),
                        dateOrNull(node, "effectiveUntil"),
                        node.has("confidence") ? node.get("confidence").asDouble(0.5) : 0.5));
            }
            return result;
        } catch (Exception e) {
            log.warn("Malformed extraction output — no candidates accepted: {}", e.getMessage());
            return List.of();
        }
    }

    private static LocalDate dateOrNull(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull() || node.get(field).asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(node.get(field).asText());
        } catch (Exception e) {
            return null;
        }
    }

    private StructuredKnowledgeItem toItem(ExtractedCandidate c, Document document, DocumentChunk chunk) {
        StructuredKnowledgeKind kind;
        try {
            kind = StructuredKnowledgeKind.valueOf(c.kind().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // invalid kind → skipped by caller via null check
        }
        if (kind == null || c.payload() == null) {
            return null;
        }
        return new StructuredKnowledgeItem(
                UUID.randomUUID(),
                kind,
                c.domain(),
                c.key(),
                c.payload().toString(),
                StructuredKnowledgeStatus.CANDIDATE,
                c.effectiveFrom(),
                c.effectiveUntil(),
                document.id(),
                document.currentVersion(),
                chunk.position() != null ? chunk.position().pageNumber() : null,
                chunk.position() != null ? chunk.position().chunkIndex() : null,
                chunk.text(),
                c.confidence(),
                EXTRACTED_BY,
                null, null, null);
    }

    /** Outcome of one extraction run. */
    public record ExtractionRun(List<StructuredKnowledgeItem> items, int active, int candidates, int rejected) {
    }

    /** Raw LLM output for one candidate, before validation. */
    record ExtractedCandidate(String kind, String domain, String key, JsonNode payload,
                              LocalDate effectiveFrom, LocalDate effectiveUntil, double confidence) {
    }

    private static final class Counters {
        int rejected;
        int candidates;
        int active;
    }

    private void persist(StructuredKnowledgeItem item, KnowledgeItemValidator.Outcome outcome,
                         List<StructuredKnowledgeItem> allItems, Counters counters) {
        StructuredKnowledgeItem persisted = store.save(new StructuredKnowledgeItem(
                item.id(), item.kind(), item.domain(), item.key(), item.payloadJson(),
                outcome.status(), item.effectiveFrom(), item.effectiveUntil(),
                item.sourceDocumentId(), item.sourceDocumentVersion(), item.sourcePage(),
                item.sourceChunkIndex(), item.sourceExcerpt(), item.extractionConfidence(),
                EXTRACTED_BY, null, null, null));
        allItems.add(persisted);
        switch (outcome.status()) {
            case ACTIVE -> counters.active++;
            case CANDIDATE -> counters.candidates++;
            case REJECTED -> counters.rejected++;
            default -> { }
        }
    }
}
