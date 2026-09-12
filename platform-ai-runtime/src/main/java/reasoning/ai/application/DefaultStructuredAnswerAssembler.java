package reasoning.ai.application;

import reasoning.ai.api.ClaimValidator;
import reasoning.ai.api.StructuredAnswerAssembler;
import reasoning.ai.model.AuthorityReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Assembles a raw answer into structured sections by detecting section headers in the text.
 */
@Service
public class DefaultStructuredAnswerAssembler implements StructuredAnswerAssembler {

    private static final Logger log = LoggerFactory.getLogger(DefaultStructuredAnswerAssembler.class);

    private final ClaimValidator claimValidator;

    public DefaultStructuredAnswerAssembler(ClaimValidator claimValidator) {
        this.claimValidator = claimValidator;
    }

    private static final Pattern SECTION_BREAK = Pattern.compile(
            "(?i)(STRONGEST POSITION|LEGAL BASIS|PRACTICAL STEPS|RISKS|ALTERNATIVES|BOTTOM LINE|" +
            "STRONGEST|FRAMEWORK|STEPS|RISKS AND LIMITATIONS|ALTERNATIVE|CONCLUSION)");

    @Override
    public StructuredAnswer assemble(String rawAnswer, String query) {
        String findings = extractSection(rawAnswer, null, "STRONGEST");
        String objective = "";
        String norms = extractSection(rawAnswer, "LEGAL|FRAMEWORK", "PRACTICAL|STEPS");
        String strongest = extractSection(rawAnswer, "STRONGEST", "LEGAL|PRACTICAL|FRAMEWORK");
        String steps = extractSection(rawAnswer, "PRACTICAL|STEPS", "RISKS|ALTERNATIVES|BOTTOM|CONCLUSION");
        String risks = extractSection(rawAnswer, "RISKS", "ALTERNATIVES|BOTTOM|CONCLUSION");
        String bottomLine = extractSection(rawAnswer, "BOTTOM|CONCLUSION", null);

        if (strongest.isBlank() && norms.isBlank()) {
            strongest = rawAnswer.length() > 1500 ? rawAnswer.substring(0, 1500) + "..." : rawAnswer;
        }

        return new StructuredAnswer(
                findings,
                objective,
                norms,
                strongest,
                steps,
                risks,
                bottomLine,
                "This is not professional advice.",
                true);
    }

    /**
     * Assembles a structured answer and enriches it with claim validation against authority references.
     */
    public StructuredAnswer assembleWithAuthorities(String rawAnswer, String query,
                                                      List<AuthorityReference> references) {
        StructuredAnswer base = assemble(rawAnswer, query);

        var claimCheck = claimValidator.validate(rawAnswer, references);
        boolean fullyValidated = base.fullyValidated() && claimCheck.valid();

        StringBuilder enrichedRisks = new StringBuilder(base.risksAndLimitations());
        if (!claimCheck.unsupportedClaims().isEmpty()) {
            enrichedRisks.append("\n[Reference validation: ").append(String.join("; ", claimCheck.unsupportedClaims())).append("]");
        }

        return new StructuredAnswer(
                base.factualFindings(), base.objective(), base.governingNorms(),
                base.strongestPosition(), base.practicalSteps(),
                enrichedRisks.toString(), base.bottomLine(), base.disclaimer(),
                fullyValidated);
    }

    private String extractSection(String text, String startPattern, String endPattern) {
        if (text == null || text.isBlank()) return "";

        String[] lines = text.split("\\n");
        StringBuilder section = new StringBuilder();
        boolean inSection = (startPattern == null);

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (startPattern != null && SECTION_BREAK.matcher(trimmed).find()
                    && trimmed.toUpperCase().matches(".*" + startPattern + ".*")) {
                inSection = true;
                continue;
            }

            if (inSection && endPattern != null && SECTION_BREAK.matcher(trimmed).find()
                    && trimmed.toUpperCase().matches(".*" + endPattern + ".*")) {
                break;
            }

            if (inSection) {
                section.append(trimmed).append("\n");
            }
        }

        return section.toString().trim();
    }
}
