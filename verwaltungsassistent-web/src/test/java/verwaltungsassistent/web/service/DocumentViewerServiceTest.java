package verwaltungsassistent.web.service;

import reasoning.ai.model.SourceCitation;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.api.IngestionJobPage;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.document.model.DocumentMetadata;
import reasoning.document.api.TextExtractionService;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.ChunkType;
import reasoning.common.model.IngestionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the shared document viewer / provenance enrichment service:
 * citation enrichment must reuse the existing chunk/document data (never
 * fabricate metadata) and degrade gracefully when a chunk is missing.
 */
class DocumentViewerServiceTest {

    private final UUID docId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID chunkId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DocumentViewerService service(Document doc, List<DocumentChunkEntity> chunks) {
        DocumentFacade facade = mock(DocumentFacade.class);
        JpaDocumentChunkRepository repo = mock(JpaDocumentChunkRepository.class);
        TextExtractionService textExtraction = mock(TextExtractionService.class);
        when(facade.getDocument(eq(docId), any())).thenReturn(doc);
        when(repo.findByDocumentIdOrderByChunkIndex(docId)).thenReturn(chunks);
        return new DocumentViewerService(facade, repo, textExtraction);
    }

    private Document doc(String title, String category) {
        return new Document(docId, "tenant",
                new DocumentMetadata(title, DocumentFileType.PDF, category, Set.of(), "INTERNAL"),
                DocumentStatus.READY, 2, "a@b.de", "a@b.de", Instant.now(), Instant.now(),
                List.of());
    }

    private DocumentChunkEntity chunk(String text, int index, Integer page) {
        return new DocumentChunkEntity(chunkId, docId, 2, ChunkType.TEXT, text, page, null,
                index, null, null, "Direktaufträge nach §55 LHO", DocumentFileType.PDF, "CONTRACT",
                Set.of(), "upload", null, Instant.now(), Set.of(), null,
                null, null, null, null);
    }

    private SourceCitation citation() {
        return new SourceCitation(docId, chunkId, 2, "AV zu §55 LHO Berlin", 4, 10, 80,
                "Direktauftrag bis 10.000 € zulässig.", 0.9,
                SourceCitation.SourceTier.PRIMARY, SourceCitation.SourceType.AUTHORITATIVE,
                List.of("Keyword"));
    }

    @Test
    void fromCitations_enrichesChunkAndDocumentProvenance() {
        DocumentViewerService service = service(doc("AV zu §55 LHO Berlin", "POLICY_DOCUMENT"),
                List.of(chunk("Volltext des Abschnitts.", 23, 4)));

        var views = service.fromCitations(List.of(citation()));

        assertEquals(1, views.size());
        var v = views.get(0);
        assertEquals(23, v.chunkIndex(), "chunk index from the chunk index");
        assertEquals("Direktaufträge nach §55 LHO", v.chunkTitle(), "chunk title from the chunk");
        assertEquals("PDF", v.documentTypeLabel(), "document type from chunk");
        assertEquals("Vertrag", v.categoryLabel(), "chunk category wins over document category");
        assertEquals("Volltext des Abschnitts.", v.chunkText(), "full chunk text");
        assertEquals("Primär", v.tierLabel(), "tier label in German");
        assertEquals("success", v.tierVariant());
        assertEquals("AV zu §55 LHO Berlin", v.title(), "citation title preserved");
        assertEquals(0.9, v.confidenceScore());
        assertEquals(List.of("Keyword"), v.retrievalSources());
    }

    @Test
    void fromCitations_missingChunk_keepsProvenanceFieldsNull() {
        DocumentViewerService service = service(doc("AV zu §55 LHO Berlin", "CONTRACT"),
                List.of());

        var views = service.fromCitations(List.of(citation()));

        assertEquals(1, views.size());
        var v = views.get(0);
        assertNull(v.chunkIndex(), "no fabricated chunk index");
        assertNull(v.chunkText(), "no fabricated chunk text");
        assertEquals("PDF", v.documentTypeLabel(), "type falls back to document metadata");
        assertEquals("Vertrag", v.categoryLabel(), "category falls back to document metadata");
    }

    @Test
    void fromCitations_nullOrEmpty_returnsEmpty() {
        DocumentViewerService service = service(doc("AV", "OTHER"), List.of());
        assertTrue(service.fromCitations(null).isEmpty());
        assertTrue(service.fromCitations(List.of()).isEmpty());
    }

    @Test
    void view_buildsPresentationViewWithLabelsAndChunks() {
        Document doc = doc("Reisepass beantragen", "MANUAL");
        DocumentChunkEntity c = chunk("Antragstext...", 0, 1);
        DocumentViewerService service = service(doc, List.of(c));

        var view = service.view(docId, "user@example.de");

        assertEquals("Reisepass beantragen", view.title());
        assertEquals("PDF", view.typeLabel());
        assertEquals("Handbuch", view.categoryLabel());
        assertEquals("Bereit", view.statusLabel());
        assertEquals("success", view.statusVariant());
        assertEquals(2, view.version());
        assertEquals(1, view.chunks().size());
        assertEquals(0, view.chunks().get(0).index());
        assertEquals(1, view.chunks().get(0).pageNumber());
        assertEquals("Antragstext...", view.chunks().get(0).text());
        assertEquals(docId, view.documentId());
    }

    @Test
    void view_withHighlightQuery_marksQueryTermSegments() {
        Document doc = doc("Reisepass beantragen", "MANUAL");
        DocumentChunkEntity c = chunk("Für die Ummeldung brauchen Sie Ihre Wohnungsgeberbestätigung.", 0, 1);
        DocumentViewerService service = service(doc, List.of(c));

        var view = service.view(docId, "user@example.de", "Ummeldung nach Umzug");

        var segments = view.chunks().get(0).segments();
        assertTrue(segments.stream().anyMatch(s -> s.highlighted() && s.text().equals("Ummeldung")),
                "query term must be highlighted: " + segments);
        assertTrue(segments.stream().anyMatch(s -> !s.highlighted() && s.text().contains("Wohnungsgeberbestätigung")),
                "non-matching text stays unhighlighted: " + segments);
        StringBuilder joined = new StringBuilder();
        segments.forEach(s -> joined.append(s.text()));
        assertEquals(c.getText(), joined.toString(), "segments must reconstruct the chunk text");
    }

    @Test
    void view_withoutHighlightQuery_keepsPlainChunkText() {
        DocumentViewerService service = service(doc("Dokument", "OTHER"),
                List.of(chunk("Volltext ohne Hervorhebung.", 0, 1)));

        var view = service.view(docId, "user@example.de");

        assertNull(view.chunks().get(0).segments(), "no segments when no highlight query");
        assertEquals("Volltext ohne Hervorhebung.", view.chunks().get(0).text());
    }

    @Test
    void view_missingTitle_usesNeutralFallback() {
        DocumentViewerService service = service(doc(null, "OTHER"), List.of());

        var view = service.view(docId, "user@example.de");

        assertEquals("Dokument ohne Titel", view.title(),
                "a missing title must never render as the raw UUID");
    }

    @Test
    void view_usesIngestionJobStatusWhenPresent() throws Exception {
        Document doc = doc("Dokument", "OTHER");
        DocumentFacade facade = mock(DocumentFacade.class);
        JpaDocumentChunkRepository repo = mock(JpaDocumentChunkRepository.class);
        TextExtractionService textExtraction = mock(TextExtractionService.class);
        when(facade.getDocument(eq(docId), any())).thenReturn(doc);
        when(repo.findByDocumentIdOrderByChunkIndex(docId)).thenReturn(List.of());
        when(facade.findIngestionJobs(any(IngestionJobFilter.class))).thenReturn(
                new IngestionJobPage(
                        List.of(new DocumentIngestionJob(UUID.randomUUID(), docId,
                                IngestionStatus.COMPLETED, "upload", "user@example.de", "tenant",
                                null, Instant.now(), Instant.now(), Instant.now(), 1)),
                        0, 1, 1, 1));
        DocumentViewerService service = new DocumentViewerService(facade, repo, textExtraction);

        var view = service.view(docId, "user@example.de");

        assertEquals("Abgeschlossen", view.ingestionStatus());
    }
}
