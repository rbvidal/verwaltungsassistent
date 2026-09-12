package verwaltungsassistent.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalisiert das strukturierte Analyse-Ergebnis, BEVOR es gerendert oder
 * exportiert wird (neue und gespeicherte Analysen gleichermaßen).
 *
 * <ul>
 *   <li>{@link #removePureDocumentReferences}: reine Dokumentverweise (z. B.
 *       "info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen") sind
 *       KEINE Feststellungen zum Sachverhalt und gehören nicht in
 *       Kernfeststellungen / Weitere Erkenntnisse / RECHTSGRUNDLAGE — sie
 *       gehören in die Beleg-/Quellenliste. Legitime Feststellungen, die ein
 *       Dokument nur erwähnen, bleiben erhalten.</li>
 *   <li>{@link #removeLegalBasisDuplicates}: "Weitere Erkenntnisse" dürfen die
 *       Rechtsgrundlage nicht duplizieren.</li>
 * </ul>
 */
@Component
public class AnalysisResultSanitizer {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultSanitizer.class);

    /** Max. Länge des Resttextes nach dem Dokumenttitel, der noch als "nur Verweis" gilt. */
    private static final int MAX_REFERENCE_REMAINDER = 40;

    private static final String[] ANSWER_MARKERS = {
            "KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE", "VERFAHREN", "NÄCHSTER SCHRITT"
    };

    /** Führt alle Normalisierungen eines Analyse-Ergebnisses aus (idempotent). */
    @SuppressWarnings("unchecked")
    public void sanitize(Map<String, Object> m) {
        if (m == null) {
            return;
        }
        removePureDocumentReferences(m);
        removeLegalBasisDuplicates(m);
    }

    // ── Reine Dokumentverweise aus Feststellungen / Rechtsgrundlage ─────────

    /**
     * Entfernt reine Dokumentverweise aus Kernfeststellungen (primaryFindings),
     * Weitere Erkenntnisse (secondaryFindings), den Rechtsgrundlagen-Referenzen
     * (authorities) und aus dem RECHTSGRUNDLAGE-Abschnitt der Antwort.
     */
    @SuppressWarnings("unchecked")
    void removePureDocumentReferences(Map<String, Object> m) {
        List<String> titles = documentTitles(m);
        // Kein Früh-Return bei leerer Belegliste: reine Dateinamen-Referenzen
        // ("info_gewerbe_anmelden.pdf, Abschnitt: …") und Evidenz-Boilerplate
        // ("Keine Dokumente gefunden.") werden auch OHNE bekannten Titel
        // erkannt (Muster-Prüfung auf dem Originaltext).
        m.put("primaryFindings", filterFindings(m.get("primaryFindings"), titles));
        m.put("secondaryFindings", filterFindings(m.get("secondaryFindings"), titles));
        m.put("proceduralFindings", filterFindings(m.get("proceduralFindings"), titles));
        m.put("supportingFindings", filterFindings(m.get("supportingFindings"), titles));
        scrubAuthorities(m, titles);
        scrubAnswerSection(m, "RECHTSGRUNDLAGE", titles);
    }

    /** Dokumenttitel aus den Belegen (evidenceItems) und den Vorgangsdokumenten. */
    @SuppressWarnings("unchecked")
    private List<String> documentTitles(Map<String, Object> m) {
        List<String> titles = new ArrayList<>();
        for (Object o : rawList(m.get("evidenceItems"))) {
            if (o instanceof Map<?, ?> item) {
                Object title = item.get("title");
                if (title != null && !String.valueOf(title).isBlank()) {
                    titles.add(String.valueOf(title));
                }
            }
        }
        for (Object o : rawList(m.get("documentNames"))) {
            if (o != null && !String.valueOf(o).isBlank()) {
                titles.add(String.valueOf(o));
            }
        }
        return titles.stream().distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterFindings(Object raw, List<String> titles) {
        if (!(raw instanceof List<?> findings)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Object o : findings) {
            if (!(o instanceof Map<?, ?> f)) {
                kept.add((Map<String, Object>) o);
                continue;
            }
            Object labelObj = f.get("label");
            Object descObj = f.get("description");
            String label = labelObj != null ? String.valueOf(labelObj) : "";
            String description = descObj != null ? String.valueOf(descObj) : "";
            if (isPureDocumentReference(label, description, titles)
                    || isEvidenceStateBoilerplate(label, description)) {
                continue;
            }
            kept.add((Map<String, Object>) f);
        }
        return kept;
    }

    /**
     * True, wenn die Feststellung im Wesentlichen nur ein Dokumentverweis ist:
     * Label oder Beschreibung enthalten einen bekannten Dokumenttitel (oder
     * einen Dateinamen wie "info_gewerbe_anmelden.pdf"), und der Rest danach
     * ist leer oder nur ein kurzer Abschnitts-/Seitenverweis
     * ("Abschnitt: …", "S. 3", …). Label und Beschreibung werden getrennt
     * geprüft (die LLM-Ausgabe wiederholt den Verweis oft in beiden Feldern —
     * die kombinierte Prüfung würde den Rest künstlich verlängern). Ein
     * substantieller Rest behält die Feststellung.
     */
    static boolean isPureDocumentReference(String label, String description, List<String> titles) {
        String labelNorm = normalize(label);
        String descNorm = normalize(description);
        String combined = normalize(label + " " + description);
        String[] candidates = { combined, labelNorm, descNorm };
        for (String candidate : candidates) {
            if (candidate.isBlank()) {
                continue;
            }
            if (referenceRemainderIsShort(candidate, titles)) {
                return true;
            }
        }
        // Roh-Text-Prüfung: ein Dateiname mit Punkt ("info_gewerbe_anmelden.pdf,
        // Abschnitt: …") am Textanfang mit kurzem Rest ist ein reiner Verweis —
        // die Normalisierung entfernt den Punkt, daher wird der Originaltext geprüft.
        if (rawFilenameReferenceRemainderIsShort(label) || rawFilenameReferenceRemainderIsShort(description)) {
            return true;
        }
        return false;
    }

    /** True, wenn nach Entfernen aller Vorkommen eines Dokumenttitels nur ein kurzer Rest bleibt. */
    private static boolean referenceRemainderIsShort(String normalizedText, List<String> titles) {
        for (String title : titles) {
            String normalizedTitle = normalize(title);
            if (normalizedTitle.isEmpty() || !normalizedText.contains(normalizedTitle)) {
                continue;
            }
            String remainder = normalizedText.replaceAll(normalizedTitle, " ")
                    .replaceAll("[\\s,.:;-]+", " ").trim();
            if (remainder.isEmpty() || remainder.length() <= MAX_REFERENCE_REMAINDER) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern RAW_DOC_FILENAME = Pattern.compile(
            "(?i)^[-•\\s]*([a-z0-9_\\-]+\\.(?:pdf|docx?|odt|rtf|png|jpe?g))");

    /**
     * Reine Dateinamen-Referenz auch OHNE bekannten Dokumenttitel: beginnt der
     * ORIGINAL-Text mit einem Dokument-Dateinamen und der Rest danach ist nur
     * ein kurzer Abschnitts-/Seitenverweis, ist es kein substantieller Befund
     * (z. B. "info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen").
     * Die Normalisierung entfernt den Punkt aus dem Dateinamen, daher wird der
     * Roh-Text geprüft.
     */
    static boolean rawFilenameReferenceRemainderIsShort(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        var matcher = RAW_DOC_FILENAME.matcher(rawText);
        if (!matcher.find()) {
            return false;
        }
        String remainder = normalize(rawText.substring(matcher.end()));
        return remainder.isEmpty() || remainder.length() <= MAX_REFERENCE_REMAINDER;
    }

    /**
     * Evidenz-Zustands-Boilerplate ist kein substantieller Befund: Sätze wie
     * "Keine Dokumente gefunden." beschreiben den Quellenstand, nicht den
     * Sachverhalt — sie gehören nicht in Kernfeststellungen / Weitere
     * Erkenntnisse / RECHTSGRUNDLAGE.
     */
    static boolean isEvidenceStateBoilerplate(String label, String description) {
        String combined = normalize(label + " " + description);
        String[] candidates = { combined, normalize(label), normalize(description) };
        for (String candidate : candidates) {
            if (candidate.isBlank()) {
                continue;
            }
            if (candidate.startsWith("keinedokumentegefunden")
                    || candidate.startsWith("keinebelegegefunden")
                    || candidate.startsWith("keinequellengefunden")
                    || candidate.startsWith("keineunterlagengefunden")) {
                return true;
            }
        }
        return false;
    }

    /** Entfernt Rechtsgrundlagen-Referenzen, die nur ein Dokument benennen. */
    @SuppressWarnings("unchecked")
    private void scrubAuthorities(Map<String, Object> m, List<String> titles) {
        Object raw = m.get("authorities");
        if (!(raw instanceof List<?> authorities)) {
            return;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Object o : authorities) {
            if (!(o instanceof Map<?, ?> a)) {
                continue;
            }
            Object titleObj = a.get("title");
            Object refObj = a.get("reference");
            String text = normalize((titleObj != null ? String.valueOf(titleObj) : "")
                    + " " + (refObj != null ? String.valueOf(refObj) : ""));
            if (text.isBlank() || isPureDocumentReference(text, "", titles)) {
                continue;
            }
            kept.add((Map<String, Object>) a);
        }
        m.put("authorities", kept);
    }

    /**
     * Entfernt reine Dokumentverweise aus dem RECHTSGRUNDLAGE-Abschnitt der
     * Antwort. Besteht der Abschnitt nur aus Verweisen, wird er entfernt.
     */
    private void scrubAnswerSection(Map<String, Object> m, String marker, List<String> titles) {
        Object answerObj = m.get("decisionAnswer");
        if (!(answerObj instanceof String answer) || answer.isBlank()) {
            return;
        }
        int[] span = sectionSpan(answer, marker);
        if (span == null) {
            return;
        }
        String content = answer.substring(span[0], span[1]);
        String[] lines = content.split("\\R");
        List<String> keptLines = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // Der ROH-Text der Zeile wird geprüft: reine Dateinamen-Referenzen
            // ("info_gewerbe_anmelden.pdf, Abschnitt: …") verlieren durch die
            // Normalisierung den Punkt und wären sonst nicht erkennbar.
            if (!isPureDocumentReference(line, "", titles)
                    && !isEvidenceStateBoilerplate(line, "")) {
                keptLines.add(line);
            }
        }
        String cleaned = String.join("\n", keptLines).trim();
        if (cleaned.isEmpty()) {
            // Abschnitt komplett entfernen (Marker-Zeile + Inhalt)
            int lineStart = answer.lastIndexOf('\n', span[0] - 1) + 1;
            String before = answer.substring(0, lineStart);
            String after = answer.substring(span[1]);
            m.put("decisionAnswer", (before + after).replaceAll("\\n{3,}", "\n\n").trim());
            log.info("Analyse-Normalisierung: RECHTSGRUNDLAGE-Abschnitt (nur Dokumentverweise) entfernt");
        } else if (keptLines.size() != lines.length) {
            m.put("decisionAnswer", answer.substring(0, span[0]) + cleaned + answer.substring(span[1]));
            log.info("Analyse-Normalisierung: Dokumentverweise aus RECHTSGRUNDLAGE entfernt");
        }
    }

    /** [start, end) des Inhalts der benannten Antwort-Sektion, oder null. */
    static int[] sectionSpan(String answer, String marker) {
        Pattern pattern = Pattern.compile("(?im)(?:^|\\n)\\s*" + Pattern.quote(marker) + "\\b");
        var matcher = pattern.matcher(answer);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        int end = answer.length();
        for (String next : ANSWER_MARKERS) {
            if (next.equals(marker)) {
                continue;
            }
            Pattern nextPattern = Pattern.compile("(?im)(?:^|\\n)\\s*" + Pattern.quote(next) + "\\b");
            var nextMatcher = nextPattern.matcher(answer);
            if (nextMatcher.find() && nextMatcher.start() > start) {
                end = Math.min(end, nextMatcher.start());
            }
        }
        return new int[] { start, end };
    }

    // ── Rechtsgrundlagen-Duplikate in "Weitere Erkenntnisse" ─────────────────

    /**
     * "Weitere Erkenntnisse" dürfen die Rechtsgrundlage nicht duplizieren: eine
     * Sekundär-Feststellung, die nur die Rechtsgrundlage wiederholt (z. B.
     * "Gewerbeordnung (GewO), § 2a, § 3"), wird entfernt. Feststellungen, die
     * die Rechtsgrundlage nur in einem substantiellen Satz erwähnen, bleiben.
     */
    @SuppressWarnings("unchecked")
    void removeLegalBasisDuplicates(Map<String, Object> m) {
        Object raw = m.get("secondaryFindings");
        if (!(raw instanceof List<?> findings) || findings.isEmpty()) {
            return;
        }
        List<String> legalBasis = new ArrayList<>();
        Object authorities = m.get("authorities");
        if (authorities instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> a)) {
                    continue;
                }
                Object titleObj = a.get("title");
                Object refObj = a.get("reference");
                legalBasis.add(normalize((titleObj != null ? String.valueOf(titleObj) : "")
                        + " " + (refObj != null ? String.valueOf(refObj) : "")));
            }
        }
        Object answerObj = m.get("decisionAnswer");
        if (answerObj instanceof String answer) {
            int[] span = sectionSpan(answer, "RECHTSGRUNDLAGE");
            if (span != null) {
                legalBasis.add(normalize(answer.substring(span[0], span[1])));
            }
        }
        if (legalBasis.isEmpty()) {
            return;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Object o : findings) {
            if (!(o instanceof Map<?, ?> f)) {
                kept.add((Map<String, Object>) o);
                continue;
            }
            Object labelObj = f.get("label");
            Object descObj = f.get("description");
            String findingText = normalize((labelObj != null ? String.valueOf(labelObj) : "")
                    + " " + (descObj != null ? String.valueOf(descObj) : ""));
            if (findingText.isEmpty()) {
                kept.add((Map<String, Object>) f);
                continue;
            }
            boolean duplicate = legalBasis.stream().anyMatch(legal ->
                    !legal.isEmpty() && (findingText.equals(legal)
                            || legal.contains(findingText)
                            || (findingText.contains(legal) && findingText.length() <= legal.length() + 40)));
            if (!duplicate) {
                kept.add((Map<String, Object>) f);
            }
        }
        if (kept.size() != findings.size()) {
            log.info("Weitere Erkenntnisse: {} Eintrag/entfernt, der nur die Rechtsgrundlage wiederholt",
                    findings.size() - kept.size());
            m.put("secondaryFindings", kept);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Normalisierter Vergleichstext: Kleinbuchstaben, nur Buchstaben/Ziffern. */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.GERMANY).replaceAll("[^a-zäöüß0-9]", "");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}
