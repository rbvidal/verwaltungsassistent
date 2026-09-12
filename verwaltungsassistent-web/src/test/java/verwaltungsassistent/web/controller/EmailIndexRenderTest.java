package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;

class EmailIndexRenderTest {

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
    void emailIndexRendersWithoutOutcome() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("demoEmails", java.util.List.of());
        try {
            String html = engine().process("emails/index", ctx);
            System.out.println("RENDER OK len=" + html.length());
        } catch (Exception e) {
            System.out.println("RENDER FAIL: " + e.getMessage());
            fail("render failed: " + e.getMessage());
        }
    }
}
