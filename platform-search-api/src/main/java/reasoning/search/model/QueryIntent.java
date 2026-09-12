package reasoning.search.model;

import reasoning.common.model.DocumentFileType;

import java.util.Map;

/** Classified query intent with document type weights for scoring. */
public record QueryIntent(
        String intent,
        Map<DocumentFileType, Double> documentTypeWeights
) {
    public double weightFor(DocumentFileType type) {
        if (type == null) return 1.0;
        return documentTypeWeights.getOrDefault(type, 1.0);
    }
}
