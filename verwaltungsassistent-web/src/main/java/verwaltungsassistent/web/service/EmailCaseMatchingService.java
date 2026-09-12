package verwaltungsassistent.web.service;

import reasoning.common.model.WorkspaceStatus;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.SearchFilter;
import reasoning.search.model.SearchMode;
import reasoning.search.model.SearchQuery;
import reasoning.search.model.SearchRequestContext;
import reasoning.search.model.SearchResultPage;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fall-Abgleich einer E-Mail (Anwendungs-Schicht, Phase 2C.1 — jetzt auch für
 * den Mailbox-Import genutzt, Phase 2C.2-Folge): die EINZIGE Quelle der
 * Fall-/Thread-Matching-Semantik. Sowohl die manuelle E-Mail-Analyse als auch
 * die automatische Mailbox-Ingestion rufen diesen Service auf — es gibt keine
 * parallele Matching-Logik.
 *
 * <p>Priorität der Signale: EMAIL_IDENTITY (bestätigte Absender-Adresse) &lt;
 * THREAD_IDENTITY (In-Reply-To/References-Header) &lt; NAME_IDENTITY
 * (Namens-Heuristik) &lt; SEMANTIC (Vektor-Ähnlichkeit zu den Unterlagen des
 * Vorgangs, Phase 2C.7) &lt; SIMILAR (lexikalischer Vorschlag). Nichts wird je
 * automatisch zugeordnet — die Zuordnung bleibt eine ausdrückliche
 * Mitarbeiter-Aktion.</p>
 *
 * <p>Die semantische Stufe ist bewusst ein VORSCHLAG nach den deterministischen
 * Regeln: Identitäts-/Thread-Signale und explizite Vorgangsnummern haben immer
 * Vorrang; die Vektor-Ähnlichkeit nutzt die BESTEHENDE Qdrant-Chunk-Suche
 * (SearchFacade, SEMANTIC-Modus) eingeschränkt auf die Dokumente der sichtbaren
 * Vorgänge — keine zweite Vektor-Infrastruktur, keine Auto-Zuordnung.</p>
 */
@Service
public class EmailCaseMatchingService {

    private static final Logger log = LoggerFactory.getLogger(EmailCaseMatchingService.class);
    private static final ObjectMapper PHASE_DATA_MAPPER = new ObjectMapper();

    /** Match-Typ der semantischen (Vektor-)Stufe. */
    public static final String SEMANTIC = "SEMANTIC";

    private final WorkspaceService workspaceService;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final SearchFacade searchFacade;
    /** Kalibrierte Vorschlags-Schwellen (Konfiguration, Phase 2C.12a). */
    private final verwaltungsassistent.web.config.SemanticMatchingProperties thresholds;

    public EmailCaseMatchingService(WorkspaceService workspaceService,
                                    JpaIncomingEmailRepository incomingEmailRepository,
                                    SearchFacade searchFacade,
                                    verwaltungsassistent.web.config.SemanticMatchingProperties thresholds) {
        this.workspaceService = workspaceService;
        this.incomingEmailRepository = incomingEmailRepository;
        this.searchFacade = searchFacade;
        this.thresholds = thresholds != null
                ? thresholds : new verwaltungsassistent.web.config.SemanticMatchingProperties();
    }

    /**
     * Fall-Abgleich einer E-Mail. Zusätzlich zu den Personen-Signalen
     * (EMAIL_IDENTITY / NAME_IDENTITY) erkennt Phase 2C.1 die THREAD_IDENTITY:
     * referenziert die E-Mail über ihre Header (In-Reply-To/References) eine
     * Message-ID einer bereits im Vorgang liegenden E-Mail, ist die
     * Thread-Zugehörigkeit ein objektives Signal — stärker als ein bloßer
     * Namens-Treffer, aber immer noch ein VORSCHLAG: zugeordnet wird nur
     * durch die ausdrückliche Aktion der Mitarbeiterin.
     */
    public List<verwaltungsassistent.web.controller.EmailController.CaseRef> matchCases(
            String text, String senderEmail, String actorEmail, boolean isAdmin,
            Set<String> threadReferences) {
        try {
            // Abgleichsraum (Phase 2C.1): Admin sieht alle Vorgänge; Mitarbeiter
            // sehen ihre eigenen PLUS die allgemeinen Pool-Vorgänge (admin-owned)
            // — eine Bürger-Nachfrage zu einem Pool-Vorgang muss für JEDE
            // Mitarbeiterin als Treffer sichtbar sein (Zuordnung bleibt explizit).
            List<WorkspaceEntity> workspaces;
            if (isAdmin) {
                workspaces = new ArrayList<>(workspaceService.findAll());
            } else if (actorEmail != null) {
                workspaces = new ArrayList<>(workspaceService.findByOwner(actorEmail));
                for (WorkspaceEntity ws : workspaceService.findAll()) {
                    if (verwaltungsassistent.web.service.DemoDataService
                            .isGeneralPoolOwner(ws.getOwnerId())) {
                        workspaces.add(ws);
                    }
                }
            } else {
                workspaces = List.of();
            }

            Set<String> emailTokens = tokens(text);
            String senderName = senderNameFrom(text);
            String senderAddress = senderEmail != null ? senderEmail.trim().toLowerCase(Locale.ROOT) : "";
            List<CaseMatch> scored = new ArrayList<>();
            for (WorkspaceEntity ws : workspaces) {
                Set<String> caseTokens = tokens((ws.getName() != null ? ws.getName() : "") + " "
                        + (ws.getDescription() != null ? ws.getDescription() : ""));
                Set<String> overlap = new HashSet<>(emailTokens);
                overlap.retainAll(caseTokens);
                long specific = overlap.stream().filter(t -> !GENERIC_TERMS.contains(t)).count();
                // Personen-Signal UND Thread-Signal unabhängig ermitteln; es
                // gewinnt die stärkere Aussage: EMAIL_IDENTITY (0) <
                // THREAD_IDENTITY (1) < NAME_IDENTITY (2) < SIMILAR (3).
                // Ein objektiver Thread-Header (References) schlägt eine bloße
                // Namens-Heuristik, verliert aber gegen die bestätigte Adresse.
                String personType = personMatchType(senderAddress, senderName, ws);
                String threadType = threadReferences.isEmpty()
                        ? null : threadMatchType(threadReferences, ws);
                String matchType = higherPriority(personType, threadType);
                if (matchType != null || (overlap.size() >= 2 && specific >= 1)) {
                    scored.add(new CaseMatch(ws, overlap, specific,
                            matchType != null ? matchType : "SIMILAR", null, null));
                }
            }

            // Phase 2C.7 — Semantische Vorschläge (Vektor-Ähnlichkeit zu den
            // Vorgangs-Unterlagen). Deterministische Signale haben VORRANG:
            // Ein Identitäts-/Thread-Treffer wird nie durch eine semantische
            // Ähnlichkeit ersetzt oder verdrängt; ein rein lexikalischer
            // SIMILAR-Treffer wird nur dann durch den ehrlichen SEMANTIC-Treffer
            // ersetzt, wenn die Vektor-Ähnlichkeit tatsächlich belastbar ist.
            List<CaseMatch> enriched = new ArrayList<>();
            Map<String, CaseMatch> byCaseId = new LinkedHashMap<>();
            for (CaseMatch m : scored) {
                enriched.add(m);
                byCaseId.put(m.ws().getId(), m);
            }
            Map<String, WorkspaceEntity> workspaceById = new LinkedHashMap<>();
            for (WorkspaceEntity ws : workspaces) {
                workspaceById.put(ws.getId(), ws);
            }
            for (SemanticCandidate c : semanticCandidates(text, workspaces)) {
                CaseMatch existing = byCaseId.get(c.wsId());
                if (existing != null) {
                    int existingPriority = matchTypePriority(existing.matchType());
                    if (existingPriority <= 2) {
                        continue; // Identität/Thread ist stärker — nie überschreiben
                    }
                    enriched.remove(existing);
                }
                WorkspaceEntity ws = workspaceById.get(c.wsId());
                if (ws != null) {
                    enriched.add(new CaseMatch(ws, Set.of(), 0, SEMANTIC,
                            c.score(), c.context()));
                }
            }

            enriched.sort(Comparator.comparingInt((CaseMatch m) -> matchTypePriority(m.matchType()))
                    .thenComparing(Comparator.comparingDouble(
                                    (CaseMatch m) -> m.semanticScore() != null ? m.semanticScore() : -1.0)
                            .reversed())
                    .thenComparing(Comparator.comparingLong(CaseMatch::specific).reversed())
                    .thenComparing(m -> m.overlap().size(), Comparator.reverseOrder())
                    .thenComparing(m -> m.ws().getName() != null ? m.ws().getName() : ""));
            return enriched.stream()
                    .map(m -> new verwaltungsassistent.web.controller.EmailController.CaseRef(
                            m.ws().getId().toString(),
                            m.ws().getName() != null ? m.ws().getName() : m.ws().getWorkspaceCode(),
                            matchReason(m),
                            m.matchType()))
                    .toList();
        } catch (Exception e) {
            log.warn("Case matching during e-mail analysis failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Semantische Vorschlags-Stufe (Phase 2C.7): EIN Vektor-Suchlauf über die
     * BESTEHENDE Qdrant-Chunk-Infrastruktur (SearchFacade, SEMANTIC-Modus),
     * eingeschränkt auf die Dokumente der sichtbaren, offenen Vorgänge — die
     * Zugriffsgrenzen des Aufrufers gelten damit auch für die Vorschläge.
     * Ergebnis: je Vorgang die beste Chunk-Ähnlichkeit (0..1) plus ein kurzer
     * Kontext-Auszug als Erklärung. Schwache Treffer unterhalb der
     * Vorschlags-Schwelle werden verworfen — "nicht sicher" schlägt eine
     * falsche Zuordnung.
     */
    private List<SemanticCandidate> semanticCandidates(String text, List<WorkspaceEntity> workspaces) {
        if (searchFacade == null || text == null || text.isBlank() || workspaces.isEmpty()) {
            return List.of();
        }
        try {
            Set<UUID> docIds = new HashSet<>();
            Map<UUID, String> docToCase = new HashMap<>();
            for (WorkspaceEntity ws : workspaces) {
                if (ws.getStatus() != WorkspaceStatus.ACTIVE && ws.getStatus() != WorkspaceStatus.DRAFT) {
                    continue;
                }
                try {
                    for (WorkspaceDocumentLinkEntity link : workspaceService.getWorkspaceDocuments(ws.getId())) {
                        UUID docUuid = link.getDocumentUuid() != null
                                ? link.getDocumentUuid()
                                : (link.getDocumentId() != null ? UUID.fromString(link.getDocumentId()) : null);
                        if (docUuid == null) {
                            continue;
                        }
                        docIds.add(docUuid);
                        docToCase.putIfAbsent(docUuid, ws.getId());
                    }
                } catch (Exception e) {
                    log.debug("Dokumente von Vorgang {} für semantischen Abgleich nicht lesbar: {}",
                            ws.getId(), e.getMessage());
                }
            }
            if (docIds.isEmpty()) {
                return List.of();
            }
            SearchResultPage page = searchFacade.search(new SearchQuery(
                    text, SearchMode.SEMANTIC,
                    new SearchFilter(docIds, null, null, null, null, null, null, null, List.of()),
                    new SearchRequestContext("system", null, null, null), 0, 30));
            if (page == null || page.results() == null || page.results().isEmpty()) {
                return List.of();
            }
            // Je Vorgang: beste Chunk-Ähnlichkeit + Kontext-Auszug des besten Treffers.
            Map<String, SemanticCandidate> best = new LinkedHashMap<>();
            for (reasoning.search.model.SearchResult r : page.results()) {
                UUID docId = r.chunk() != null ? r.chunk().documentId() : null;
                if (docId == null || !docToCase.containsKey(docId)) {
                    continue;
                }
                double vector = r.vectorScore() > 0 ? r.vectorScore() : r.score();
                if (vector < thresholds.getSuggestionFloor()) {
                    continue;
                }
                String caseId = docToCase.get(docId);
                SemanticCandidate current = best.get(caseId);
                if (current == null || vector > current.score()) {
                    best.put(caseId, new SemanticCandidate(caseId, vector,
                            compactSnippet(r.text(), 140)));
                }
            }
            return best.values().stream()
                    .sorted(Comparator.comparingDouble(SemanticCandidate::score).reversed())
                    .limit(3)
                    .toList();
        } catch (Exception e) {
            log.warn("Semantischer Fall-Abgleich nicht verfügbar (Vorschläge übersprungen): {}", e.getMessage());
            return List.of();
        }
    }

    /** Semantischer Vorschlags-Kandidat (Vorgangs-ID, Ähnlichkeit, Kontext). */
    private record SemanticCandidate(String wsId, double score, String context) {}

    private static String compactSnippet(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, maxLen).trim() + "…";
    }

    private static int matchTypePriority(String type) {
        return switch (type) {
            case "EMAIL_IDENTITY" -> 0;
            case "THREAD_IDENTITY" -> 1;
            case "NAME_IDENTITY" -> 2;
            case SEMANTIC -> 3;
            default -> 4; // SIMILAR (lexikalischer Vorschlag)
        };
    }

    /** Stärkeres von zwei Match-Signalen (niedrigere Prioritätszahl gewinnt). */
    private static String higherPriority(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return matchTypePriority(a) <= matchTypePriority(b) ? a : b;
    }

    /**
     * Header-basierte Thread-Zugehörigkeit (Phase 2C.1): referenziert die
     * E-Mail über In-Reply-To/References eine Message-ID einer im Vorgang
     * liegenden E-Mail, liegt ein objektives Thread-Signal vor. Bewusst
     * getrennt von der Personen-Identität — die Zuordnung bleibt explizit.
     */
    private String threadMatchType(Set<String> threadReferences, WorkspaceEntity ws) {
        try {
            boolean hit = incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(ws.getId()))
                    .stream()
                    .map(IncomingEmailEntity::getMessageId)
                    .filter(m -> m != null && !m.isBlank())
                    .anyMatch(threadReferences::contains);
            return hit ? "THREAD_IDENTITY" : null;
        } catch (Exception e) {
            log.debug("Thread-Abgleich für Fall {} nicht lesbar: {}", ws.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Das Personen-Signal eines Falls: EMAIL_IDENTITY (Absender-Adresse ist
     * im Vorgang bereits durch eine zugeordnete E-Mail belegt) vor
     * NAME_IDENTITY (nur Namens-Übereinstimmung mit phaseData.citizen).
     * Themen-Begriffe zählen hier NIE — Identität entsteht nur über die
     * Person, nicht über den Sachverhalt.
     */
    private String personMatchType(String senderAddress, String senderName, WorkspaceEntity ws) {
        if (!senderAddress.isEmpty()) {
            boolean addressKnown = incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(ws.getId()))
                    .stream()
                    .map(IncomingEmailEntity::getSenderEmail)
                    .filter(e -> e != null)
                    .anyMatch(e -> e.trim().toLowerCase(Locale.ROOT).equals(senderAddress));
            if (addressKnown) {
                return "EMAIL_IDENTITY";
            }
        }
        if (senderName != null && !senderName.isBlank() && nameIdentityMatch(senderName, ws)) {
            return "NAME_IDENTITY";
        }
        return null;
    }

    /** Absender-NAME (z. B. "Erika Schulze") aus der E-Mail-Signatur, sofern vorhanden. */
    public static String senderNameFrom(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = Pattern.compile(
                "(?im)(?:mit freundlichen grüßen|vielen dank|grüße)[\\s\\n]*\\R?\\s*([a-zäöüß\\-]+(?:\\s+[a-zäöüß\\-]+)?)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Namens-Identität NUR über eine bedeutungsvolle Namens-Übereinstimmung:
     * der NACHNAME des Absenders muss zur Person des Falls passen (citizen
     * oder — ohne citizen — zum Fallnamen). Ein reiner Vornamen-Treffer
     * ("Erika Schulze" vs. "Erika Müller") ist KEINE Identität. Sind auf
     * beiden Seiten Vor- UND Nachname bekannt, müssen beide übereinstimmen —
     * gleicher Nachname bei unterschiedlichen Vornamen ist ebenfalls keine
     * Personen-Identität (andere Person derselben Familie). Bewusst ein
     * EIGENER statischer Mapper: die Prüfung darf nicht vom injizierten
     * (ggf. gemockten) ObjectMapper abhängen.
     */
    private boolean nameIdentityMatch(String senderName, WorkspaceEntity ws) {
        NameParts sender = parseNameParts(senderName);
        if (sender == null || sender.surname() == null) {
            return false;
        }
        String citizen = "";
        try {
            Map<String, Object> data = PHASE_DATA_MAPPER.readValue(
                    ws.getPhaseData() == null ? "{}" : ws.getPhaseData(),
                    new TypeReference<Map<String, Object>>() {});
            Object c = data.get("citizen");
            citizen = c != null ? String.valueOf(c) : "";
        } catch (Exception e) {
            log.debug("citizen-Feld von Fall {} nicht lesbar: {}", ws.getName(), e.getMessage());
        }
        if (citizen != null && !citizen.isBlank()) {
            // Person des Falls ist namentlich bekannt: Nachname muss
            // übereinstimmen, und wenn BEIDE Vornamen bekannt sind, müssen
            // auch sie übereinstimmen.
            NameParts person = parseNameParts(citizen);
            if (person == null || person.surname() == null) {
                return false;
            }
            if (!sender.surname().equals(person.surname())) {
                return false;
            }
            return sender.first() == null || person.first() == null
                    || sender.first().equals(person.first());
        }
        // Kein citizen erfasst: der Nachname muss im Fallnamen vorkommen
        // (z. B. "Fall Müller - Wohngeld") — ebenfalls ein Nachnamen-Signal,
        // nie ein Vornamen-Treffer.
        if (ws.getName() == null || ws.getName().isBlank()) {
            return false;
        }
        return tokens(ws.getName()).stream()
                .anyMatch(t -> normalizeNameToken(t).equals(sender.surname()));
    }

    /** Vor-/Nachname einer Namenszeile, umlautnormalisiert ("Müller" = "Mueller"). */
    private record NameParts(String first, String surname) {}

    private NameParts parseNameParts(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String clean = name.replaceAll("(?i)\\b(dr|prof|herr|frau|fr|hr|h)\\.?\\b", " ").trim();
        List<String> parts = new ArrayList<>();
        for (String raw : clean.split("[^a-zäöüß\\-]+")) {
            String t = raw.trim();
            if (!t.isEmpty()) {
                parts.add(normalizeNameToken(t));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return new NameParts(parts.size() >= 2 ? parts.get(0) : null,
                parts.get(parts.size() - 1));
    }

    /** Umlaut-Faltung für den Namensvergleich ("Müller" ↔ "Mueller", "ß" ↔ "ss"). */
    private static String normalizeNameToken(String token) {
        return token.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /** Scored candidate case: deterministische Signale (Identität/Begriffe) und —
     *  bei SEMANTIC-Treffern — die Vektor-Ähnlichkeit samt Kontext-Auszug. */
    private record CaseMatch(WorkspaceEntity ws, Set<String> overlap, long specific, String matchType,
                             Double semanticScore, String semanticContext) {}

    /**
     * Anzeige-Grund eines Treffers: bei einem Personen-/Thread-Signal wird
     * ehrlich dessen Stärke genannt (EMAIL_IDENTITY: „Identität bestätigt";
     * THREAD_IDENTITY: Header-Beleg; NAME_IDENTITY: „Mögliche
     * Personenübereinstimmung") — die Begriffs-Überschneidung ist bei diesen
     * Treffern NICHT das Signal und erscheint daher nicht. Nur ein SIMILAR-
     * Treffer beruht auf gemeinsamen inhaltlichen Begriffen; sein Grund
     * benennt genau dieses lexikalische Verfahren (generisches
     * Verwaltungsvokabular ist kein Relevanzsignal und erscheint nicht).
     */
    private String matchReason(CaseMatch m) {
        if ("EMAIL_IDENTITY".equals(m.matchType())) {
            return "Identität bestätigt: Die Absender-Adresse stimmt mit der E-Mail-Adresse der Person des Falls überein.";
        }
        if ("THREAD_IDENTITY".equals(m.matchType())) {
            return "Thread-Zugehörigkeit: Die E-Mail bezieht sich über ihre Header (In-Reply-To/References) "
                    + "auf eine E-Mail in diesem Vorgang.";
        }
        if ("NAME_IDENTITY".equals(m.matchType())) {
            return "Mögliche Personenübereinstimmung: Der Name des Absenders (Vor- und Nachname) passt zur Person des Falls.";
        }
        if (SEMANTIC.equals(m.matchType()) && m.semanticScore() != null) {
            return semanticReason(m.semanticScore(), m.semanticContext());
        }
        return similarReason(m);
    }

    /**
     * Erklärung eines SEMANTIC-Treffers (Phase 2C.7): benennt ehrlich den
     * Mechanismus (Vektor-Ähnlichkeit zu den Unterlagen des Vorgangs), die
     * gemessene Ähnlichkeit und — falls vorhanden — den gemeinsamen Kontext
     * als kurzen Auszug. Es werden keine erfundenen Begründungen angezeigt.
     */
    private String semanticReason(double score, String context) {
        String band = score >= thresholds.getHighFloor() ? "hoch" : "mittel";
        StringBuilder sb = new StringBuilder(
                "Semantische Ähnlichkeit mit den Unterlagen dieses Vorgangs: ")
                .append(String.format(Locale.GERMANY, "%.2f", score))
                .append(" · ").append(band);
        if (context != null && !context.isBlank()) {
            sb.append(" · Gemeinsamer Kontext: „").append(context).append("“");
        }
        return sb.toString();
    }

    /**
     * Der Grund eines rein thematischen SIMILAR-Treffers beschreibt ehrlich
     * das tatsächliche Verfahren: ein Abgleich gemeinsamer inhaltlicher
     * Begriffe zwischen E-Mail und Vorgang (Name/Beschreibung) — keine
     * semantische Ähnlichkeitsberechnung, keine Personen-Identität. Es werden
     * nur SPEZIFISCHE Begriffe genannt; generisches Verwaltungsvokabular
     * (z. B. "prüfen", "unterlagen", "antrag") ist kein Relevanzsignal.
     */
    private static String similarReason(CaseMatch m) {
        List<String> specific = m.overlap().stream()
                .filter(t -> !GENERIC_TERMS.contains(t))
                .sorted()
                .toList();
        if (specific.isEmpty()) {
            return "Thematisch ähnlicher Vorgang: Der Vorgang teilt inhaltliche Begriffe mit der E-Mail.";
        }
        return "Thematisch ähnlicher Vorgang: Der Vorgang teilt inhaltliche Begriffe mit der E-Mail "
                + "(Abgleich über gemeinsame Begriffe, keine bestätigte Zuordnung). Gemeinsame Begriffe: "
                + String.join(", ", specific);
    }

    /**
     * Generic administrative terms that appear in many unrelated requests.
     * They are ignored when ranking case matches, so they cannot lift an
     * unrelated case to primary solely because of "unterlagen"/"antrag".
     * Also used by the textual-evidence rule ({@link verwaltungsassistent.web.controller.KnowledgeController}) so
     * a document cannot count as relevant on generic vocabulary alone.
     */
    public static final Set<String> GENERIC_TERMS = Set.of(
            "antrag", "unterlagen", "prüfen", "prüfung", "geprüft", "fehlen", "fehlende",
            "offen", "benötigt", "benötigte", "benötigen", "auskunft", "anfrage",
            "bearbeitungsstand", "sachstand", "rückmeldung", "unvollständig",
            "vorhanden", "fristen", "klären", "anliegen", "einreichen", "eingereicht",
            "einreichung", "beantragung", "beantragen", "mitbringen", "erledigt",
            "aktuell", "anschreiben", "vorgang", "aktenzeichen", "status");

    private Set<String> tokens(String text) {
        return tokensOf(text);
    }

    /** Tokenisierung (öffentlich für Wiederverwendung, z. B. "Ähnliche Anfragen"). */
    public static Set<String> tokensOf(String text) {
        Set<String> result = new HashSet<>();
        for (String raw : text.toLowerCase().split("[^a-zäöüß]+")) {
            String t = raw.trim();
            if (t.length() >= 4 && !STOPWORDS.contains(t)) result.add(t);
        }
        return result;
    }

    public static final Set<String> STOPWORDS = Set.of(
            "guten", "sehr", "geehrte", "damen", "herren", "bitte", "habe", "haben", "nicht",
            "noch", "meine", "mein", "meinen", "meiner", "mich", "mir", "ihre", "ihren",
            "ihnen", "ihrer", "vielen", "danke", "freundlichen", "grüßen", "grusse", "dass",
            "wurde", "wird", "sind", "sein", "eine", "einen", "einem", "einer", "eines",
            "dieser", "diese", "dieses", "diesem", "dies", "oder", "auch", "nach", "bei",
            "vom", "zum", "zur", "als", "auf", "aus", "was", "wie", "wer", "der", "die",
            "das", "den", "dem", "des", "ist", "ein", "im", "in", "an", "am", "es", "und",
            "mit", "für", "fue", "über", "ueber", "sich", "wir", "uns", "sie", "ihr", "bin",
            "kann", "können", "koennen", "muss", "müssen", "muessen", "welche", "welcher",
            "welches", "wann", "wo", "warum", "bereits", "schon", "leider", "erhalten",
            "eingereicht", "gemacht", "geht", "gebe", "gibt");
}
