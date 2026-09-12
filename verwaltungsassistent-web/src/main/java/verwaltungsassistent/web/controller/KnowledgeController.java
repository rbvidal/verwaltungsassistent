package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.search.api.SearchFacade;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Minimum fused hybrid score for a result to be presented in the
     * knowledge-base view. The displayed score is
     * (keywordScore*0.40 + vectorScore*0.40 + confidence*0.20) * docTypeWeight,
     * clamped to [0,1]; the existing UI already treats scores below 0.3 as
     * "weak" (neutral badge). Values below that band are not useful to a
     * municipal employee and are filtered here — presentation only, the
     * retrieval pipeline is unchanged.
     */
    private static final double MIN_RELEVANCE_SCORE = 0.30;

    /**
     * In HYBRID mode a result is presented only when it carries textual
     * evidence: at least one chunk of the document must contain at least one
     * query term (length > 2). Measured on the live corpus: keyword and
     * vector hits rarely share a chunk id, so almost all candidates keep
     * their raw single-source score (vector-only ~0.69 for ANY Berlin
     * service document, e.g. "Beglaubigung" for the query "bauaufsicht
     * prenzlauer berg"). Requiring document-level textual evidence removes
     * those coincidence hits; SEMANTIC mode intentionally shows pure
     * similarity results instead.
     */
    private final SearchFacade searchFacade;
    private final DocumentFacade documentFacade;
    private final JpaDocumentChunkRepository chunkRepository;

    public KnowledgeController(SearchFacade searchFacade, DocumentFacade documentFacade,
                               JpaDocumentChunkRepository chunkRepository) {
        this.searchFacade = searchFacade;
        this.documentFacade = documentFacade;
        this.chunkRepository = chunkRepository;
    }

    @GetMapping("/knowledge")
    public String searchPage(@RequestParam(required = false) String q,
                             @RequestParam(required = false) String mode,
                             @RequestParam(required = false) String documentType,
                             @RequestParam(required = false) String category,
                             @RequestParam(defaultValue = "title") String kbSort,
                             @RequestParam(defaultValue = "asc") String kbDir,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                             @AuthenticationPrincipal AuthenticatedUser user,
                             Model model) {

        SearchMode searchMode = parseMode(mode);
        SearchFilter filter = buildFilter(documentType, category);
        boolean hasSearch = q != null && !q.isBlank();

        List<SearchResultRow> results = List.of();
        long totalResults = 0;
        int totalPages = 0;
        boolean noRelevantResults = false;

        if (hasSearch) {
            // Real user attribution for the search audit trail; "system" only
            // when there is no authenticated request context at all.
            SearchQuery query = new SearchQuery(
                    q.trim(), searchMode, filter,
                    new SearchRequestContext(user != null ? user.email() : "system", null, null, null),
                    page, 15);
            try {
                SearchResultPage resultPage = searchFacade.search(query);
                totalResults = resultPage.totalElements();
                totalPages = resultPage.totalPages();

                List<SearchResultRow> allRows = resultPage.results().stream()
                        .map(r -> {
                            String docTitle = r.citation() != null && r.citation().title() != null
                                    ? r.citation().title() : r.chunk().title();
                            String excerpt = r.citation() != null && r.citation().excerpt() != null
                                    ? r.citation().excerpt()
                                    : (r.text() != null ? excerptText(r.text()) : "Kein Auszug verfügbar");

                            return new SearchResultRow(
                                    r.chunk().documentId().toString(),
                                    r.chunk().chunkId().toString(),
                                    docTitle,
                                    excerpt,
                                    r.score(),
                                    r.chunk().documentType() != null ? r.chunk().documentType().name() : "—",
                                    r.provider(),
                                    r.citation() != null && r.citation().pageNumber() != null
                                            && r.citation().pageNumber() > 0
                                            ? "S. " + r.citation().pageNumber() : null,
                                    r.keywordScore(),
                                    r.vectorScore(),
                                    categoryLabel(r.chunk().documentId()));
                        })
                        .toList();

                List<SearchResultRow> relevantRows = allRows.stream()
                        .filter(r -> isRelevant(r, searchMode, q.trim()))
                        .toList();
                noRelevantResults = !allRows.isEmpty() && relevantRows.isEmpty();
                results = relevantRows;
            } catch (Exception e) {
                log.warn("Search failed: {}", e.getMessage());
                model.addAttribute("searchError", "Suche konnte nicht ausgeführt werden.");
                // A failed search must never present partial counts as if they
                // belonged to it — the view falls back to the honest empty state.
                totalResults = 0;
                totalPages = 0;
            }
        }

        model.addAttribute("query", q != null ? q : "");
        model.addAttribute("searchMode", mode != null ? mode : "HYBRID");
        model.addAttribute("documentType", documentType != null ? documentType : "");
        model.addAttribute("category", category != null ? category : "");
        model.addAttribute("results", results);
        model.addAttribute("totalResults", totalResults);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("page", page);
        model.addAttribute("hasSearched", hasSearch);
        model.addAttribute("noRelevantResults", noRelevantResults);
        model.addAttribute("documentTypes", DocumentType.values());
        model.addAttribute("documentTypeLabels", documentTypeLabels());
        model.addAttribute("categories", reasoning.common.model.DocumentCategory.values());
        model.addAttribute("categoryLabels", categoryLabels());
        model.addAttribute("kbDocuments", kbDocumentOverview(kbSort, kbDir));
        model.addAttribute("kbSort", kbSort != null ? kbSort : "title");
        model.addAttribute("kbDir", "desc".equalsIgnoreCase(kbDir) ? "desc" : "asc");
        model.addAttribute("pageTitle", "Wissensbasis");
        model.addAttribute("activeSection", "knowledge");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Wissensbasis", "/knowledge")));

        if (hxRequest != null) {
            return "knowledge/fragments :: searchResults";
        }
        return "knowledge/search";
    }

    // ── Helpers ──

    private SearchMode parseMode(String mode) {
        if (mode == null) return SearchMode.HYBRID;
        return switch (mode) {
            case "KEYWORD" -> SearchMode.KEYWORD;
            case "SEMANTIC" -> SearchMode.SEMANTIC;
            case "HYBRID" -> SearchMode.HYBRID;
            default -> SearchMode.HYBRID;
        };
    }

    private SearchFilter buildFilter(String documentType, String category) {
        reasoning.common.model.DocumentFileType dt = null;
        if (documentType != null && !documentType.isBlank()) {
            try {
                dt = reasoning.common.model.DocumentFileType.valueOf(documentType);
            } catch (IllegalArgumentException ignored) {}
        }
        String cat = category != null && !category.isBlank() ? category : null;
        return new SearchFilter(null, dt, cat, null, null, null, null, null, List.of());
    }

    private String excerptText(String text) {
        if (text.length() <= 300) return text;
        return text.substring(0, 300) + "...";
    }

    /** Minimum fused/raw score floor applied in every search mode. */
    static boolean passesScoreFloor(double score) {
        return score >= MIN_RELEVANCE_SCORE;
    }

    private boolean isRelevant(SearchResultRow r, SearchMode mode, String query) {
        if (r.score() < MIN_RELEVANCE_SCORE) return false;
        if (mode == SearchMode.HYBRID) {
            try {
                return hasTextualEvidence(chunkRepository, UUID.fromString(r.documentId()), query);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when at least one chunk of the document contains at least one
     * SPECIFIC query term (length > 2, German diacritics folded, neither a
     * generic administrative word nor a function word) — the textual evidence
     * check for the HYBRID presentation rule. Generic vocabulary
     * ("unterlagen", "beantragen") and function words ("sie", "und") appear in
     * nearly every municipal document and alone must not make an unrelated
     * document look relevant.
     */
    static boolean hasTextualEvidence(JpaDocumentChunkRepository chunks, UUID documentId, String query) {
        if (query == null || query.isBlank()) return false;
        List<String> terms = new ArrayList<>();
        for (String raw : query.toLowerCase().split("\\s+")) {
            if (raw.length() > 2 && !EmailController.GENERIC_TERMS.contains(raw)
                    && !EmailController.STOPWORDS.contains(raw)) {
                // Deutsche Präfix-Varianten ("ummeldung" → "ummeldung | meldung"),
                // damit z. B. "Anmeldung" im Dokument einen "Ummeldung"-Treffer belegt.
                for (String v : reasoning.common.text.GermanTermVariants.of(raw)) {
                    if (!terms.contains(v)) terms.add(v);
                    String ascii = v.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
                    if (!ascii.equals(v) && !terms.contains(ascii)) terms.add(ascii);
                }
            }
        }
        if (terms.isEmpty()) return false;
        for (var chunk : chunks.findByDocumentIdOrderByChunkIndex(documentId)) {
            String text = chunk.getText() != null ? chunk.getText().toLowerCase() : "";
            for (String term : terms) {
                if (text.contains(term)) return true;
            }
        }
        return false;
    }

    private String categoryLabel(UUID documentId) {
        try {
            Document doc = documentFacade.getDocument(documentId, "system");
            if (doc != null && doc.metadata() != null && doc.metadata().category() != null) {
                return DocumentController.categoryLabel(doc.metadata().category());
            }
        } catch (Exception e) {
            log.debug("Category lookup failed for {}: {}", documentId, e.getMessage());
        }
        return "—";
    }

    /**
     * Document-oriented view of the knowledge base: which documents are
     * indexed, their status and how many chunks were produced. Built from
     * the live document store and the chunk index.
     */
    private List<KbDocumentRow> kbDocumentOverview(String sort, String dir) {
        List<KbDocumentRow> rows = new ArrayList<>();
        try {
            var pageDocs = documentFacade.findDocuments(
                    new reasoning.document.api.DocumentFilter(
                            null, null, null, null, null, null, null, 0, 200));
            for (Document doc : pageDocs.documents()) {
                if (doc.status() == null || doc.status() == reasoning.common.model.DocumentStatus.DELETED) continue;
                long chunkCount = 0;
                try {
                    chunkCount = chunkRepository.countByDocumentId(doc.id());
                } catch (Exception e) {
                    log.debug("Chunk count failed for {}: {}", doc.id(), e.getMessage());
                }
                rows.add(new KbDocumentRow(
                        doc.id(),
                        doc.metadata() != null ? doc.metadata().title() : "—",
                        doc.metadata() != null && doc.metadata().type() != null
                                ? doc.metadata().type().name() : "—",
                        doc.metadata() != null ? DocumentController.categoryLabel(doc.metadata().category()) : "—",
                        DocumentController.statusLabel(doc.status()),
                        DocumentController.statusVariant(doc.status()),
                        chunkCount,
                        doc.createdAt() != null
                                ? DATE_FMT.format(doc.createdAt().atZone(ZoneId.systemDefault())) : "—"));
            }
            rows.sort(sortKbDocuments(sort, dir));
        } catch (Exception e) {
            log.warn("Could not build document overview: {}", e.getMessage());
        }
        return rows;
    }

    /** Comparator for the knowledge-base overview table (server-side sorting). */
    static java.util.Comparator<KbDocumentRow> sortKbDocuments(String sort, String dir) {
        String field = sort != null ? sort : "title";
        boolean desc = "desc".equalsIgnoreCase(dir);
        java.util.Comparator<KbDocumentRow> byField = switch (field) {
            case "type" -> java.util.Comparator.comparing(KbDocumentRow::type, String.CASE_INSENSITIVE_ORDER);
            case "category" -> java.util.Comparator.comparing(KbDocumentRow::category, String.CASE_INSENSITIVE_ORDER);
            case "status" -> java.util.Comparator.comparing(KbDocumentRow::statusLabel, String.CASE_INSENSITIVE_ORDER);
            case "chunks" -> java.util.Comparator.comparingLong(KbDocumentRow::chunkCount);
            case "createdAt" -> java.util.Comparator.comparing(KbDocumentRow::createdAt, String.CASE_INSENSITIVE_ORDER);
            default -> java.util.Comparator.comparing(KbDocumentRow::title, String.CASE_INSENSITIVE_ORDER);
        };
        return desc ? byField.reversed() : byField;
    }

    private static Map<String, String> documentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (DocumentType t : DocumentType.values()) {
            labels.put(t.name(), typeLabel(t.name()));
        }
        return labels;
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "PDF" -> "PDF";
            case "DOCX" -> "Word";
            case "TXT" -> "Text";
            case "HTML" -> "HTML";
            default -> type;
        };
    }

    private static Map<String, String> categoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (reasoning.common.model.DocumentCategory c
                : reasoning.common.model.DocumentCategory.values()) {
            labels.put(c.name(), DocumentController.categoryLabel(c.name()));
        }
        return labels;
    }

    // ── Enums for filter UI ──

    public enum DocumentType {
        PDF, DOCX, TXT, HTML
    }

    /** Row DTO for search results. */
    public record SearchResultRow(String documentId, String chunkId, String title,
                                   String excerpt, double score, String documentType,
                                   String provider, String pageRef,
                                   double keywordScore, double vectorScore, String category) {

        /** German label for the retrieval source of this result. */
        public String providerLabel() {
            if (provider == null) return "—";
            if (provider.contains("graph")) return "Text + Semantik + Graph";
            if (provider.contains("hybrid")) return "Text + Semantik";
            if ("keyword".equals(provider)) return "Textsuche";
            return "Semantische Suche";
        }
    }

    /** Row DTO for the document overview of the knowledge base. */
    public record KbDocumentRow(UUID id, String title, String type, String category,
                                String statusLabel, String statusVariant,
                                long chunkCount, String createdAt) {}
}
