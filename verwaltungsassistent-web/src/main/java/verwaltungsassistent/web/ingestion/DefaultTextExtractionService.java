package verwaltungsassistent.web.ingestion;

import reasoning.common.model.DocumentFileType;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.DocumentVersion;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class DefaultTextExtractionService implements TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTextExtractionService.class);
    private final Path uploadDir;

    public DefaultTextExtractionService(@Value("${app.upload-dir:uploads}") String uploadDirPath) {
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
    }

    @Override
    public String extractText(DocumentFileType type, DocumentVersion version) {
        if (!"local".equalsIgnoreCase(version.storageProvider()) && !"local-fs".equalsIgnoreCase(version.storageProvider())) {
            throw new IllegalStateException("Unsupported storage provider: " + version.storageProvider());
        }
        Path path = uploadDir.resolve(version.storageKey());
        if (!Files.exists(path)) {
            throw new IllegalStateException("Stored file not found: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return switch (type) {
                case TXT -> extractTxt(in);
                case HTML -> extractHtml(in);
                case PDF -> extractPdf(in);
                default -> new Tika().parseToString(in);
            };
        } catch (Exception ex) {
            log.warn("Text extraction failed for {}: {}", type, ex.getMessage());
            return "";
        }
    }

    private String extractTxt(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String extractHtml(InputStream in) throws java.io.IOException {
        return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).text();
    }

    private String extractPdf(InputStream in) throws java.io.IOException {
        byte[] bytes = in.readAllBytes();
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
