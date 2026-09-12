package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.application.DocumentService;
import reasoning.document.api.DocumentPage;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.VectorSearchProvider;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Document deletion endpoint tests: success surfaces a clear message and
 * calls the soft-delete, failures are reported (never silently swallowed),
 * and deleting an already-deleted document is idempotent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DocumentDeleteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private VectorSearchProvider vectorSearchProvider;

    @MockBean
    private GraphEnrichmentService graphEnrichmentService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("ADMIN"));

    private final UUID docId = UUID.randomUUID();
    private Document doc;

    private Document docWithStatus(DocumentStatus status) {
        return new Document(docId, "tenant",
                new DocumentMetadata("Data-Path-Test", DocumentFileType.PDF, "POLICY_DOCUMENT",
                        Set.of(), "INTERNAL"),
                status, 1, "user@example.com", "user@example.com",
                Instant.now(), Instant.now(),
                List.of(new reasoning.document.model.DocumentVersion(
                        UUID.randomUUID(), 1, "data-path-test.pdf", "application/pdf",
                        66_500, "local", "data-path-test.pdf", null, "user@example.com",
                        Instant.now())));
    }

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        doc = docWithStatus(DocumentStatus.FAILED);

        when(documentService.getDocument(eq(docId), any())).thenReturn(doc);
        when(documentService.findDocuments(any())).thenReturn(
                new DocumentPage(List.of(doc), 0, 200, 1, 1));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delete_success_removesDocAndShowsSuccessMessage() throws Exception {
        mockMvc.perform(delete("/documents/" + docId + "/delete").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Dokument Data-Path-Test wurde gelöscht.")))
                .andExpect(content().string(not(containsString("konnte nicht gelöscht werden"))));

        verify(documentService).deleteDocument(eq(docId), any());
        verify(vectorSearchProvider).deleteByDocument(docId);
    }

    @Test
    void delete_failure_showsHumanReadableError() throws Exception {
        when(documentService.deleteDocument(eq(docId), any()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(delete("/documents/" + docId + "/delete").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Das Dokument konnte nicht gelöscht werden.")))
                .andExpect(content().string(not(containsString("wurde gelöscht"))));
    }

    @Test
    void delete_alreadyDeleted_isIdempotent() throws Exception {
        Document deleted = docWithStatus(DocumentStatus.DELETED);
        when(documentService.getDocument(eq(docId), any())).thenReturn(deleted);

        mockMvc.perform(delete("/documents/" + docId + "/delete").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Dokument Data-Path-Test wurde gelöscht.")));

        verify(documentService, never()).deleteDocument(any(), any());
        verify(vectorSearchProvider).deleteByDocument(docId);
    }

    @Test
    void delete_unknownDocument_returns404() throws Exception {
        when(documentService.getDocument(eq(docId), any()))
                .thenThrow(new RuntimeException("nicht gefunden"));

        mockMvc.perform(delete("/documents/" + docId + "/delete").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
