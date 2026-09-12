package verwaltungsassistent.web.pdfplayground;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Playground proof-of-concept: render a self-contained HTML/CSS decision page
 * to PDF using OpenHTMLToPDF (already on the project classpath).
 *
 * <p>Input:  {@code target/pdf-playground/decision-green.html}
 * Output: {@code target/pdf-playground/html-green-test.pdf}
 *
 * <p>Run after {@code mvn -q compile}:
 * <pre>
 * cd verwaltungsassistent-web
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;$(cat target/cp.txt)" verwaltungsassistent.web.pdfplayground.HtmlToPdfProofOfConcept
 * </pre>
 */
public final class HtmlToPdfProofOfConcept {

    public static void main(String[] args) throws Exception {
        Path htmlPath = Path.of("target", "pdf-playground", "decision-green.html").toAbsolutePath();
        Path outPath = Path.of("target", "pdf-playground", "html-green-test.pdf").toAbsolutePath();

        if (!Files.exists(htmlPath)) {
            throw new IllegalStateException("HTML-Vorlage fehlt: " + htmlPath);
        }

        String html = Files.readString(htmlPath);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, htmlPath.getParent().toUri().toString());
            builder.toStream(out);
            builder.run();
            Files.write(outPath, out.toByteArray());
        }

        System.out.println("HTML/CSS PDF geschrieben nach " + outPath);
        System.out.println("  Größe: " + Files.size(outPath) + " bytes");
    }
}
