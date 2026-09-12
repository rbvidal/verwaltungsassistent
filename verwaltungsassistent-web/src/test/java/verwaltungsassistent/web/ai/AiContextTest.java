package verwaltungsassistent.web.ai;

import reasoning.ai.api.*;
import reasoning.ai.application.*;
import reasoning.search.api.*;
import reasoning.search.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every bean in the AI runtime dependency graph is present.
 *
 * <pre>
 * DecisionWorkspaceController
 *   → AiFacade → AiService
 *     → RetrievalAugmentationService → DefaultRetrievalAugmentationService
 *       → SearchFacade → SearchService
 *         → HybridRetrievalService → DefaultHybridRetrievalService
 *           → KeywordSearchProvider → JpaKeywordSearchProvider
 *           → VectorSearchProvider → NoOpVectorSearchProvider
 *           → GraphSearchProvider → NoOpGraphSearchProvider
 *           → RerankingService → DefaultRerankingService
 *     → ContextAssembler → DefaultContextAssembler
 *     → PromptBuilder → DefaultPromptBuilder
 *     → ModelProvider → DefaultModelProvider
 *     → ChatCompletionProvider → OllamaChatProvider (conditional)
 *     → GroundingService → DefaultGroundingService
 *     → QueryIntentClassifier → DefaultQueryIntentClassifier
 *     → EvidenceCoverageValidator
 *     → PipelineProfiler
 *     → DecisionRouter
 * </pre>
 */
@SpringBootTest
class AiContextTest {

    @Autowired
    private ApplicationContext ctx;

    // ── AI API ──

    @Test
    void aiFacadeShouldBeAvailable() {
        assertThat(ctx.getBean(AiFacade.class)).isNotNull();
    }

    // ── AI Runtime ──

    @Test
    void aiServiceShouldBeAvailable() {
        assertThat(ctx.getBean(AiService.class)).isNotNull();
    }

    @Test
    void retrievalAugmentationServiceShouldBeAvailable() {
        assertThat(ctx.getBean(RetrievalAugmentationService.class)).isNotNull();
    }

    @Test
    void contextAssemblerShouldBeAvailable() {
        assertThat(ctx.getBean(ContextAssembler.class)).isNotNull();
    }

    @Test
    void promptBuilderShouldBeAvailable() {
        assertThat(ctx.getBean(PromptBuilder.class)).isNotNull();
    }

    @Test
    void modelProviderShouldBeAvailable() {
        assertThat(ctx.getBean(ModelProvider.class)).isNotNull();
    }

    @Test
    void groundingServiceShouldBeAvailable() {
        assertThat(ctx.getBean(GroundingService.class)).isNotNull();
    }

    @Test
    void decisionRouterShouldBeAvailable() {
        assertThat(ctx.getBean(DecisionRouter.class)).isNotNull();
    }

    @Test
    void pipelineProfilerShouldBeAvailable() {
        assertThat(ctx.getBean(PipelineProfiler.class)).isNotNull();
    }

    @Test
    void evidenceCoverageValidatorShouldBeAvailable() {
        assertThat(ctx.getBean(EvidenceCoverageValidator.class)).isNotNull();
    }

    @Test
    void aiQueryIntentClassifierShouldBeAvailable() {
        assertThat(ctx.getBean(reasoning.ai.application.DefaultQueryIntentClassifier.class)).isNotNull();
    }

    @Test
    void chatCompletionProviderShouldBeAvailable() {
        assertThat(ctx.getBean(ChatCompletionProvider.class)).isNotNull();
    }

    // ── Search Runtime ──

    @Test
    void searchFacadeShouldBeAvailable() {
        assertThat(ctx.getBean(SearchFacade.class)).isNotNull();
    }

    @Test
    void hybridRetrievalServiceShouldBeAvailable() {
        assertThat(ctx.getBean(HybridRetrievalService.class)).isNotNull();
    }

    @Test
    void keywordSearchProviderShouldBeAvailable() {
        assertThat(ctx.getBean(KeywordSearchProvider.class)).isNotNull();
    }

    @Test
    void vectorSearchProviderShouldBeAvailable() {
        assertThat(ctx.getBean(VectorSearchProvider.class)).isNotNull();
    }

    @Test
    void graphSearchProviderShouldBeAvailable() {
        assertThat(ctx.getBean(GraphSearchProvider.class)).isNotNull();
    }

    @Test
    void rerankingServiceShouldBeAvailable() {
        assertThat(ctx.getBean(RerankingService.class)).isNotNull();
    }

    @Test
    void retrievalPortShouldBeAvailable() {
        assertThat(ctx.getBean(RetrievalPort.class)).isNotNull();
    }
}
