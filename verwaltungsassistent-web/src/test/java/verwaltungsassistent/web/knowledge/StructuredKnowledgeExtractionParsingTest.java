package verwaltungsassistent.web.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON-boundary robustness: the 14B model sometimes wraps its JSON output in
 * Markdown code fences (```json … ```). The parser must accept that wrapper
 * while remaining strict about the JSON itself — malformed or non-JSON output
 * is still rejected and can never produce candidates.
 */
class StructuredKnowledgeExtractionParsingTest {

    private final StructuredKnowledgeExtractionService service =
            new StructuredKnowledgeExtractionService(null, null, null, null, null, null,
                    null, new ObjectMapper());

    private static final String ITEM = """
            {"kind":"THRESHOLD","domain":"PROCUREMENT","key":"AV §55 LHO",
             "payload":{"bounds":[{"min":0,"max":10000,"outcome":"Direktauftrag","requirements":["Vergabevermerk"]}]},
             "effectiveFrom":"2024-01-01","confidence":0.95}""";

    @Test
    void plainJsonIsParsed() {
        List<StructuredKnowledgeExtractionService.ExtractedCandidate> candidates =
                service.parse("{\"items\":[" + ITEM + "]}");
        assertEquals(1, candidates.size());
        assertEquals("THRESHOLD", candidates.getFirst().kind());
    }

    @Test
    void fencedJsonWithLanguageMarkerIsParsed() {
        String fenced = "```json\n{\"items\":[" + ITEM + "]}\n```";
        assertEquals(1, service.parse(fenced).size());
    }

    @Test
    void fencedJsonWithoutLanguageMarkerIsParsed() {
        String fenced = "```\n{\"items\":[" + ITEM + "]}\n```";
        assertEquals(1, service.parse(fenced).size());
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        String padded = "   \n  {\"items\":[" + ITEM + "]}  \n ";
        assertEquals(1, service.parse(padded).size());
    }

    @Test
    void plainMalformedJsonIsStillRejected() {
        assertTrue(service.parse("kein json {").isEmpty());
        assertTrue(service.parse("{\"items\":[{\"kind\":").isEmpty());
    }

    @Test
    void fenceContainingMalformedJsonIsStillRejected() {
        String fencedMalformed = "```json\nkein json {\n```";
        assertTrue(service.parse(fencedMalformed).isEmpty());
    }

    @Test
    void fenceWithOnlyMarkerYieldsNothing() {
        assertTrue(service.parse("```json```").isEmpty());
    }

    @Test
    void emptyOrNullResponseYieldsNothing() {
        assertTrue(service.parse(null).isEmpty());
        assertTrue(service.parse("").isEmpty());
        assertTrue(service.parse("   ").isEmpty());
    }
}
