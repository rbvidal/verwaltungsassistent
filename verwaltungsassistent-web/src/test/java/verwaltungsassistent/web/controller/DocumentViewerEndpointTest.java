package verwaltungsassistent.web.controller;

import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.IngestionStatus;
import reasoning.document.application.DocumentService;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.document.model.DocumentMetadata;
import reasoning.document.model.DocumentVersion;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.api.IngestionJobPage;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.ChunkType;
import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint tests for the reusable document viewer fragment
 * (GET /documents/{id}/view): metadata + chunk text rendered, UUID only as
 * metadata, 404 for unknown documents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DocumentViewerEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private reasoning.document.application.DocumentService documentService;

    @MockBean
    private JpaDocumentChunkRepository chunkRepository;

    @MockBean
    private TextExtractionService textExtractionService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final UUID docId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID chunkId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final Document doc = new Document(
            docId, "tenant",
            new DocumentMetadata("Reisepass beantragen", DocumentFileType.PDF, "MANUAL",
                    Set.of(), "INTERNAL"),
            DocumentStatus.READY, 1, "user@example.com", "user@example.com",
            Instant.now(), Instant.now(), List.of());

    private final DocumentChunkEntity chunk = new DocumentChunkEntity(
            chunkId, docId, 1, ChunkType.TEXT, "Der Antrag ist im Bürgeramt zu stellen.",
            2, null, 0, null, null, "Reisepass beantragen", DocumentFileType.PDF, "MANUAL",
            Set.of(), "upload", null, Instant.now(), Set.of(), null,
            null, null, null, null);

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(documentService.getDocument(eq(docId), any())).thenReturn(doc);
        when(chunkRepository.findByDocumentIdOrderByChunkIndex(docId)).thenReturn(List.of(chunk));
        when(documentService.findIngestionJobs(any(IngestionJobFilter.class))).thenReturn(
                new IngestionJobPage(
                        List.of(new DocumentIngestionJob(UUID.randomUUID(), docId,
                                IngestionStatus.COMPLETED, "upload", "user@example.com", "tenant",
                                null, Instant.now(), Instant.now(), Instant.now(), 1)),
                        0, 1, 1, 1));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void view_rendersDocumentMetadataAndPdfEmbed() throws Exception {
        mockMvc.perform(get("/documents/" + docId + "/view"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reisepass beantragen")))
                .andExpect(content().string(containsString("Handbuch")))
                .andExpect(content().string(containsString("Bereit")))
                .andExpect(content().string(containsString("Version 1")))
                .andExpect(content().string(containsString("Abgeschlossen")))
                .andExpect(content().string(containsString("Dokument-ID")))
                .andExpect(content().string(containsString(docId.toString())))
                .andExpect(content().string(containsString("Originaldokument")))
                .andExpect(content().string(containsString("/documents/" + docId + "/content")));
    }

    @Test
    void view_unknownDocument_returns404() throws Exception {
        when(documentService.getDocument(eq(docId), any()))
                .thenThrow(new RuntimeException("nicht gefunden"));

        mockMvc.perform(get("/documents/" + docId + "/view"))
                .andExpect(status().isNotFound());
    }

    /**
     * GET /documents/{id}/content must return the real PDF bytes with
     * Content-Type application/pdf so the browser can render it inside the
     * viewer modal's &lt;embed&gt;. Also asserts the frame policy allows the
     * same-origin embed (X-Frame-Options: DENY would show the broken-PDF icon).
     */
    @Test
    void content_pdf_returnsPdfBytesWithPdfContentType() throws Exception {
        String storageKey = UUID.randomUUID() + "_test.pdf";
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
        Path file = uploadDir.resolve(storageKey);
        String minimalPdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF\n";
        try {
            Files.createDirectories(uploadDir);
            Files.write(file, minimalPdf.getBytes(StandardCharsets.ISO_8859_1));

            Document pdfDoc = new Document(
                    docId, "tenant",
                    new DocumentMetadata("Reisepass beantragen", DocumentFileType.PDF, "MANUAL",
                            Set.of(), "INTERNAL"),
                    DocumentStatus.READY, 1, "user@example.com", "user@example.com",
                    Instant.now(), Instant.now(),
                    List.of(new DocumentVersion(UUID.randomUUID(), 1, "reisepass.pdf",
                            "application/octet-stream", minimalPdf.getBytes(StandardCharsets.ISO_8859_1).length,
                            "local", storageKey, null, "user@example.com", Instant.now())));
            when(documentService.getDocument(eq(docId), any())).thenReturn(pdfDoc);

            mockMvc.perform(get("/documents/" + docId + "/content"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                    .andExpect(header().string("Content-Disposition", "inline; filename=\"reisepass.pdf\""))
                    // The frame policy must not block the same-origin PDF embed.
                    .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                    .andExpect(content().bytes(minimalPdf.getBytes(StandardCharsets.ISO_8859_1)));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A PDF stored with an incorrect browser-declared content type must still be served as application/pdf. */
    @Test
    void content_pdfWithNonPdfStoredContentType_stillServesApplicationPdf() throws Exception {
        String storageKey = UUID.randomUUID() + "_test2.pdf";
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
        Path file = uploadDir.resolve(storageKey);
        byte[] bytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        try {
            Files.createDirectories(uploadDir);
            Files.write(file, bytes);

            Document pdfDoc = new Document(
                    docId, "tenant",
                    new DocumentMetadata("Reisepass beantragen", DocumentFileType.PDF, "MANUAL",
                            Set.of(), "INTERNAL"),
                    DocumentStatus.READY, 1, "user@example.com", "user@example.com",
                    Instant.now(), Instant.now(),
                    List.of(new DocumentVersion(UUID.randomUUID(), 1, "reisepass.pdf",
                            "application/octet-stream", bytes.length,
                            "local", storageKey, null, "user@example.com", Instant.now())));
            when(documentService.getDocument(eq(docId), any())).thenReturn(pdfDoc);

            mockMvc.perform(get("/documents/" + docId + "/content"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                    .andExpect(content().bytes(bytes));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Missing files must be a 404, never an empty or wrong-typed body. */
    @Test
    void content_missingFile_returns404() throws Exception {
        Document pdfDoc = new Document(
                docId, "tenant",
                new DocumentMetadata("Reisepass beantragen", DocumentFileType.PDF, "MANUAL",
                        Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "user@example.com", "user@example.com",
                Instant.now(), Instant.now(),
                List.of(new DocumentVersion(UUID.randomUUID(), 1, "reisepass.pdf",
                        "application/pdf", 10, "local", "missing_" + UUID.randomUUID() + ".pdf",
                        null, "user@example.com", Instant.now())));
        when(documentService.getDocument(eq(docId), any())).thenReturn(pdfDoc);

        mockMvc.perform(get("/documents/" + docId + "/content"))
                .andExpect(status().isNotFound());
    }

    @Test
    void view_nonPdfWithoutFullText_showsHonestEmptyState() throws Exception {
        Document txtDoc = new Document(
                docId, "tenant",
                new DocumentMetadata("Reisepass beantragen", DocumentFileType.TXT, "MANUAL",
                        Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "user@example.com", "user@example.com",
                Instant.now(), Instant.now(), List.of());
        when(documentService.getDocument(eq(docId), any())).thenReturn(txtDoc);
        when(chunkRepository.findByDocumentIdOrderByChunkIndex(docId)).thenReturn(List.of());

        mockMvc.perform(get("/documents/" + docId + "/view"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "keine indexierten Textabschnitte")))
                .andExpect(content().string(not(containsString("Abschnitt 0"))));
    }
}
