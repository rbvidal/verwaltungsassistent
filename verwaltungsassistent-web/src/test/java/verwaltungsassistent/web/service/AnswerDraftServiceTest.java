package verwaltungsassistent.web.service;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The answer draft must be assembled deterministically from the persisted
 * decision analysis: correct sections, nothing invented when data is absent,
 * and honest limited-coverage handling.
 */
class AnswerDraftServiceTest {

    private final String caseId = UUID.randomUUID().toString();

    private WorkspaceEntity entity() {
        WorkspaceEntity e = new WorkspaceEntity("WS-007", "Reisepass – Minderjährige",
                "Testfall", "GENERAL", "user@example.com");
        e.setPhaseData("{}");
        return e;
    }

    private WorkspaceService workspaceService(WorkspaceEntity e) {
        WorkspaceService ws = mock(WorkspaceService.class);
        when(ws.findById(caseId)).thenReturn(Optional.of(e));
        return ws;
    }

    private final verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository emailRepo =
            mock(verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository.class);

    private AnswerDraftService draftService(WorkspaceService ws) {
        return new AnswerDraftService(ws, emailRepo);
    }

    private Map<String, Object> analysis() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionAnswer", "KURZANTWORT: Die Ausstellung eines Reisepasses für Minderjährige "
                + "erfordert die Zustimmung der Sorgeberechtigten.\n\nENTSCHEIDUNG: Zustimmung erforderlich.\n");
        m.put("grounded", true);
        m.put("primaryFindings", List.of(Map.of(
                "label", "Zustimmung der Sorgeberechtigten erforderlich",
                "description", "Für Minderjährige ist die Zustimmung der Sorgeberechtigten nachzuweisen.")));
        m.put("authorities", List.of(Map.of(
                "title", "Passgesetz",
                "reference", "§ 5 PassG")));
        m.put("missingDocs", List.of("Einverständniserklärung der Sorgeberechtigten"));
        m.put("proceduralFindings", List.of(Map.of(
                "label", "Unterlagen anfordern",
                "description", "Fehlende Einverständniserklärung anfordern.")));
        return m;
    }

    @Test
    void generate_assemblesAllSectionsFromAnalysis() {
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Reisepass – Minderjährige", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(workspaceService(entity())).generate(analysis(), dto);

        assertNotNull(draft);
        assertTrue(draft.contains("Betreff: Reisepass – Minderjährige (Aktenzeichen WS-007)"),
                "Betreff with real case name and number");
        assertTrue(draft.contains("Sehr geehrte Damen und Herren,"), "Anrede without invented name");
        assertTrue(draft.contains("Die Ausstellung eines Reisepasses für Minderjährige"),
                "answer content from the decision proposal");
        assertFalse(draft.contains("KURZANTWORT"), "internal pipeline markers must be stripped");
        assertTrue(draft.contains("Zustimmung der Sorgeberechtigten erforderlich"), "Begründung from findings");
        assertTrue(draft.contains("Passgesetz (§ 5 PassG)"), "Rechtsgrundlage from authorities");
        assertTrue(draft.contains("Einverständniserklärung der Sorgeberechtigten"), "missing documents listed");
        assertTrue(draft.contains("Mit freundlichen Grüßen"), "Grußformel");
    }

    @Test
    void generate_groundedDraft_hasNoCoverageNote() {
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(workspaceService(entity())).generate(analysis(), dto);

        assertFalse(draft.contains("eingeschränkter Quellenlage"),
                "a grounded draft must not claim limited coverage");
    }

    @Test
    void generate_limitedCoverage_isNeverHidden() {
        Map<String, Object> m = analysis();
        m.put("grounded", false);
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(workspaceService(entity())).generate(m, dto);

        assertTrue(draft.contains("eingeschränkter Quellenlage"),
                "limited source coverage must remain visible in the draft");
    }

    @Test
    void generate_withoutAuthorities_omitsRechtsgrundlageSection() {
        Map<String, Object> m = analysis();
        m.put("authorities", List.of());
        m.put("missingDocs", List.of());
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(workspaceService(entity())).generate(m, dto);

        assertFalse(draft.contains("Rechtsgrundlage:"),
                "no legal basis section when the analysis has none — nothing invented");
        assertFalse(draft.contains("benötigen wir noch"), "no missing-documents section when none are missing");
    }

    @Test
    void generate_nullAnalysis_returnsNull() {
        assertNull(draftService(workspaceService(entity())).generate(null, null));
    }

    @Test
    void saveAndLoad_roundTrip_persistsDraftInPhaseData() {
        WorkspaceEntity e = entity();
        WorkspaceService ws = workspaceService(e);
        AnswerDraftService service = draftService(ws);

        service.saveGenerated(caseId, "Entwurfstext", "user@example.com", 3, false);

        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(ws).save(captor.capture());
        assertTrue(captor.getValue().getPhaseData().contains("\"answerDraft\""),
                "draft must be persisted in the case phase data");

        AnswerDraftService.AnswerDraft loaded = service.load(caseId);
        assertNotNull(loaded, "draft must survive a reload from the case artifact store");
        assertEquals("Entwurfstext", loaded.text());
        assertEquals(3, loaded.analysisVersion());
        assertTrue(loaded.limitedCoverage(), "coverage limitation must persist with the draft");
    }

    // ── Phase 2C.1: Kommunikationsbezug + Erstellungs-Anweisung ──

    @Test
    void generate_withLinkedEmail_addsBezugLine() {
        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity email =
                new verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity(
                        UUID.randomUUID(), "Reisepass beantragen", "Julia Weber",
                        "julia.weber@example.de", "Text",
                        java.time.Instant.parse("2026-08-04T09:15:00Z"),
                        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo.GENERAL,
                        null);
        when(emailRepo.findByWorkspaceIdOrderByReceivedAtDesc(java.util.UUID.fromString(caseId)))
                .thenReturn(List.of(email));
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(workspaceService(entity())).generate(analysis(), dto);

        assertTrue(draft.contains("Bezug: Ihre E-Mail vom 04.08.2026"),
                "draft must reference the case's communication origin");
    }

    @Test
    void generate_withDocumentsInstruction_andDeadline_appendsDeadlineSentence() {
        reasoning.workspace.api.TimelineEventEntity deadline =
                org.mockito.Mockito.mock(reasoning.workspace.api.TimelineEventEntity.class);
        when(deadline.getEventType()).thenReturn(
                reasoning.workspace.model.TimelineEventType.DEADLINE);
        when(deadline.getEventDate()).thenReturn(java.time.LocalDate.of(2026, 9, 15));
        WorkspaceService ws = workspaceService(entity());
        when(ws.getTimeline(caseId)).thenReturn(List.of(deadline));
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(ws).generate(analysis(), dto,
                "Bitte erklären, welche Unterlagen noch fehlen und bis wann sie nachgereicht werden müssen.");

        assertTrue(draft.contains("Bitte reichen Sie die fehlenden Unterlagen bis zum 15.09.2026 nach."),
                "document deadline sentence must be appended when the instruction asks for it");
    }

    @Test
    void generate_withInstruction_withoutDeadline_inventsNoDate() {
        WorkspaceService ws = workspaceService(entity());
        when(ws.getTimeline(caseId)).thenReturn(List.of());
        var dto = new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-007", "Fall", null, "GENERAL", null, null,
                null, Map.of(), List.of(), List.of(), null, null);

        String draft = draftService(ws).generate(analysis(), dto,
                "Bitte erklären, welche Unterlagen noch fehlen und bis wann sie nachgereicht werden müssen.");

        assertFalse(draft.contains("Bitte reichen Sie die fehlenden Unterlagen bis zum"),
                "no invented deadline when the case has none");
    }

    @Test
    void save_withInstruction_roundTripsInstruction() {
        WorkspaceService ws = workspaceService(entity());
        AnswerDraftService service = draftService(ws);

        service.saveGenerated(caseId, "Entwurf", "user@example.com", 3, true,
                "Bitte Unterlagenfrist nennen.");

        assertEquals("Bitte Unterlagenfrist nennen.", service.load(caseId).instruction(),
                "Erstellungs-Anweisung muss mit dem Entwurf gespeichert werden");
        assertFalse(service.load(caseId).text().contains("Bitte Unterlagenfrist nennen."),
                "die Anweisung darf nie ungeprüft im Brieftext landen");
    }

    @Test
    void load_withoutDraft_returnsNull() {
        assertNull(draftService(workspaceService(entity())).load(caseId));
    }

    // ── Phase 2D.5: Bearbeitungs-/Prüfzustand des Entwurfs ──

    @Test
    void generatedDraft_isUnedited_andNotReviewed() {
        WorkspaceEntity e = entity();
        AnswerDraftService service = draftService(workspaceService(e));

        service.saveGenerated(caseId, "KI-Text", "user@example.com", 3, true);

        AnswerDraftService.AnswerDraft loaded = service.load(caseId);
        assertFalse(loaded.edited(), "a freshly generated draft is not marked as manually edited");
        assertFalse(loaded.reviewed(), "a freshly generated draft carries no review verdict");
        assertTrue(loaded.text().contains("KI-Text"));
    }

    @Test
    void saveEdited_marksDraftAsEdited_recordsEditor_andPreservesBasis() {
        WorkspaceEntity e = entity();
        AnswerDraftService service = draftService(workspaceService(e));
        service.saveGenerated(caseId, "KI-Text", "user@example.com", 3, true);

        service.saveEdited(caseId, "Bearbeiteter Text", "erika@example.com");

        AnswerDraftService.AnswerDraft loaded = service.load(caseId);
        assertTrue(loaded.edited(), "a manual save must mark the draft as edited");
        assertEquals("Bearbeiteter Text", loaded.text());
        assertEquals(3, loaded.analysisVersion(), "the analysis basis must be preserved");
        assertEquals("erika@example.com", loaded.updatedBy(), "the editing employee must be recorded");
        assertEquals("user@example.com", loaded.createdBy(), "the original creator stays recorded");
    }

    @Test
    void editAfterReview_resetsReviewVerdict() {
        WorkspaceEntity e = entity();
        AnswerDraftService service = draftService(workspaceService(e));
        service.saveGenerated(caseId, "KI-Text", "user@example.com", 3, true);

        service.markReviewed(caseId, "erika@example.com");
        assertTrue(service.load(caseId).reviewed(), "review verdict is set");
        assertEquals("erika@example.com", service.load(caseId).reviewedBy());

        service.saveEdited(caseId, "Geänderter Text", "erika@example.com");

        AnswerDraftService.AnswerDraft after = service.load(caseId);
        assertFalse(after.reviewed(), "content changed after the verdict — verdict no longer applies");
        assertTrue(after.edited());
    }

    @Test
    void markReviewed_withoutDraft_rejects() {
        WorkspaceEntity e = entity();
        AnswerDraftService service = draftService(workspaceService(e));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.markReviewed(caseId, "user@example.com"));
    }
}
