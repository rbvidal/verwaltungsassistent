package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ModelProvider;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.ModelCapabilities;
import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for the „Allgemeine Fragen" tab: the direct-LLM path must be
 * architecturally separated from the municipal Assistant pipeline (no
 * retrieval, no grounding, no verifier, no citations). Also covers the
 * default tab, the warning box, conversation context, error handling,
 * authentication and the „Foto herunterladen" label.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class GeneralChatControllerTest {

    private static final String ANSWER = "Die Hauptstadt von Frankreich ist Paris.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatCompletionProvider chatCompletionProvider;
    @MockBean
    private ModelProvider modelProvider;

    // The municipal pipeline — must NEVER be touched by the general path.
    // AiFacade is the single gateway into retrieval/grounding/verifier, so
    // verifying it is never invoked proves the architectural separation.
    // (SearchFacade/GroundingService are intentionally NOT mocked: their only
    // implementations also provide ChunkManagementService/DefaultGroundingService.)
    @MockBean
    private AiFacade aiFacade;
    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            java.util.UUID.randomUUID(), "user@example.com", "Test User",
            java.util.Set.of("USER"));

    @BeforeEach
    void setUp() {
        when(chatCompletionProvider.isAvailable()).thenReturn(true);
        when(chatCompletionProvider.complete(anyString(), any(ModelCapabilities.class)))
                .thenReturn(ANSWER);
        when(modelProvider.capabilities(any())).thenReturn(new ModelCapabilities(
                "ollama", "test-model", 8192, true, true, true));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assistantPage_defaultsToMunicipalTab_withGeneralTabPresent() throws Exception {
        String html = normalize(mockMvc.perform(get("/assistant"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertTrue(html.contains("id=\"assistant-tab-assistant\""),
                "the 'Assistent' tab button must exist");
        assertTrue(html.contains("assistant-tab-assistant\" class=\"btn btn--primary\""),
                "the 'Assistent' tab is selected by default");
        assertTrue(html.contains("assistant-tab-general"),
                "the 'Allgemeine Fragen' tab must exist");
        assertTrue(html.contains("id=\"assistant-panel\""),
                "the municipal panel is rendered");
        assertTrue(html.contains("assistant-panel--hidden\""),
                "the municipal panel is visible by default");
    }

    @Test
    void generalTab_showsWarningAboutMissingDocumentGrounding() throws Exception {
        String html = normalize(mockMvc.perform(get("/assistant").param("tab", "general"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertTrue(html.contains("Hinweis zu „Allgemeine Fragen\""),
                "the warning box must be present");
        assertTrue(html.contains("nicht durch die Quellen- und Prüfmechanismen des Assistenten verifiziert"),
                "the warning must state that answers are not verified against the knowledge base");
        assertTrue(html.contains("general-question"), "the general input must exist");
    }

    @Test
    void generalQuestion_directLlmResponse_withoutMunicipalPipeline() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Was ist die Hauptstadt von Frankreich?")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Was ist die Hauptstadt von Frankreich?"),
                "the user turn is rendered");
        assertTrue(html.contains(ANSWER), "the LLM answer is rendered");
        // architectural separation: the municipal pipeline is never invoked
        verify(aiFacade, never()).answer(any());
        verify(decisionRouter, never()).route(anyString());
        // no sources/citations/confidence indicators in the general tab
        assertFalse(html.contains("Belege"), "no Belege section");
        assertFalse(html.contains("Konfidenz"), "no confidence indicator");
        assertFalse(html.contains("Quellenlage"), "no Quellenlage badge");
    }

    @Test
    void followUpQuestion_receivesConversationContext() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Was ist die Hauptstadt von Frankreich?")
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Und wie viele Einwohner hat sie?")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Und wie viele Einwohner hat sie?")));

        org.mockito.ArgumentCaptor<String> promptCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chatCompletionProvider, times(2)).complete(promptCaptor.capture(), any());
        String secondPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(secondPrompt.contains("Was ist die Hauptstadt von Frankreich?"),
                "the follow-up prompt must include the earlier question");
        assertTrue(secondPrompt.contains(ANSWER),
                "the follow-up prompt must include the earlier answer");
    }

    @Test
    void emptyQuestion_isRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "   ")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Bitte geben Sie eine Frage ein.")));
        verify(chatCompletionProvider, never()).complete(anyString(), any());
    }

    @Test
    void llmUnavailable_showsGermanError() throws Exception {
        when(chatCompletionProvider.isAvailable()).thenReturn(false);
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Was ist 1+1?")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Die Anfrage konnte momentan nicht beantwortet werden")));
    }

    private static String normalize(String html) {
        return html.replaceAll("\\s+", " ");
    }

    @Test
    void unauthenticatedRequest_isRedirectedToLogin() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/assistant/general/ask")
                        .param("question", "Test?")
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    @Test
    void generalChat_doesNotPersistPromptOrResponse() throws Exception {
        // The General-Chat mode must not persist conversation content in any
        // application table (no analyses, no workspaces, no documents).
        long analysesBefore = injectedAnalyses.count();
        long workspacesBefore = injectedWorkspaces.findAll().size();
        long documentsBefore = injectedDocuments.findDocuments(
                new reasoning.document.api.DocumentFilter(
                        null, null, null, null, null, null, null, 0, 1)).totalElements();

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Geheimfrage XYZ-42 – Wie funktioniert ein Dieselmotor?")
                        .with(csrf()))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(analysesBefore, injectedAnalyses.count(),
                "no email analysis record may be created");
        org.junit.jupiter.api.Assertions.assertEquals(workspacesBefore, injectedWorkspaces.findAll().size(),
                "no workspace may be created");
        org.junit.jupiter.api.Assertions.assertEquals(documentsBefore, injectedDocuments.findDocuments(
                new reasoning.document.api.DocumentFilter(
                        null, null, null, null, null, null, null, 0, 1)).totalElements(),
                "no document may be created");
        // the conversation itself stays transient in the HTTP session
        org.junit.jupiter.api.Assertions.assertEquals(2,
                ((List<?>) session.getAttribute("generalChatTurns")).size(),
                "the conversation lives only in the session");
    }

    @Autowired
    private verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository injectedAnalyses;
    @Autowired
    private reasoning.workspace.application.WorkspaceService injectedWorkspaces;
    @Autowired
    private reasoning.document.api.DocumentFacade injectedDocuments;

    // ── Layout-Regression: kompakte Gesprächsblasen (Allgemeine Fragen) ─────

    /**
     * Layout-Regression: white-space: pre-wrap darf NUR auf dem Inhalts-Span
     * liegen, nicht auf der Blase selbst. Auf der Blase rendert das
     * Zeilenumbruch-Whitespace des Thymeleaf-Templates jede Leerzeile als
     * leere Textzeile — eine einzeilige Frage wurde dadurch ~110 px hoch
     * aufgebläht („riesige Leerflächen"). Genau EIN Vorkommen = die
     * Inhalts-Spanne beider Turns; ein zweites Vorkommen auf den Blasen
     * würde die Lücke wieder öffnen.
     */
    @Test
    void renderedConversation_preWrapOnlyOnContentSpans_notOnBubbles() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mockMvc.perform(post("/assistant/general/ask")
                        .session(session)
                        .param("question", "Wie schnell muss ich mich nach meinem Umzug anmelden?")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int occurrences = countOccurrences(html, "white-space: pre-wrap");
        assertEquals(2, occurrences,
                "pre-wrap must exist exactly on the two content spans (user + assistant), "
                        + "not on the bubbles (template whitespace would inflate every bubble)");
        int spanPos = html.indexOf("white-space: pre-wrap");
        assertTrue(html.lastIndexOf("style=\"display: block;", spanPos) > html.lastIndexOf("<div", spanPos),
                "the pre-wrap style must belong to the content span, not the bubble div");
    }

    /**
     * Layout-Regression: der Gesprächscontainer darf keine große feste
     * Mindesthöhe haben (leere Fläche ohne Inhalt) — die Höhe muss vom Inhalt
     * kommen; ein max-height mit Scroll bleibt für lange Antworten erhalten.
     */
    @Test
    void chatTemplate_conversationContainerHasNoLargeFixedMinHeight() throws Exception {
        String template = Files.readString(Path.of(
                "src/main/resources/templates/assistant/chat.html"), StandardCharsets.UTF_8);
        assertFalse(template.contains("min-height: 220px"),
                "the conversation container must not reserve a large fixed min-height");
        assertTrue(template.contains("max-height: 60vh"),
                "long conversations stay bounded by a scrollable max-height");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }


    @Test
    void photoDownloadLabel_saysFotoHerunterladen() throws Exception {
        // template-level check: the UI action must say "Foto herunterladen"
        for (String template : List.of(
                "src/main/resources/templates/geoinformation/index.html",
                "src/main/resources/templates/geoinformation/geo-case-detail.html")) {
            String html = Files.readString(Path.of(template), StandardCharsets.UTF_8);
            assertTrue(html.contains("Foto herunterladen"),
                    template + " must offer 'Foto herunterladen'");
            assertFalse(html.contains("Original herunterladen"),
                    template + " must not offer 'Original herunterladen'");
        }
    }
}
