package reasoning.ai.application;

import reasoning.ai.api.PromptBuilder;
import reasoning.ai.model.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Builds a compact German-language prompt with strict evidence-first reasoning.
 *
 * <p>Design principles:
 * <ul>
 *   <li>Max 3-4 unique document sources in the prompt</li>
 *   <li>Each document contributes only its most relevant excerpt (max 500 chars)</li>
 *   <li>No duplicate evidence — grouped by document</li>
 *   <li>Target: under 4,000 characters total</li>
 *   <li>LLM explains results; deterministic rules handle calculations</li>
 * </ul>
 */
@Component
public class DefaultPromptBuilder implements PromptBuilder {

    private static final String TEMPLATE_VERSION = "compact-v9";

    @Override
    public String build(PromptContext context) {
        return build(context, null);
    }

    @Override
    public String build(PromptContext context, String userLanguage) {
        EvidencePackage evidence = context.evidencePackage();
        boolean insufficient = evidence == null || evidence.isEmpty();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Sie sind ein Verwaltungsassistent. Antworten Sie NUR mit den bereitgestellten Beweisstücken. Kein eigenes Wissen.\n");
        if (!AnswerLanguage.germanDefault(userLanguage)) {
            prompt.append("ANTWORTSPRACHE: ").append(AnswerLanguage.displayName(userLanguage)).append(".\n");
            prompt.append("Die gesamte Antwort (alle Überschriften und Sätze) muss in dieser Sprache verfasst sein.\n");
        }
        prompt.append("\n");
        prompt.append(reasoning.ai.model.PromptBoundary.UNTRUSTED_CONTENT_MARKER).append("\n\n");

        // ═══ EVIDENCE (compact) ═══
        if (insufficient) {
            prompt.append(AnswerLanguage.noDocumentsBlock(userLanguage)).append("\n");
        } else {
            prompt.append("BEWEISSTÜCKE (ausschließliche Quelle):\n");
            LocalDate asOf = evidence.asOf();
            for (EvidenceItem item : evidence.items()) {
                prompt.append("── ").append(item.index()).append(". ")
                      .append(item.documentTitle()).append(" ──\n");
                prompt.append("Behörde: ").append(item.authority()).append("\n");
                prompt.append("Version: ").append(item.documentVersion());
                if (item.pageNumber() != null) {
                    prompt.append(" · Seite: ").append(item.pageNumber());
                }
                prompt.append("\n");
                prompt.append("Gültig von: ").append(item.validFrom() != null ? item.validFrom() : "unbekannt");
                prompt.append(" · Gültig bis: ").append(item.validUntil() != null ? item.validUntil() : "unbekannt");
                prompt.append("\n");
                prompt.append("Stichtag (as of): ").append(asOf != null ? asOf : LocalDate.now()).append("\n");
                prompt.append("Abschnitte: ").append(item.paragraph()).append("\n");
                String excerpt = item.excerpt();
                if (excerpt.length() > 1200) excerpt = excerpt.substring(0, 1200) + "...";
                prompt.append("Text: \"").append(excerpt).append("\"\n");

                if (item.hasNumericData()) {
                    prompt.append("Zahlenwerte (exakt verwenden): ");
                    appendNumericCompact(prompt, item.numericExtraction());
                }
                prompt.append("Unterstützt: ").append(item.supports()).append("\n\n");
            }
        }

        // ═══ RULES ═══
        prompt.append("REGELN:\n");
        prompt.append("- Nur Beweisstücke verwenden. Keine eigenen Vorschriften erfinden.\n");
        prompt.append("- Extrahierte Zahlenwerte direkt zitieren, nicht neu berechnen.\n");
        prompt.append("- Bei Widersprüchen: Konflikt benennen, nicht auflösen.\n");
        prompt.append("- Bei fehlenden Informationen: Das klar sagen, nicht erfinden.\n");
        if (AnswerLanguage.germanDefault(userLanguage)) {
            prompt.append("- Sprache: deutsche Kommunalverwaltung. Keine Floskeln.\n\n");
        } else {
            prompt.append("- Antwortsprache: ").append(AnswerLanguage.displayName(userLanguage)).append(".\n");
            prompt.append("- Die gesamte Antwort (einschließlich der Abschnittsüberschriften) in dieser Sprache.\n");
            prompt.append("- Offizielle Bezeichnungen, Dokumenttitel und wörtliche Zitate unverändert lassen;\n");
            prompt.append("  bei Bedarf in der Antwortsprache erklären.\n");
            prompt.append("- Keine Floskeln.\n\n");
        }

        // ═══ QUESTION ═══
        prompt.append("FRAGE: ").append(context.userQuestion()).append("\n\n");

        // ═══ OUTPUT FORMAT (compact) ═══
        prompt.append(AnswerLanguage.retrievalFormatBlock(userLanguage));

        return prompt.toString();
    }

    private void appendNumericCompact(StringBuilder sb, NumericExtraction n) {
        if (!n.salaryGrades().isEmpty()) {
            for (var sg : n.salaryGrades()) {
                if (sg.amount() > 0)
                    sb.append(sg.grade()).append("=").append(formatMoney(sg.amount())).append("€ ");
                else
                    sb.append(sg.grade()).append(" ");
            }
        }
        if (!n.moneyValues().isEmpty()) {
            for (var m : n.moneyValues())
                sb.append(m.label()).append("=").append(formatMoney(m.amount())).append("€ ");
        }
        if (!n.thresholds().isEmpty()) {
            for (var t : n.thresholds())
                sb.append("Grenze:").append(formatMoney(t.amount())).append("€ ");
        }
        if (!n.percentages().isEmpty()) {
            for (var p : n.percentages())
                sb.append(p.value()).append("% ");
        }
        sb.append("\n");
    }

    private String formatMoney(double amount) {
        if (amount == (long) amount) return String.format(java.util.Locale.US, "%.0f", amount);
        return String.format(java.util.Locale.US, "%.2f", amount);
    }

    @Override
    public String templateVersion() {
        return TEMPLATE_VERSION;
    }
}
