package verwaltungsassistent.web.service;

import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceCitation;
import reasoning.common.model.DocumentFileType;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentVersion;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import verwaltungsassistent.web.controller.DocumentController;
import verwaltungsassistent.web.controller.EmailController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared document-viewing and source-provenance enrichment for the UI.
 * One place that loads a document together with its indexed chunks and
 * translates them into presentation-ready German labels — used by the
 * document viewer modal (Fälle, later Wissensbasis/Assistant) and by the
 * evidence sources dialog (assistant answer, Beispiele evaluation).
 *
 * <p>Only reads existing backend state; nothing here changes the pipeline.
 * Chunk-level fields (chunk index, chunk title, document type, category,
 * full chunk text) are presented when the chunk index has them — a citation
 * without a matching chunk simply keeps those fields null.</p>
 */
@Service
public class DocumentViewerService {

    private static final Logger log = LoggerFactory.getLogger(DocumentViewerService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final DocumentFacade documentFacade;
    private final JpaDocumentChunkRepository chunkRepository;
    private final TextExtractionService textExtractionService;

    public DocumentViewerService(DocumentFacade documentFacade,
                                 JpaDocumentChunkRepository chunkRepository,
                                 TextExtractionService textExtractionService) {
        this.documentFacade = documentFacade;
        this.chunkRepository = chunkRepository;
        this.textExtractionService = textExtractionService;
    }

    /** Presentation view of a document with its indexed chunks, for the viewer modal. */
    public DocumentView view(UUID documentId, String actor) {
        return view(documentId, actor, null);
    }

    /**
     * Like {@link #view(UUID, String)} but additionally highlights the
     * query's specific terms inside the chunk text (segments with
     * {@code highlighted=true}); used by analysis results so the viewer shows
     * WHERE the document matches. Without a highlight query the chunks carry
     * plain text only (existing behavior).
     */
    public DocumentView view(UUID documentId, String actor, String highlightQuery) {
        return view(documentId, actor, highlightQuery, null);
    }

    /**
     * Wie {@link #view(UUID, String, String)}, aber die Hervorhebung wird auf
     * die EVIDENCE-Chunks begrenzt ({@code highlightChunkIds}): Nur die
     * Abschnitte, die tatsächlich als Beleg für die Antwort verwendet wurden,
     * tragen die Markierung — nicht das gesamte Dokument. Das verhindert den
     * „Keyword-Dump"-Eindruck und zeigt dem Sachbearbeiter, welche Passage
     * der Beleg ist.
     */
    public DocumentView view(UUID documentId, String actor, String highlightQuery,
                             java.util.Set<UUID> highlightChunkIds) {
        Document doc = documentFacade.getDocument(documentId, actor);

        List<String> highlightTerms = highlightTerms(highlightQuery);
        boolean evidenceScoped = highlightChunkIds != null && !highlightChunkIds.isEmpty();
        List<ChunkView> chunks = new ArrayList<>();
        try {
            for (DocumentChunkEntity c : chunkRepository.findByDocumentIdOrderByChunkIndex(documentId)) {
                boolean isEvidence = evidenceScoped && highlightChunkIds.contains(c.getId());
                List<Segment> segments = null;
                if (!highlightTerms.isEmpty() && (!evidenceScoped || isEvidence)) {
                    segments = highlightSegments(c.getText(), highlightTerms);
                }
                chunks.add(new ChunkView(c.getId(), c.getChunkIndex(), c.getPageNumber(),
                        c.getTitle(), c.getDocumentVersion(), c.getText(), segments, isEvidence));
            }
        } catch (Exception e) {
            log.debug("Chunk load failed for {}: {}", documentId, e.getMessage());
        }

        boolean isPdf = doc.metadata() != null && doc.metadata().type() == DocumentFileType.PDF;
        // Bilddokument (JPG/PNG/…): echte Bildanzeige statt erfundener OCR-Texte.
        boolean isImage = false;
        try {
            if (!doc.versions().isEmpty()) {
                String contentType = doc.versions().get(doc.versions().size() - 1).contentType();
                isImage = contentType != null && contentType.startsWith("image/");
            }
        } catch (Exception e) {
            log.debug("Bild-Typ von Dokument {} nicht lesbar: {}", documentId, e.getMessage());
        }
        String fullText = null;
        List<Segment> fullTextSegments = null;
        if (!isPdf && !isImage) {
            fullText = extractFullText(doc);
            if (fullText != null && !highlightTerms.isEmpty() && !evidenceScoped) {
                fullTextSegments = highlightSegments(fullText, highlightTerms);
            }
        }

        String ingestionStatus = "Nicht indiziert";
        try {
            var jobs = documentFacade.findIngestionJobs(new IngestionJobFilter(documentId, null, null, 0, 1));
            if (!jobs.jobs().isEmpty()) {
                ingestionStatus = DocumentController.ingestionStatusLabel(jobs.jobs().get(0).status().name());
            }
        } catch (Exception e) {
            log.debug("Ingestion status lookup failed for {}: {}", documentId, e.getMessage());
        }

        return new DocumentView(
                doc.id(),
                doc.metadata() != null && doc.metadata().title() != null && !doc.metadata().title().isBlank()
                        ? doc.metadata().title() : "Dokument ohne Titel",
                doc.metadata() != null && doc.metadata().type() != null ? doc.metadata().type().name() : "—",
                doc.metadata() != null ? DocumentController.categoryLabel(doc.metadata().category()) : "—",
                DocumentController.statusLabel(doc.status()),
                DocumentController.statusVariant(doc.status()),
                doc.currentVersion(),
                doc.currentVersion() > 0 && !doc.versions().isEmpty()
                        ? DocumentController.formatFileSize(doc.versions().get(doc.versions().size() - 1).sizeBytes())
                        : "—",
                ingestionStatus,
                doc.createdAt() != null ? DATE_FMT.format(doc.createdAt().atZone(ZoneId.systemDefault())) : "—",
                doc.updatedAt() != null ? DATE_FMT.format(doc.updatedAt().atZone(ZoneId.systemDefault())) : "—",
                doc.createdBy() != null ? doc.createdBy() : "—",
                doc.updatedBy() != null ? doc.updatedBy() : "—",
                fullText,
                fullTextSegments,
                isPdf,
                isImage,
                chunks);
    }

    /** Extracts the complete text from the original stored file, if available. */
    private String extractFullText(Document doc) {
        if (doc.metadata() == null || doc.versions() == null || doc.versions().isEmpty()) {
            return null;
        }
        DocumentVersion version = doc.versions().get(doc.versions().size() - 1);
        if (version == null || version.storageKey() == null || version.storageKey().isBlank()) {
            return null;
        }
        try {
            return textExtractionService.extractText(doc.metadata().type(), version);
        } catch (Exception e) {
            log.debug("Full-text extraction failed for {}: {}", doc.id(), e.getMessage());
            return null;
        }
    }

    /**
     * Enriches answer citations with the evidence they point to: chunk index,
     * chunk title, document type/category and the full chunk text, so the
     * sources dialog can show real provenance instead of only the citation
     * excerpt. Missing chunks degrade gracefully (fields stay null).
     */
    public List<SourceView> fromCitations(List<SourceCitation> citations) {
        if (citations == null || citations.isEmpty()) return List.of();

        Map<UUID, DocumentChunkEntity> chunkById = new HashMap<>();
        Map<UUID, Document> docByDocumentId = new HashMap<>();

        for (SourceCitation c : citations) {
            if (c.chunkId() == null) continue;
            if (chunkById.containsKey(c.chunkId())) continue;
            List<DocumentChunkEntity> docChunks;
            try {
                docChunks = chunkRepository.findByDocumentIdOrderByChunkIndex(c.documentId());
            } catch (Exception e) {
                log.debug("Chunk lookup failed for {}: {}", c.documentId(), e.getMessage());
                continue;
            }
            for (DocumentChunkEntity chunk : docChunks) {
                chunkById.putIfAbsent(chunk.getId(), chunk);
            }
        }

        List<SourceView> views = new ArrayList<>(citations.size());
        for (SourceCitation c : citations) {
            DocumentChunkEntity chunk = c.chunkId() != null ? chunkById.get(c.chunkId()) : null;
            Document doc = null;
            if (chunk == null && c.documentId() != null) {
                doc = docByDocumentId.computeIfAbsent(c.documentId(), id -> loadDocument(id));
            }

            String documentType = null;
            if (chunk != null && chunk.getDocumentType() != null) {
                documentType = chunk.getDocumentType().name();
            } else if (doc != null && doc.metadata() != null && doc.metadata().type() != null) {
                documentType = doc.metadata().type().name();
            }
            String category = null;
            if (chunk != null && chunk.getCategory() != null && !chunk.getCategory().isBlank()) {
                category = DocumentController.categoryLabel(chunk.getCategory());
            } else if (doc != null && doc.metadata() != null && doc.metadata().category() != null) {
                category = DocumentController.categoryLabel(doc.metadata().category());
            }

            views.add(new SourceView(
                    c.documentId(), c.chunkId(), c.documentVersion(), c.title(),
                    c.pageNumber(), c.excerpt(), c.confidenceScore(),
                    tierLabel(c.tier()), tierVariant(c.tier()), c.retrievalSources(),
                    chunk != null ? chunk.getChunkIndex() : null,
                    chunk != null && chunk.getTitle() != null && !chunk.getTitle().isBlank()
                            ? chunk.getTitle() : null,
                    documentType, category,
                    chunk != null ? chunk.getText() : null));
        }
        return views;
    }

    private Document loadDocument(UUID documentId) {
        try {
            return documentFacade.getDocument(documentId, "system");
        } catch (Exception e) {
            log.debug("Document lookup failed for {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the backing document for a structured-knowledge source (e.g.
     * the "TV-L Entgelttabellen 2025" salary table) by its title in the
     * document store. Returns null when no document matches — the
     * structured-knowledge entry then stays a non-clickable source; a
     * document link is never invented.
     */
    public UUID resolveDocumentIdByTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String norm = normalizeTitle(title);
        if (norm.isBlank()) {
            return null;
        }
        try {
            var filter = new reasoning.document.api.DocumentFilter(
                    null, null, null, null, null, null, null, 0, 200);
            var result = documentFacade.findDocuments(filter);
            UUID exact = null;
            UUID contains = null;
            for (Document d : result.documents()) {
                String t = d.metadata() != null ? d.metadata().title() : null;
                if (t == null || t.isBlank()) {
                    continue;
                }
                String tn = normalizeTitle(t);
                if (tn.equals(norm)) {
                    exact = d.id();
                    break;
                }
                if (contains == null && (tn.contains(norm) || norm.contains(tn))) {
                    contains = d.id();
                }
            }
            return exact != null ? exact : contains;
        } catch (Exception e) {
            log.debug("Document title resolution failed for {}: {}", title, e.getMessage());
            return null;
        }
    }

    /**
     * Finds the chunk of a document whose text contains a distinctive
     * fragment of the source excerpt — used to mark the passage as a Beleg
     * in the document viewer. Null when no chunk matches.
     */
    public UUID resolveChunkByExcerpt(UUID documentId, String excerpt) {
        if (documentId == null || excerpt == null || excerpt.isBlank()) {
            return null;
        }
        String probe = excerpt.trim().replaceAll("\\s+", " ");
        if (probe.length() > 48) {
            probe = probe.substring(0, 48);
        }
        try {
            for (DocumentChunkEntity chunk : chunkRepository.findByDocumentIdOrderByChunkIndex(documentId)) {
                String text = chunk.getText();
                if (text != null && text.replaceAll("\\s+", " ").contains(probe)) {
                    return chunk.getId();
                }
            }
        } catch (Exception e) {
            log.debug("Chunk resolution failed for {}: {}", documentId, e.getMessage());
        }
        return null;
    }

    /** Umlaut-/Sonderzeichen-faltende Titel-Normalisierung für den Abgleich. */
    private static String normalizeTitle(String title) {
        return title.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-zäöüß0-9]", "")
                .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
    }

    private static String tierLabel(SourceCitation.SourceTier tier) {
        if (tier == null) return null;
        return switch (tier) {
            case PRIMARY -> "Primär";
            case SUPPORTING -> "Unterstützend";
            case BACKGROUND -> "Hintergrund";
        };
    }

    private static String tierVariant(SourceCitation.SourceTier tier) {
        if (tier == null) return null;
        return switch (tier) {
            case PRIMARY -> "success";
            case SUPPORTING -> "info";
            case BACKGROUND -> "neutral";
        };
    }

    /** Document metadata + chunk list for the reusable viewer modal. */
    public record DocumentView(
            UUID documentId, String title, String typeLabel, String categoryLabel,
            String statusLabel, String statusVariant, int version, String fileSize,
            String ingestionStatus, String createdAt, String updatedAt,
            String createdBy, String updatedBy,
            String fullText, List<Segment> fullTextSegments, boolean pdf, boolean image,
            List<ChunkView> chunks) {}

    /** One indexed chunk of a document; {@code evidence} marks a Beleg-Abschnitt. */
    public record ChunkView(UUID chunkId, int index, Integer pageNumber, String title,
                            int documentVersion, String text, List<Segment> segments,
                            boolean evidence) {
        public ChunkView(UUID chunkId, int index, Integer pageNumber, String title,
                         int documentVersion, String text, List<Segment> segments) {
            this(chunkId, index, pageNumber, title, documentVersion, text, segments, false);
        }
    }

    /** One text segment of a chunk; {@code highlighted} marks a query-term match. */
    public record Segment(String text, boolean highlighted) {}

    /**
     * The specific terms of the highlight query (length &gt; 2, neither generic
     * administrative vocabulary nor function words, umlauts folded), matching
     * the textual-evidence rule of the Wissensbasis.
     */
    static List<String> highlightTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> terms = new ArrayList<>();
        for (String raw : query.toLowerCase().split("\\s+")) {
            String cleaned = raw.replaceAll("[^a-zäöüß0-9]", "");
            if (cleaned.length() > 2 && !EmailController.GENERIC_TERMS.contains(cleaned)
                    && !EmailController.STOPWORDS.contains(cleaned)) {
                terms.add(cleaned);
                String ascii = cleaned.replace("ä", "a").replace("ö", "o")
                        .replace("ü", "u").replace("ß", "ss");
                if (!ascii.equals(cleaned)) terms.add(ascii);
            }
        }
        return terms;
    }

    /** Splits the chunk text into alternating plain/highlighted segments. */
    static List<Segment> highlightSegments(String text, List<String> terms) {
        if (text == null || text.isEmpty() || terms.isEmpty()) {
            return List.of(new Segment(text == null ? "" : text, false));
        }
        String lower = text.toLowerCase();
        List<Segment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int best = -1;
            for (String term : terms) {
                int idx = lower.indexOf(term, cursor);
                if (idx >= 0 && (best < 0 || idx < best)) best = idx;
            }
            if (best < 0) {
                segments.add(new Segment(text.substring(cursor), false));
                break;
            }
            if (best > cursor) segments.add(new Segment(text.substring(cursor, best), false));
            int anchor = best;
            int end = best + terms.stream()
                    .filter(t -> lower.startsWith(t, anchor))
                    .mapToInt(String::length)
                    .max()
                    .orElse(1);
            segments.add(new Segment(text.substring(best, end), true));
            cursor = end;
        }
        return segments;
    }

    /** A citation enriched with chunk/document provenance for the sources dialog. */
    public record SourceView(
            UUID documentId, UUID chunkId, int documentVersion, String title,
            Integer pageNumber, String excerpt, double confidenceScore,
            String tierLabel, String tierVariant, List<String> retrievalSources,
            Integer chunkIndex, String chunkTitle, String documentTypeLabel,
            String categoryLabel, String chunkText) {
        /** Ungruppierte Sicht: keine Abschnitts-Anzahl (Template-Kompatibilität). */
        public Integer chunkCount() {
            return null;
        }
    }

    /**
     * Gruppiert die Beleg-Chunks nach Dokument: EINE Karte pro Dokument statt
     * mehrerer Chunk-Einträge. Die Passage ist der zusammengeführte Auszug
     * (umgebender Zusammenhang), die Konfidenz das Maximum der Gruppe.
     * Die chunk-spezifischen Felder bleiben null — die Quellen-Karte rendert
     * dann ohne Chunk-Jargon.
     */
    public List<SourceGroupView> groupByDocument(List<SourceView> views) {
        if (views == null || views.isEmpty()) return List.of();
        Map<UUID, List<SourceView>> byDoc = new java.util.LinkedHashMap<>();
        for (SourceView v : views) {
            if (v.documentId() == null) continue;
            byDoc.computeIfAbsent(v.documentId(), k -> new ArrayList<>()).add(v);
        }
        List<SourceGroupView> out = new ArrayList<>();
        for (var e : byDoc.entrySet()) {
            List<SourceView> group = e.getValue();
            SourceView first = group.getFirst();
            String passage = group.stream()
                    .map(SourceView::excerpt)
                    .filter(x -> x != null && !x.isBlank())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" … "));
            double conf = group.stream().mapToDouble(SourceView::confidenceScore).max().orElse(0);
            List<String> chunkIds = group.stream()
                    .map(SourceView::chunkId)
                    .filter(java.util.Objects::nonNull)
                    .map(UUID::toString)
                    .toList();
            out.add(new SourceGroupView(e.getKey(), first.title(), first.documentVersion(),
                    first.pageNumber(),
                    passage.isBlank() ? null : passage, conf,
                    first.documentTypeLabel(), first.categoryLabel(),
                    chunkIds, group.size()));
        }
        return out;
    }

    /** Ein Beleg, gruppiert nach Dokument (Sachbearbeiter-Sicht). */
    public record SourceGroupView(
            UUID documentId, UUID chunkId, int documentVersion, String title,
            Integer pageNumber, String excerpt, double confidenceScore,
            String tierLabel, String tierVariant, List<String> retrievalSources,
            Integer chunkIndex, String chunkTitle, String documentTypeLabel,
            String categoryLabel, String chunkText, List<String> chunkIds, int chunkCount) {
        public SourceGroupView(UUID documentId, String title, int documentVersion,
                               Integer pageNumber, String excerpt, double confidenceScore,
                               String documentTypeLabel, String categoryLabel,
                               List<String> chunkIds, int chunkCount) {
            this(documentId, null, documentVersion, title, pageNumber, excerpt, confidenceScore,
                    null, null, null, null, null, documentTypeLabel, categoryLabel, null,
                    chunkIds, chunkCount);
        }
    }
}
