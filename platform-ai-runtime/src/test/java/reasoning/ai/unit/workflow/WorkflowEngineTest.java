package reasoning.ai.unit.workflow;

import reasoning.ai.application.DefaultWorkflowEngine;
import reasoning.ai.api.WorkflowEngine;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Workflow Engine")
class WorkflowEngineTest {

    private DefaultWorkflowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultWorkflowEngine();
    }

    @Nested
    @DisplayName("Workflow lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("starts document-intelligence workflow")
        void startsDocumentIntelligenceWorkflow() {
            var instance = engine.start("document-intelligence", Map.of("name", "test"));
            assertNotNull(instance.id());
            assertEquals("document-intelligence", instance.workflowDefinitionId());
            assertEquals("setup", instance.currentStep());
            assertEquals(WorkflowEngine.WorkflowStatus.ACTIVE, instance.status());
        }

        @Test
        @DisplayName("advances through all 5 stages to COMPLETE")
        void advancesThroughAllStages() {
            var instance = engine.start("document-intelligence", Map.of());

            instance = engine.advance(instance.id());
            assertEquals("ingestion", instance.currentStep());

            instance = engine.advance(instance.id());
            assertEquals("analysis", instance.currentStep());

            instance = engine.advance(instance.id());
            assertEquals("review", instance.currentStep());

            instance = engine.advance(instance.id());
            assertEquals("complete", instance.currentStep());
            instance = engine.advance(instance.id());
            assertEquals("complete", instance.currentStep());
            assertEquals(WorkflowEngine.WorkflowStatus.COMPLETED, instance.status());
        }

        @Test
        @DisplayName("previous returns to earlier step")
        void previousReturnsToEarlierStep() {
            var instance = engine.start("document-intelligence", Map.of());
            instance = engine.advance(instance.id());
            instance = engine.advance(instance.id());
            assertEquals("analysis", instance.currentStep());

            instance = engine.previous(instance.id());
            assertEquals("ingestion", instance.currentStep());
        }

        @Test
        @DisplayName("cannot go before first step")
        void cannotGoBeforeFirstStep() {
            var instance = engine.start("document-intelligence", Map.of());
            instance = engine.previous(instance.id());
            assertEquals("setup", instance.currentStep());
        }

        @Test
        @DisplayName("finds active instances")
        void findsActiveInstances() {
            engine.start("document-intelligence", Map.of());
            engine.start("batch-ingestion", Map.of());
            var active = engine.listActive();
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("throws on unknown workflow definition")
        void throwsOnUnknownWorkflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.start("nonexistent", Map.of()));
        }
    }

    @Nested
    @DisplayName("Batch ingestion workflow")
    class BatchIngestion {

        @Test
        @DisplayName("completes batch ingestion workflow")
        void completesBatchIngestion() {
            var instance = engine.start("batch-ingestion", Map.of("folder", "/docs"));
            assertEquals("ingest", instance.currentStep());

            instance = engine.advance(instance.id());
            assertEquals("enrich", instance.currentStep());

            instance = engine.advance(instance.id());
            assertEquals("complete", instance.currentStep());
            instance = engine.advance(instance.id());
            assertEquals("complete", instance.currentStep());
            assertEquals(WorkflowEngine.WorkflowStatus.COMPLETED, instance.status());
        }
    }
}
