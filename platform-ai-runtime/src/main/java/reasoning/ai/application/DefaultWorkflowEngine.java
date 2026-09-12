package reasoning.ai.application;

import reasoning.ai.api.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory workflow engine implementation.
 * Manages workflow instances with configurable step transitions.
 * Suitable for single-node deployments; production multi-node deployments would use a database-backed store.
 */
@Component
public class DefaultWorkflowEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngine.class);

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, WorkflowInstance> instances = new ConcurrentHashMap<>();

    public DefaultWorkflowEngine() {
        registerDefaultWorkflows();
    }

    @Override
    public WorkflowInstance start(String workflowDefinitionId, Map<String, Object> context) {
        WorkflowDefinition def = definitions.get(workflowDefinitionId);
        if (def == null) throw new IllegalArgumentException("Unknown workflow: " + workflowDefinitionId);

        String instanceId = UUID.randomUUID().toString();
        WorkflowInstance instance = new WorkflowInstance(
                instanceId, workflowDefinitionId, def.initialStep(),
                WorkflowStatus.ACTIVE, new LinkedHashMap<>(context),
                Instant.now(), Instant.now());
        instances.put(instanceId, instance);
        log.info("Started workflow {} instance {}", workflowDefinitionId, instanceId);
        return instance;
    }

    @Override
    public WorkflowInstance advance(String instanceId) {
        WorkflowInstance instance = instances.get(instanceId);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + instanceId);
        if (instance.status() != WorkflowStatus.ACTIVE) return instance;

        WorkflowDefinition def = definitions.get(instance.workflowDefinitionId());
        if (def == null) return instance;

        WorkflowStep currentStep = def.steps().stream()
                .filter(s -> s.id().equals(instance.currentStep()))
                .findFirst().orElse(null);

        String nextStepId;
        if (currentStep == null || currentStep.nextSteps().isEmpty()) {
            nextStepId = instance.currentStep(); // stay at current step
        } else {
            nextStepId = currentStep.nextSteps().get(0);
        }

        WorkflowStatus status = currentStep != null && currentStep.nextSteps().isEmpty()
                ? WorkflowStatus.COMPLETED : WorkflowStatus.ACTIVE;

        WorkflowInstance updated = new WorkflowInstance(
                instance.id(), instance.workflowDefinitionId(), nextStepId,
                status, instance.context(), instance.startedAt(), Instant.now());
        instances.put(instanceId, updated);
        log.debug("Advanced workflow {} to step {}", instanceId, nextStepId);
        return updated;
    }

    @Override
    public WorkflowInstance previous(String instanceId) {
        WorkflowInstance instance = instances.get(instanceId);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + instanceId);
        if (instance.status() != WorkflowStatus.ACTIVE) return instance;

        WorkflowDefinition def = definitions.get(instance.workflowDefinitionId());
        if (def == null) return instance;

        // Find the step that transitions to current step
        String prevStepId = def.steps().stream()
                .filter(s -> s.nextSteps().contains(instance.currentStep()))
                .map(WorkflowStep::id)
                .findFirst()
                .orElse(instance.currentStep());

        WorkflowInstance updated = new WorkflowInstance(
                instance.id(), instance.workflowDefinitionId(), prevStepId,
                WorkflowStatus.ACTIVE, instance.context(), instance.startedAt(), Instant.now());
        instances.put(instanceId, updated);
        log.debug("Returned workflow {} to step {}", instanceId, prevStepId);
        return updated;
    }

    @Override
    public Optional<WorkflowInstance> findInstance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    @Override
    public List<WorkflowInstance> listActive() {
        return instances.values().stream()
                .filter(i -> i.status() == WorkflowStatus.ACTIVE)
                .toList();
    }

    private void registerDefaultWorkflows() {
        definitions.put("document-intelligence", new WorkflowDefinition(
                "document-intelligence", "Document Intelligence Review",
                "Upload → Ingest → Enrich → Analyze → Review → Complete",
                List.of(
                        new WorkflowStep("setup", "Setup", "Configure workspace and upload documents",
                                "manual", Map.of(), List.of("ingestion")),
                        new WorkflowStep("ingestion", "Ingestion", "Documents are extracted, chunked, and indexed",
                                "automated", Map.of("handler", "ingestion"), List.of("analysis")),
                        new WorkflowStep("analysis", "Analysis", "AI analyzes documents and extracts insights",
                                "ai", Map.of("handler", "analysis"), List.of("review")),
                        new WorkflowStep("review", "Review", "Human reviews AI-generated findings",
                                "manual", Map.of(), List.of("complete")),
                        new WorkflowStep("complete", "Complete", "Workflow finished",
                                "manual", Map.of(), List.of())
                ), "setup"));

        definitions.put("batch-ingestion", new WorkflowDefinition(
                "batch-ingestion", "Batch Document Ingestion",
                "Upload folder → Extract → Enrich → Index → Complete",
                List.of(
                        new WorkflowStep("ingest", "Ingest", "Extract and chunk documents",
                                "automated", Map.of(), List.of("enrich")),
                        new WorkflowStep("enrich", "Enrich", "Semantic enrichment and graph population",
                                "automated", Map.of(), List.of("complete")),
                        new WorkflowStep("complete", "Complete", "All documents processed",
                                "manual", Map.of(), List.of())
                ), "ingest"));
    }
}
