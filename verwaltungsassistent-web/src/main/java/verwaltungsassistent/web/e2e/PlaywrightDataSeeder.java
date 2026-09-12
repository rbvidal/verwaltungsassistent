package verwaltungsassistent.web.e2e;

import reasoning.common.model.WorkspacePhase;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.planning.persistence.EffortObservationEntity;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import verwaltungsassistent.web.service.DemoDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministischer E2E-Ausgangszustand (playwright-Profil). Baut auf dem
 * vorhandenen {@link DemoDataService#reset()} auf (Benutzer, Mailboxen,
 * E-Mail-Katalog, Fotos — alle Demo-Arbeitsbereiche werden entfernt) und legt
 * danach die Szenario-Fälle mit berechenbarer Priorität an:
 *
 * <pre>
 *   Bauantrag Weber         demo01  Baugenehmigung  Warten 11 T. (30) + Frist HEUTE (25) + Wirkung (10) = 65  HOCH   bearbeitbar, kritische Frist
 *   Gewerbeanmeldung Müller demo01  Gewerbeanmeldung Warten 4 T. (10) + Frist +2 (18) + Wirkung (15) = 43  MITTEL REVIEW, begonnen (ca. 1 Min. Rest)
 *   Wohngeldantrag Schmidt  demo01  Wohngeld        Warten 12 T. (30) + Frist +1 (25) + Wirkung (15) = 70  SEHR HOCH blockiert (Unterlagen)
 *   Gewerbeanmeldung Hartmann demo01 Gewerbeanmeldung Warten 2 T. (0) + Wirkung (15)                   = 15  NIEDRIG bearbeitbar
 *   Ummeldung Krüger        demo02  Ummeldung       Warten 4 T. (10) + Frist +1 (25) + Wirkung (10) = 45  MITTEL bearbeitbar
 *   Reisepass Wagner        demo02  Reisepass       Warten 9 T. (20) + Wirkung (10)                 = 30  MITTEL bearbeitbar
 * </pre>
 *
 * <p>Wartezeit-Faktor laut {@code PriorityCalculationService}: 3–5 T. → 10,
 * 6–10 T. → 20, &gt;10 T. → 30. Frist-Faktor: ≤1 T. → 25, ≤3 → 18, ≤7 → 12.
 * Klassen: ≥70 Sehr hoch, ≥50 Hoch, ≥30 Mittel, sonst Niedrig.</p>
 *
 * <p>Empirische Lern-Tabellen werden geleert; für Test C liegen vier
 * Gewerbeanmeldung-Beobachtungen (8, 8, 9, 9) vor, sodass der fünfte
 * (per Browser) abgeschlossene Fall die Statistik auf den Median 8 kippt
 * (Baseline 3 im Playwright-Profil).</p>
 */
@Component
@Profile("playwright")
@Order(Ordered.LOWEST_PRECEDENCE)
public class PlaywrightDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightDataSeeder.class);

    public static final String EMAIL_A_SUBJECT = "Gewerbeanmeldung – Anzeige zum 1. September";
    public static final String CASE_BAU = "Bauantrag Weber";
    public static final String CASE_GEWERBE_MUELLER = "Gewerbeanmeldung Müller";
    public static final String CASE_WOHNGELD_SCHMIDT = "Wohngeldantrag Schmidt";
    public static final String CASE_GEWERBE_FISCHER = "Gewerbeanmeldung Hartmann";
    public static final String CASE_UMMELDUNG = "Ummeldung Krüger";
    public static final String CASE_REISEPASS = "Reisepass Wagner";

    private final DemoDataService demoDataService;
    private final WorkspaceService workspaceService;
    private final JpaIncomingEmailRepository emailRepository;
    private final JpaEffortObservationRepository observationRepository;
    private final JpaEffortEstimateRepository estimateRepository;
    private final JpaCasePlanningRepository planningRepository;
    private final TestClock testClock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final verwaltungsassistent.web.mailbox.DemoMailboxSeeder demoMailboxSeeder;

    public PlaywrightDataSeeder(DemoDataService demoDataService,
                                WorkspaceService workspaceService,
                                JpaIncomingEmailRepository emailRepository,
                                JpaEffortObservationRepository observationRepository,
                                JpaEffortEstimateRepository estimateRepository,
                                JpaCasePlanningRepository planningRepository,
                                TestClock testClock,
                                verwaltungsassistent.web.mailbox.DemoMailboxSeeder demoMailboxSeeder) {
        this.demoDataService = demoDataService;
        this.workspaceService = workspaceService;
        this.emailRepository = emailRepository;
        this.observationRepository = observationRepository;
        this.estimateRepository = estimateRepository;
        this.planningRepository = planningRepository;
        this.testClock = testClock;
        this.demoMailboxSeeder = demoMailboxSeeder;
    }

    @Override
    public void run(String... args) {
        reseed();
    }

    /** Deterministischer Ausgangszustand; auch über GET /test/reset aufrufbar. */
    public synchronized void reseed() {
        Instant now = Instant.now();
        testClock.set(now);
        demoDataService.reset();
        // Allgemeiner Arbeitspool (admin-owned CASE-Vorgänge aus früheren
        // demo-Profil-Läufen derselben Datenbank) gehört NICHT zum E2E-
        // Szenario — das Szenario ist in sich geschlossen (eigene Fälle,
        // eigene Empfehlungen). Sonst verändert der Pool die Alternativen
        // der Empfehlungskarte der Szenario-Mitarbeiterinnen.
        removeGeneralPoolWorkspaces();
        observationRepository.deleteAll();
        estimateRepository.deleteAll();
        planningRepository.deleteAll();
        // Phase 2C.2: GreenMail-Demo-Mailbox deterministisch neu befüllen.
        demoMailboxSeeder.reseed();

        seedScenarioEmail(now);
        // Bauantrag Weber: Frist HEUTE (kritische Bande, Phase 2B.6) —
        // Priorität unverändert 65 (Frist ≤1 T. → 25).
        scenarioCase(CASE_BAU, "Baugenehmigung für ein Carport – Nachweise prüfen.",
                "demo01@verwaltungsassistent.local", "Baugenehmigung", WorkspacePhase.ANALYSIS,
                Map.of("ingestionResolved", true), 11, 0);
        // Gewerbeanmeldung Müller: REVIEW → "Kurz vor dem Abschluss" (kurzer
        // Restaufwand ca. 1 Min.) — Konkurrenz für die kritische Frist.
        scenarioCase(CASE_GEWERBE_MUELLER, "Gewerbeanmeldung zum 1. September – prüfen und abschließen.",
                "demo01@verwaltungsassistent.local", "Gewerbeanmeldung", WorkspacePhase.REVIEW,
                Map.of("ingestionResolved", true,
                        "workState", Map.of("state", "ACTIVE", "employee", "demo01@verwaltungsassistent.local",
                                "since", now.minus(2, ChronoUnit.MINUTES).toString(), "accumulatedMinutes", 2L)),
                4, 2);
        // Wohngeldantrag Schmidt: SEHR HOCH (70) und blockiert — erklärt in der
        // "Warum nicht?"-Zeile, ist aber nie Kandidat (Phase 2B.7).
        scenarioCase(CASE_WOHNGELD_SCHMIDT, "Wohngeldantrag – Einkommensnachweise fehlen noch.",
                "demo01@verwaltungsassistent.local", "Wohngeld", WorkspacePhase.SETUP,
                Map.of("waitingOn", Map.of("type", "DOCUMENTS", "since", now.minus(2, ChronoUnit.DAYS).toString(),
                        "note", "Einkommensnachweise erwartet")),
                12, 1);
        scenarioCase(CASE_GEWERBE_FISCHER, "Gewerbeanmeldung – Rückfragen zur Tätigkeit.",
                "demo01@verwaltungsassistent.local", "Gewerbeanmeldung", WorkspacePhase.ANALYSIS,
                Map.of("ingestionResolved", true), 2, null);
        scenarioCase(CASE_UMMELDUNG, "Ummeldung nach Umzug nach Oranienburg.",
                "demo02@verwaltungsassistent.local", "Ummeldung", WorkspacePhase.ANALYSIS,
                Map.of("ingestionResolved", true), 4, 1);
        scenarioCase(CASE_REISEPASS, "Reisepass für Minderjährige – Anwesenheit der Eltern.",
                "demo02@verwaltungsassistent.local", "Reisepass", WorkspacePhase.ANALYSIS,
                Map.of("ingestionResolved", true), 9, null);

        for (int minutes : new int[]{8, 8, 9, 9}) {
            EffortObservationEntity observation = new EffortObservationEntity(UUID.randomUUID());
            observation.setCategory("Gewerbeanmeldung");
            observation.setObservedActiveMinutes(minutes);
            observation.setCompletedAt(now);
            observationRepository.save(observation);
        }
        log.info("Playwright-Ausgangszustand gesetzt: 6 Szenario-Fälle, 1 E-Mail, 4 Beobachtungen (Gewerbeanmeldung)");
    }

    private void removeGeneralPoolWorkspaces() {
        for (WorkspaceEntity ws : workspaceService.findAll()) {
            if (!"CASE".equalsIgnoreCase(ws.getWorkspaceType())) {
                continue;
            }
            if (!DemoDataService.isGeneralPoolOwner(ws.getOwnerId())) {
                continue;
            }
            // Nur OFFENE Pool-Vorgänge gehören nicht ins E2E-Szenario. Die
            // ABGESCHLOSSENEN Abschluss-Szenario-Vorgänge (Müllsäcke,
            // Straßenlaterne, Termin) sind Teil des E-Mail-Szenarios — ihre
            // Erledigt-E-Mails hängen an ihnen (Phase 2C.1).
            if (ws.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED
                    || ws.getStatus() == reasoning.common.model.WorkspaceStatus.ARCHIVED) {
                continue;
            }
            try {
                workspaceService.deleteWorkspace(ws.getId().toString());
                planningRepository.deleteByCaseId(java.util.UUID.fromString(ws.getId()));
            } catch (Exception e) {
                log.warn("Pool-Vorgang '{}' konnte nicht entfernt werden: {}", ws.getName(), e.getMessage());
            }
        }
    }

    private void seedScenarioEmail(Instant now) {
        IncomingEmailEntity email = new IncomingEmailEntity(UUID.randomUUID(),
                EMAIL_A_SUBJECT, "Claudia Fischer", "claudia.fischer@example.de",
                "Betreff: Gewerbeanmeldung\n\nSehr geehrte Damen und Herren,\n\nich möchte zum 1. September "
                        + "mein Gewerbe anmelden. Welche Unterlagen benötigen Sie dafür und wie vereinbare ich "
                        + "einen Termin?\n\nMit freundlichen Grüßen\nClaudia Fischer",
                now.minus(3, ChronoUnit.DAYS), AddressedTo.EMPLOYEE, "demo01@verwaltungsassistent.local");
        emailRepository.save(email);
    }

    private WorkspaceEntity scenarioCase(String name, String description, String owner,
                                         String category, WorkspacePhase phase,
                                         Map<String, Object> phaseEntries, int ageDays,
                                         Integer deadlineDays) {
        WorkspaceEntity ws = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(name, description, "CASE", owner));
        ws.setPhase(phase);
        ws.setCreatedAt(Instant.now().minus(ageDays, ChronoUnit.DAYS));
        Map<String, Object> data = new LinkedHashMap<>(phaseEntries);
        data.put("caseCategory", category);
        try {
            ws.setPhaseData(mapper.writeValueAsString(data));
        } catch (Exception e) {
            ws.setPhaseData("{}");
        }
        workspaceService.save(ws);
        if (deadlineDays != null) {
            workspaceService.addTimelineEvent(ws.getId(), LocalDate.now().plusDays(deadlineDays),
                    "Frist", "Frist für die abschließende Bearbeitung",
                    TimelineEventType.DEADLINE, null, 1.0, false);
        }
        return ws;
    }
}
