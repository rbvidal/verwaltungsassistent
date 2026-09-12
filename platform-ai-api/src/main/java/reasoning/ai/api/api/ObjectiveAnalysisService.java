package reasoning.ai.api;

import reasoning.ai.model.AnalysisObjective;

import java.util.List;

/**
 * Classifies a query into a prioritized list of analysis objectives.
 */
public interface ObjectiveAnalysisService {
    /**
     * Classifies a query and returns a list of analysis objectives sorted by priority and confidence.
     */
    List<AnalysisObjective> classify(String query);
}
