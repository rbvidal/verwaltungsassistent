package reasoning.ai.unit.registry;

import reasoning.ai.application.DefaultPromptRegistry;
import reasoning.ai.model.PromptTemplate;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Prompt Registry")
class PromptRegistryTest {

    private DefaultPromptRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultPromptRegistry();
    }

    @Nested
    @DisplayName("Prompt lookup")
    class Lookup {

        @Test
        @DisplayName("resolves rag-answer latest version")
        void resolvesLatestRagAnswer() {
            var prompt = registry.getLatest("rag-answer");
            assertTrue(prompt.isPresent());
            assertEquals("rag-answer", prompt.get().getId());
            assertEquals(PromptTemplate.Category.RETRIEVAL, prompt.get().getCategory());
        }

        @Test
        @DisplayName("resolves by qualified ID with version")
        void resolvesByQualifiedId() {
            var prompt = registry.get("rag-answer/v1");
            assertTrue(prompt.isPresent());
            assertEquals(1, prompt.get().getVersion());
        }

        @Test
        @DisplayName("returns empty for unknown prompt")
        void returnsEmptyForUnknown() {
            assertTrue(registry.getLatest("nonexistent").isEmpty());
            assertTrue(registry.get("nonexistent/v1").isEmpty());
        }
    }

    @Nested
    @DisplayName("Prompt categories")
    class Categories {

        @Test
        @DisplayName("finds prompts by category")
        void findsByCategory() {
            var retrieval = registry.findByCategory(PromptTemplate.Category.RETRIEVAL);
            var extraction = registry.findByCategory(PromptTemplate.Category.EXTRACTION);
            var evaluation = registry.findByCategory(PromptTemplate.Category.EVALUATION);

            assertFalse(retrieval.isEmpty(), "Should have at least one RETRIEVAL prompt");
            assertFalse(extraction.isEmpty(), "Should have at least one EXTRACTION prompt");
            assertFalse(evaluation.isEmpty(), "Should have at least one EVALUATION prompt");
        }

        @Test
        @DisplayName("all registered prompts belong to valid categories")
        void allPromptsHaveValidCategories() {
            for (String id : registry.listPromptIds()) {
                var prompt = registry.getLatest(id);
                assertTrue(prompt.isPresent());
                assertNotNull(prompt.get().getCategory(), "Prompt " + id + " should have a category");
            }
        }
    }

    @Nested
    @DisplayName("Prompt versioning")
    class Versioning {

        @Test
        @DisplayName("getLatest returns highest version")
        void getLatestReturnsHighestVersion() {
            registry.register(new PromptTemplate("test-prompt", 1,
                    PromptTemplate.Category.SYSTEM, "v1", "template {{x}}",
                    java.util.List.of("x"), "text", java.util.List.of("*"),
                    0.5, java.util.List.of(), Map.of()));
            registry.register(new PromptTemplate("test-prompt", 2,
                    PromptTemplate.Category.SYSTEM, "v2", "template {{x}} {{y}}",
                    java.util.List.of("x", "y"), "text", java.util.List.of("*"),
                    0.5, java.util.List.of(), Map.of()));

            var latest = registry.getLatest("test-prompt");
            assertTrue(latest.isPresent());
            assertEquals(2, latest.get().getVersion());
        }

        @Test
        @DisplayName("prompt IDs are listed")
        void listsAllPromptIds() {
            var ids = registry.listPromptIds();
            assertTrue(ids.contains("rag-answer"));
            assertTrue(ids.contains("entity-extraction"));
            assertTrue(ids.contains("rerank-evaluation"));
        }
    }

    @Nested
    @DisplayName("Prompt rendering")
    class Rendering {

        @Test
        @DisplayName("renders template with variable substitution")
        void rendersTemplate() {
            var prompt = registry.getLatest("rag-answer");
            assertTrue(prompt.isPresent());
            String rendered = prompt.get().render(Map.of("context", "test context", "question", "test question"));
            assertTrue(rendered.contains("test context"));
            assertTrue(rendered.contains("test question"));
            assertFalse(rendered.contains("{{context}}"));
            assertFalse(rendered.contains("{{question}}"));
        }

        @Test
        @DisplayName("rendered prompt includes instructions")
        void renderedPromptIncludesInstructions() {
            var prompt = registry.getLatest("rag-answer");
            assertTrue(prompt.isPresent());
            String rendered = prompt.get().render(Map.of("context", "...", "question", "..."));
            assertTrue(rendered.contains("Cite") || rendered.contains("RETRIEVED CONTEXT"));
        }
    }
}
