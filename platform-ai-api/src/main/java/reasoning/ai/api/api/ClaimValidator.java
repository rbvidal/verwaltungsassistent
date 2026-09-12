package reasoning.ai.api;

import reasoning.ai.model.AuthorityReference;

import java.util.List;

/**
 * Validates that claims in an answer are supported by the available authority references.
 */
public interface ClaimValidator {

    /**
     * The result of a claim validation check, listing supported and unsupported claims.
     */
    record ClaimCheckResult(boolean valid, List<String> unsupportedClaims, List<String> validClaims) {}

    /**
     * Validates the claims in an answer against the available authority references.
     */
    ClaimCheckResult validate(String answer, List<AuthorityReference> availableReferences);
}
