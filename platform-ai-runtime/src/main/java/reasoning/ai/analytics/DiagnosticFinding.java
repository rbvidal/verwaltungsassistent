package reasoning.ai.analytics;

public record DiagnosticFinding(
        RegressionType regressionType,
        String metricLabel,
        double baselineValue,
        double currentValue,
        double absoluteChange,
        double relativeChange,
        String direction,        // "increased", "decreased", "stable"
        String affectedStage,
        Severity severity,
        double detectionConfidence,
        String explanation,
        CausalRelation causalRelation
) {}
