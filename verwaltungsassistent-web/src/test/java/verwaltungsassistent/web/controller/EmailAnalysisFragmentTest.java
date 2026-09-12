package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EmailAnalysisFragmentTest {

    private TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setDialect(new SpringStandardDialect());
        return engine;
    }

    @Test
    void emailAnalysisFragmentParsesAndRenders() {
        EmailController.EmailOutcome outcome = new EmailController.EmailOutcome(
                "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Wohngeld",
                "GENERAL",
                "REQUEST_DOCUMENTS",
                List.of(new EmailController.CaseRef("c1", "Musterfall Wohngeld",
                        "Übereinstimmende Begriffe: wohngeld, unterlagen", "SIMILAR")),
                List.of(new EmailController.DocRef("d1", "Wohngeldgesetz (WoGG)", 0.87)),
                List.of("Mietvertrag", "Einkommensnachweise"),
                List.of(
                        new EmailController.Step(1, "Angaben zur Person prüfen", "Beschreibung", "Fall öffnen", "/cases/c1", true),
                        new EmailController.Step(6, "Antwortentwurf erstellen", "Beschreibung", "Noch nicht verfügbar", null, false)),
                null, null, null, List.of());

        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", outcome);

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);
        assertTrue(html.contains("Wohngeld"), "topic label must be rendered");
        assertTrue(html.contains("Musterfall Wohngeld"), "matched case must be rendered");
        assertTrue(html.contains("0,87"), "score must be rendered in German number format");
    }

    @Test
    void emailAnalysis_documentLinksOpenContentViewer_neverDetailPage() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Ummeldung nach Umzug – was wird benötigt?", "An- / Ummeldung",
                "GENERAL", null,
                List.of(),
                List.of(new EmailController.DocRef("d1", "Änderung/Wechsel der Hauptwohnung", 0.72)),
                List.of(), List.of(),
                null, null, null, List.of()));
        ctx.setVariable("subjectQueryParam", "Ummeldung+nach+Umzug");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        // The document opens as a content viewer modal, not as /documents/{id}.
        assertTrue(html.contains("hx-get=\"/documents/d1/view?q=Ummeldung+nach+Umzug\""),
                "document button must load the content viewer with the query for highlighting");
        assertTrue(html.contains("emdoc-d1-body"), "viewer modal body id must be unique per document");
        assertTrue(html.contains("viewerOpen"), "viewer modal must be Alpine-scoped");
        assertFalse(html.contains("href=\"/documents/d1\""),
                "the analysis result must never link to the metadata detail page");
        assertFalse(html.contains("hx-get=\"/emails/analyses/similar"),
                "without a similarCount the similar dialog link stays hidden");
    }

    @Test
    void emailAnalysis_similarDialogLinkShownWithCount() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Ummeldung nach Umzug – was wird benötigt?", "An- / Ummeldung",
                "GENERAL", null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, List.of()));
        ctx.setVariable("subjectQueryParam", "Ummeldung+nach+Umzug");
        ctx.setVariable("similarQueryParam", "Ummeldung+nach+Umzug");
        ctx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");
        ctx.setVariable("similarCount", 2);

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        assertTrue(html.contains("Ähnliche Anfragen"), "similar dialog link must be rendered");
        // The dialog loads OTHER analyses: it excludes the current analysis and
        // compares subject + full text (similarQueryParam).
        assertTrue(html.contains("hx-get=\"/emails/analyses/similar?q=Ummeldung+nach+Umzug&amp;exclude=11111111-2222-3333-4444-555555555555\""),
                "similar dialog must load its entries via htmx (excluding the current analysis)");
        assertTrue(html.contains("id=\"similar-list\""), "dialog body must have the htmx target id");
    }

    @Test
    void buildOutcome_withMatchedCase_makesDraftStepAvailable() {
        EmailController controller = new EmailController(
                mock(verwaltungsassistent.web.service.JobProgressService.class),
                mock(reasoning.ai.application.DecisionRouter.class),
                mock(reasoning.ai.application.DomainGate.class),
                mock(reasoning.search.api.SearchFacade.class),
                mock(reasoning.workspace.application.WorkspaceService.class),
                mock(reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository.class),
                mock(org.thymeleaf.TemplateEngine.class),
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class),
                mock(verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository.class),
                mock(verwaltungsassistent.web.analysis.persistence.MailboxRepository.class),
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class),
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                new verwaltungsassistent.web.planning.PriorityCalculationService(),
                mock(reasoning.ai.api.AiFacade.class),
                mock(verwaltungsassistent.web.security.CaseAccessGuard.class),
                mock(verwaltungsassistent.web.service.EmailCaseMatchingService.class));

        EmailController.EmailOutcome outcome = controller.buildOutcome(
                "Reisepass für Kind beantragen", "Guten Tag, wir möchten einen Reisepass beantragen.",
                "GENERAL", "GENERAL", List.of(),
                List.of(new EmailController.CaseRef("case-1", "Reisepass – Minderjährige", "Match", "SIMILAR")),
                null);

        EmailController.Step draftStep = outcome.steps().get(5);
        assertEquals("Antwortentwurf erstellen", draftStep.title());
        assertTrue(draftStep.available(), "the draft step must be available when a case is matched");
        assertEquals("/cases/case-1/draft", draftStep.actionUrl(),
                "the draft step must link to the case answer draft");
        assertEquals("Antwortentwurf öffnen", draftStep.actionLabel());
    }

    @Test
    void emailAnalysis_matchedCase_offersAssignToCaseAsPrimaryAndNewCaseAsSecondary() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Ummeldung nach Umzug – was wird benötigt?", "An- / Ummeldung",
                "GENERAL", null,
                List.of(new EmailController.CaseRef(
                        "case-1", "Ummeldung nach Umzug", "Übereinstimmende Begriffe: unterlagen, ummeldung", "NAME_IDENTITY")),
                List.of(), List.of(), List.of(),
                null, null, null, List.of()));
        ctx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");
        ctx.setVariable("_csrfToken", "csrf-token");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        assertTrue(html.contains("Diesem Fall zuordnen"),
                "with a matching case the assignment action is offered at the case row");
        assertTrue(html.contains("action=\"/emails/11111111-2222-3333-4444-555555555555/assign-case\""),
                "assign-case posts to the analysis-specific endpoint");
        assertTrue(html.contains("name=\"caseId\" value=\"case-1\""),
                "the assign-case form carries the SELECTED suggestion's case id");
        assertTrue(html.contains("Neuen Fall anlegen (abweichend vom Treffer)"),
                "creating a new case remains possible as an explicit secondary override");
        assertFalse(html.contains(">Vorgang aus E-Mail anlegen</button>"),
                "the plain create label is replaced when a match exists");
    }

    /**
     * Issue 3: Die Zuordnung ist PRO VORGESCHLAGENEM Fall an dessen Zeile —
     * es gibt keinen generischen Zuordnen-Button über der Liste, der still
     * den ersten Treffer zuordnet. Zwei Vorschläge → zwei Zuordnungs-Formulare
     * mit den jeweils passenden Fall-IDs.
     */
    @Test
    void matchedCases_eachSuggestionHasItsOwnAssignActionWithItsCaseId() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Wohngeldantrag – welche Unterlagen fehlen noch?", "Wohngeld",
                "GENERAL", null,
                List.of(new EmailController.CaseRef("case-1", "Fall Müller - Wohngeld",
                                "Übereinstimmende Begriffe: wohngeld", "NAME_IDENTITY"),
                        new EmailController.CaseRef("case-2", "Fall Schmidt - Wohngeld",
                                "Übereinstimmende Begriffe: wohngeld", "EMAIL_IDENTITY")),
                List.of(), List.of(), List.of(),
                null, null, null, List.of()));
        ctx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");
        ctx.setVariable("_csrfToken", "csrf-token");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        // Genau zwei Zuordnungs-Formulare — eines pro vorgeschlagenem Fall.
        int assignForms = occurrences(html, "/assign-case\"");
        assertEquals(2, assignForms, "each suggested case carries exactly one assign action");
        assertTrue(html.contains("name=\"caseId\" value=\"case-1\""),
                "first suggestion carries its own case id");
        assertTrue(html.contains("name=\"caseId\" value=\"case-2\""),
                "second suggestion carries its own case id");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void buildOutcome_withoutCase_draftStepStaysUnavailable() {
        EmailController controller = new EmailController(
                mock(verwaltungsassistent.web.service.JobProgressService.class),
                mock(reasoning.ai.application.DecisionRouter.class),
                mock(reasoning.ai.application.DomainGate.class),
                mock(reasoning.search.api.SearchFacade.class),
                mock(reasoning.workspace.application.WorkspaceService.class),
                mock(reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository.class),
                mock(org.thymeleaf.TemplateEngine.class),
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class),
                mock(verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository.class),
                mock(verwaltungsassistent.web.analysis.persistence.MailboxRepository.class),
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class),
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                new verwaltungsassistent.web.planning.PriorityCalculationService(),
                mock(reasoning.ai.api.AiFacade.class),
                mock(verwaltungsassistent.web.security.CaseAccessGuard.class),
                mock(verwaltungsassistent.web.service.EmailCaseMatchingService.class));

        EmailController.EmailOutcome outcome = controller.buildOutcome(
                "Reisepass für Kind beantragen", "Guten Tag, wir möchten einen Reisepass beantragen.",
                "GENERAL", "GENERAL", List.of(), List.of(), null);

        EmailController.Step draftStep = outcome.steps().get(5);
        assertFalse(draftStep.available(), "no case — the draft step must stay unavailable");
    }

    @Test
    void emailAnalysisFragmentRendersEmptyStatesWithoutError() {
        EmailController.EmailOutcome outcome = new EmailController.EmailOutcome(
                "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Wohngeld",
                null,
                null,
                List.of(),
                List.of(),
                List.of("Mietvertrag / Mietbescheinigung"),
                List.of(),
                null, null, null, List.of());

        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", outcome);

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);
        assertTrue(html.contains("Vorläufige E-Mail-Analyse"), "panel must be labeled as preliminary");
        assertTrue(html.contains("Vorläufige Einschätzung"), "explicit limitation section must be rendered");
        assertTrue(html.contains("Vorläufiger Antwortentwurf"), "answer draft section must be rendered");
        assertTrue(html.contains("Kein ähnlicher bestehender Fall erkannt"), "empty case suggestion state must be rendered");
        assertTrue(html.contains("Keine ausreichend belegten Treffer in der Wissensbasis."), "empty documents state must be rendered");
        assertTrue(html.contains("Mietvertrag / Mietbescheinigung"), "missing documents checklist must be rendered");
        assertTrue(html.contains("Vollständige Prüfung"), "case workflow explanation must be rendered");
    }

    /**
     * Re-analyzing the SAME e-mail text must update the existing record, not
     * insert a duplicate — the dashboard feed stays unambiguous.
     */
    @Test
    void persistAnalysis_sameQuestionText_updatesExistingRecordInsteadOfDuplicating() throws Exception {
        var repo = mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        EmailController controller = new EmailController(
                mock(verwaltungsassistent.web.service.JobProgressService.class),
                mock(reasoning.ai.application.DecisionRouter.class),
                mock(reasoning.ai.application.DomainGate.class),
                mock(reasoning.search.api.SearchFacade.class),
                mock(reasoning.workspace.application.WorkspaceService.class),
                mock(reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository.class),
                mock(org.thymeleaf.TemplateEngine.class),
                repo,
                mock(verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository.class),
                mock(verwaltungsassistent.web.analysis.persistence.MailboxRepository.class),
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class),
                mapper,
                new verwaltungsassistent.web.planning.PriorityCalculationService(),
                mock(reasoning.ai.api.AiFacade.class),
                mock(verwaltungsassistent.web.security.CaseAccessGuard.class),
                mock(verwaltungsassistent.web.service.EmailCaseMatchingService.class));

        var existing = new verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity(
                java.util.UUID.randomUUID(), "user@example.com", "Der gleiche Text", "Alter Betreff",
                "Altes Thema", "1.0.0", "{}", java.time.Instant.now().minusSeconds(60));
        when(repo.findByUserEmailAndQuestionTextOrderByCreatedAtDesc("user@example.com", "Der gleiche Text"))
                .thenReturn(List.of(existing));

        var outcome = new EmailController.EmailOutcome("Der gleiche Text", "Wohngeld", null, null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, List.of());
        java.util.UUID id = controller.persistAnalysis(
                "Der gleiche Text", "Neuer Betreff", "Neues Thema", outcome, "user@example.com");

        assertEquals(existing.getId(), id, "the existing record id must be reused");
        assertEquals("Neuer Betreff", existing.getSubject());
        assertEquals("Neues Thema", existing.getTopic());
        assertTrue(existing.getResultJson() != null && existing.getResultJson().contains("Wohngeld"),
                "the updated record must carry the fresh result");
        verify(repo).findByUserEmailAndQuestionTextOrderByCreatedAtDesc("user@example.com", "Der gleiche Text");
        verify(repo).save(existing);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void emailAnalysis_withAnalysisId_rendersCreateCaseButton() {
        EmailController.EmailOutcome outcome = new EmailController.EmailOutcome(
                "Umzugsgeld nach Umzug nach Berlin?", "An- / Ummeldung",
                null, null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, List.of());

        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", outcome);
        ctx.setVariable("analysisId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);
        assertTrue(html.contains("Vorgang aus E-Mail anlegen"), "create-case button must be rendered");
        assertTrue(html.contains("action=\"/emails/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/create-case\""),
                "create-case button must post to the analysis-specific endpoint");
        assertTrue(html.contains("kopieren"), "copy button must be rendered");
    }

    /** Phase 2C.7: Ein SEMANTIC-Vorschlag wird ehrlich als „Semantisch ähnlicher
     *  Vorgang" mit Ähnlichkeits-Erklärung angezeigt; die Zuordnung bleibt die
     *  explizite Aktion „Diesem Vorgang zuordnen". */
    @Test
    void emailAnalysis_semanticCandidate_showsHonestSemanticLabelAndAssignAction() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE); // Raw-Render ohne RoleModelAdvice (2C.5/2C.7a)
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Ich habe noch eine Frage zu meiner Ummeldung. Die Unterlagen hatte ich letzte Woche geschickt.",
                "An- / Ummeldung",
                "GENERAL", null,
                List.of(new EmailController.CaseRef("case-1", "Ummeldung nach Umzug – Familie Lang",
                        "Semantische Ähnlichkeit mit den Unterlagen dieses Vorgangs: 0,87 · hoch. "
                                + "Gemeinsamer Kontext: „Ummeldung nach Umzug – neue Wohnung anmelden…“",
                        "SEMANTIC")),
                List.of(), List.of(), List.of(),
                null, null, null, List.of()));
        ctx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");
        ctx.setVariable("_csrfToken", "csrf-token");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        assertTrue(html.contains("Semantisch ähnlicher Vorgang"),
                "semantic candidates carry the honest semantic label");
        assertTrue(html.contains("Semantische Ähnlichkeit mit den Unterlagen dieses Vorgangs: 0,87 · hoch"),
                "the reason names the real mechanism and similarity");
        assertTrue(html.contains("Diesem Vorgang zuordnen"),
                "semantic candidates keep the explicit assignment action");
        assertTrue(html.contains("semantische Ähnlichkeit seiner Unterlagen vorgeschlagen"),
                "single semantic candidates explain that no automatic assignment happens");
        assertFalse(html.contains("Übereinstimmende Begriffe"),
                "vector matches must not be explained as keyword overlap");
    }

    /** Phase 2C.9: lokale Entwurfs-Stände (Kein Entwurf / KI-Entwurf / Manuell
     *  bearbeitet / Entwurf geprüft) und die Read-only-Sicht der Leitung. */
    @Test
    void emailDraftArea_showsLocalStatesAndNoSendWording() {
        String analysisId = "11111111-2222-3333-4444-555555555555";
        EmailController.EmailOutcome outcome = new EmailController.EmailOutcome(
                "Frage zum Wohngeld", "Wohngeld", "GENERAL", null,
                List.of(), List.of(), List.of(), List.of(),
                null, false, null, List.of(), null);
        String draftText = "Sehr geehrte Bürgerin,\n\nvielen Dank für Ihre Nachricht.";

        // 1) Kein Entwurf, aber Antwort erforderlich -> Status + Erstellen-Aktion.
        Context noneCtx = new Context(Locale.GERMANY);
        noneCtx.setVariable("supervisory", Boolean.FALSE);
        noneCtx.setVariable("emailDraft", null);
        noneCtx.setVariable("draftAllowed", Boolean.TRUE);
        noneCtx.setVariable("outcome", outcome);
        noneCtx.setVariable("analysisId", analysisId);
        String noneHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), noneCtx);
        assertTrue(noneHtml.contains("Kein Entwurf"), "status Kein Entwurf must be visible");
        assertTrue(noneHtml.contains("Antwortentwurf erstellen"), "create action available for employee");

        // 2) Frischer KI-Entwurf.
        Context freshCtx = new Context(Locale.GERMANY);
        freshCtx.setVariable("supervisory", Boolean.FALSE);
        freshCtx.setVariable("emailDraft", new EmailController.EmailDraft(
                draftText, "02.09.2026 10:00", false, null, null));
        freshCtx.setVariable("draftAllowed", Boolean.TRUE);
        freshCtx.setVariable("outcome", outcome);
        freshCtx.setVariable("analysisId", analysisId);
        String freshHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), freshCtx);
        assertTrue(freshHtml.contains("KI-Entwurf"), "fresh draft is labeled KI-Entwurf");
        assertTrue(freshHtml.contains("Nur Entwurf · kein Versand"), "no-send label stays visible");
        assertTrue(freshHtml.contains("Entwurf speichern"), "save action available");
        assertFalse(freshHtml.contains(">Senden<"), "no send button in fresh draft view");
        assertFalse(freshHtml.contains("Antwort senden"), "no send action label");

        // 3) Manuell bearbeitet und danach geprüft (lokal).
        Context editedCtx = new Context(Locale.GERMANY);
        editedCtx.setVariable("supervisory", Boolean.FALSE);
        editedCtx.setVariable("emailDraft", new EmailController.EmailDraft(
                draftText + "\n(ergänzt)", "02.09.2026 10:00", true, null, null));
        editedCtx.setVariable("draftAllowed", Boolean.TRUE);
        editedCtx.setVariable("outcome", outcome);
        editedCtx.setVariable("analysisId", analysisId);
        String editedHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), editedCtx);
        assertTrue(editedHtml.contains("Manuell bearbeitet"), "manual-edit state visible");

        Context reviewedCtx = new Context(Locale.GERMANY);
        reviewedCtx.setVariable("supervisory", Boolean.FALSE);
        reviewedCtx.setVariable("emailDraft", new EmailController.EmailDraft(
                draftText, "02.09.2026 10:00", true,
                "02.09.2026 11:30", "demo01@verwaltungsassistent.local"));
        reviewedCtx.setVariable("draftAllowed", Boolean.TRUE);
        reviewedCtx.setVariable("outcome", outcome);
        reviewedCtx.setVariable("analysisId", analysisId);
        String reviewedHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), reviewedCtx);
        assertTrue(reviewedHtml.contains("Entwurf geprüft · lokal"), "reviewed state visible");
        assertTrue(reviewedHtml.contains("Geprüft von demo01@verwaltungsassistent.local am 02.09.2026 11:30"),
                "review actor/date visible");
        assertTrue(reviewedHtml.contains("Entwurf speichern"),
                "saving after review remains possible (review is not final)");
        assertFalse(reviewedHtml.contains(">Senden<"), "no send button in reviewed draft view");
        assertFalse(reviewedHtml.contains("Jetzt versenden"), "no send wording");

        // 4) Leitungs-Konto: read-only (readonly textarea, keine Aktions-Buttons).
        Context adminCtx = new Context(Locale.GERMANY);
        adminCtx.setVariable("supervisory", Boolean.TRUE);
        adminCtx.setVariable("emailDraft", new EmailController.EmailDraft(
                draftText, "02.09.2026 10:00", false, null, null));
        adminCtx.setVariable("draftAllowed", Boolean.TRUE);
        adminCtx.setVariable("outcome", outcome);
        adminCtx.setVariable("analysisId", analysisId);
        String adminHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), adminCtx);
        assertTrue(adminHtml.contains("readonly=\"readonly\""), "admin sees a readonly draft");
        assertFalse(adminHtml.contains("Entwurf speichern"), "admin has no save action");
        assertFalse(adminHtml.contains("Antwortentwurf erstellen"), "admin has no create action");
        assertTrue(adminHtml.contains("Leitungs-Konto: Entwürfe werden nur lesend eingesehen."),
                "admin read-only hint visible");
    }

    /** Phase 2C.11: Belege tragen Quelle-Referenzen — „Quelle öffnen" öffnet den
     *  Dokument-Viewer auf den EVIDENCE-Chunk; ohne Chunk ehrlich nur das Dokument. */
    @Test
    void emailAnalysis_evidenceWithSourceReference_offersExactSourceLink() {
        UUID chunk = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("supervisory", Boolean.FALSE);
        ctx.setVariable("emailDraft", null);
        ctx.setVariable("draftAllowed", Boolean.FALSE);
        ctx.setVariable("outcome", new EmailController.EmailOutcome(
                "Welche Unterlagen fehlen für meinen Antrag?", "Wohngeld",
                "GENERAL", null,
                List.of(), List.of(), List.of(), List.of(),
                "Antworttext.", true, 80,
                List.of(new EmailController.AiEvidence(
                        "Richtlinie Wohngeld", "Belegter Auszug", 3, 0.9, "Primär",
                        doc.toString(), chunk.toString())),
                null));
        ctx.setVariable("subjectQueryParam", "Wohngeld+Unterlagen");
        ctx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");

        String html = engine().process("emails/fragments", Set.of("emailAnalysis"), ctx);

        assertTrue(html.contains("Quelle öffnen"), "source action offered for evidence");
        assertTrue(html.contains("chunks=" + chunk), "viewer request targets the exact evidence chunk");
        assertTrue(html.contains("/documents/" + doc + "/view?q=Wohngeld+Unterlagen"),
                "viewer request references the actual source document");
        // Ohne Chunk-Referenz: ehrlicher Fallback (Dokument öffnen, keine Passage).
        Context plainCtx = new Context(Locale.GERMANY);
        plainCtx.setVariable("supervisory", Boolean.FALSE);
        plainCtx.setVariable("emailDraft", null);
        plainCtx.setVariable("draftAllowed", Boolean.FALSE);
        plainCtx.setVariable("outcome", new EmailController.EmailOutcome(
                "Welche Unterlagen fehlen für meinen Antrag?", "Wohngeld",
                "GENERAL", null,
                List.of(), List.of(), List.of(), List.of(),
                "Antworttext.", true, 80,
                List.of(new EmailController.AiEvidence(
                        "Richtlinie Wohngeld", "Belegter Auszug", 3, 0.9, "Primär")),
                null));
        plainCtx.setVariable("subjectQueryParam", "Wohngeld+Unterlagen");
        plainCtx.setVariable("analysisId", "11111111-2222-3333-4444-555555555555");
        String plainHtml = engine().process("emails/fragments", Set.of("emailAnalysis"), plainCtx);
        assertTrue(plainHtml.contains("Quelle öffnen"), "fallback action still offered");
        assertFalse(plainHtml.contains("chunks="),
                "without a chunk reference no fake passage link is created");
    }
}