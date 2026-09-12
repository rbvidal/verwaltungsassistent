package verwaltungsassistent.web.service;

import verwaltungsassistent.web.service.JobProgressService.Job;

import java.util.List;
import java.util.Map;

/**
 * Baut die Knotenliste für die gemeinsame Pipeline-Visualisierung aus dem
 * ECHTEN Job-Zustand (JobProgressService stages + stageData). Die Knotenreihen-
 * folge ist je Ablauf fest; der Zustand (done/active/pending) folgt dem
 * tatsächlich erreichten Stufenstrom — nichts wird erfunden. Die
 * Visualisierung ist reine Observability und beeinflusst die Pipeline nie.
 */
public final class PipelineDiagramSupport {

    private PipelineDiagramSupport() {
    }

    public record PipelineNode(String id, String label, String state, String info) {}

    /** Knoten der kommunalen LLM-Pipeline (Assistent + Entscheidungsanalyse). */
    private static final List<String[]> PIPELINE_STAGES = List.of(
            new String[]{"frage", "Frage"},
            new String[]{"intent", "Intent"},
            new String[]{"retrieval", "Retrieval"},
            new String[]{"evidence", "Belege"},
            new String[]{"ground", "Absicherung"},
            new String[]{"coverage", "Verifier"},
            new String[]{"antwort", "Antwort"});

    /** Echte Schritte der E-Mail-Analyse (Triage + KI-Beantwortung). */
    private static final List<String[]> EMAIL_STAGES = List.of(
            new String[]{"email-lesen", "E-Mail lesen"},
            new String[]{"email-routing", "Anliegen erkennen"},
            new String[]{"email-retrieval", "Wissensbasis"},
            new String[]{"email-faelle", "Fälle abgleichen"},
            new String[]{"email-belege", "Belege"},
            new String[]{"email-feststellungen", "Feststellungen"},
            new String[]{"email-antwort", "Antwort"},
            new String[]{"email-schritte", "Nächste Schritte"});

    public static List<PipelineNode> pipelineNodes(Job job) {
        return nodes(job, PIPELINE_STAGES, Map.of(
                "retrieval", "retrieval-done",
                "evidence", "evidence",
                "ground", "ground",
                "coverage", "coverage"));
    }

    public static List<PipelineNode> emailNodes(Job job) {
        return nodes(job, EMAIL_STAGES, Map.of());
    }

    private static List<PipelineNode> nodes(Job job, List<String[]> stageDefs,
                                            Map<String, String> dataStages) {
        if (job == null) {
            return stageDefs.stream()
                    .map(s -> new PipelineNode(s[0], s[1], "pending", null)).toList();
        }
        java.util.List<String> reached = job.stages;
        // Nur Stufen, die tatsächlich einem Knoten entsprechen, bestimmen den
        // aktiven Zustand — Zwischenstufen (routing, retrieval-started,
        // answer-generation) verschieben den Aktiv-Marker nicht.
        java.util.List<String> mappedKeys = stageDefs.stream()
                .map(s -> dataStages.getOrDefault(s[0], s[0])).toList();
        String lastReached = reached.stream()
                .filter(mappedKeys::contains)
                .reduce((a, b) -> b)
                .orElse(null);
        boolean terminal = "DONE".equals(job.state) || "ERROR".equals(job.state);
        return stageDefs.stream().map(s -> {
            String key = dataStages.getOrDefault(s[0], s[0]);
            String state;
            if ("frage".equals(s[0])) {
                state = "done"; // Frage ist eingegangen, sobald der Job existiert
            } else if (!reached.contains(key)) {
                state = "pending";
            } else if (terminal) {
                state = "done"; // abgeschlossener Job: alle erreichten Knoten grün
            } else if (key.equals(lastReached)) {
                state = "active";
            } else {
                state = "done";
            }
            return new PipelineNode(s[0], s[1], state, infoText(s[0], job));
        }).toList();
    }

    private static String infoText(String id, Job job) {
        return switch (id) {
            case "frage" -> "Anfrage erkannt, Verarbeitung läuft.";
            case "intent" -> {
                Map<String, Object> d = data(job, "intent");
                if (d == null || d.isEmpty()) {
                    yield "Die Anfrage wird analysiert und eingeordnet.";
                }
                java.util.List<String> parts = new java.util.ArrayList<>();
                if (d.get("domain") != null) {
                    parts.add("Bereich " + domainLabel(String.valueOf(d.get("domain"))));
                }
                if (d.get("language") != null) {
                    parts.add("Sprache " + String.valueOf(d.get("language")).toUpperCase(java.util.Locale.GERMANY));
                }
                yield "Anliegen erkannt" + (parts.isEmpty() ? "" : ": " + String.join(", ", parts))
                        + msSuffix(d, "ms");
            }
            case "retrieval" -> {
                Map<String, Object> d = data(job, "retrieval-done");
                int n = intData(job, "retrieval-done", "sources");
                if (d == null) {
                    yield "Relevante Quellen werden gesucht.";
                }
                yield "Dokumentensuche ausgeführt: " + n + " Quelle" + (n == 1 ? "" : "n") + msSuffix(d, "ms");
            }
            case "evidence" -> {
                Map<String, Object> d = data(job, "evidence");
                int n = intData(job, "evidence", "evidenceItems");
                if (d == null || n == 0) {
                    yield "Die gefundenen Informationen werden auf ihre Eignung geprüft.";
                }
                yield n + " Belege für die weitere Prüfung ausgewählt: Belegprüfung ausgeführt, "
                        + intData(job, "evidence", "sources") + " Quelle"
                        + (intData(job, "evidence", "sources") == 1 ? "" : "n") + msSuffix(d, "ms");
            }
            case "ground" -> {
                Boolean grounded = boolData(job, "ground", "grounded");
                Integer confidence = intOrNull(job, "ground", "confidence");
                int unsupported = intData(job, "ground", "unsupportedFindings");
                if (grounded == null) {
                    yield "Die Angaben werden unabhängig geprüft.";
                }
                StringBuilder sb = new StringBuilder("Antwort auf ausreichende Belege geprüft");
                if (confidence != null) {
                    sb.append(" (Konfidenz ").append(confidence).append(" %)");
                }
                if (unsupported > 0) {
                    sb.append("; ").append(unsupported).append(" Punkt(e) nicht durch Belege gedeckt");
                }
                sb.append(": Absicherung ausgeführt").append(msSuffix(data(job, "ground"), "ms"));
                yield sb.toString();
            }
            case "coverage" -> {
                Map<String, Object> d = data(job, "coverage");
                if (d != null && "EXECUTED".equals(d.get("status"))) {
                    yield "Abdeckungsprüfung: ausgeführt" + msSuffix(d, "ms");
                }
                yield "Die Belege werden mit der Antwort abgeglichen.";
            }
            case "antwort" -> {
                Map<String, Object> d = data(job, "antwort");
                if (d != null && "EXECUTED".equals(d.get("status"))) {
                    String role = d.get("role") != null ? roleLabel(String.valueOf(d.get("role"))) : null;
                    yield "Antwort erzeugt (KI-Modell"
                            + (role != null ? ", Rolle " + role : "") + ")" + msSuffix(d, "ms");
                }
                yield "Die Antwort wird erstellt.";
            }
            case "email-lesen" -> {
                Map<String, Object> d = data(job, "email-lesen");
                if (d == null || d.isEmpty()) {
                    yield "Die E-Mail wird gelesen und entgegengenommen.";
                }
                String subject = d.get("subject") != null ? String.valueOf(d.get("subject")) : "";
                yield "E-Mail gelesen" + (subject.isBlank() ? "" : ": „" + subject + "“")
                        + msSuffix(d, "ms");
            }
            case "email-routing" -> {
                Map<String, Object> d = data(job, "email-routing");
                if (d == null || d.isEmpty()) {
                    yield "Anliegen und Fachbereich werden erkannt.";
                }
                java.util.List<String> parts = new java.util.ArrayList<>();
                if (d.get("domain") != null && !String.valueOf(d.get("domain")).isBlank()) {
                    parts.add("Bereich " + domainLabel(String.valueOf(d.get("domain"))));
                }
                if (d.get("intentType") != null && !String.valueOf(d.get("intentType")).isBlank()) {
                    parts.add("Anliegenart " + intentLabel(String.valueOf(d.get("intentType"))));
                }
                if (d.get("strategy") != null && !String.valueOf(d.get("strategy")).isBlank()) {
                    parts.add("Verarbeitungsstrategie " + strategyLabel(String.valueOf(d.get("strategy"))));
                }
                yield "Anliegen und Fachbereich erkannt" + (parts.isEmpty() ? "" : ": " + String.join(", ", parts))
                        + msSuffix(d, "ms");
            }
            case "email-retrieval" -> {
                Map<String, Object> d = data(job, "email-retrieval");
                if (d == null || d.isEmpty()) {
                    yield "Die Wissensbasis wird durchsucht.";
                }
                int n = d.get("sources") instanceof Number num ? num.intValue() : 0;
                yield "Wissensbasis durchsucht: " + n + " relevante" + (n == 1 ? " Quelle" : " Quellen")
                        + (d.get("mode") != null ? ", Suchverfahren " + modeLabel(String.valueOf(d.get("mode"))) : "")
                        + msSuffix(d, "ms");
            }
            case "email-faelle" -> {
                Map<String, Object> d = data(job, "email-faelle");
                if (d == null || d.isEmpty()) {
                    yield "Bestehende Fälle werden abgeglichen.";
                }
                int n = d.get("cases") instanceof Number num ? num.intValue() : 0;
                yield "Bestehende Fälle abgeglichen: " + n + " Treffer" + msSuffix(d, "ms");
            }
            case "email-schritte" -> {
                Map<String, Object> d = data(job, "email-schritte");
                if (d == null || d.isEmpty()) {
                    yield "Die nächsten Schritte werden zusammengestellt.";
                }
                int n = d.get("steps") instanceof Number num ? num.intValue() : 0;
                yield "Nächste Schritte zusammengestellt: " + n + " Schritte" + msSuffix(d, "ms");
            }
            case "email-belege" -> {
                Map<String, Object> d = data(job, "email-belege");
                if (d == null || d.isEmpty()) {
                    yield "Belege werden aus den Quellen ausgewählt.";
                }
                int n = d.get("evidence") instanceof Number num ? num.intValue() : 0;
                yield "Belege ausgewählt: " + n + " Beleg" + (n == 1 ? "" : "e")
                        + (d.get("sources") instanceof Number s && s.intValue() > 0
                                ? ", " + s.intValue() + " Quellen" : "") + msSuffix(d, "ms");
            }
            case "email-feststellungen" -> {
                Map<String, Object> d = data(job, "email-feststellungen");
                if (d == null || d.isEmpty()) {
                    yield "Belegte Feststellungen werden ermittelt.";
                }
                int n = d.get("claims") instanceof Number num ? num.intValue() : 0;
                yield "Belegte Feststellungen: " + n + " Aussage" + (n == 1 ? "" : "n")
                        + " durch Quellen gedeckt" + msSuffix(d, "ms");
            }
            case "email-antwort" -> {
                Map<String, Object> d = data(job, "email-antwort");
                if (d == null || d.isEmpty()) {
                    yield "Die Antwort wird erstellt.";
                }
                String status = d.get("status") != null ? String.valueOf(d.get("status")) : "";
                if ("FEHLER".equals(status)) {
                    yield "Beantwortung nicht möglich (keine ausreichenden Quellen)" + msSuffix(d, "ms");
                }
                boolean grounded = Boolean.TRUE.equals(d.get("grounded"));
                Object conf = d.get("confidence");
                yield (grounded ? "Antwort quellenbelegt" : "Antwort erzeugt (eingeschränkte Quellenlage)")
                        + (conf instanceof Number c && c.intValue() > 0 ? ", Konfidenz " + c.intValue() + " %" : "")
                        + msSuffix(d, "ms");
            }
            default -> "";
        };
    }

    // ── Präsentations-Ebene: verständliche deutsche Bezeichnungen ──

    /** Verarbeitungsstrategie der Pipeline (interne Enum-Werte → deutsch). */
    static String strategyLabel(String strategy) {
        if (strategy == null || strategy.isBlank()) return "unbestimmt";
        return switch (strategy.toUpperCase()) {
            case "RULE_ENGINE" -> "regelbasiert";
            case "GRAPH_REASONING" -> "wissensbasierte Begründung";
            case "HYBRID_RETRIEVAL", "HYBRID" -> "hybride Suche";
            case "SEMANTIC" -> "semantische Suche";
            case "KEYWORD" -> "Stichwortsuche";
            case "INDEX_INSPECTION" -> "Index-Prüfung";
            default -> strategy;
        };
    }

    /** Fachbereich (Domain-Enum → deutsch). */
    static String domainLabel(String domain) {
        if (domain == null || domain.isBlank()) return "Allgemein";
        return switch (domain.toUpperCase()) {
            case "GENERAL" -> "Allgemein";
            case "BUILDING" -> "Bau";
            case "TRAVEL", "BRKG" -> "Reisekosten";
            case "HR", "TVL", "AV" -> "Personal / Besoldung";
            case "PROCUREMENT" -> "Vergabe";
            case "GEWERBE" -> "Gewerbe / Gastronomie";
            case "BMG", "MELD" -> "Meldewesen";
            default -> domain;
        };
    }

    /** Anliegenart (intentType → deutsch). */
    static String intentLabel(String intentType) {
        if (intentType == null || intentType.isBlank()) return "Allgemeine Anfrage";
        return switch (intentType.toUpperCase()) {
            case "GENERAL" -> "Allgemeine Anfrage";
            case "SALARY_LOOKUP" -> "Besoldungsauskunft";
            case "TRAVEL_ALLOWANCE" -> "Reisekostenauskunft";
            case "PROCUREMENT_THRESHOLD" -> "Vergabeschwellenabfrage";
            case "REQUEST_DOCUMENTS" -> "Unterlagenanfrage";
            default -> intentType;
        };
    }

    /** Rolle des KI-Modells bei der Antwortgenerierung → deutsch. */
    static String roleLabel(String role) {
        if (role == null || role.isBlank()) return "";
        return switch (role.toLowerCase()) {
            case "reason" -> "Begründung";
            case "explain-only" -> "Erläuterung";
            default -> role;
        };
    }

    /** Suchverfahren (SearchMode → deutsch). */
    static String modeLabel(String mode) {
        if (mode == null || mode.isBlank()) return "unbestimmt";
        return switch (mode.toUpperCase()) {
            case "HYBRID", "HYBRID_GRAPH" -> "hybride Suche";
            case "SEMANTIC" -> "semantische Suche";
            case "KEYWORD" -> "Stichwortsuche";
            case "GRAPH" -> "Wissensgraph";
            default -> mode;
        };
    }

    private static Map<String, Object> data(Job job, String stage) {
        return job != null ? job.stageData.get(stage) : null;
    }

    private static int intData(Job job, String stage, String key) {
        Integer v = intOrNull(job, stage, key);
        return v != null ? v : 0;
    }

    private static Integer intOrNull(Job job, String stage, String key) {
        Map<String, Object> data = data(job, stage);
        if (data == null) return null;
        Object v = data.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    /** ", 130 ms" (deutsches Tausendertrennzeichen) oder "" wenn kein Timing vorliegt. */
    private static String msSuffix(Map<String, Object> data, String key) {
        if (data == null) return "";
        Object v = data.get(key);
        if (!(v instanceof Number n)) return "";
        return ", " + java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(n.longValue()) + " ms";
    }

    private static Boolean boolData(Job job, String stage, String key) {
        Map<String, Object> data = job != null ? job.stageData.get(stage) : null;
        if (data == null) return null;
        Object v = data.get(key);
        return v instanceof Boolean b ? b : null;
    }
}
