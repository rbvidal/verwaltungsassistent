package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.search.api.VectorSearchProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for the document upload surface: German category labels,
 * multiple files per request, the JSON batch endpoint and the per-document
 * status endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DocumentUploadBatchTest {

    @Autowired
    private MockMvc mockMvc;

    // The controller talks to the DocumentFacade interface, but the mock is
    // registered on the implementation class: replacing the interface bean
    // would also remove DocumentService, which the real DocumentIngestionWorker
    // in the context depends on. ChunkManagementService is intentionally NOT
    // mocked — its only implementation (SearchService) also provides SearchFacade.
    @MockBean
    private reasoning.document.application.DocumentService documentService;

    @MockBean
    private VectorSearchProvider vectorSearchProvider;

    @MockBean
    private reasoning.document.api.DocumentIngestionProcessor ingestionProcessor;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final UUID docId = UUID.randomUUID();
    private final Document doc = new Document(
            docId, "tenant", new DocumentMetadata("Titel", DocumentFileType.PDF, "OTHER",
            Set.of(), "INTERNAL"), DocumentStatus.INGESTION_PENDING, 1,
            testUser.email(), testUser.email(), Instant.now(), Instant.now(), List.of());

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(documentService.createDocument(any())).thenReturn(doc);
        when(documentService.createIngestionJob(any(), anyString())).thenReturn(newJob());
    }

    private reasoning.document.model.DocumentIngestionJob newJob() {
        return new reasoning.document.model.DocumentIngestionJob(
                UUID.randomUUID(), docId, reasoning.common.model.IngestionStatus.PENDING,
                "upload", "user@example.com", "tenant", null,
                Instant.now(), Instant.now(), null, 1);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadForm_showsGermanCategoryLabels() throws Exception {
        mockMvc.perform(get("/documents/upload"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"CONTRACT\">Vertrag")))
                .andExpect(content().string(containsString("value=\"CORRESPONDENCE\">Schriftverkehr")))
                .andExpect(content().string(containsString("value=\"OTHER\">Sonstiges")))
                // The option VALUE keeps the backend enum (API contract); the
                // displayed label must be German, not the raw enum name.
                .andExpect(content().string(not(containsString("value=\"CONTRACT\">CONTRACT"))))
                .andExpect(content().string(not(containsString("value=\"TECHNICAL_SPEC\">TECHNICAL_SPEC"))));
    }

    @Test
    void multipleFiles_areAllIngestedInOneRequest() throws Exception {
        MockMultipartFile first = new MockMultipartFile("file", "a.pdf", "application/pdf",
                "first document".getBytes());
        MockMultipartFile second = new MockMultipartFile("file", "b.pdf", "application/pdf",
                "second document".getBytes());

        mockMvc.perform(multipart("/documents/upload").file(first).file(second)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/documents"));

        verify(documentService, org.mockito.Mockito.times(2)).createDocument(any());
        verify(documentService, org.mockito.Mockito.times(2)).createIngestionJob(any(), anyString());
    }

    @Test
    void upload_startsIngestionImmediately() throws Exception {
        // Regression: after "Hochladen und indexieren" the ingestion job must
        // be STARTED synchronously (status flips to "In Verarbeitung" right
        // away) and the actual indexing must run in the background — the user
        // must never see "Start" or need back/forward navigation to trigger it.
        reasoning.document.model.DocumentIngestionJob job = newJob();
        when(documentService.startIngestion(any(), anyString())).thenReturn(job);
        when(documentService.completeIngestion(any(), anyString())).thenReturn(job);
        when(documentService.completeIngestion(eq(job.id()), anyString())).thenReturn(job);

        MockMultipartFile file = new MockMultipartFile("file", "sofort.pdf", "application/pdf",
                "sofortiger Inhalt".getBytes());

        mockMvc.perform(multipart("/documents/upload").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/documents"));

        verify(documentService).createDocument(any());
        verify(documentService).createIngestionJob(eq(docId), anyString());
        // the job must be started synchronously, before the redirect
        verify(documentService).startIngestion(any(), anyString());
        // and the indexing must actually run (background) without any user action
        verify(ingestionProcessor, org.mockito.Mockito.timeout(5000)).ingest(eq(docId));
        verify(documentService, org.mockito.Mockito.timeout(5000))
                .completeIngestion(any(), anyString());
    }

    @Test
    void batchUpload_returnsJsonWithDocumentId() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "info.pdf", "application/pdf",
                "content".getBytes());

        mockMvc.perform(multipart("/documents/upload/batch").file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].documentId").value(docId.toString()));
    }

    @Test
    void directoryUpload_pathInFilename_isSanitizedToBareFileName() throws Exception {
        // Browsers send the webkitdirectory relative path as the multipart
        // filename ("Ordner\\anlage.pdf" on Windows, "Ordner/anlage.pdf" on
        // POSIX); a path separator in the storage key would resolve into a
        // nonexistent subdirectory and fail the file write.
        MockMultipartFile win = new MockMultipartFile("file", "Ordner\\anlage.pdf",
                "application/pdf", "win".getBytes());
        MockMultipartFile posix = new MockMultipartFile("file", "Ordner/anlage.pdf",
                "application/pdf", "posix".getBytes());

        mockMvc.perform(multipart("/documents/upload/batch").file(win).file(posix)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[1].ok").value(true));

        // The dev-profile startup seeder (DemoDataService) creates additional
        // photo evidence documents — the upload flow is identified by its
        // file names (bare "anlage.pdf", path separators sanitized).
        ArgumentCaptor<CreateDocumentCommand> captor =
                ArgumentCaptor.forClass(CreateDocumentCommand.class);
        verify(documentService, org.mockito.Mockito.atLeast(2)).createDocument(captor.capture());
        List<CreateDocumentCommand> uploads = captor.getAllValues().stream()
                .filter(c -> "anlage.pdf".equals(c.fileName())).toList();
        assertEquals(2, uploads.size(), "the two uploads must be registered");
        assertEquals("anlage.pdf", uploads.get(0).fileName());
        assertEquals("anlage.pdf", uploads.get(1).fileName());
        assertEquals("anlage.pdf", uploads.get(0).title());
    }

    @Test
    void batchUpload_reportsPerFileErrors() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "bild.jpg", "image/jpeg",
                "not a document".getBytes());

        mockMvc.perform(multipart("/documents/upload/batch").file(bad)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ok").value(false))
                .andExpect(jsonPath("$.results[0].error").value(containsString("nicht unterstützt")));
    }

    @Test
    void documentStatus_returnsGermanLabels() throws Exception {
        var jobs = new reasoning.document.api.IngestionJobPage(
                List.of(new reasoning.document.model.DocumentIngestionJob(
                        UUID.randomUUID(), docId, reasoning.common.model.IngestionStatus.COMPLETED,
                        "PDF", testUser.email(), "tenant", null,
                        Instant.now(), Instant.now(), null, 1L)), 0, 1, 1, 1);
        when(documentService.getDocument(docId, testUser.email())).thenReturn(doc);
        when(documentService.findIngestionJobs(any())).thenReturn(jobs);

        mockMvc.perform(get("/documents/" + docId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INGESTION_PENDING"))
                .andExpect(jsonPath("$.statusLabel").value("Indexierung ausstehend"))
                .andExpect(jsonPath("$.ingestionLabel").value("Abgeschlossen"));
    }
}
