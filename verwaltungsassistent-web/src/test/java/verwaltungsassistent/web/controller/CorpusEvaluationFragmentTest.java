package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beispiele "Fall auswerten" evidence badge: a deterministic RULE_ENGINE
 * answer (structured knowledge, grounded, deliberately no document citations)
 * must be labeled "Aus strukturiertem Wissen belegt" — never
 * "Eingeschränkte Quellenlage". The warning stays for ungrounded results.
 */
class CorpusEvaluationFragmentTest {

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

    private Context context(boolean grounded, String strategy, List<String> citations) {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("evalQuestion", "Was verdient ein Sachbearbeiter in EG 9a Stufe 3 nach TV-L?");
        ctx.setVariable("evalAnswer", "3.900,00 € monatlich");
        ctx.setVariable("evalGrounded", grounded);
        ctx.setVariable("evalCitations", citations);
        ctx.setVariable("evalStrategy", strategy);
        ctx.setVariable("evalKeywordHits", List.of("EG 9a", "Stufe 3", "3.900", "monatlich"));
        ctx.setVariable("evalExpectedKeywords", List.of("EG 9a", "Stufe 3", "3.900", "monatlich"));
        return ctx;
    }

    @Test
    void ruleEngineGroundedResult_showsStructuredKnowledgeBadgeNotQuellenlageWarning() {
        // H01 Tarifrecht: RULE_ENGINE, grounded=true, no document citations.
        String html = engine().process("corpus/fragments",
                Set.of("caseEvaluation"), context(true, "Regelbasiert", List.of()));

        assertTrue(html.contains("Aus strukturiertem Wissen belegt"),
                "a rule-backed answer must not imply an inadequate source basis");
        assertFalse(html.contains("Eingeschränkte Quellenlage"));
        assertTrue(html.contains("3.900,00 € monatlich"));
    }

    @Test
    void ungroundedHybridResult_stillShowsQuellenlageWarning() {
        String html = engine().process("corpus/fragments",
                Set.of("caseEvaluation"), context(false, "Hybride Suche", List.of()));

        assertTrue(html.contains("Eingeschränkte Quellenlage"));
        assertFalse(html.contains("Aus strukturiertem Wissen belegt"));
    }
}
