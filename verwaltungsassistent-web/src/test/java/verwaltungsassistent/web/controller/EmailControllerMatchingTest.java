package verwaltungsassistent.web.controller;

import reasoning.ai.application.DecisionRouter;
import reasoning.search.api.SearchFacade;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused regression test for the E-Mail → existing case matching:
 * a specific match (Baugenehmigung Carport) must outrank an unrelated case
 * that shares only generic terms (Fall Müller - Wohngeld: "unterlagen", "antrag"),
 * and every "Fall öffnen" action must target the primary case.
 */
@ExtendWith(MockitoExtension.class)
class EmailControllerMatchingTest {

    @Mock
    private JobProgressService progressService;
    @Mock
    private DecisionRouter decisionRouter;
    @Mock
    private reasoning.ai.application.DomainGate domainGate;
    @Mock
    private SearchFacade searchFacade;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private JpaDocumentChunkRepository chunkRepository;
    @Mock
    private TemplateEngine templateEngine;
    @Mock
    private JpaEmailAnalysisRepository analysisRepository;
    @Mock
    private verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;
    @Mock
    private ObjectMapper objectMapper;

    private EmailController controller;

    private static final String MUELLER_ID = UUID.randomUUID().toString();
    private static final String CARPORT_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        controller = new EmailController(progressService, decisionRouter, domainGate, searchFacade,
                workspaceService, chunkRepository, templateEngine, analysisRepository,
                incomingEmailRepository,
                mock(verwaltungsassistent.web.analysis.persistence.MailboxRepository.class),
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class), objectMapper,
                new verwaltungsassistent.web.planning.PriorityCalculationService(),
                mock(reasoning.ai.api.AiFacade.class),
                mock(verwaltungsassistent.web.security.CaseAccessGuard.class),
                // ECHTER Matching-Service (mit den gemockten Abhängigkeiten):
                // die Semantik des Fall-Abgleichs bleibt durch die Delegation
                // des Controllers testbar.
                new verwaltungsassistent.web.service.EmailCaseMatchingService(
                        workspaceService, incomingEmailRepository,
                        mock(reasoning.search.api.SearchFacade.class),
                        new verwaltungsassistent.web.config.SemanticMatchingProperties()));
    }

    private List<WorkspaceEntity> demoWorkspaces() {
        // Mirrors the demo DB: Müller was created first, so findAll returns it first.
        return List.of(
                ws(MUELLER_ID, "Fall Müller - Wohngeld",
                        "Antrag auf Wohngeld prüfen, Unterlagen unvollständig."),
                ws(UUID.randomUUID().toString(), "Keller umstellen auf Gemeinschaftsraum",
                        "Test-Beschreibung aktualisiert"),
                ws(UUID.randomUUID().toString(), "Ummeldung nach Umzug",
                        "Ummeldung nach Umzug – benötigte Unterlagen und Fristen klären."),
                ws(UUID.randomUUID().toString(), "Reisepass – Minderjährige",
                        "Reisepass für Minderjährige – benötigte Unterlagen und Anwesenheit der Eltern."),
                ws(CARPORT_ID, "Baugenehmigung Carport",
                        "Antrag auf Baugenehmigung für ein Carport – fehlende Unterlagen."),
                ws(UUID.randomUUID().toString(), "Gewerbeanmeldung",
                        "Gewerbeanmeldung zum 1. September – benötigte Unterlagen und Online-Termin."));
    }

    private static WorkspaceEntity ws(String id, String name, String description) {
        WorkspaceEntity e = new WorkspaceEntity("WS-" + id.substring(0, 8).toUpperCase(),
                name, description, "CASE", "admin@verwaltungsassistent.local");
        e.setId(id);
        return e;
    }

    /** Identitäts-Test (Issue 1): Ein Fall mit phaseData.citizen + Absender-Signatur. */
    @Test
    void nameMatch_senderNameMatchingCitizen_isMarkedNameIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nErika Müller",
                null, null, true);

        assertEquals(1, matches.size());
        assertEquals("NAME_IDENTITY", matches.get(0).matchType(),
                "sender NAME matching the case citizen is a name-based identity signal");
        assertTrue(matches.get(0).reason().contains("Personenübereinstimmung"),
                "name-based matches honestly say 'possible person match', not confirmed identity");
    }

    /**
     * Issue 3 (Identitäts-Hierarchie): ein reiner VORNAMEN-Treffer darf nie
     * eine Personen-Identität erzeugen. "Erika Schulze" (Absender) gegen
     * "Fall Müller – Wohngeld" mit Bürgerin "Erika Müller" teilt nur den
     * Vornamen — das ist SIMILAR/Referenz, keine NAME_IDENTITY.
     */
    @Test
    void firstNameCoincidence_only_isNeverNameIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nErika Schulze",
                null, null, true);

        assertFalse(matches.stream().anyMatch(m -> m.matchType().equals("NAME_IDENTITY")),
                "Erika == Erika alone must never be a person identity");
        matches.stream()
                .filter(m -> m.matchType().equals("SIMILAR"))
                .forEach(m -> assertFalse(m.reason().contains("Personenübereinstimmung"),
                        "topic similarity must not be phrased as identity: " + m.reason()));
    }

    /**
     * Issue 3: gleicher Vor- UND Nachname ("Erika Schulze" gegen Bürgerin
     * "Erika Schulze") ist eine mögliche Personen-Identität (NAME_IDENTITY) —
     * ehrlich als "möglich" gekennzeichnet, nicht als bestätigt.
     */
    @Test
    void fullNameMatch_isMarkedNameIdentity() {
        WorkspaceEntity schulze = ws(UUID.randomUUID().toString(), "Fall Schulze - Wohngeld",
                "Wohngeldantrag – Unterlagen unvollständig.");
        schulze.setPhaseData("{\"citizen\":\"Erika Schulze\"}");
        when(workspaceService.findAll()).thenReturn(List.of(schulze));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nErika Schulze",
                null, null, true);

        assertEquals(1, matches.size());
        assertEquals("NAME_IDENTITY", matches.get(0).matchType(),
                "full-name match is a possible person identity");
        assertTrue(matches.get(0).reason().contains("Personenübereinstimmung"),
                "name identity stays honest: possible, not confirmed");
    }

    /** Issue 3: gleicher Nachname, aber unterschiedliche Vornamen — andere Person, keine Identität. */
    @Test
    void sameSurnameDifferentFirstNames_isNotIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nThomas Müller",
                null, null, true);

        assertFalse(matches.stream().anyMatch(m -> m.matchType().equals("NAME_IDENTITY")),
                "same surname with different known first names is a different person");
    }

    /** Issue 3: die Absender-ADRESSE (stärkstes Signal) hat Vorrang vor dem Namen. */
    @Test
    void emailMatch_beatsNameIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));
        IncomingEmailEntity prior = new IncomingEmailEntity(UUID.randomUUID(),
                "Wohngeldantrag", "Erika Müller", "erika.schulze@example.de",
                "Betreff: Wohngeldantrag\n\nMit freundlichen Grüßen\nErika Schulze",
                java.time.Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(MUELLER_ID)))
                .thenReturn(List.of(prior));

        // Vorname passt ("Erika"), Nachname nicht ("Schulze" vs "Müller") —
        // aber die Absender-ADRESSE ist im Vorgang hinterlegt: EMAIL_IDENTITY.
        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nErika Schulze",
                "erika.schulze@example.de", null, true);

        assertEquals(1, matches.size());
        assertEquals("EMAIL_IDENTITY", matches.get(0).matchType(),
                "the sender address on file is the strongest identity signal");
        assertTrue(matches.get(0).reason().contains("Identität bestätigt"),
                "email identity is phrased as confirmed");
    }

    @Test
    void emailMatch_senderAddressKnownInCase_isMarkedEmailIdentity() {
        WorkspaceEntity carport = ws(CARPORT_ID, "Baugenehmigung Carport",
                "Antrag auf Baugenehmigung für ein Carport – fehlende Unterlagen.");
        carport.setPhaseData("{\"citizen\":\"Bernd Becker\"}");
        when(workspaceService.findAll()).thenReturn(List.of(carport));
        // Die Adresse des Absenders ist im Vorgang bereits durch eine
        // zugeordnete E-Mail belegt → stärkstes Personen-Signal.
        IncomingEmailEntity prior = new IncomingEmailEntity(UUID.randomUUID(),
                "Sachstand Baugenehmigung", "Bernd Becker", "bernd.becker@example.de",
                "Betreff: Sachstand\n\nMit freundlichen Grüßen\nBernd Becker",
                java.time.Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(CARPORT_ID)))
                .thenReturn(List.of(prior));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Baugenehmigung Carport\n\nMit freundlichen Grüßen\nBernd Becker",
                "bernd.becker@example.de", null, true);

        assertEquals(1, matches.size());
        assertEquals("EMAIL_IDENTITY", matches.get(0).matchType(),
                "a sender address already on file in the case is confirmed identity");
        assertTrue(matches.get(0).reason().contains("Identität bestätigt"),
                "email identity reasons say 'identity confirmed'");
    }

    @Test
    void emailAddressMismatch_doesNotImplyEmailIdentity() {
        WorkspaceEntity carport = ws(CARPORT_ID, "Baugenehmigung Carport",
                "Antrag auf Baugenehmigung für ein Carport – fehlende Unterlagen.");
        carport.setPhaseData("{\"citizen\":\"Bernd Becker\"}");
        when(workspaceService.findAll()).thenReturn(List.of(carport));
        IncomingEmailEntity prior = new IncomingEmailEntity(UUID.randomUUID(),
                "Sachstand Baugenehmigung", "Bernd Becker", "bernd.becker@example.de",
                "Betreff: Sachstand\n\nMit freundlichen Grüßen\nBernd Becker",
                java.time.Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(CARPORT_ID)))
                .thenReturn(List.of(prior));

        // Gleicher NAME, aber eine ANDERE Adresse → keine bestätigte Identität,
        // höchstens eine mögliche Personenübereinstimmung über den Namen.
        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Baugenehmigung Carport\n\nMit freundlichen Grüßen\nBernd Becker",
                "b.becker@other.example.de", null, true);

        assertEquals(1, matches.size());
        assertEquals("NAME_IDENTITY", matches.get(0).matchType(),
                "an unknown address must not claim confirmed identity");
    }

    @Test
    void similarOnlyMatch_isNotMarkedIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        WorkspaceEntity carport = ws(CARPORT_ID, "Baugenehmigung Carport",
                "Antrag auf Baugenehmigung für ein Carport – fehlende Unterlagen.");
        carport.setPhaseData("{\"citizen\":\"Bernd Becker\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller, carport));

        // Absender ist Bernd Becker, Thema Wohngeld+Carport → Namens-Signal zu
        // Bernds Carport-Fall, reine Themen-Ähnlichkeit zum Müllers Wohngeld-Fall.
        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeld und Carport Baugenehmigung\n\nBitte prüfen Sie meinen Wohngeldantrag "
                        + "und meinen Bauantrag für das Carport.\n\nMit freundlichen Grüßen\nBernd Becker",
                null, null, true);

        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> m.matchType().equals("NAME_IDENTITY")
                        && m.name().contains("Carport")),
                "person signal goes to the case of the sender");
        assertTrue(matches.stream().anyMatch(m -> m.matchType().equals("SIMILAR")
                        && m.name().contains("Müller")),
                "topic-only overlap is a reference candidate, never an identity claim");
    }

    private static final String CARPORT_EMAIL = """
            Betreff: Baugenehmigung für ein Carport – fehlende Unterlagen

            Sehr geehrte Damen und Herren,

            ich habe einen Antrag auf Baugenehmigung für ein Carport auf
            meinem Grundstück gestellt. Leider habe ich noch keine Rückmeldung
            erhalten. Welche Unterlagen fehlen meinem Bauantrag noch?

            Mit freundlichen Grüßen
            Bernd Becker""";

    private static final String WOHNGELD_EMAIL = """
            Betreff: Wohngeldantrag – welche Unterlagen fehlen noch?

            Guten Tag,

            ich habe vor zwei Wochen meinen Antrag auf Wohngeld eingereicht.
            Ich bin mir aber nicht sicher, ob alle Unterlagen angekommen sind.

            Mit freundlichen Grüßen
            Erika Müller""";

    @Test
    void carportEmail_primaryCaseIsCarportNotWohngeld() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        List<EmailController.CaseRef> matches = controller.matchCases(CARPORT_EMAIL, null, null, true);

        assertEquals(1, matches.size());
        assertEquals("Baugenehmigung Carport", matches.get(0).name());
        assertEquals(CARPORT_ID, matches.get(0).id());
    }

    @Test
    void carportEmail_allFallOeffnenActionsTargetPrimaryCarportCase() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        List<EmailController.CaseRef> matches = controller.matchCases(CARPORT_EMAIL, null, null, true);
        EmailController.EmailOutcome outcome = controller.buildOutcome(
                "Baugenehmigung für ein Carport – fehlende Unterlagen", CARPORT_EMAIL,
                "BUILDING", null, List.of(), matches, null);

        assertEquals("Baugenehmigung Carport", outcome.matchedCases().get(0).name());
        // Bestehender Fall → Fall öffnen
        assertEquals("/cases/" + CARPORT_ID,
                "/cases/" + outcome.matchedCases().get(0).id());
        // Nächste Schritte → Fall öffnen (step 1)
        EmailController.Step step1 = outcome.steps().get(0);
        assertEquals("Fall öffnen", step1.actionLabel());
        assertEquals("/cases/" + CARPORT_ID, step1.actionUrl());
        // Checkliste/Entscheidung steps also use the primary case
        assertEquals("/cases/" + CARPORT_ID, outcome.steps().get(2).actionUrl());
        assertEquals("/cases/" + CARPORT_ID + "/decision", outcome.steps().get(4).actionUrl());
    }

    @Test
    void wohngeldEmail_primaryCaseIsStillMuller() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        List<EmailController.CaseRef> matches = controller.matchCases(WOHNGELD_EMAIL, null, null, true);

        assertEquals(1, matches.size());
        assertEquals("Fall Müller - Wohngeld", matches.get(0).name());
        assertEquals(MUELLER_ID, matches.get(0).id());
    }

    @Test
    void genericTermsAlone_doNotProduceAMatch() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        // Only generic administrative terms — no case may match on them alone.
        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Antrag und Unterlagen prüfen\n\nBitte prüfen Sie den Antrag und die Unterlagen.", null, null, true);

        assertTrue(matches.isEmpty());
    }

    /** Der Anzeige-Grund eines Treffers nennt nur SPEZIFISCHE Begriffe — generisches
     *  Verwaltungsvokabular ("prüfen", "unterlagen", "antrag") ist kein Relevanzsignal
     *  und darf nicht als Treffer-Begründung erscheinen. */
    @Test
    void matchReason_listsOnlySpecificTerms() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        List<EmailController.CaseRef> matches = controller.matchCases(WOHNGELD_EMAIL, null, null, true);

        assertEquals(1, matches.size());
        String reason = matches.get(0).reason();
        assertTrue(reason.contains("wohngeld"), "specific term is shown: " + reason);
        assertFalse(reason.contains("prüfen"), "generic term must not be presented as a match reason: " + reason);
        assertFalse(reason.contains("unterlagen"), "generic term must not be presented as a match reason: " + reason);
        assertFalse(reason.contains("antrag"), "generic term must not be presented as a match reason: " + reason);
    }

    /**
     * Phase 2C.4: Der SIMILAR-Treffer beruht auf dem lexikalischen Abgleich
     * gemeinsamer inhaltlicher Begriffe — die Anzeige nennt das Verfahren
     * ehrlich und erfindet keine semantische Ähnlichkeit oder einen
     * Personen-Bezug. Der irreführende Begriff "Übereinstimmende Begriffe"
     * erscheint nicht mehr.
     */
    @Test
    void similarMatch_reasonHonestlyDescribesLexicalOverlap() {
        when(workspaceService.findAll()).thenReturn(demoWorkspaces());

        List<EmailController.CaseRef> matches = controller.matchCases(WOHNGELD_EMAIL, null, null, true);

        assertEquals(1, matches.size());
        assertEquals("SIMILAR", matches.get(0).matchType());
        String reason = matches.get(0).reason();
        assertTrue(reason.startsWith("Thematisch ähnlicher Vorgang"), reason);
        assertTrue(reason.contains("Gemeinsame Begriffe"),
                "the shared meaningful terms are named: " + reason);
        assertFalse(reason.contains("Übereinstimmende Begriffe"),
                "keyword-matching wording must not appear: " + reason);
        assertFalse(reason.contains("semantisch"),
                "no semantic-similarity claim for a lexical match: " + reason);
    }

    /**
     * Phase 2C.4: Ein Identitäts-Treffer wird über sein Personensignal erklärt —
     * die Liste überlappender Begriffe ist dort kein Erklärungsmechanismus und
     * darf nicht als Keyword-Matching dargestellt werden.
     */
    @Test
    void identityMatch_reasonDoesNotListKeywordOverlap() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));
        IncomingEmailEntity prior = new IncomingEmailEntity(UUID.randomUUID(),
                "Wohngeldantrag", "Erika Müller", "erika.schulze@example.de",
                "Betreff: Wohngeldantrag\n\nMit freundlichen Grüßen\nErika Schulze",
                java.time.Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "kontakt@verwaltungsassistent.local");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(MUELLER_ID)))
                .thenReturn(List.of(prior));

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeldantrag\n\nich habe meinen Antrag eingereicht.\n\nMit freundlichen Grüßen\nErika Schulze",
                "erika.schulze@example.de", null, true);

        assertEquals(1, matches.size());
        assertEquals("EMAIL_IDENTITY", matches.get(0).matchType());
        String reason = matches.get(0).reason();
        assertTrue(reason.contains("Identität bestätigt"), reason);
        assertFalse(reason.contains("Übereinstimmende Begriffe"),
                "identity is explained by the address, not by keyword overlap: " + reason);
    }

    /**
     * Thema-Erkennung für die Straßenbeleuchtungs-E-Mail (Issue 6): der aktuelle
     * TOPIC_RULES-Katalog kennt KEIN Thema für Straßen-/Infrastruktur-Meldungen —
     * "Defekte Straßenlaterne" fällt auf "Allgemeines Anliegen" zurück. Dadurch
     * ist der Themen-Anker-Filter der E-Mail-Suche für diese Anfrage wirkungslos
     * (keine Ankerbegriffe), und bei fehlendem Straßen-Dokument im Korpus werden
     * die nächstbesten schwachen Treffer zugelassen. Das dokumentiert die
     * Ursache — es ist primär eine Datenlücke (kein Beleuchtungs-Dokument im
     * Korpus), nicht ein Fehler im Anker-Mechanismus selbst.
     */
    @Test
    void streetLampEmail_topicDetectionFallsBackToGeneral() {
        String emailText = "Betreff: Straßenbeleuchtung\n\nSehr geehrte Damen und Herren, die "
                + "Straßenlaterne gegenüber der Lehnitzer Straße 12 fällt seit drei Nächten "
                + "komplett aus. Bitte veranlassen Sie eine Reparatur.";

        assertEquals("Allgemeines Anliegen", EmailController.detectTopic(emailText),
                "kein TOPIC_RULE deckt Straßenbeleuchtung/Infrastruktur ab → Anker-Gate ist wirkungslos");
    }

    @Test
    void generalTopic_hasNoAnchorTerms() {
        // "Allgemeines Anliegen" definiert keine Ankerbegriffe — der
        // containsTopicAnchor-Filter kehrt für dieses Thema leer zurück und
        // lässt die Roh-Reihung unverändert passieren.
        assertTrue(EmailController.TOPIC_RULES.stream()
                        .noneMatch(r -> r.topic().equals("Allgemeines Anliegen") && r.terms().length > 0),
                "Allgemeines Anliegen hat keine Ankerbegriffe");
    }

    // ── Topic anchor for presented documents ──

    private static final UUID REISEPASS_DOC = UUID.randomUUID();
    private static final UUID HAUPTWOHNUNG_DOC = UUID.randomUUID();

    /**
     * Realistic corpus evidence measured live for "Ummeldung nach Umzug – was
     * wird benötigt?": the topic documents contain Anmeldung/Ummeldung terms,
     * while "Reisepass beantragen" only mentions "Wohnung"/"Hauptwohnsitz"
     * incidentally (custody note) and "schnell" (query wording) — it must be
     * dropped by the An-/Ummeldung anchor, the topic documents must pass.
     */
    @Test
    void topicAnchor_dropsIncidentalPassportDoc_keepsUmmeldungDocs() {
        when(chunkRepository.findByDocumentIdOrderByChunkIndex(REISEPASS_DOC)).thenReturn(List.of(
                chunkText("Sich ohne feste Wohnung in Berlin aufhalten, oder vorübergehend "
                        + "mit Hauptwohnsitz oder alleiniger Wohnung gemeldet sein."),
                chunkText("Wie schnell das Kind betreut wird, entscheidet das Jugendamt.")));
        when(chunkRepository.findByDocumentIdOrderByChunkIndex(HAUPTWOHNUNG_DOC)).thenReturn(List.of(
                chunkText("Bei einem Wohnungswechsel ist die Anmeldung der neuen Wohnung "
                        + "innerhalb der Frist zu erklären.")));

        assertFalse(EmailController.containsTopicAnchor(chunkRepository, REISEPASS_DOC, "An- / Ummeldung"),
                "incidental Wohnung/Hauptwohnsitz mentions are not Ummeldung topic evidence");
        assertTrue(EmailController.containsTopicAnchor(chunkRepository, HAUPTWOHNUNG_DOC, "An- / Ummeldung"),
                "Anmeldung mentions anchor the Ummeldung topic document");
    }

    @Test
    void topicAnchor_genericTopicWithoutRules_fallsBackToFalse() {
        // No stubbing: a topic without rules anchors nothing and must never
        // touch the chunk repository — the caller falls back to the raw ranking.
        assertFalse(EmailController.containsTopicAnchor(chunkRepository, HAUPTWOHNUNG_DOC, "Allgemeines Anliegen"),
                "a topic without rules anchors nothing; the caller falls back to the raw ranking");
    }

    // ── Phase 2C.1: Header-basierte Thread-Zugehörigkeit ──

    @Test
    void threadReference_matchingLinkedMessageId_isThreadIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag – Unterlagen prüfen.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));
        // Im Vorgang liegt eine E-Mail mit messageId msg-wohngeld-01.
        IncomingEmailEntity linked = new IncomingEmailEntity(UUID.randomUUID(),
                "Wohngeldantrag", "Erika Müller", "erika.mueller@example.de", "Text",
                java.time.Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, null);
        linked.setMessageId("msg-wohngeld-01");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(any())).thenReturn(List.of(linked));

        // Absender weder per Adresse noch per Name im Vorgang bekannt — der
        // THREAD-Header (References) ist das einzige Signal.
        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeld Nachfrage\n\nGuten Tag, ich frage nach dem Stand.\n\nMit freundlichen Grüßen\nThomas Schulze",
                "t.schulze@example.de", null, true, Set.of("msg-wohngeld-01"));

        assertEquals("THREAD_IDENTITY", matches.get(0).matchType(),
                "die References-Header müssen die Thread-Zugehörigkeit belegen");
        assertTrue(matches.get(0).reason().contains("Thread-Zugehörigkeit"),
                "der Grund muss die Header-basierte Zuordnung erklären");
    }

    @Test
    void threadReference_unknownMessageId_inventsNoThreadIdentity() {
        WorkspaceEntity muller = ws(MUELLER_ID, "Fall Müller - Wohngeld",
                "Wohngeldantrag – Unterlagen prüfen.");
        muller.setPhaseData("{\"citizen\":\"Erika Müller\"}");
        when(workspaceService.findAll()).thenReturn(List.of(muller));
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(any())).thenReturn(List.of());

        List<EmailController.CaseRef> matches = controller.matchCases(
                "Betreff: Wohngeld Nachfrage\n\nMit freundlichen Grüßen\nThomas Schulze",
                "t.schulze@example.de", null, true, Set.of("msg-unbekannt"));

        assertTrue(matches.stream().noneMatch(m -> "THREAD_IDENTITY".equals(m.matchType())),
                "eine unbekannte Referenz darf keine Thread-Zugehörigkeit erfinden");
    }

    private static reasoning.search.infrastructure.persistence.DocumentChunkEntity chunkText(String text) {
        return new reasoning.search.infrastructure.persistence.DocumentChunkEntity(
                UUID.randomUUID(), REISEPASS_DOC, 1,
                reasoning.search.model.ChunkType.TEXT, text, 1, null,
                0, null, null, "Titel", reasoning.common.model.DocumentFileType.PDF,
                "OTHER", java.util.Set.of(), "upload", null, java.time.Instant.now(),
                java.util.Set.of(), null, null, null, null, null);
    }
}
