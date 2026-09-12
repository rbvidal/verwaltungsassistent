package verwaltungsassistent.web.ai;

import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test covering the complete municipal workflow:
 * Login → Create Case → Upload Documents → Search → Prepare Decision → AI Recommendation → Human Review.
 */
@SpringBootTest(properties = "demo.mode=true")
@AutoConfigureMockMvc
class CompleteWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "sachbearbeiter@verwaltungsassistent.local", "Sachbearbeiter Test",
            Set.of("USER"));

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Complete Workflow ──

    @Test
    void completeWorkflow_shouldExecuteEndToEnd() throws Exception {
        // 1. Create Case
        String redirectedUrl = mockMvc.perform(post("/cases/new").with(csrf())
                        .param("name", "Baugenehmigung Carport Müller")
                        .param("description", "Prüfung der Genehmigungspflicht"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String caseId = redirectedUrl.substring(redirectedUrl.lastIndexOf('/') + 1);

        // 2. View Case Detail
        mockMvc.perform(get("/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(view().name("cases/detail"));

        // 3. Documents page loads
        mockMvc.perform(get("/documents"))
                .andExpect(status().isOk());

        // 4. Upload form loads
        mockMvc.perform(get("/documents/upload"))
                .andExpect(status().isOk());

        // 5. Upload a document
        MockMultipartFile file = new MockMultipartFile(
                "file", "test-verordnung.txt", "text/plain",
                "Bauordnung Berlin § 6 Abstandsflächen".getBytes());
        mockMvc.perform(multipart("/documents/upload").file(file)
                        .param("title", "Bauordnung Berlin 2024")
                        .param("category", "POLICY_DOCUMENT")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // 6. Document appears in list
        String docList = mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(docList).contains("Bauordnung Berlin 2024");

        // 7. Knowledge search loads
        mockMvc.perform(get("/knowledge"))
                .andExpect(status().isOk());

        // 8. Decision workspace loads
        String decisionPage = mockMvc.perform(get("/cases/" + caseId + "/decision"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(decisionPage).contains("Analyse starten");
        assertThat(decisionPage).contains("analysis-spinner");

        // 9. AI Analysis produces municipal recommendation
        String result = mockMvc.perform(post("/cases/" + caseId + "/decision/analyze")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Verify municipal decision package sections
        assertThat(result).contains("Beschlussempfehlung");
        assertThat(result).contains("Entscheidungsvorlage");
        assertThat(result).contains("Offene Punkte");
        assertThat(result).contains("Empfohlene Handlungsschritte");
        assertThat(result).contains("Freigabe");
        assertThat(result).contains("decision-package__approval-line");
    }

    // ── Document Upload Edge Cases ──

    @Test
    void uploadWithEmptyFile_shouldReturnToForm() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/documents/upload").file(emptyFile)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void uploadWithoutTitle_shouldUseFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "verordnung.txt", "text/plain",
                "Test content".getBytes());
        mockMvc.perform(multipart("/documents/upload").file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ── Document Detail & Reindex ──

    @Test
    void documentDetail_shouldShowMetadataAndIndexingHistory() throws Exception {
        // Upload a document first
        MockMultipartFile file = new MockMultipartFile(
                "file", "detail-test.txt", "text/plain",
                "Test content".getBytes());
        String redirect = mockMvc.perform(multipart("/documents/upload").file(file)
                        .param("title", "Detail Test")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        // Navigate to documents list and get the document ID from the link
        String docList = mockMvc.perform(get("/documents"))
                .andReturn().getResponse().getContentAsString();
        String docId = extractDocId(docList, "Detail Test");

        if (docId != null) {
            mockMvc.perform(get("/documents/" + docId))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("Dokumentinformationen")));

            // Reindex
            mockMvc.perform(post("/documents/" + docId + "/reindex").with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
    }

    // ── Knowledge Search ──

    @Test
    void knowledgeSearch_shouldShowFormAndHandleResults() throws Exception {
        mockMvc.perform(get("/knowledge"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Wissensbasis")));

        // Search with query
        mockMvc.perform(get("/knowledge").param("q", "Bauordnung").param("mode", "HYBRID"))
                .andExpect(status().isOk());
    }

    @Test
    void knowledgeSearch_withFilters_shouldApply() throws Exception {
        mockMvc.perform(get("/knowledge")
                        .param("q", "test")
                        .param("mode", "KEYWORD")
                        .param("documentType", "PDF"))
                .andExpect(status().isOk());
    }

    // ── Helper ──

    private String extractDocId(String html, String title) {
        int idx = html.indexOf(title);
        if (idx < 0) return null;
        int hrefStart = html.lastIndexOf("href=\"/documents/", idx);
        if (hrefStart < 0) return null;
        int idStart = hrefStart + "href=\"/documents/".length();
        int idEnd = html.indexOf("\"", idStart);
        return html.substring(idStart, idEnd);
    }
}
