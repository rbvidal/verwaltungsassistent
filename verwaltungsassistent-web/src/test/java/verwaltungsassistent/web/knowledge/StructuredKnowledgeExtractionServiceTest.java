package verwaltungsassistent.web.knowledge;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.document.model.DocumentVersion;
import reasoning.search.api.ChunkingStrategy;
import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkType;
import reasoning.search.model.DocumentChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end extraction service behavior with a mocked LLM: valid JSON becomes
 * candidates with provenance; malformed output never produces knowledge;
 * rejected/candidate items cannot become ACTIVE.
 */
class StructuredKnowledgeExtractionServiceTest {

    private ChatCompletionProvider llm;
    private ClaimVerificationService claimVerifier;
    private StructuredKnowledgeStore store;
    private StructuredKnowledgeExtractionService service;
    private Document document;
    private DocumentFacade documents;
    private TextExtractionService textExtraction;
    private ChunkingStrategy chunking;

    @BeforeEach
    void setUp() {
        llm = mock(ChatCompletionProvider.class);
        claimVerifier = mock(ClaimVerificationService.class);
        store = mock(StructuredKnowledgeStore.class);
        documents = mock(DocumentFacade.class);
        textExtraction = mock(TextExtractionService.class);
        chunking = mock(ChunkingStrategy.class);
        AiProviderProperties properties = new AiProviderProperties();

        UUID docId = UUID.randomUUID();
        document = new Document(docId, null,
                new DocumentMetadata("AV zu Paragraph 55 LHO Berlin — Wertgrenzen", null, "procurement-regulations", null, null),
                reasoning.common.model.DocumentStatus.READY, 1, "system", null,
                Instant.now(), Instant.now(),
                java.time.LocalDate.of(2024, 1, 1), null, null, null,
                List.of(new DocumentVersion(UUID.randomUUID(), 1, "av.txt", "text/plain", 10,
                        "local-fs", "uploads/av.txt", "hash", "system", Instant.now())));

        DocumentChunk chunk = new DocumentChunk(UUID.randomUUID(), docId, 1, ChunkType.TEXT,
                "Direktauftrag bis 10.000 Euro fuer Lieferungen und Dienstleistungen. "
                        + "Beschraenkte Ausschreibung bis 100.000 Euro. Vergabevermerk erforderlich.",
                new ChunkPosition(2, null, 0, 0, 120), null, null, null);

        when(documents.getDocument(docId, "system")).thenReturn(document);
        when(textExtraction.extractText(document.metadata().type(),
                document.versions().getFirst())).thenReturn("text");
        when(chunking.chunk(docId, 1, document.metadata().title(), "text")).thenReturn(List.of(chunk));
        when(store.save(org.mockito.ArgumentMatchers.any(StructuredKnowledgeItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new StructuredKnowledgeExtractionService(
                documents, textExtraction, chunking, llm, properties, store, new KnowledgeItemValidator(
                        new ObjectMapper(), claimVerifier), new ObjectMapper());
    }

    private void llmReturns(String json) {
        when(llm.complete(anyString(),
                org.mockito.ArgumentMatchers.any(reasoning.ai.model.ModelCapabilities.class),
                org.mockito.ArgumentMatchers.eq(0.0))).thenReturn(json);
    }

    private void entail(ClaimVerification.Verdict verdict) {
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenReturn(List.of(List.of(new ClaimVerification("c", "e", verdict, 0.9, "r"))));
    }

    @Test
    void validExtractionCreatesActiveItemWithProvenance() {
        entail(ClaimVerification.Verdict.ENTAILED);
        llmReturns("{\"items\":[{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\","
                + "\"requirements\":[\"Vergabevermerk\"]}]},\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.95}]}");

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());

        assertEquals(1, run.active());
        assertEquals(0, run.candidates());
        assertEquals(0, run.rejected());
        StructuredKnowledgeItem item = run.items().getFirst();
        assertEquals(StructuredKnowledgeStatus.ACTIVE, item.status());
        assertEquals(document.id(), item.sourceDocumentId());
        assertEquals(1, item.sourceDocumentVersion());
        assertEquals(2, item.sourcePage());
        assertEquals(0, item.sourceChunkIndex());
        assertTrue(item.sourceExcerpt().contains("10.000 Euro"));
    }

    @Test
    void malformedOutputProducesNoItems() {
        llmReturns("kein json {");
        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());
        assertEquals(0, run.items().size());
        assertEquals(0, run.active());
    }

    @Test
    void unsupportedStatementCannotBecomeActive() {
        entail(ClaimVerification.Verdict.CONTRADICTED);
        llmReturns("{\"items\":[{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]},"
                + "\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.95}]}");
        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());
        assertEquals(0, run.active(), "failed entailment must never yield ACTIVE knowledge");
        assertTrue(run.items().stream().allMatch(i -> i.status() != StructuredKnowledgeStatus.ACTIVE));
    }

    @Test
    void missingEffectiveDateStaysCandidateEvenWhenEntailed() {
        entail(ClaimVerification.Verdict.ENTAILED);
        llmReturns("{\"items\":[{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]},"
                + "\"confidence\":0.9}]}");
        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());
        assertEquals(0, run.active(), "unknown validity must never silently become current");
        assertEquals(1, run.candidates());
    }

    @Test
    void documentValidityMetadataPropagatesIntoActivation() {
        // The document carries authoritative validity metadata (set via the
        // admin metadata API, not the LLM). An item without its own
        // effectiveFrom may then become ACTIVE — the Document → ingestion →
        // extraction → StructuredKnowledgeItem chain carries the metadata.
        UUID docId = UUID.randomUUID();
        Document validDoc = new Document(docId, null,
                new DocumentMetadata("AV zu Paragraph 55 LHO Berlin — Wertgrenzen", null,
                        "procurement-regulations", null, null),
                reasoning.common.model.DocumentStatus.READY, 1, "system", null,
                Instant.now(), Instant.now(),
                null, java.time.LocalDate.of(2024, 1, 1), null, null,
                List.of(new DocumentVersion(UUID.randomUUID(), 1, "av.txt", "text/plain", 10,
                        "local-fs", "uploads/av.txt", "hash", "system", Instant.now())));
        DocumentChunk chunk = new DocumentChunk(UUID.randomUUID(), docId, 1, ChunkType.TEXT,
                "Direktauftrag bis 10.000 Euro fuer Lieferungen und Dienstleistungen.",
                new ChunkPosition(2, null, 0, 0, 60), null, null, null);
        when(documents.getDocument(docId, "system")).thenReturn(validDoc);
        when(textExtraction.extractText(validDoc.metadata().type(),
                validDoc.versions().getFirst())).thenReturn("text");
        when(chunking.chunk(docId, 1, validDoc.metadata().title(), "text")).thenReturn(List.of(chunk));

        entail(ClaimVerification.Verdict.ENTAILED);
        llmReturns("{\"items\":[{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\","
                + "\"requirements\":[\"Vergabevermerk\"]}]},\"confidence\":0.95}]}");

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(docId);

        assertEquals(1, run.active(),
                "document-level validity metadata backs items without their own effectiveFrom");
        assertEquals(StructuredKnowledgeStatus.ACTIVE, run.items().getFirst().status());
    }

    // ── Batched entailment (one claim-batch call per chunk) ──

    private void llmReturnsTwoThresholds() {
        llmReturns("{\"items\":["
                + "{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\","
                + "\"requirements\":[\"Vergabevermerk\"]}]},\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.9},"
                + "{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":10000,\"max\":null,\"outcome\":\"Beschränkte Ausschreibung\","
                + "\"requirements\":[]}]},\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.9}"
                + "]}");
    }

    @Test
    void multipleCandidatesFromOneChunkUseOneBatchedEntailmentCall() {
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenAnswer(inv -> {
                    List<?> claims = inv.getArgument(0);
                    return claims.stream()
                            .map(c -> List.of(new ClaimVerification((String) c, "e",
                                    ClaimVerification.Verdict.ENTAILED, 0.9, "r")))
                            .toList();
                });
        llmReturnsTwoThresholds();

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> claimsCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(claimVerifier, times(1)).verifyAllClaims(claimsCaptor.capture(), anyList());
        assertEquals(2, claimsCaptor.getValue().size(),
                "both candidates of the chunk must be verified in ONE batched call");
        assertEquals(2, run.active());
    }

    @Test
    void oneFailedClaimPreventsOnlyThatCandidate() {
        when(claimVerifier.verifyAllClaims(anyList(), anyList())).thenReturn(List.of(
                List.of(new ClaimVerification("a", "e", ClaimVerification.Verdict.ENTAILED, 0.9, "r")),
                List.of(new ClaimVerification("b", "e", ClaimVerification.Verdict.CONTRADICTED, 0.9, "r"))));
        llmReturnsTwoThresholds();

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());

        assertEquals(1, run.active(), "only the entailed candidate becomes ACTIVE");
        assertEquals(1, run.candidates(), "the contradicted candidate stays CANDIDATE");
        verify(claimVerifier, times(1)).verifyAllClaims(anyList(), anyList());
    }

    @Test
    void malformedCandidatesDoNotEnterTheEntailmentBatch() {
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenAnswer(inv -> {
                    List<?> claims = inv.getArgument(0);
                    return claims.stream()
                            .map(c -> List.of(new ClaimVerification((String) c, "e",
                                    ClaimVerification.Verdict.ENTAILED, 0.9, "r")))
                            .toList();
                });
        // One malformed RULE ({"text": ...}) plus one valid THRESHOLD.
        llmReturns("{\"items\":["
                + "{\"kind\":\"RULE\",\"domain\":\"HR\",\"key\":\"UrlVO Bln\","
                + "\"payload\":{\"text\":\"Nur erzaehlend.\"},\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.9},"
                + "{\"kind\":\"THRESHOLD\",\"domain\":\"PROCUREMENT\",\"key\":\"AV §55 LHO\","
                + "\"payload\":{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\","
                + "\"requirements\":[\"Vergabevermerk\"]}]},\"effectiveFrom\":\"2024-01-01\",\"confidence\":0.9}"
                + "]}");

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> claimsCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(claimVerifier, times(1)).verifyAllClaims(claimsCaptor.capture(), anyList());
        assertEquals(1, claimsCaptor.getValue().size(),
                "malformed candidate must be rejected before entailment");
        assertEquals(1, run.rejected());
        assertEquals(1, run.active());
    }

    @Test
    void verificationFailureIsFailSafe() {
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenThrow(new RuntimeException("verifier down"));
        llmReturnsTwoThresholds();

        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());

        assertEquals(0, run.active(), "verification failure must never produce ACTIVE knowledge");
        assertEquals(2, run.candidates());
    }

    @Test
    void extractionFailureNeverRejectsCandidateIntoExecution() {
        when(llm.complete(anyString(),
                org.mockito.ArgumentMatchers.any(reasoning.ai.model.ModelCapabilities.class),
                org.mockito.ArgumentMatchers.eq(0.0)))
                .thenThrow(new RuntimeException("ollama down"));
        StructuredKnowledgeExtractionService.ExtractionRun run = service.extract(document.id());
        assertEquals(0, run.items().size());
        verify(store, never()).save(org.mockito.ArgumentMatchers.any(StructuredKnowledgeItem.class));
    }
}
