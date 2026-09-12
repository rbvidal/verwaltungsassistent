package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.TimelineEventDto;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2D.16 — Regression „Analyseergebnis öffnen": Ein gespeichertes Analyse-
 * Ergebnis, dessen JSON ein {@code jobId}-Feld enthält (wie echte Pipeline-
 * Ergebnisse), darf die Entscheidungsseite NICHT mehr zum Absturz bringen.
 *
 * <p>Ursache: Das Ergebnis-JSON wird per addAllAttributes ins Model gelegt;
 * eine th:if-Wächter-Klausel ({@code jobId != null and autoOpenPdf}) wertete
 * dann das NICHT gesetzte Attribut {@code autoOpenPdf} aus → SpEL
 * „cannot convert from null to boolean" → 500/leere Seite. Die Klausel ist
 * entfernt; dieser Test sichert das Verhalten mit genau dieser Datenlage ab
 * (gespeicherter Lauf + jobId im Ergebnis, ohne autoOpenPdf-Attribut).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecisionPageStoredRunRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private reasoning.document.application.DocumentService documentService;

    @MockBean
    private verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity entity;
    private WorkspaceAnalysisRunEntity run;

    @BeforeEach
    void setUp() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "demo02@verwaltungsassistent.local",
                "Clara Dietrich", Set.of("USER", "ANALYST"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        entity = new WorkspaceEntity("WS-635A0C43", "Defekte Straßenlaterne in der Lehnitzer Straße",
                "Straßenlaterne fällt aus.", "CASE", null);
        entity.setId(caseId);
        entity.setStatus(WorkspaceStatus.ACTIVE);
        entity.setPhase(WorkspacePhase.REVIEW);
        entity.setPhaseData("{\"ingestionResolved\": true}");

        run = new WorkspaceAnalysisRunEntity(UUID.randomUUID(), UUID.fromString(caseId), 1,
                "COMPLETED", "demo02@verwaltungsassistent.local", Instant.now().minusSeconds(900));
        run.setCompletedAt(Instant.now().minusSeconds(600));
        // Ergebnis wie ein echter Pipeline-Lauf: enthält u. a. jobId …
        run.setResultJson("{}");

        when(workspaceService.findById(anyString())).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenReturn(new WorkspaceDto(
                caseId, "WS-635A0C43", "Defekte Straßenlaterne in der Lehnitzer Straße",
                "Straßenlaterne fällt aus.", "CASE", WorkspaceStatus.ACTIVE,
                WorkspacePhase.REVIEW, null, Map.of("ingestionResolved", true),
                List.of(), List.of(new TimelineEventDto("evt-1", caseId,
                        java.time.LocalDate.now(), "Dokument hinzugefügt", null,
                        TimelineEventType.EVENT, null, 0.0, false)),
                Instant.now().minusSeconds(900), Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(anyString())).thenReturn(Optional.of(run));
        when(workspaceService.listAnalysisRuns(anyString())).thenReturn(List.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(storedResult());
        when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());
        when(workspaceService.getCompletedSteps(anyString())).thenReturn(List.of());
        when(workspaceService.getTimeline(anyString())).thenReturn(List.of());
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(any()))
                .thenReturn(List.of());
    }

    /** Ergebnisstruktur wie ein echter Analyse-Lauf (inkl. jobId-Schlüssel). */
    private static Map<String, Object> storedResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobId", "analysis:da8c3fc3-7f32-4027-99be-b11be1434911");
        r.put("caseId", UUID.randomUUID().toString());
        r.put("caseName", "Defekte Straßenlaterne in der Lehnitzer Straße");
        r.put("analysisQuestion", "Analysiere den Fall.");
        r.put("asOf", "2026-09-05");
        r.put("asOfDisplay", "05.09.2026");
        r.put("decisionAnswer", "KURZANTWORT\nDie Laterne fällt aus.\n\nENTSCHEIDUNG\nReparatur veranlassen.");
        r.put("grounded", Boolean.FALSE);
        r.put("model", "qwen2.5:14b");
        r.put("strategy", "Hybride Suche");
        r.put("requestedAt", "05.09.2026 13:00");
        r.put("completedAt", "05.09.2026 13:05");
        r.put("confidenceScore", "59%");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sourceConfidence", 0.4);
        c.put("semanticConfidence", 0.6);
        c.put("structuralConfidence", 0.0);
        c.put("completenessConfidence", 0.7);
        c.put("overallConfidence", 0.59);
        c.put("explanation", "Erklärung");
        r.put("confidence", c);
        r.put("evidenceItems", List.of());
        r.put("authorities", List.of());
        r.put("primaryFindings", List.of());
        r.put("secondaryFindings", List.of());
        r.put("proceduralFindings", List.of());
        r.put("supportingFindings", List.of());
        r.put("findingRelationships", List.of());
        r.put("coverageIssues", List.of());
        r.put("presentRoles", List.of());
        r.put("missingRoles", List.of());
        r.put("missingDocs", List.of());
        r.put("analysisComplete", true);
        return r;
    }

    @Test
    void decisionPage_storedRunWithJobIdInResult_rendersSuccessfully() throws Exception {
        // Vor dem Fix: SpEL-Fehler (autoOpenPdf fehlt im Model) → Fehlerseite.
        mockMvc.perform(get("/cases/" + caseId + "/decision"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Analyse vorhanden")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Interner Serverfehler"))));
    }
}
