package verwaltungsassistent.web.ai;

import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded semantic corpus: LLM-only reliability evaluation with the
 * intentType safety guard in place (Phase 3-5 of the LLM-only experiment).
 *
 * <p>Runs a 128-question multilingual corpus (DE/EN/PT/FR) through the
 * configured semantic parser + DecisionRouter (parser+router level; no E2E).
 * Two modes via concrete subclasses:
 * RUN A — regex parser (baseline), RUN B — LLM parser (candidate).
 *
 * <p>Classification (LLM mode):
 * A completely correct, B correct intent/wrong parameter,
 * C correct parameter/wrong intent, D wrong domain,
 * E ambiguous correctly rejected from deterministic path,
 * F ambiguous incorrectly forced into deterministic path,
 * G unsupported handled correctly, H other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractExpandedSemanticCorpusTest {

    @Autowired protected SemanticIntentParser semanticIntentParser;
    @Autowired protected DecisionRouter decisionRouter;
    @Autowired(required = false) protected AiProviderProperties aiProperties;

    protected abstract String mode();

    protected abstract String reportFileName();

    protected abstract String corpusResource();

    protected record CorpusRow(String id, String language, String category, String question,
                               String expectedDomain, String expectedIntentType,
                               Map<String, String> expectedParams, boolean expectRule,
                               String expectedDecision, String expectedLanguage) {}

    protected List<CorpusRow> corpus = new ArrayList<>();

    private final List<Long> parserMs = new ArrayList<>();
    private final List<Long> routeMs = new ArrayList<>();
    private final Map<String, Integer> classCounts = new LinkedHashMap<>();
    private int passCount = 0;
    private int failCount = 0;
    private int languageOkCount = 0;
    private int languageBadCount = 0;

    protected final StringBuilder R = new StringBuilder();
    private Instant startTime;

    @BeforeAll
    void init() {
        startTime = Instant.now();
        loadCorpus();
        R.append("=".repeat(80)).append("\n");
        R.append("  EXPANDED SEMANTIC CORPUS — ").append(mode()).append(" MODE (parser+router, guard active)\n");
        R.append("  ").append(Instant.now()).append("\n");
        R.append("=".repeat(80)).append("\n");
        R.append("  Corpus: ").append(corpus.size()).append(" queries (DE/EN/PT/FR)\n");
        R.append("  semantic-intent.enabled = ").append(mode().equals("LLM")).append("\n");
        R.append("  Guard: intentType authorizes parameters (TRAVEL_ALLOWANCE→hours, "
            + "PROCUREMENT_THRESHOLD→amountEur, SALARY_LOOKUP→grade/step)\n\n");
    }

    @AfterAll
    void report() {
        R.append("\n").append("=".repeat(80)).append("\n");
        R.append("  SUMMARY — ").append(mode()).append("\n");
        R.append("=".repeat(80)).append("\n");
        for (var e : classCounts.entrySet()) {
            R.append(String.format("  %s: %d%n", e.getKey(), e.getValue()));
        }
        R.append(String.format("  LANGUAGE: %d ok / %d mismatch (rows with expected language)%n",
            languageOkCount, languageBadCount));
        R.append(String.format("  VERDICTS: %d PASS / %d FAIL%n", passCount, failCount));
        appendStats(R, "semantic parser (warmup phase)", parserMs, 1);
        appendStats(R, "routing incl. parse", routeMs, 0);
        long sec = java.time.Duration.between(startTime, Instant.now()).toSeconds();
        R.append("  Total duration: ").append(sec).append("s\n");
        R.append("=".repeat(80)).append("\n");
        writeReport();
        System.out.println(R.toString());
    }

    // ── Corpus loading ──

    private void loadCorpus() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(corpusResource())) {
            if (in == null) throw new IllegalStateException("corpus CSV not found: " + corpusResource());
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] f = line.split("\\|", -1);
                if (f.length < 9) throw new IllegalStateException("bad CSV row " + i + ": " + line);
                Map<String, String> params = new LinkedHashMap<>();
                if (!f[6].isBlank()) {
                    for (String kv : f[6].split(";")) {
                        String[] p = kv.split("=", 2);
                        if (p.length == 2) params.put(p[0].trim(), p[1].trim());
                    }
                }
                corpus.add(new CorpusRow(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(),
                    f[4].trim(), f[5].trim(), params,
                    "RULE_ENGINE".equals(f[7].trim()), f[8].trim(),
                    f.length > 9 ? f[9].trim() : ""));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load corpus CSV", e);
        }
    }

    // ── Parser warmup: cold/warm latency ──

    @Test @Order(1) @DisplayName("PARSER WARMUP")
    void parserWarmup() {
        R.append("-".repeat(80)).append("\n");
        R.append("  PARSER WARMUP PHASE (").append(mode()).append(")\n");
        String model = aiProperties != null ? aiProperties.getOllama().getVerifierModel() : "n/a";
        R.append("  parser model: ").append(model).append("\n");
        for (int i = 0; i < corpus.size(); i++) {
            long t0 = System.currentTimeMillis();
            semanticIntentParser.parse(corpus.get(i).question());
            long ms = System.currentTimeMillis() - t0;
            parserMs.add(ms);
            String tag = i == 0 ? "COLD" : "warm";
            R.append(String.format("  parse %03d [%s]: %dms%n", i + 1, tag, ms));
        }
        R.append("\n");
        writeReport();
    }

    // ── Corpus run ──

    @TestFactory @Order(2)
    List<DynamicTest> corpusTests() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            final int idx = i;
            CorpusRow row = corpus.get(i);
            tests.add(DynamicTest.dynamicTest(
                String.format("%03d %s [%s] %s", idx + 1, row.id(), row.language(), row.category()),
                () -> runQuery(idx)));
        }
        return tests;
    }

    private void runQuery(int idx) {
        CorpusRow row = corpus.get(idx);
        String question = row.question();

        long t0 = System.currentTimeMillis();
        DecisionRouter.RoutingResult routing;
        try {
            routing = decisionRouter.route(question);
        } catch (Exception e) {
            R.append(String.format("  [%03d/%d] %s ROUTE ERROR: %s%n%n",
                idx + 1, corpus.size(), row.id(), e.getMessage()));
            writeReport();
            fail(row.id() + ": routing failed: " + e.getMessage());
            return;
        }
        long rtMs = System.currentTimeMillis() - t0;
        routeMs.add(rtMs);

        StructuredIntent intent = routing.intent();
        String actualIntent = intent != null ? intent.intentType() : "n/a";
        String actualDomain = intent != null && intent.domain() != null
            ? intent.domain().name() : "null";
        Map<String, Object> actualParams = intent != null ? intent.parameters() : Map.of();
        String actualLanguage = intent != null ? intent.language() : null;
        String actualRoute = routing.strategy().name();
        String actualDecision = routing.decision() != null
            ? shortDecision(routing.decision()) : "none";

        // ── Comparison vs ground truth ──
        List<String> problems = new ArrayList<>();
        String cls = classify(row, routing, problems);

        // Language check (informational dimension; LLM mode only)
        boolean languageOk = true;
        if (mode().equals("LLM") && !row.expectedLanguage().isBlank()) {
            languageOk = row.expectedLanguage().equalsIgnoreCase(
                actualLanguage == null ? "" : actualLanguage);
            if (!languageOk) {
                problems.add("language " + actualLanguage + " != " + row.expectedLanguage());
            }
        }

        boolean pass = problems.isEmpty() && clsOk(cls);
        classCounts.merge(cls, 1, Integer::sum);
        if (languageOk) languageOkCount++; else languageBadCount++;
        if (pass) passCount++; else failCount++;

        R.append("-".repeat(80)).append("\n");
        R.append(String.format("  [%03d/%d] %s | %s | %s (%dms)%n",
            idx + 1, corpus.size(), row.id(), row.language(), row.category(), rtMs));
        R.append("  Q: ").append(question).append("\n");
        R.append(String.format("  actual:   intent=%s domain=%s lang=%s params=%s route=%s decision=%s%n",
            actualIntent, actualDomain, actualLanguage, actualParams, actualRoute, actualDecision));
        R.append(String.format("  expected: intent=%s domain=%s lang=%s params=%s route=%s decision=%s%n",
            row.expectedIntentType(), row.expectedDomain(),
            row.expectedLanguage().isBlank() ? "-" : row.expectedLanguage(),
            row.expectedParams(),
            row.expectRule() ? "RULE_ENGINE" : "NO_RULE",
            row.expectedDecision().isBlank() ? "-" : row.expectedDecision()));
        R.append("  CLASS: ").append(cls);
        if (!problems.isEmpty()) {
            R.append("  (").append(String.join("; ", problems)).append(")");
        }
        R.append("\n\n");
        writeReport();

        // ── Assertions ──
        List<Executable> checks = new ArrayList<>();
        if (row.category().equals("AMBIGUOUS") || row.category().equals("UNSUPPORTED")
                || row.category().equals("BUILDING") || row.category().equals("RETRIEVAL")) {
            checks.add(() -> assertNotEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
                row.id() + ": must NOT force a deterministic answer"));
        } else {
            if (row.expectRule()) {
                checks.add(() -> assertEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
                    row.id() + ": expected RULE_ENGINE"));
                if (!row.expectedDecision().isBlank()) {
                    checks.add(() -> {
                        assertNotNull(routing.decision(), row.id() + ": expected a decision");
                        assertTrue(decisionMatches(routing.decision(), row.expectedDecision()),
                            row.id() + ": decision '" + routing.decision().decision()
                                + "' must match '" + row.expectedDecision() + "'");
                    });
                }
            } else {
                checks.add(() -> assertNotEquals(DecisionStrategy.RULE_ENGINE, routing.strategy(),
                    row.id() + ": expected no deterministic rule"));
            }
        }
        if (mode().equals("LLM") && !row.expectedLanguage().isBlank()) {
            checks.add(() -> assertEquals(row.expectedLanguage().toLowerCase(),
                actualLanguage == null ? null : actualLanguage.toLowerCase(),
                row.id() + ": expected language " + row.expectedLanguage()));
        }
        try {
            assertAll(row.id(), checks);
        } catch (AssertionError ignored) {
            // counted via pass/fail; details already in report
        }
    }

    // ── Classification ──

    private String classify(CorpusRow row, DecisionRouter.RoutingResult routing,
                            List<String> problems) {
        boolean forced = routing.strategy() == DecisionStrategy.RULE_ENGINE;
        String cat = row.category();
        if (cat.equals("AMBIGUOUS")) {
            return forced ? "F" : "E";
        }
        if (cat.equals("UNSUPPORTED")) {
            return forced ? "H (unsupported forced)" : "G";
        }
        if (cat.equals("BUILDING") || cat.equals("RETRIEVAL")) {
            if (forced) {
                problems.add("forced deterministic answer");
                return "H (forced)";
            }
            return "A";
        }

        // TRAVEL / SALARY / PROCUREMENT — strict structured-intent comparison
        StructuredIntent intent = routing.intent();
        if (intent == null) {
            problems.add("no intent produced");
            return "H";
        }

        // Domain (LLM mode only — regex never produces a domain by design)
        if (!mode().equals("REGEX")) {
            String actualDomain = intent.domain() != null ? intent.domain().name() : "null";
            if (!row.expectedDomain().equals(actualDomain)) {
                problems.add("domain " + actualDomain + " != " + row.expectedDomain());
                return "D";
            }
        }

        if (!row.expectedIntentType().equals(intent.intentType())) {
            problems.add("intentType " + intent.intentType() + " != " + row.expectedIntentType());
            return "C";
        }

        Map<String, Object> actual = intent.parameters();
        for (var e : row.expectedParams().entrySet()) {
            if (!paramMatches(e.getKey(), e.getValue(), actual.get(e.getKey()))) {
                problems.add("param " + e.getKey() + ": expected " + e.getValue()
                    + " but was " + actual.get(e.getKey()));
                return "B";
            }
        }
        for (String extra : new String[]{"hours", "distanceKm", "amountEur", "salaryGrade", "salaryStep"}) {
            if (actual.containsKey(extra) && !row.expectedParams().containsKey(extra)) {
                problems.add("unexpected param " + extra + "=" + actual.get(extra) + " (guard-neutral)");
            }
        }

        if (row.expectRule()) {
            if (!forced) {
                problems.add("expected RULE_ENGINE but was " + routing.strategy());
                return "H (wrong route)";
            }
            if (!row.expectedDecision().isBlank()
                    && !decisionMatches(routing.decision(), row.expectedDecision())) {
                problems.add("decision mismatch: " + (routing.decision() != null
                    ? routing.decision().decision() : "null"));
                return "H (wrong decision)";
            }
        } else {
            if (forced) {
                problems.add("unexpected forced deterministic answer");
                return "H (forced)";
            }
        }
        return "A";
    }

    /** True when the class letter represents a successful outcome. */
    private static boolean clsOk(String cls) {
        return cls.startsWith("A") || cls.startsWith("E") || cls.startsWith("G");
    }

    // ── Helpers ──

    private static boolean paramMatches(String key, String expected, Object actual) {
        if (actual == null) return false;
        if (key.equals("salaryGrade")) {
            String normExp = expected.toUpperCase().replace(" ", "").replaceFirst("^EG", "");
            String normAct = String.valueOf(actual).toUpperCase().replace(" ", "").replaceFirst("^EG", "");
            return normExp.equals(normAct);
        }
        if (key.equals("salaryStep")) {
            try {
                return Integer.parseInt(expected) == ((Number) actual).intValue();
            } catch (Exception e) {
                return false;
            }
        }
        try {
            double exp = Double.parseDouble(expected);
            double act = ((Number) actual).doubleValue();
            return Math.abs(exp - act) < 1e-6;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean decisionMatches(DecisionResult d, String expected) {
        if (d == null) return false;
        if (d instanceof DecisionResult.ProcurementDecision pd) {
            return pd.procedure().equals(expected);
        }
        return d.decision() != null && d.decision().contains(expected);
    }

    private static String shortDecision(DecisionResult d) {
        if (d instanceof DecisionResult.TravelDecision td) {
            if ("mileage".equals(td.category())) {
                return "Kilometerpauschale " + fmt(td.allowanceEur()) + " €/km";
            }
            return "Tagegeld " + fmt(td.allowanceEur()) + " € / " + fmt(td.hours()) + "h";
        }
        if (d instanceof DecisionResult.SalaryDecision sd) {
            return sd.grade() + " Stufe " + sd.step() + " = " + fmt(sd.monthlyAmount()) + " €";
        }
        if (d instanceof DecisionResult.ProcurementDecision pd) {
            return pd.procedure() + " (" + fmt(pd.amount()) + " €)";
        }
        return d.getClass().getSimpleName();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static void appendStats(StringBuilder sb, String label, List<Long> values, int skipFirst) {
        if (values.isEmpty() || values.size() <= skipFirst) {
            sb.append(String.format("  %-34s: no data%n", label));
            return;
        }
        List<Long> v = values.subList(skipFirst, values.size());
        List<Long> sorted = new ArrayList<>(v);
        Collections.sort(sorted);
        long sum = 0;
        for (long x : v) sum += x;
        double avg = sum / (double) v.size();
        double median = sorted.size() % 2 == 1
            ? sorted.get(sorted.size() / 2)
            : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
        sb.append(String.format("  %-34s: n=%d avg=%.0fms median=%.0fms min=%dms max=%dms%n",
            label, v.size(), avg, median, sorted.get(0), sorted.get(sorted.size() - 1)));
    }

    protected void writeReport() {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/" + reportFileName()), R.toString());
        } catch (IOException ignored) {}
    }
}
