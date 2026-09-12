package verwaltungsassistent.web.evaluation.runner;

import verwaltungsassistent.web.evaluation.model.EvalModels.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML evaluation dashboard.
 * No external dependencies — pure HTML with inline CSS and SVG charts.
 */
final class HtmlReportGenerator {

    private HtmlReportGenerator() {}

    static String generate(EvalSummary summary) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
         .append("<title>Evaluationsbericht — Kommunaler Entscheidungsassistent</title>");
        css(h);
        h.append("</head><body>");

        header(h, summary);
        overallScore(h, summary);
        domainScores(h, summary);
        aggregateMetrics(h, summary);
        regressions(h, summary);
        calibration(h, summary);
        hallucination(h, summary);
        ruleCoverage(h, summary);
        topFailures(h, summary);
        worstBenchmarks(h, summary);
        allResults(h, summary);

        h.append("<footer class=\"report-footer\">")
         .append("<p>Evaluationsbericht erstellt am ").append(summary.timestamp()).append("</p>")
         .append("<p>Kommunaler Entscheidungsassistent — Automatisierte Qualitätssicherung</p>")
         .append("</footer></body></html>");
        return h.toString();
    }

    private static void header(StringBuilder h, EvalSummary s) {
        String color = s.overallScore() >= 0.8 ? "#38a169" : s.overallScore() >= 0.6 ? "#d69e2e" : "#e53e3e";
        h.append("<header class=\"report-header\">")
         .append("<h1>Evaluationsbericht</h1>")
         .append("<p>Kommunaler Entscheidungsassistent — Qualitätssicherung</p>")
         .append("<div class=\"overall-badge\" style=\"background:").append(color).append(";\">")
         .append(String.format("%.0f%%", s.overallScore() * 100)).append("</div>")
         .append("</header>");
    }

    private static void overallScore(StringBuilder h, EvalSummary s) {
        h.append("<section class=\"card\"><h2>Gesamtergebnis</h2>")
         .append("<div class=\"summary-grid\">")
         .append(metricCard("Fälle gesamt", String.valueOf(s.totalCases()), "#3182ce"))
         .append(metricCard("Bestanden", String.valueOf(s.passed()), "#38a169"))
         .append(metricCard("Fehlgeschlagen", String.valueOf(s.failed()), "#e53e3e"))
         .append(metricCard("Erfolgsquote", String.format("%.0f%%", s.overallScore() * 100),
                    s.overallScore() >= 0.8 ? "#38a169" : s.overallScore() >= 0.6 ? "#d69e2e" : "#e53e3e"))
         .append("</div>");

        // Trend bar
        if (s.aggregates() != null) {
            AggregateMetrics a = s.aggregates();
            h.append("<div class=\"metric-bars\">")
             .append(barRow("Precision@5", a.avgPrecisionAt5()))
             .append(barRow("Recall@5", a.avgRecallAt5()))
             .append(barRow("MRR", a.avgMrr()))
             .append(barRow("Grounding", a.avgGroundingScore()))
             .append(barRow("Rule Trigger Rate", a.ruleTriggerRate()))
             .append(barRow("Durchschn. Latenz", a.avgLatencyMs() / 1000.0, "s", 30.0))
             .append("</div>");
        }
        h.append("</section>");
    }

    private static void domainScores(StringBuilder h, EvalSummary s) {
        if (s.domains() == null || s.domains().isEmpty()) return;
        h.append("<section class=\"card\"><h2>Domänen-Ergebnisse</h2>")
         .append("<table class=\"data-table\"><thead><tr>")
         .append("<th>Domäne</th><th>Score</th><th>Bestanden</th><th>Fehlgeschlagen</th>")
         .append("<th>Ø Retrieval</th><th>Ø Grounding</th><th>Ø Latenz</th>")
         .append("</tr></thead><tbody>");

        for (var e : s.domains().entrySet()) {
            DomainSummary d = e.getValue();
            String sc = d.score() >= 0.8 ? "#38a169" : d.score() >= 0.6 ? "#d69e2e" : "#e53e3e";
            h.append("<tr><td><strong>").append(domainLabel(e.getKey())).append("</strong></td>")
             .append("<td><span class=\"badge\" style=\"background:").append(sc).append(";\">")
             .append(String.format("%.0f%%", d.score() * 100)).append("</span></td>")
             .append("<td>").append(d.passed()).append("</td>")
             .append("<td>").append(d.failed()).append("</td>")
             .append("<td>").append(String.format("%.2f", d.avgRetrieval())).append("</td>")
             .append("<td>").append(String.format("%.2f", d.avgGrounding())).append("</td>")
             .append("<td>").append(String.format("%.0f ms", d.avgLatencyMs())).append("</td>")
             .append("</tr>");
        }
        h.append("</tbody></table></section>");
    }

    private static void aggregateMetrics(StringBuilder h, EvalSummary s) {
        if (s.aggregates() == null) return;
        AggregateMetrics a = s.aggregates();
        h.append("<section class=\"card\"><h2>Aggregierte Metriken</h2>")
         .append("<div class=\"metric-grid\">")
         .append(metricRow("Precision@5", String.format("%.3f", a.avgPrecisionAt5())))
         .append(metricRow("Recall@5", String.format("%.3f", a.avgRecallAt5())))
         .append(metricRow("MRR", String.format("%.3f", a.avgMrr())))
         .append(metricRow("Grounding Score", String.format("%.3f", a.avgGroundingScore())))
         .append(metricRow("Durchschn. Konfidenz", String.format("%.3f", a.avgConfidence())))
         .append(metricRow("Durchschn. Latenz", String.format("%.0f ms", a.avgLatencyMs())))
         .append(metricRow("Rule Trigger Rate", String.format("%.0f%%", a.ruleTriggerRate() * 100)))
         .append(metricRow("Erfolgsquote", String.format("%.0f%%", a.passRate() * 100)))
         .append("</div></section>");
    }

    private static void regressions(StringBuilder h, EvalSummary s) {
        if (s.regressions() == null || s.regressions().isEmpty()) {
            h.append("<section class=\"card\"><h2>Regressionen</h2>")
             .append("<p class=\"text-ok\">Keine Regressionen erkannt.</p></section>");
            return;
        }
        h.append("<section class=\"card\"><h2>⚠ Regressionen</h2><ul class=\"regression-list\">");
        for (RegressionAlert r : s.regressions()) {
            String sev = "HIGH".equals(r.severity()) ? "#e53e3e" : "#d69e2e";
            h.append("<li style=\"border-left: 3px solid ").append(sev).append(";\">")
             .append("<strong>[").append(r.severity()).append("] ").append(r.metric()).append("</strong>: ")
             .append(String.format("%.3f → %.3f (Schwelle: %.3f)", r.previousValue(), r.currentValue(), r.threshold()))
             .append("</li>");
        }
        h.append("</ul></section>");
    }

    private static void calibration(StringBuilder h, EvalSummary s) {
        if (s.calibrationReport() == null || s.calibrationReport().buckets().isEmpty()) return;
        h.append("<section class=\"card\"><h2>Konfidenz-Kalibrierung</h2>");
        if (s.calibrationReport().overconfident()) {
            h.append("<p class=\"text-warn\">⚠ Systematische Überkonfidenz erkannt</p>");
        }
        h.append("<table class=\"data-table\"><thead><tr>")
         .append("<th>Konfidenz-Bereich</th><th>Fälle</th><th>Korrekt</th><th>Genauigkeit</th>")
         .append("</tr></thead><tbody>");
        for (var b : s.calibrationReport().buckets().values()) {
            String sc = b.accuracy() >= 0.8 ? "#38a169" : b.accuracy() >= 0.5 ? "#d69e2e" : "#e53e3e";
            h.append("<tr><td>").append(b.range()).append("</td>")
             .append("<td>").append(b.total()).append("</td>")
             .append("<td>").append(b.correct()).append("</td>")
             .append("<td><span class=\"badge\" style=\"background:").append(sc).append(";\">")
             .append(String.format("%.0f%%", b.accuracy() * 100)).append("</span></td></tr>");
        }
        h.append("</tbody></table></section>");
    }

    private static void hallucination(StringBuilder h, EvalSummary s) {
        if (s.hallucinationReport() == null) return;
        HallucinationReport hr = s.hallucinationReport();
        h.append("<section class=\"card\"><h2>Halluzinations-Erkennung</h2>")
         .append("<div class=\"metric-grid\">")
         .append(metricRow("Empfehlungen ohne Belege", String.valueOf(hr.recommendationsWithoutEvidence())))
         .append(metricRow("Feststellungen ohne Zitate", String.valueOf(hr.findingsWithoutCitations())))
         .append(metricRow("Rechtsref. ohne Belege", String.valueOf(hr.legalRefsNotInEvidence())))
         .append(metricRow("Autoritäten ohne Doku", String.valueOf(hr.authorityRefsWithoutDocs())))
         .append(metricRow("Halluzinationsrate", String.format("%.1f%%", hr.hallucinationRate() * 100)))
         .append("</div></section>");
    }

    private static void ruleCoverage(StringBuilder h, EvalSummary s) {
        if (s.ruleCoverageReport() == null) return;
        RuleCoverageReport rc = s.ruleCoverageReport();
        h.append("<section class=\"card\"><h2>Regelabdeckung</h2>");
        if (!rc.neverExercisedRules().isEmpty()) {
            h.append("<div class=\"text-warn\"><strong>Nie ausgeführte Regeln:</strong> ")
             .append(String.join(", ", rc.neverExercisedRules())).append("</div>");
        }
        if (!rc.entries().isEmpty()) {
            h.append("<table class=\"data-table\"><thead><tr>")
             .append("<th>Regel</th><th>Evaluiert</th><th>Ausgeführt</th><th>Erfolg</th><th>Fehler</th><th>Domänen</th>")
             .append("</tr></thead><tbody>");
            for (var e : rc.entries()) {
                h.append("<tr><td>").append(e.rule()).append("</td>")
                 .append("<td>").append(e.evaluated()).append("</td>")
                 .append("<td>").append(e.executed()).append("</td>")
                 .append("<td>").append(e.succeeded()).append("</td>")
                 .append("<td>").append(e.failed()).append("</td>")
                 .append("<td>").append(String.join(", ", e.domains())).append("</td></tr>");
            }
            h.append("</tbody></table>");
        }
        h.append("</section>");
    }

    private static void topFailures(StringBuilder h, EvalSummary s) {
        List<EvalResult> failures = s.results().stream()
                .filter(r -> !r.passed())
                .sorted(Comparator.comparingDouble(EvalResult::overallScore))
                .limit(10).toList();
        if (failures.isEmpty()) return;
        h.append("<section class=\"card\"><h2>Top-Fehler</h2>");
        for (EvalResult r : failures) {
            h.append("<div class=\"failure-item\">")
             .append("<strong>").append(r.benchmarkId()).append("</strong> ")
             .append("(").append(domainLabel(r.domain())).append(" — ").append(r.difficulty()).append(") ")
             .append(String.format("Score: %.1f%%", r.overallScore() * 100))
             .append("<ul>");
            for (String f : r.failures()) {
                h.append("<li>").append(f).append("</li>");
            }
            h.append("</ul></div>");
        }
        h.append("</section>");
    }

    private static void worstBenchmarks(StringBuilder h, EvalSummary s) {
        List<EvalResult> worst = s.results().stream()
                .sorted(Comparator.comparingDouble(EvalResult::overallScore))
                .limit(5).toList();
        if (worst.isEmpty()) return;
        h.append("<section class=\"card\"><h2>Schwächste Benchmark-Fragen</h2>")
         .append("<table class=\"data-table\"><thead><tr>")
         .append("<th>ID</th><th>Domäne</th><th>Schwierigkeit</th><th>Score</th><th>Gründe</th>")
         .append("</tr></thead><tbody>");
        for (EvalResult r : worst) {
            h.append("<tr><td>").append(r.benchmarkId()).append("</td>")
             .append("<td>").append(domainLabel(r.domain())).append("</td>")
             .append("<td>").append(r.difficulty()).append("</td>")
             .append("<td>").append(String.format("%.0f%%", r.overallScore() * 100)).append("</td>")
             .append("<td>").append(String.join("; ", r.failures())).append("</td></tr>");
        }
        h.append("</tbody></table></section>");
    }

    private static void allResults(StringBuilder h, EvalSummary s) {
        h.append("<section class=\"card\"><h2>Alle Ergebnisse (").append(s.totalCases()).append(")</h2>")
         .append("<table class=\"data-table\"><thead><tr>")
         .append("<th>ID</th><th>Domäne</th><th>Ergebnis</th><th>Score</th>")
         .append("<th>P@5</th><th>Grounding</th><th>Latenz</th><th>Warnungen</th>")
         .append("</tr></thead><tbody>");
        for (EvalResult r : s.results()) {
            String bg = r.passed() ? "#f0fff4" : "#fff5f5";
            h.append("<tr style=\"background:").append(bg).append(";\">")
             .append("<td>").append(r.benchmarkId()).append("</td>")
             .append("<td>").append(domainLabel(r.domain())).append("</td>")
             .append("<td>").append(r.passed() ? "✅" : "❌").append("</td>")
             .append("<td>").append(String.format("%.0f%%", r.overallScore() * 100)).append("</td>")
             .append("<td>").append(String.format("%.2f", r.retrieval().precisionAt5())).append("</td>")
             .append("<td>").append(String.format("%.2f", r.grounding().groundingScore())).append("</td>")
             .append("<td>").append(r.performance().totalMs()).append("ms</td>")
             .append("<td>").append(r.warnings().size()).append("</td></tr>");
        }
        h.append("</tbody></table></section>");
    }

    // ── Helpers ──

    private static String metricCard(String label, String value, String color) {
        return String.format("<div class=\"metric-card\"><span class=\"metric-value\" style=\"color:%s;\">%s</span><span class=\"metric-label\">%s</span></div>", color, value, label);
    }

    private static String metricRow(String label, String value) {
        return String.format("<div class=\"metric-row\"><span class=\"metric-label\">%s</span><span class=\"metric-value-small\">%s</span></div>", label, value);
    }

    private static String barRow(String label, double value) {
        return barRow(label, value, "", 1.0);
    }

    private static String barRow(String label, double value, String unit, double max) {
        double pct = Math.min(100, (value / max) * 100);
        String color = pct >= 70 ? "#38a169" : pct >= 40 ? "#d69e2e" : "#e53e3e";
        return String.format("<div class=\"bar-row\"><span class=\"bar-label\">%s</span><div class=\"bar-track\"><div class=\"bar-fill\" style=\"width:%.0f%%;background:%s;\"></div></div><span class=\"bar-value\">%s%s</span></div>", label, pct, color, value < 10 ? String.format("%.3f", value) : String.format("%.1f", value), unit);
    }

    static String domainLabel(String domain) {
        return switch (domain) {
            case "procurement" -> "Vergabewesen";
            case "travel" -> "Reisekosten";
            case "hr" -> "Personal";
            case "building" -> "Bauordnung";
            default -> domain;
        };
    }

    // ── Inline CSS ──

    private static void css(StringBuilder h) {
        h.append("<style>")
         .append("*{box-sizing:border-box;margin:0;padding:0}")
         .append("body{font-family:system-ui,-apple-system,sans-serif;background:#f7f8fa;color:#1a202c;line-height:1.5;padding:1rem}")
         .append(".report-header{background:#fff;padding:1.5rem 2rem;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.1);margin-bottom:1.5rem;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:1rem}")
         .append(".report-header h1{font-size:1.5rem;color:#1a202c}")
         .append(".report-header p{color:#718096;font-size:.875rem}")
         .append(".overall-badge{color:#fff;font-size:1.75rem;font-weight:700;padding:.75rem 1.5rem;border-radius:8px;min-width:100px;text-align:center}")
         .append(".card{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.1);padding:1.25rem 1.5rem;margin-bottom:1rem}")
         .append(".card h2{font-size:1.125rem;margin-bottom:1rem;padding-bottom:.5rem;border-bottom:2px solid #edf2f7}")
         .append(".summary-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:1rem;margin-bottom:1rem}")
         .append(".metric-card{text-align:center;padding:1rem;background:#f7f8fa;border-radius:6px}")
         .append(".metric-value{font-size:1.75rem;font-weight:700}")
         .append(".metric-label{font-size:.75rem;color:#718096;text-transform:uppercase;letter-spacing:.04em;display:block;margin-top:.25rem}")
         .append(".metric-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:.5rem 1.5rem}")
         .append(".metric-row{display:flex;justify-content:space-between;padding:.375rem 0;border-bottom:1px solid #edf2f7;font-size:.875rem}")
         .append(".metric-value-small{font-weight:600}")
         .append(".metric-bars{display:flex;flex-direction:column;gap:.5rem}")
         .append(".bar-row{display:flex;align-items:center;gap:.75rem;font-size:.8125rem}")
         .append(".bar-label{min-width:130px;color:#718096}")
         .append(".bar-track{flex:1;height:10px;background:#edf2f7;border-radius:5px;overflow:hidden}")
         .append(".bar-fill{height:100%;border-radius:5px;transition:width .4s}")
         .append(".bar-value{min-width:60px;text-align:right;font-weight:600;font-size:.75rem}")
         .append(".data-table{width:100%;border-collapse:collapse;font-size:.8125rem}")
         .append(".data-table th{text-align:left;padding:.5rem .75rem;background:#f7f8fa;font-weight:600;font-size:.75rem;text-transform:uppercase;letter-spacing:.03em;color:#718096;border-bottom:2px solid #edf2f7}")
         .append(".data-table td{padding:.5rem .75rem;border-bottom:1px solid #edf2f7}")
         .append(".data-table tr:hover{background:#f7f8fa}")
         .append(".badge{display:inline-block;color:#fff;padding:.125rem .5rem;border-radius:12px;font-size:.6875rem;font-weight:600}")
         .append(".text-ok{color:#38a169;font-size:.875rem}")
         .append(".text-warn{color:#d69e2e;font-size:.875rem;padding:.5rem;background:#fffdf0;border-radius:4px;margin-bottom:.5rem}")
         .append(".regression-list{list-style:none;display:flex;flex-direction:column;gap:.5rem}")
         .append(".regression-list li{padding:.5rem .75rem;background:#fff5f5;border-radius:4px;font-size:.8125rem}")
         .append(".failure-item{padding:.75rem;background:#fff5f5;border-radius:4px;margin-bottom:.5rem;font-size:.8125rem}")
         .append(".failure-item ul{margin-top:.25rem;padding-left:1.25rem}")
         .append(".report-footer{text-align:center;padding:2rem 1rem;color:#a0aec0;font-size:.75rem}")
         .append("@media(max-width:768px){.report-header{flex-direction:column;text-align:center}.summary-grid{grid-template-columns:1fr}.data-table{font-size:.6875rem}}")
         .append("</style>");
    }
}
