package reasoning.ai.analytics;

import java.util.List;

public record PipelineDiagnosticReport(
        String overallHealth,          // "GOOD", "WARNING", "CRITICAL", "INSUFFICIENT_DATA"
        List<DiagnosticFinding> findings,
        DiagnosticFinding primaryRegression,
        StageHealth primaryBottleneck,
        List<DiagnosticFinding> correlatedSignals,
        boolean insufficientData,
        int baselineRunCount,
        int currentRunCount
) {}
