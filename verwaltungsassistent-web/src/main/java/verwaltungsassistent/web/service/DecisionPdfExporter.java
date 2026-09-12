package verwaltungsassistent.web.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders the decision result model into an administrative PDF.
 *
 * <p>The visual layout is defined by the validated HTML master templates
 * {@code templates/pdf/decision-{green,blue,warning}.html}. The matching
 * template is selected from the analysis result and rendered to A4 PDF via
 * {@link ChromiumPdfRenderer} (Chromium/Playwright).
 */
@Service
public class DecisionPdfExporter {

    private static final DateTimeFormatter EXPORT_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Application version from pom.xml (via {@code app.version}); shown in the PDF footer. */
    @Value("${app.version:1.0.0-RC2}")
    String appVersion;

    private final ChromiumPdfRenderer chromiumRenderer;

    public DecisionPdfExporter(ChromiumPdfRenderer chromiumRenderer) {
        this.chromiumRenderer = chromiumRenderer;
    }

    /**
     * Exports the stored decision model as PDF bytes.
     *
     * @param data decision model built by {@code DecisionWorkspaceController}
     */
    public byte[] export(Map<String, Object> data) throws IOException {
        Map<String, Object> model = new HashMap<>(data);
        enrichModel(model);

        TemplateKind kind = selectTemplate(model);
        String html = loadTemplate(kind);
        Map<String, Object> templateData = buildTemplateData(model);

        return chromiumRenderer.render(html, templateData);
    }

    private void enrichModel(Map<String, Object> model) {
        model.putIfAbsent("appVersion", appVersion);
        model.putIfAbsent("caseName", "");
        model.putIfAbsent("workspaceCode", "");

        String caseName = str(model, "caseName");
        String workspaceCode = str(model, "workspaceCode");
        if (!model.containsKey("aktenzeichen") || str(model, "aktenzeichen").isEmpty()) {
            model.put("aktenzeichen", buildAktenzeichen(caseName, workspaceCode));
        }

        Object documentCount = model.get("documentCount");
        if (documentCount instanceof Number n) {
            model.put("documentCount", String.valueOf(n.intValue()));
        }
        if (model.get("confidenceScore") == null) {
            model.put("confidenceScore", "");
        }
    }

    private String buildAktenzeichen(String caseName, String workspaceCode) {
        if (workspaceCode == null) workspaceCode = "";
        String numeric = workspaceCode.replaceAll("[^0-9]", "");
        if (numeric.isEmpty()) numeric = "000345";
        return "51.10-" + caseTypeAbbreviation(caseName) + "-" + Year.now() + "/" + numeric;
    }

    private String caseTypeAbbreviation(String caseName) {
        if (caseName == null || caseName.isBlank()) return "ALLG";
        String norm = caseName.toUpperCase().replaceAll("[^A-ZÄÖÜß]", "");
        if (norm.length() >= 4) return norm.substring(0, 4);
        return norm + "ALLG".substring(norm.length());
    }

    /**
     * Selects the visual template using the same semantics as the previous
     * PDFBox renderer: green for grounded usable results, warning for
     * fail-closed/insufficient coverage, blue otherwise.
     */
    private TemplateKind selectTemplate(Map<String, Object> model) {
        boolean grounded = Boolean.TRUE.equals(model.get("grounded"));
        String answer = str(model, "decisionAnswer");
        boolean insufficientAnswer = answer != null && answer.contains("keine ausreichenden Informationen");
        boolean noCoreFindings = list(model, "primaryFindings").isEmpty();
        boolean insufficientCoverage = hasRealCoverageIssue(model);
        boolean failClosed = insufficientAnswer || (noCoreFindings && !grounded);

        if (grounded && !failClosed) return TemplateKind.GREEN;
        if (failClosed || insufficientCoverage) return TemplateKind.WARNING;
        return TemplateKind.BLUE;
    }

    /**
     * Coverage issues are produced either as maps (tests, legacy) or as
     * German strings (current pipeline). A mere hint/info issue does not
     * trigger the warning template; everything else does.
     */
    private static boolean hasRealCoverageIssue(Map<String, Object> model) {
        List<Object> issues = rawList(model, "coverageIssues");
        if (issues.isEmpty()) {
            return false;
        }
        return issues.stream().map(i -> {
            if (i instanceof Map<?, ?> m) {
                Object text = m.get("text");
                Object label = m.get("label");
                Object value = text != null ? text : label;
                return value != null ? String.valueOf(value).toLowerCase() : "";
            }
            return String.valueOf(i).toLowerCase();
        }).anyMatch(t -> !t.contains("hinweis") && !t.contains("info"));
    }

    private String loadTemplate(TemplateKind kind) throws IOException {
        String resource = "/templates/pdf/decision-" + kind.resourceName + ".html";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Decision HTML template missing: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Maps the persisted analysis model to the flat keys expected by
     * {@code templates/pdf/fill-template.js}.
     */
    private Map<String, Object> buildTemplateData(Map<String, Object> model) {
        Map<String, Object> data = new HashMap<>();

        String caseName = str(model, "caseName");
        data.put("caseTitle", caseName);
        data.put("processingStatus", str(model, "processingStatus"));
        data.put("confidence", str(model, "confidenceScore"));
        // DOKUMENTE IM VORGANG: Vorgangsdokumente + E-Mail-Referenzen der
        // zugeordneten Bürgerkommunikation ("E-Mail: <Betreff>").
        List<String> documents = new ArrayList<>(strings(model, "documentNames"));
        documents.addAll(strings(model, "emailReferences"));
        data.put("documents", documents);

        // Bilddokumente (Vorschaubilder): [{title, dataUrl}] — generisch, wenn
        // der Aufrufer die Vorschau-Daten liefert; sonst leer.
        List<Object> imageDocs = rawList(model, "documentImages");
        data.put("documentImages", imageDocs);

        data.put("processorName", str(model, "caseOwnerDisplay"));
        data.put("processorRole", str(model, "caseOwnerRole"));
        data.put("processorRoom", str(model, "caseOwnerRoom"));
        data.put("processorPhone", str(model, "caseOwnerPhone"));
        data.put("processorEmail", str(model, "caseOwnerEmail"));

        data.put("vorgangsnummer", str(model, "workspaceCode"));
        data.put("aktenzeichen", str(model, "aktenzeichen"));
        data.put("fallart", str(model, "caseType"));

        Map<String, String> answerSections = parseAnswerSections(str(model, "decisionAnswer"));
        data.put("kurzantwort", answerSections.getOrDefault("kurzantwort", ""));
        data.put("entscheidung", answerSections.getOrDefault("entscheidung", ""));
        data.put("rechtsgrundlage", answerSections.getOrDefault("rechtsgrundlage", ""));
        data.put("recommendation", answerSections.isEmpty() ? str(model, "decisionAnswer") : "");

        data.put("kernfeststellungen", findingDescriptions(model, "primaryFindings"));
        data.put("weitereErkenntnisse", findingDescriptions(model, "secondaryFindings"));
        data.put("offenePunkte", openPoints(model));
        data.put("naechsteSchritte", nextSteps(model, answerSections));
        // Konfidenz-Erläuterung (nur bei tatsächlich reduzierter Konfidenz):
        // Die PDF nennt den Grund, statt die Mitarbeiterin mit einer
        // unerklärten Zahl allein zu lassen.
        data.put("confidenceHint", confidenceHint(model));

        data.put("footerPage", "Seite 1/1");
        data.put("exportDate", model.get("generatedAt") instanceof String s && !s.isBlank()
                ? extractDate(s)
                : LocalDate.now().format(EXPORT_DATE_FMT));

        return data;
    }

    private static String extractDate(String dateTime) {
        int space = dateTime.indexOf(' ');
        return space > 0 ? dateTime.substring(0, space) : dateTime;
    }

    /**
     * Erläuterung der reduzierten Gesamtkonfidenz für die PDF — dieselbe
     * Semantik wie die Erklärbox der Entscheidungsseite: Wurde keine
     * spezifische Rechtsgrundlage abgerufen (structuralConfidence = 0), ist
     * die Gesamtkonfidenz trotz guter Quellenlage bewusst reduziert. Das
     * Konfidenzmodell selbst wird NICHT verändert.
     */
    private static String confidenceHint(Map<String, Object> model) {
        Object confidence = model.get("confidence");
        boolean noLegalBasis = false;
        if (confidence instanceof reasoning.ai.model.ConfidenceProfile cp) {
            noLegalBasis = cp.structuralConfidence() <= 0;
        } else if (confidence instanceof Map<?, ?> m) {
            Object s = m.get("structuralConfidence");
            noLegalBasis = s instanceof Number n && n.doubleValue() <= 0;
        }
        if (!noLegalBasis) {
            return null;
        }
        return "Hinweis: Die Gesamtkonfidenz ist reduziert, da für einzelne Aussagen keine spezifische "
                + "Rechtsgrundlage abgerufen wurde. Nicht durch Belege gedeckte Punkte werden bei der "
                + "Gesamtkonfidenz berücksichtigt.";
    }

    private static List<String> nextSteps(Map<String, Object> model, Map<String, String> answerSections) {
        List<String> steps = findingDescriptions(model, "proceduralFindings");
        if (!steps.isEmpty()) {
            return steps;
        }
        List<String> fromAnswer = new ArrayList<>();
        if (answerSections != null) {
            // "Empfohlene nächste Schritte" sollen HANDLUNGEN enthalten, keine
            // Verfahrensbeschreibungen. VERFAHREN/NÄCHSTER SCHRITT sind nur ein
            // Fallback — Zeilen, die einen Verfahrenszustand schildern statt eine
            // Handlung vorzugeben (z. B. "Kein spezifisches Verfahren vorhanden,
            // aber eine schnelle Instandsetzung ist zu empfehlen."), werden
            // verworfen; bleibt nichts übrig, greift der handlungsorientierte
            // Standard-Fallback.
            if (answerSections.containsKey("verfahren")) {
                fromAnswer.addAll(actionLines(answerSections.get("verfahren")));
            }
            if (answerSections.containsKey("naechsterSchritt")) {
                fromAnswer.addAll(actionLines(answerSections.get("naechsterSchritt")));
            }
        }
        if (!fromAnswer.isEmpty()) {
            return fromAnswer;
        }
        // Same fallback as the UI fragment so the exported PDF never shows an
        // empty "Empfohlene nächste Schritte" section.
        return List.of(
                "Entscheidungsvorlage prüfen: Prüfen Sie die Beschlussempfehlung auf Vollständigkeit und Richtigkeit.",
                "Ggf. weitere Dokumente anfordern: Falls Informationen fehlen, fordern Sie diese bei den zuständigen Stellen an.",
                "Entscheidung dokumentieren: Dokumentieren Sie die getroffene Entscheidung mit Verweis auf diese Vorlage.",
                "Beteiligte informieren: Informieren Sie Antragsteller und beteiligte Fachbereiche über die Entscheidung.");
    }

    private static List<String> splitSectionLines(String section) {
        return Arrays.stream(section.split("\\n"))
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Nächste-Schritte-Zeilen aus einer Antwort-Sektion; verwirft Zeilen, die
     * einen Verfahrenszustand schildern statt eine Handlung vorzugeben.
     */
    private static List<String> actionLines(String section) {
        return splitSectionLines(section).stream()
                .filter(l -> !isProceduralStatement(l))
                .collect(Collectors.toList());
    }

    private static boolean isProceduralStatement(String line) {
        String lower = line.toLowerCase(Locale.GERMANY);
        return lower.contains("kein spezifisches verfahren")
                || lower.contains("kein spezifischer verfahren")
                || lower.contains("ist zu empfehlen")
                || lower.startsWith("das verfahren")
                || lower.startsWith("es ist kein")
                || lower.startsWith("es liegt kein");
    }

    /**
     * Parses the German retrieval answer format into named sections.
     * Only KURZANTWORT, ENTSCHEIDUNG and RECHTSGRUNDLAGE belong in the
     * recommendation box; VERFAHREN and NÄCHSTER SCHRITT are used elsewhere.
     */
    private static Map<String, String> parseAnswerSections(String rawAnswer) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return sections;
        }
        String[] markers = {
                "KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE",
                "VERFAHREN", "NÄCHSTER SCHRITT"
        };
        String[] keys = {
                "kurzantwort", "entscheidung", "rechtsgrundlage",
                "verfahren", "naechsterSchritt"
        };

        List<int[]> hits = new ArrayList<>();
        for (int i = 0; i < markers.length; i++) {
            int idx = findAnswerMarker(rawAnswer, markers[i]);
            if (idx >= 0) {
                hits.add(new int[] { idx, idx + markers[i].length(), i });
            }
        }
        if (hits.isEmpty()) {
            return sections;
        }
        hits.sort(Comparator.comparingInt(a -> a[0]));

        for (int h = 0; h < hits.size(); h++) {
            int contentStart = hits.get(h)[1];
            int contentEnd = rawAnswer.length();
            if (h + 1 < hits.size()) {
                contentEnd = hits.get(h + 1)[0];
            }
            String cleaned = cleanSectionText(rawAnswer.substring(contentStart, contentEnd));
            if (!cleaned.isBlank()) {
                sections.put(keys[hits.get(h)[2]], cleaned);
            }
        }
        return sections;
    }

    private static int findAnswerMarker(String text, String marker) {
        Pattern pattern = Pattern.compile("(?im)(?:^|\\n)\\s*" + Pattern.quote(marker) + "\\b");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            // The match includes optional leading whitespace; return the start
            // of the marker itself, not the start of the whitespace prefix.
            return matcher.end() - marker.length();
        }
        return -1;
    }

    private static String cleanSectionText(String raw) {
        return raw.replaceAll("\\*\\*", "")
                .replaceAll("__", "")
                .replaceAll("\\r\\n?", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    private static List<String> findingDescriptions(Map<String, Object> model, String key) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(model, key)) {
            String label = String.valueOf(f.getOrDefault("label", "")).trim();
            String desc = String.valueOf(f.getOrDefault("description", "")).trim();
            out.add(desc.isBlank() ? label : desc);
        }
        return out;
    }

    private static List<String> openPoints(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Object m : rawList(model, "missingDocs")) {
            String v = openPointText(m, "label", "role");
            if (v != null) out.add(v);
        }
        for (Object c : rawList(model, "coverageIssues")) {
            String v = openPointText(c, "text", "label");
            if (v != null) out.add(v);
        }
        return out;
    }

    private static String openPointText(Object entry, String preferredKey, String fallbackKey) {
        String raw;
        if (entry instanceof String s) {
            raw = s;
        } else if (entry instanceof Map<?, ?> mm) {
            Object preferred = mm.get(preferredKey);
            Object fallback = mm.get(fallbackKey);
            Object value = preferred != null ? preferred : fallback;
            raw = value != null ? String.valueOf(value) : "";
        } else {
            return null;
        }
        String v = raw.trim();
        if (v.isBlank() || "null".equals(v)) {
            return null;
        }
        // Internal role identifiers must never leak into the exported document.
        if (v.equals(entry) && v.matches("^[A-Z_]+$")) {
            return null;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> raw) {
            return (List<Object>) raw;
        }
        return List.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> raw) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map<?, ?> entry) {
                    out.add((Map<String, Object>) entry);
                }
            }
            return out;
        }
        return List.of();
    }

    private static List<String> strings(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> raw) {
            List<String> out = new ArrayList<>();
            for (Object o : raw) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of();
    }

    private enum TemplateKind {
        GREEN("green"),
        BLUE("blue"),
        WARNING("warning");

        final String resourceName;

        TemplateKind(String resourceName) {
            this.resourceName = resourceName;
        }
    }
}
