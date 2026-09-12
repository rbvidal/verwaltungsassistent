package reasoning.ai.application;

import reasoning.ai.api.ClaimValidator;
import reasoning.ai.model.AuthorityReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates claims by checking that cited references exist in the available authority references.
 */
@Service
public class DefaultClaimValidator implements ClaimValidator {

    private static final Pattern REF_PATTERN = Pattern.compile(
            "§\\s*(\\d+[a-z]?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CLAIM_PATTERN = Pattern.compile(
            "(can|may|must|should|have the right|are entitled|is entitled|" +
            "the party may|the party can)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public ClaimCheckResult validate(String answer, List<AuthorityReference> availableReferences) {
        if (answer == null || answer.isBlank()) {
            return new ClaimCheckResult(true, List.of(), List.of());
        }

        Set<String> availableEntries = new HashSet<>();
        for (AuthorityReference a : availableReferences) {
            availableEntries.add(a.entryNumber());
        }

        List<String> unsupportedClaims = new ArrayList<>();
        List<String> validClaims = new ArrayList<>();

        Set<String> citedEntries = new HashSet<>();
        var refMatcher = REF_PATTERN.matcher(answer);
        while (refMatcher.find()) {
            String entryNum = refMatcher.group(1);
            citedEntries.add(entryNum);
        }

        for (String cited : citedEntries) {
            if (!availableEntries.contains(cited)) {
                unsupportedClaims.add("Ref § " + cited + " is cited but not available in retrieved references");
            } else {
                validClaims.add("§ " + cited);
            }
        }

        var claimMatcher = CLAIM_PATTERN.matcher(answer);
        int claimCount = 0;
        int supportedClaimCount = 0;
        while (claimMatcher.find()) {
            claimCount++;
            int start = Math.max(0, claimMatcher.start() - 200);
            int end = Math.min(answer.length(), claimMatcher.end() + 200);
            String surrounding = answer.substring(start, end);
            if (REF_PATTERN.matcher(surrounding).find()) {
                supportedClaimCount++;
            }
        }

        if (claimCount > 0 && citedEntries.isEmpty()) {
            unsupportedClaims.add("Claims made without any reference citation — all " + claimCount + " claims are unsupported");
        } else if (claimCount > 0 && (double) supportedClaimCount / claimCount < 0.3) {
            unsupportedClaims.add("Only " + supportedClaimCount + " of " + claimCount
                    + " claims are supported by citations");
        }

        boolean valid = unsupportedClaims.isEmpty();
        return new ClaimCheckResult(valid, unsupportedClaims, validClaims);
    }
}
