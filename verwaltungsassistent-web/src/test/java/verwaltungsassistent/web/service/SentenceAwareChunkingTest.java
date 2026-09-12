package verwaltungsassistent.web.service;

import reasoning.search.application.ChunkingProperties;
import reasoning.search.application.SentenceAwareChunkingStrategy;
import reasoning.search.model.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for paragraph-break chunk truncation.
 * The sentence-aware chunker must not drop the first character of a paragraph
 * when it begins a new chunk and must not skip text between chunks.
 */
class SentenceAwareChunkingTest {

    private static final UUID DOC_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SentenceAwareChunkingStrategy strategy(int maxChunkSize, int overlap) {
        ChunkingProperties p = new ChunkingProperties();
        p.setMaxChunkSize(maxChunkSize);
        p.setOverlap(overlap);
        return new SentenceAwareChunkingStrategy(p);
    }

    @Test
    void doesNotTruncateFirstCharacterAfterParagraphBreak() {
        // Force a split exactly at the paragraph break; the second chunk must
        // start with the first character of the new paragraph.
        String first = "First paragraph. " + "word ".repeat(35);
        String second = "Second paragraph starts here. More text follows.";
        String text = first + "\n\n" + second;

        var chunks = strategy(200, 0).chunk(DOC_ID, 1, "Test", text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(1).text()).startsWith("Second paragraph starts here.");
        assertThat(chunks.get(0).text()).doesNotContain("Second");
    }

    @Test
    void reconstructsOriginalTextWithoutOverlap() {
        String text = "First sentence.\n\nSecond paragraph begins here. Third sentence in the same paragraph. "
                + "Fourth sentence has enough text to cross the chunk boundary. "
                + "Fifth sentence.\n\nSixth paragraph starts here.";

        var chunks = strategy(120, 0).chunk(DOC_ID, 1, "Test", text);

        String joined = chunks.stream().map(DocumentChunk::text).collect(Collectors.joining(""));
        // Whitespace at sentence boundaries is removed by trim(), so compare
        // the non-whitespace character sequence.
        assertThat(joined.replaceAll("\\s+", ""))
                .isEqualTo(text.replaceAll("\\s+", ""));
    }

    /**
     * Regression (BMG § 17): Nummerierte Gesetzes-Absätze folgen auf einen
     * Punkt mit „(2) …" statt einem Großbuchstaben. Ohne die Absatz-Grenze
     * würde der Chunker die Norm mitten im Satz hart abschneiden — die
     * beantwortende Passage „… innerhalb von zwei Wochen nach dem Einzug …
     * anzumelden." (Abs. 1) kam zerstückelt in die Evidenz und die Antwort
     * orientierte sich an Abs. 2 (Abmeldung). Absätze müssen als vollständige
     * Sätze erhalten bleiben; ein Chunk darf nur an Satzgrenzen enden.
     */
    @Test
    void keepsNumberedLegalParagraphsIntact() {
        String abs1 = "(1) Wer eine Wohnung bezieht, hat sich innerhalb von zwei Wochen nach dem Einzug "
                + "bei der Meldebehörde anzumelden.";
        String abs2 = "(2) Wer aus einer Wohnung auszieht und keine neue Wohnung im Inland bezieht, "
                + "hat sich innerhalb von zwei Wochen nach dem Auszug bei der Meldebehörde abzumelden.";
        String abs3 = "(3) Wer eine Wohnung im Ausland bezieht, hat sich innerhalb von zwei Wochen "
                + "nach dem Einzug bei der zuständigen Meldebehörde des neuen Aufenthaltsortes zu melden.";
        String text = abs1 + " " + abs2 + " " + abs3;

        // maxChunkSize 150: idealEnd liegt mitten in Absatz (1). Ohne die
        // Absatz-Grenzen nach „…anzumelden. (2)" würde der Chunker bei 150
        // mitten im Satz hart schneiden.
        var chunks = strategy(150, 0).chunk(DOC_ID, 1, "Test", text);

        assertThat(chunks).isNotEmpty();
        String joined = chunks.stream().map(DocumentChunk::text).collect(Collectors.joining(" "));
        assertThat(joined.replaceAll("\\s+", ""))
                .isEqualTo(text.replaceAll("\\s+", ""),
                        "no text may be lost or duplicated across the numbered paragraphs");
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            boolean last = i == chunks.size() - 1;
            assertThat(chunk.text().trim().endsWith("."))
                    .as("chunk %d must end at a sentence boundary, got: %s", i,
                            chunk.text().substring(Math.max(0, chunk.text().length() - 60)))
                    .isTrue();
        }
        // Der Normsatz aus Abs. 1 bleibt in EINEM Chunk vollständig lesbar.
        assertThat(chunks.stream().anyMatch(c -> c.text().contains(abs1)))
                .as("§17(1) must stay readable as one sentence inside one chunk")
                .isTrue();
    }

    /**
     * Gesetzesstruktur (BMG): Abschnittsüberschrift „§ 17 …" und der
     * nummerierte Absatz (1) müssen als EINHEIT erhalten bleiben — kein
     * Chunk darf den Normsatz mitten im Satz zerschneiden, und die
     * Überschrift darf nicht vom Absatz getrennt werden.
     */
    @Test
    void keepsSectionHeadingAndNumberedParagraphTogether() {
        String text = "Bis zum Ablauf dieser Frist darf das Archiv die übernommenen Daten und Hinweise "
                + "nur nach Maßgabe des § 13 Absatz 2 Satz 2 bis 4 verarbeiten.\n"
                + "Abschnitt 3\nAllgemeine Meldepflichten\n"
                + "§ 17 Anmeldung, Abmeldung\n"
                + "(1) Wer eine Wohnung bezieht, hat sich innerhalb von zwei Wochen nach dem Einzug bei der "
                + "Meldebehörde anzumelden.";
        var chunks = strategy(180, 0).chunk(DOC_ID, 1, "Test", text);

        String joined = chunks.stream().map(DocumentChunk::text).collect(Collectors.joining(" "));
        assertThat(joined.replaceAll("\\s+", "")).isEqualTo(text.replaceAll("\\s+", ""),
                "no text may be lost across heading and paragraph");
        assertThat(chunks.stream().anyMatch(c ->
                c.text().contains("§ 17 Anmeldung, Abmeldung")
                        && c.text().contains("(1) Wer eine Wohnung bezieht, hat sich innerhalb von zwei Wochen "
                                + "nach dem Einzug bei der Meldebehörde anzumelden.")))
                .as("§ 17 heading and Abs. 1 must stay readable together")
                .isTrue();
        for (DocumentChunk chunk : chunks) {
            assertThat(chunk.text().trim().endsWith("."))
                    .as("chunk must end at a sentence boundary, got: %s",
                            chunk.text().substring(Math.max(0, chunk.text().length() - 60)))
                    .isTrue();
        }
    }

    /**
     * Text ohne innere Satzgrenzen (ein einziger langer Satz ohne Punkt):
     * Der Chunker darf nichts verlieren und nichts duplizieren; harte
     * Schnitte sind in diesem Fall unvermeidbar, aber verlustfrei.
     */
    @Test
    void longTextWithoutSentenceBoundaries_losesNothing() {
        String single = "Ein sehr langer Satz ohne Satzzeichen und ohne innere Grenzen der mit Absicht "
                + "keinerlei Punkte enthält damit der Satzgrenzen-Erkenner keine einzige Grenze finden kann "
                + "und der Chunker gezwungen ist hart zu schneiden ohne dabei Text zu verlieren oder zu "
                + "wiederholen und das über mehrere Chunks hinweg konsistent bleibt";
        String text = single + " " + single + " " + single;
        var chunks = strategy(180, 0).chunk(DOC_ID, 1, "Test", text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        String joined = chunks.stream().map(DocumentChunk::text).collect(Collectors.joining(""));
        assertThat(joined.replaceAll("\\s+", ""))
                .isEqualTo(text.replaceAll("\\s+", ""),
                        "hard cuts must be loss-free");
    }
}

