package verwaltungsassistent.web.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: Eine legitime Assistenten-Antwort mit unzureichender Quellenlage
 * (keine Belege, keine Rechtsgrundlagen, Konfidenz 0, fail-closed) MUSS als
 * internes Informations-PDF exportierbar sein — der Export darf weder an
 * fehlenden Beleg-Objekten noch am Template scheitern. Deterministisches
 * Fixture, kein LLM-Aufruf.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AssistantAnswerPdfExportRegressionTest {

    @Autowired
    private AssistantAnswerPdfExporter exporter;

    @Test
    void insufficientSourceAnswer_exportsPdf() throws Exception {
        var outcome = new JobProgressService.AssistantOutcome(
                "Die Informationen in unserem System reichen für diese Frage derzeit nicht aus.",
                false,
                "HYBRID",
                List.of(),
                List.of(),
                0,
                true);

        byte[] pdf = exporter.export(
                "Wie erhalte ich mehrere Dienstleistungen in nur einem Termin?",
                outcome,
                LocalDateTime.of(2026, 9, 6, 12, 0));

        assertTrue(pdf != null && pdf.length > 500,
                "PDF muss erzeugt werden (war " + (pdf == null ? "null" : pdf.length) + " Bytes)");
        String head = new String(pdf, 0, Math.min(8, pdf.length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF-"), "Ausgabe muss ein gültiges PDF sein, war: " + head);
    }
}
