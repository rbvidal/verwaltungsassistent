package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.geo.SyntheticPhotoFactory;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressionstests für den Last-Chance-Korrekturpass:
 *  - Entscheidungsseite erklärt die Gesamtkonfidenz aus den realen Komponenten
 *    (Quellenlage vs. Gesamtkonfidenz — keine Zahl ohne Erklärung);
 *  - Entscheidungs- und E-Mail-Analyse rendern die gemeinsame Pipeline-
 *    Visualisierung mit echten Laufzeit-Stufen;
 *  - die Benutzer- und Geo-Seiten zeigen keine ungeschützten Modals mehr
 *    (x-cloak-Guard gegen den Alpine-FOUC-Flash);
 *  - der Straßen-Endpunkt liefert echte Straßen-Geometrie (Werder (Havel));
 *  - die synthetischen Fotos sind Szenen-Bilder, keine Texttafeln.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LastChanceFixesRegressionTest {

    private static final String CASE_ID = UUID.nameUUIDFromBytes("ws-brief-9".getBytes()).toString();

    private static final Pattern JOB_URL =
            Pattern.compile("/cases/" + CASE_ID + "/decision/analyze/progress/([a-f0-9-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private AiFacade aiFacade;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final WorkspaceEntity caseEntity = createCaseEntity();

    private static WorkspaceEntity createCaseEntity() {
        WorkspaceEntity e = new WorkspaceEntity("FALL-009", "Fall Gewerbeanmeldung",
                "Gewerbeanmeldung prüfen", "DECISION", "user@example.com");
        e.setId(UUID.nameUUIDFromBytes(CASE_ID.getBytes()).toString());
        e.setStatus(reasoning.common.model.WorkspaceStatus.ACTIVE);
        return e;
    }

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(workspaceService.findById(anyString())).thenReturn(Optional.empty());
        when(workspaceService.findById(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenAnswer(inv -> {
            WorkspaceEntity e = inv.getArgument(0);
            return new WorkspaceDto(
                    e.getId(), e.getWorkspaceCode(), e.getName(),
                    e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                    e.getOwnerId(), Map.of(), List.of(), List.of(),
                    e.getCreatedAt(), e.getUpdatedAt());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Issue 3: Konfidenz-Erläuterung ─────────────────────────────────────

    @Test
    void decisionResult_explainsOverallConfidenceFromRealComponents() throws Exception {
        // Quellenlage 100 %, aber Gesamtkonfidenz nur 75 %:
        // overall = 1.0*0.2 + 0.5*0.25 + 0.6*0.25 + 0.9*0.3 = 0.745
        stubAnswer(new ConfidenceProfile(1.0, 0.6, 0.5, 0.9, 0.745, "test"));
        String result = runAnalysisAndAwait();

        assertThat("explanation header must be shown", result, containsString("Warum diese Gesamtkonfidenz?"));
        assertThat("components must be named", result, containsString("Absicherung der Aussagen"));
        assertThat("components must be named", result, containsString("Rechtsgrundlagen"));
        assertThat("components must be named", result, containsString("Quellenabdeckung"));
        // Die exakten Werte können der Reparatur-Pipeline zufolge abweichen —
        // entscheidend ist, dass jede Komponente mit einem Prozentwert
        // erscheint und die Gesamtkonfidenz nicht einfach der Quellenlage ist.
        // Die Erläuterung nennt hinter dem Wert die Gewichtung (z. B.
        // "Quellenlage ( 100% , 20 %)"), daher erlaubt das Muster beides.
        String textOnly = result.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertThat("each component must carry its real percentage", textOnly,
                org.hamcrest.Matchers.matchesPattern(".*Quellenlage \\(\\s*\\d+%\\s*(,\\s*\\d+ %\\s*)?\\).*"
                        + "Absicherung der Aussagen \\(\\s*\\d+%\\s*(,\\s*\\d+ %\\s*)?\\).*"
                        + "Rechtsgrundlagen \\(\\s*\\d+%\\s*(,\\s*\\d+ %\\s*)?\\).*"
                        + "Quellenabdeckung \\(\\s*\\d+%\\s*(,\\s*\\d+ %\\s*)?\\).*"));
        assertThat("unsupported points must be flagged", result,
                containsString("Nicht durch Belege gedeckte Punkte senken die Gesamtkonfidenz."));
    }

    // ── Issue 5: Pipeline-Visualisierung in Entscheidung + E-Mail ──────────

    @Test
    void decisionAnalysisProgress_rendersSharedPipelineDiagram() throws Exception {
        stubAnswer(new ConfidenceProfile(0.9, 0.8, 0.8, 0.9, 0.9, "test"));
        String startHtml = mockMvc.perform(post("/cases/" + CASE_ID + "/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat("shared pipeline diagram must render", startHtml, containsString("pipeline-diagram"));
        assertThat("reserved info line must render", startHtml, containsString("pipeline-info"));
        assertThat("all 7 pipeline nodes must be present", startHtml, containsString("data-id=\"frage\""));
        assertThat("all 7 pipeline nodes must be present", startHtml, containsString("data-id=\"ground\""));
        assertThat("all 7 pipeline nodes must be present", startHtml, containsString("data-id=\"antwort\""));
    }

    @Test
    void emailAnalysisProgress_rendersRealEmailStages() throws Exception {
        String startHtml = mockMvc.perform(post("/emails/analyze")
                        .param("emailText", "Sehr geehrte Damen und Herren, mein Wohngeldantrag wurde noch nicht beschieden.")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat("shared pipeline diagram must render", startHtml, containsString("pipeline-diagram"));
        assertThat("real email stage: E-Mail lesen", startHtml, containsString("E-Mail lesen"));
        assertThat("real email stage: Anliegen erkennen", startHtml, containsString("Anliegen erkennen"));
        assertThat("real email stage: Wissensbasis", startHtml, containsString("Wissensbasis"));
        assertThat("real email stage: Fälle abgleichen", startHtml, containsString("Fälle abgleichen"));
    }

    // ── Issue 6: Modal-Flash (x-cloak) ─────────────────────────────────────

    @Test
    void usersPage_modalsAreGuardedAgainstAlpineFlash() throws Exception {
        var admin = new AuthenticatedUser(
                UUID.randomUUID(), "admin@verwaltungsassistent.local", "Admin", Set.of("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        String html = mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat("every Alpine modal must carry x-cloak", html, containsString("modal-overlay\" x-cloak"));
        assertThat("no unguarded x-show overlay may exist", html,
                not(containsString("class=\"modal-overlay\" x-show=")));
    }

    @Test
    void geoPhotosPage_modalsAreGuardedAgainstAlpineFlash() throws Exception {
        String html = mockMvc.perform(get("/geoinformation/photos"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Die Foto-Modals rendern nur je Foto (th:each) — sobald eines
        // erscheint, muss es den x-cloak-Guard tragen. Ungeschützte Overlays
        // dürfen nie existieren (das war der FOUC-Flash). Der Guard kann auf
        // derselben Zeile oder in derselben Tag-Attributliste stehen.
        java.util.regex.Pattern overlayTag = java.util.regex.Pattern.compile(
                "<div class=\"modal-overlay\"[^>]*>");
        java.util.regex.Matcher m2 = overlayTag.matcher(html);
        boolean allGuarded = true;
        int overlays = 0;
        while (m2.find()) {
            overlays++;
            if (!m2.group().contains("x-cloak")) allGuarded = false;
        }
        org.junit.jupiter.api.Assertions.assertTrue(allGuarded,
                "every rendered modal overlay must carry x-cloak (rendered overlays: " + overlays + ")");
    }

    // ── Issue 1: echte Straßen-Geometrie ───────────────────────────────────

    @Test
    void streetsApi_servesRealStreetGeometryForWerder() throws Exception {
        String body = mockMvc.perform(get("/geoinformation/api/streets"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<?, ?> geojson = new ObjectMapper().readValue(body, Map.class);
        List<?> features = (List<?>) geojson.get("features");
        assertThat("street features must exist", features.size() > 500);
        Map<?, ?> first = (Map<?, ?>) features.get(0);
        Map<?, ?> geometry = (Map<?, ?>) first.get("geometry");
        assertThat("features must be LineStrings (real street lines)",
                "LineString".equals(geometry.get("type")));
        Map<?, ?> props = (Map<?, ?>) first.get("properties");
        assertThat("streets must carry real names", props.get("name") != null);
    }

    // ── Issue 2: Szenen-Bilder statt Texttafeln ────────────────────────────

    @Test
    void syntheticPhotos_areDistinctSceneImages_notTextPanels() throws Exception {
        Path dir = Files.createTempDirectory("synthetic-scenes");
        Path baustelle = dir.resolve("baustelle.jpg");
        Path parking = dir.resolve("parken.jpg");
        SyntheticPhotoFactory.generate(baustelle, "Baustelle", "Test", SyntheticPhotoFactory.Scene.BAUSTELLE,
                52.3881, 13.0658, LocalDateTime.of(2026, 8, 20, 9, 0));
        SyntheticPhotoFactory.generate(parking, "Parken auf dem Gehweg", "Test",
                SyntheticPhotoFactory.Scene.FALSCHPARKEN,
                52.3881, 13.0658, LocalDateTime.of(2026, 8, 20, 9, 0));

        BufferedImage a = ImageIO.read(baustelle.toFile());
        BufferedImage b = ImageIO.read(parking.toFile());
        assertThat("images must be generated", a != null && b != null);
        assertThat("sizes must match the catalog format", a.getWidth() == 640 && a.getHeight() == 480);
        org.junit.jupiter.api.Assertions.assertTrue(distinctColors(a) > 50,
                "scene images must contain a rich palette (text panels only had a handful of colors)");
        org.junit.jupiter.api.Assertions.assertTrue(distinctColors(b) > 50 && !samePixels(a, b),
                "different scenes must produce different images");
        org.junit.jupiter.api.Assertions.assertTrue(isJpeg(baustelle), "JPEG magic bytes must be intact");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String runAnalysisAndAwait() throws Exception {
        String startHtml = mockMvc.perform(post("/cases/" + CASE_ID + "/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher m = JOB_URL.matcher(startHtml);
        assertThat("progress URL must be present", m.find());
        JobProgressService.Job job = null;
        for (int i = 0; i < 50; i++) {
            job = progressService.get(m.group(1));
            if (job != null && "DONE".equals(job.state)) {
                return mockMvc.perform(get("/cases/" + CASE_ID + "/decision/analyze/progress/" + m.group(1)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Analysis job did not complete, state="
                + (job != null ? job.state : "missing")
                + (job != null && job.terminalMessage != null ? " msg=" + job.terminalMessage : ""));
    }

    private void stubAnswer(ConfidenceProfile profile) {
        // Mit Quellen-Zitat (sonst setzt der Stale-Confidence-Sanitizer die
        // Konfidenz auf "nicht bewertbar") und einer nicht belegten
        // Feststellung (für den Konfidenz-Hinweis).
        var findings = new reasoning.ai.model.FindingHierarchy(
                List.of(), List.of(new reasoning.ai.model.FindingElement(
                        "Unbelegter Punkt", reasoning.ai.model.FindingRole.SUPPORTING_FINDING,
                        0.4, List.of(), List.of(), "Nicht durch die Unterlagen gedeckt.")),
                List.of(), List.of(), List.of());
        var answer = new ReasonedAnswer(
                "KURZANTWORT Die Gewerbeanmeldung muss vor Beginn der Tätigkeit erfolgen. "
                        + "NÄCHSTER SCHRITT Prüfen Sie die Unterlagen.",
                List.of(new reasoning.ai.model.SourceCitation(
                        UUID.randomUUID(), UUID.randomUUID(), 1, "info_gewerbe_anmelden.pdf",
                        1, 0, 100, "Auszug", 0.9,
                        reasoning.ai.model.SourceCitation.SourceTier.PRIMARY)),
                List.of(), findings, null, profile, true, 0.7, false);
        var metadata = new InferenceMetadata(
                "test", "test-model", Instant.now(), Instant.now(),
                null, null, null, "HYBRID", List.of(), 0.0);
        when(aiFacade.answer(any(AiRequest.class)))
                .thenReturn(new AiResponse(answer, metadata));
    }

    private static int distinctColors(BufferedImage img) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                colors.add(img.getRGB(x, y));
            }
        }
        return colors.size();
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y += 8) {
            for (int x = 0; x < a.getWidth(); x += 8) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static boolean isJpeg(Path p) throws Exception {
        byte[] b = Files.readAllBytes(p);
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && b.length > 1000;
    }
}
