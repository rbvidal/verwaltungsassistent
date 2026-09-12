# Handoff: Entscheidungsvorlage / PDF Generation

> Documentation-only snapshot of the production PDF generation as of 2026-08-28.

---

## 1. Overview / Purpose

The *Entscheidungsvorlage* is an administrative-style PDF draft exported from a case's completed AI analysis. It is intended for human reviewers and contains:

- municipal letterhead / processor metadata
- case identifiers (Vorgangsnummer, Aktenzeichen, Fallart)
- the AI recommendation split into Kurzantwort / Entscheidung / Rechtsgrundlage
- Kernfeststellungen, Weitere Erkenntnisse, Offene Punkte
- Empfohlene nächste Schritte
- footer with export date and application version

The pipeline that produces the recommendation is **never re-run** during export. The exporter reuses the structured analysis result already persisted by `DecisionWorkspaceController`.

---

## 2. Production Architecture & Rendering Technology

Production PDF generation is **Chromium-based, direct from Java**. There is **no Node.js, npm, Playwright, or browser download** required at runtime.

Key components:

- `DecisionPdfExporter` — selects template, maps model to flat keys, loads HTML master template.
- `ChromiumPdfRenderer` — writes self-contained temp HTML, runs a locally installed Chromium/Chrome/Edge process with `--print-to-pdf`, reads back the PDF bytes.
- `templates/pdf/decision-{blue,green,warning}.html` — validated HTML/CSS master templates.
- `templates/pdf/fill-template.js` — embedded into the HTML before `</body>` and mutates the DOM before Chromium prints.

Browser invocation (see `ChromiumPdfRenderer#runBrowser`):

```
<browser> --headless=old --disable-gpu --no-sandbox --disable-setuid-sandbox
          --disable-dev-shm-usage --run-all-compositor-stages-before-draw
          --virtual-time-budget=1000 --no-pdf-header-footer
          --print-to-pdf=<path> <file-url>
```

Browser resolution:

1. `pdf.chromium.executable` if configured and executable.
2. Auto-detect common Windows paths (`ProgramFiles`, `ProgramFiles(x86)`, `LocalAppData`) for Chrome/Edge.
3. Auto-detect common Linux paths plus `PATH` lookup for `chromium`, `chromium-browser`, `google-chrome`, `google-chrome-stable`, `microsoft-edge`.
4. Throw `IOException` if none found.

The HTML preparation serializes the data map as JSON, escapes `</` to avoid breaking the inline script, embeds `fill-template.js`, and calls `fillTemplate(__templateData)`.

---

## 3. Production vs. Old / Playground Code

### Production

- `DecisionPdfExporter.java`
- `ChromiumPdfRenderer.java`
- `templates/pdf/decision-{blue,green,warning}.html`
- `templates/pdf/fill-template.js`

These are the only paths used by the live export endpoint `/cases/{id}/decision/export-pdf`.

### Legacy / Playground (NOT production)

- `DecisionTemplatePdfRenderer.java` — old PDFBox overlay renderer. Takes pre-rendered one-page PDF templates (`template-blue.pdf`, etc.) and draws text at hard-coded point coordinates. Kept as reference but not used for live export.
- `EntscheidungPdfPlayground.java` and `PlaygroundTemplatePdfRenderer` — standalone `main()` playground for quickly rendering representative models to `target/pdf-playground/`. No Spring, no DB, purely visual reference.

**Do not accidentally route production export back to the PDFBox renderer.** The controller injects `DecisionPdfExporter` and calls `pdfExporter.export(model)`.

---

## 4. HTML Master Templates

Three validated templates live under `src/main/resources/templates/pdf/`:

| Template | Meaning |
|----------|---------|
| `decision-green.html` | Grounded, usable result (Quellenbelegt) |
| `decision-blue.html` | Normal / informative result, human review recommended |
| `decision-warning.html` | Fail-closed or insufficient coverage |

All three share the same DOM structure and CSS selectors so that `fill-template.js` works uniformly. Differences are purely visual (banner colors, iconography, wording).

### Layout evolution (dynamic vertical growth)

The A4 page was originally a fixed-height, single-page container:

```css
height: 297mm; max-height: 297mm; overflow: hidden; justify-content: space-between;
```

It was changed to allow natural vertical growth and pagination:

```css
min-height: 297mm; height: auto; max-height: none; overflow: visible; justify-content: flex-start;
```

Major sections now use `break-inside: avoid` so Chromium print-to-pdf keeps them coherent across pages. This allows the recommendation card and writing areas to grow with content instead of being truncated.

### Key DOM targets in `fill-template.js`

| Selector | Content |
|----------|---------|
| `.doc-reserved-space` | Case title |
| `#processingStatus` | Bearbeitungsstand |
| `#confidence` | Gesamtkonfidenz |
| `.status-box-vertical` | Dokumente im Vorgang list + icon |
| `.processor-name-fill` | Bearbeiter name |
| `.processor-sub` / `.processor-sub-secondary` | Role / room |
| `.processor-contact-row span` | Phone / email |
| `.field-dashed-line` (3x) | Vorgangsnummer, Aktenzeichen, Fallart |
| `.rec-dynamic-space` | Recommendation semantic blocks |
| `.writing-area` (4x) | Kernfeststellungen, Weitere Erkenntnisse, Offene Punkte, Nächste Schritte |
| `.page-marker` | Footer page text |
| `.footer-right span span` | Export date |

---

## 5. Data Model / Flat Key Mapping

`DecisionPdfExporter#buildTemplateData` converts the persisted analysis model into the flat keys consumed by `fill-template.js`:

| Output key | Source / derivation |
|------------|---------------------|
| `caseTitle` | `caseName` |
| `processingStatus` | `processingStatus` (from `ProcessingStatus.fromPhase(phase).getLabel()`) |
| `confidence` | `confidenceScore` |
| `documents` | `documentNames` |
| `processorName` | `caseOwnerDisplay` |
| `processorRole` | `caseOwnerRole` |
| `processorRoom` | `caseOwnerRoom` |
| `processorPhone` | `caseOwnerPhone` |
| `processorEmail` | `caseOwnerEmail` |
| `vorgangsnummer` | `workspaceCode` |
| `aktenzeichen` | `aktenzeichen` (or auto-built from case name + workspace code) |
| `fallart` | `caseType` |
| `kurzantwort` | parsed from `decisionAnswer` section `KURZANTWORT` |
| `entscheidung` | parsed from `decisionAnswer` section `ENTSCHEIDUNG` |
| `rechtsgrundlage` | parsed from `decisionAnswer` section `RECHTSGRUNDLAGE` |
| `recommendation` | full `decisionAnswer` only when no sections were parsed |
| `kernfeststellungen` | `primaryFindings` descriptions (or labels) |
| `weitereErkenntnisse` | `secondaryFindings` descriptions (or labels) |
| `offenePunkte` | `missingDocs` + `coverageIssues` (with Hinweis/Info filtered out of coverage) |
| `naechsteSchritte` | `proceduralFindings`, then `VERFAHREN`/`NÄCHSTER SCHRITT` from answer, then generic fallback |
| `footerPage` | hard-coded `"Seite 1/1"` |
| `exportDate` | `generatedAt` date part, or today `dd.MM.yyyy` |

The model is also enriched with `appVersion` (from `@Value("${app.version:1.0.0-RC2}")`).

---

## 6. Template Selection Logic

`DecisionPdfExporter#selectTemplate` uses the same semantics as the old PDFBox renderer:

```
grounded = model.grounded == true
insufficientAnswer = decisionAnswer contains "keine ausreichenden Informationen"
noCoreFindings = primaryFindings is empty
insufficientCoverage = hasRealCoverageIssue(model)
failClosed = insufficientAnswer || (noCoreFindings && !grounded)

if (grounded && !failClosed) -> GREEN
else if (failClosed || insufficientCoverage) -> WARNING
else -> BLUE
```

Coverage issues that only contain `hinweis` or `info` do **not** trigger the warning template; everything else does.

---

## 7. Recent Implementation Details

### Semantic answer parsing

`DecisionPdfExporter#parseAnswerSections` parses the German answer format into named blocks:

```
KURZANTWORT
...

ENTSCHEIDUNG
...

RECHTSGRUNDLAGE
...

VERFAHREN
...

NÄCHSTER SCHRITT
...
```

Markers are matched case-insensitively at the start of a line (regex `(?im)(?:^|\n)\s*MARKER\b`). The parser returns a `LinkedHashMap` of the found sections.

`fill-template.js#renderRecommendation` renders these as separate labeled paragraphs inside `.rec-dynamic-space`.

### Actual procedural findings for next steps

`DecisionPdfExporter#nextSteps` prefers, in order:

1. `proceduralFindings`
2. `VERFAHREN` and `NÄCHSTER SCHRITT` sections from `decisionAnswer`
3. A generic four-step fallback so the section never appears empty

### Dynamic vertical growth

- `.a4-page`: changed from fixed height to `min-height` / `height: auto` / `overflow: visible`.
- `.recommendation-card`: `height: auto; break-inside: avoid`.
- `.bottom-status-card`: `margin-top: auto; break-inside: avoid`.
- `.footer-bar`: `break-inside: avoid`.

This lets the recommendation and writing areas expand and paginate naturally instead of being clipped at A4 height.

### Known parser fix

An earlier bug in `findAnswerMarker` returned `matcher.start()`, which included optional leading whitespace and caused section content to start before the marker. It now returns `matcher.end() - marker.length()` so the marker start is exact.

---

## 8. Known Problems / Caveats

- **Browser dependency:** Production export requires a Chromium-based browser installed on the host. There is no bundled fallback.
- **Footer page number is static:** `footerPage` is always `"Seite 1/1"`. Multi-page documents will show an incorrect page count because Chromium's `--no-pdf-header-footer` is used.
- **HTML/CSS fragility:** The filler script relies on exact CSS selector names and DOM order (e.g. `.writing-area` 4-tuple). Template redesigns must keep these contracts or update `fill-template.js` in lockstep.
- **Legacy renderer drift:** `DecisionTemplatePdfRenderer` has its own hard-coded coordinates and template selection logic. If the selection rules change in production, the legacy renderer will silently diverge unless intentionally kept in sync.
- **Answer section parsing is heuristic:** It depends on the LLM emitting the exact German section headers. Non-standard answers fall back to the raw `recommendation` text.
- **No runtime verification in this handoff:** The statements above reflect the source code inspected on 2026-08-28.

---

## 9. Important Classes / Files

### Production

- `verwaltungsassistent-web/src/main/java/com/verwaltungsassistent/verwaltungsassistent/service/DecisionPdfExporter.java`
- `verwaltungsassistent-web/src/main/java/com/verwaltungsassistent/verwaltungsassistent/service/ChromiumPdfRenderer.java`
- `verwaltungsassistent-web/src/main/resources/templates/pdf/decision-blue.html`
- `verwaltungsassistent-web/src/main/resources/templates/pdf/decision-green.html`
- `verwaltungsassistent-web/src/main/resources/templates/pdf/decision-warning.html`
- `verwaltungsassistent-web/src/main/resources/templates/pdf/fill-template.js`

### Controller / wiring

- `verwaltungsassistent-web/src/main/java/com/verwaltungsassistent/verwaltungsassistent/controller/DecisionWorkspaceController.java` — endpoint `/cases/{id}/decision/export-pdf`, prepares model and calls `DecisionPdfExporter.export`.

### Legacy / playground (not production)

- `verwaltungsassistent-web/src/main/java/com/verwaltungsassistent/verwaltungsassistent/service/DecisionTemplatePdfRenderer.java`
- `verwaltungsassistent-web/src/main/java/com/verwaltungsassistent/verwaltungsassistent/pdfplayground/EntscheidungPdfPlayground.java`

### Tests

- `verwaltungsassistent-web/src/test/java/com/verwaltungsassistent/verwaltungsassistent/service/DecisionPdfExporterDataTest.java` — covers template data mapping, document list, and semantic answer section parsing / next-step extraction.

---

## 10. End-to-End Flow

1. User clicks *PDF export* in the case decision UI.
2. `GET /cases/{id}/decision/export-pdf` is handled by `DecisionWorkspaceController#exportDecisionPdf`.
3. Controller loads the latest completed analysis run (or reuses the active job outcome) and deserializes the result.
4. Controller enriches the model with case metadata: name, workspace code, case type, phase, processing status, document names, owner details, generated timestamp.
5. `DecisionPdfExporter.export(model)` is called.
6. `DecisionPdfExporter`:
   - enriches defaults (`appVersion`, `caseName`, `workspaceCode`, `aktenzeichen`, `confidenceScore`)
   - selects GREEN / BLUE / WARNING template
   - loads the matching HTML master template from classpath
   - builds flat template data including parsed semantic answer sections
7. `ChromiumPdfRenderer.render(html, data)`:
   - serializes data to JSON
   - embeds the JSON + `fill-template.js` before `</body>`
   - writes temp HTML file
   - resolves and launches Chromium/Edge with `--print-to-pdf`
   - waits up to 60 seconds
   - reads the generated PDF bytes and deletes temp files
8. Controller returns PDF as `application/pdf` attachment named `entscheidungsvorlage-<caseName>.pdf`.
9. A timeline event *Entscheidungsvorlage exportiert* is recorded for the case.
