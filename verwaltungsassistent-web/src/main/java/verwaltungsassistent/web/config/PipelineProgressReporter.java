package verwaltungsassistent.web.config;

import reasoning.ai.api.PipelineProgressListener;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.ProgressStageMessages;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Forwards pipeline stage events to the progress store of the matching job.
 * The AI pipeline emits stages keyed by the request id; web controllers use
 * the same id as the progress job id, so every user-facing operation
 * (assistant question, decision analysis, case evaluation) receives its own
 * German stage messages depending on its operation kind.
 */
@Component
public class PipelineProgressReporter implements PipelineProgressListener {

    private final JobProgressService progressService;

    public PipelineProgressReporter(JobProgressService progressService) {
        this.progressService = progressService;
    }

    @Override
    public void onStage(String requestId, String stage) {
        onStage(requestId, stage, java.util.Map.of());
    }

    @Override
    public void onStage(String requestId, String stage, Map<String, Object> data) {
        JobProgressService.Job job = progressService.get(requestId);
        if (job == null) return;
        String message = ProgressStageMessages.forKind(job.kind, stage);
        if (message != null) {
            progressService.recordStage(requestId, message);
        }
        progressService.recordStageData(requestId, stage, data);
    }
}
