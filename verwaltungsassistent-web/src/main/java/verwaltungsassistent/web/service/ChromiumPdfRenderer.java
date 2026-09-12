package verwaltungsassistent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Production HTML → PDF renderer using a locally installed Chromium-based browser.
 *
 * <p>The renderer fills the validated HTML decision template with the supplied
 * data by embedding both as an inline JavaScript object and a small browser
 * fill script, then asks the browser to print the page to PDF using its native
 * headless mode. No Node.js, npm, Playwright or browser download is required.
 *
 * <p>Configurable property:
 * <ul>
 *   <li>{@code pdf.chromium.executable} – absolute path to a Chromium/Chrome/Edge
 *       executable. If empty, common Windows and Linux installation locations are
 *       auto-detected.</li>
 * </ul>
 */
@Service
public class ChromiumPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(ChromiumPdfRenderer.class);

    private final String configuredExecutable;
    private final ObjectMapper objectMapper;

    private String fillScript;

    public ChromiumPdfRenderer(@Value("${pdf.chromium.executable:}") String configuredExecutable) {
        this.configuredExecutable = configuredExecutable;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Renders the supplied HTML string to PDF.
     *
     * @param html self-contained HTML decision template
     * @param data JSON-serializable data model passed to the template filler
     * @return PDF bytes
     * @throws IOException if rendering fails or no browser is found
     */
    public byte[] render(String html, Map<String, Object> data) throws IOException {
        String executable = resolveExecutable(configuredExecutable);
        String filledHtml = prepareHtml(html, data);

        Path htmlFile = Files.createTempFile("decision-", ".html");
        Path pdfFile = Files.createTempFile("decision-", ".pdf");
        try {
            Files.writeString(htmlFile, filledHtml, StandardCharsets.UTF_8);
            runBrowser(executable, htmlFile, pdfFile);
            return Files.readAllBytes(pdfFile);
        } finally {
            Files.deleteIfExists(htmlFile);
            Files.deleteIfExists(pdfFile);
        }
    }

    private String prepareHtml(String html, Map<String, Object> data) throws IOException {
        String json = objectMapper.writeValueAsString(data)
                .replace("</", "<\\/"); // prevent a closing </script> tag inside the data
        String script = getFillScript();

        String injection = "<script>\n"
                + "const __templateData = " + json + ";\n"
                + script + "\n"
                + "fillTemplate(__templateData);\n"
                + "</script>\n";

        int bodyClose = html.lastIndexOf("</body>");
        if (bodyClose < 0) {
            return html + injection;
        }
        return html.substring(0, bodyClose) + injection + html.substring(bodyClose);
    }

    private String getFillScript() throws IOException {
        if (fillScript != null) {
            return fillScript;
        }
        String resource = "templates/pdf/fill-template.js";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("PDF fill script missing: " + resource);
            }
            fillScript = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return fillScript;
        }
    }

    private void runBrowser(String executable, Path htmlFile, Path pdfFile) throws IOException {
        String fileUrl = "file:///" + htmlFile.toAbsolutePath().toString().replace('\\', '/');
        String pdfPath = pdfFile.toAbsolutePath().toString().replace('\\', '/');

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--headless=old");
        command.add("--disable-gpu");
        command.add("--no-sandbox");
        command.add("--disable-setuid-sandbox");
        command.add("--disable-dev-shm-usage");
        command.add("--run-all-compositor-stages-before-draw");
        command.add("--virtual-time-budget=1000");
        command.add("--no-pdf-header-footer");
        command.add("--print-to-pdf=" + pdfPath);
        command.add(fileUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start browser process " + executable + ". Is it executable?", e);
        }

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Browser process interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Browser process timed out:\n" + output);
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IOException("Browser process failed with exit " + exit + ":\n" + output);
        }
        if (!Files.exists(pdfFile)) {
            throw new IOException("Browser did not produce PDF output.\n" + output);
        }
        if (!output.isBlank()) {
            log.debug("Browser output:\n{}", output);
        }
    }

    private String resolveExecutable(String configured) throws IOException {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
            throw new IOException("Configured Chromium-based browser is not executable: " + configured
                    + ". Set pdf.chromium.executable to a valid Chrome/Chromium/Edge executable.");
        }

        Set<String> candidates = new LinkedHashSet<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addIfSet(candidates, System.getenv("ProgramFiles"), "Google/Chrome/Application/chrome.exe");
            addIfSet(candidates, System.getenv("ProgramFiles"), "Microsoft/Edge/Application/msedge.exe");
            addIfSet(candidates, System.getenv("ProgramFiles(x86)"), "Google/Chrome/Application/chrome.exe");
            addIfSet(candidates, System.getenv("ProgramFiles(x86)"), "Microsoft/Edge/Application/msedge.exe");
            addIfSet(candidates, System.getenv("LocalAppData"), "Google/Chrome/Application/chrome.exe");
        } else if (os.contains("linux")) {
            candidates.add("/usr/bin/chromium");
            candidates.add("/usr/lib/chromium/chromium");
            candidates.add("/usr/bin/chromium-browser");
            candidates.add("/usr/bin/google-chrome");
            candidates.add("/usr/bin/google-chrome-stable");
            candidates.add("/usr/bin/microsoft-edge");
            addPathExecutables(candidates, "chromium", "chromium-browser", "google-chrome",
                    "google-chrome-stable", "microsoft-edge");
        } else {
            throw new IOException("Auto-detection of a Chromium-based browser is not supported on " + os
                    + ". Set pdf.chromium.executable.");
        }

        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isExecutable(p)) {
                log.info("Auto-detected Chromium-based browser: {}", p);
                return p.toAbsolutePath().toString();
            }
        }

        throw new IOException("No Chromium-based browser found. "
                + "Install Google Chrome, Chromium, or Microsoft Edge, or set pdf.chromium.executable. "
                + "Searched: " + candidates);
    }

    private void addIfSet(Set<String> candidates, String base, String relative) {
        if (base == null || base.isBlank()) {
            return;
        }
        candidates.add(Path.of(base, relative).toString());
    }

    private void addPathExecutables(Set<String> candidates, String... names) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                candidates.add(Path.of(dir, name).toString());
            }
        }
    }
}
