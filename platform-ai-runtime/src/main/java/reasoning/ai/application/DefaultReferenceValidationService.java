package reasoning.ai.application;

import reasoning.ai.api.ReferenceValidationService;
import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates authority references by demoting low-confidence entries and ensuring at least one primary reference exists.
 */
@Service
public class DefaultReferenceValidationService implements ReferenceValidationService {

    @Override
    public List<AuthorityReference> validate(List<AuthorityReference> candidates, List<ExtractedConcept> concepts, String query) {
        List<AuthorityReference> validated = new ArrayList<>();
        for (AuthorityReference ref : candidates) {
            // Demote low-confidence references to BACKGROUND
            AuthorityReference.ReferenceTier adjustedTier = ref.tier();
            if (ref.relevanceScore() < 0.3) {
                adjustedTier = AuthorityReference.ReferenceTier.BACKGROUND;
            }

            validated.add(new AuthorityReference(
                    ref.documentId(),
                    ref.chunkId(),
                    ref.referenceId(),
                    ref.domainCode(),
                    ref.entryNumber(),
                    ref.entryTitle(),
                    ref.excerpt(),
                    ref.domain(),
                    ref.basis(),
                    ref.relevanceScore(),
                    ref.retrievalScore(),
                    adjustedTier));
        }

        // Ensure at least one PRIMARY tier reference exists if any survived validation
        boolean hasPrimary = validated.stream()
                .anyMatch(a -> a.tier() == AuthorityReference.ReferenceTier.PRIMARY);
        if (!hasPrimary && !validated.isEmpty()) {
            AuthorityReference top = validated.getFirst();
            validated.set(0, new AuthorityReference(
                    top.documentId(), top.chunkId(), top.referenceId(),
                    top.domainCode(), top.entryNumber(), top.entryTitle(),
                    top.excerpt(), top.domain(), top.basis(),
                    top.relevanceScore(), top.retrievalScore(),
                    AuthorityReference.ReferenceTier.PRIMARY));
        }

        return validated;
    }
}
