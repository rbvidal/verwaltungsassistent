package reasoning.search.model;

/** Classification of a document chunk (text, table, heading, footnote, unknown). */
public enum ChunkType {
    TEXT,
    TABLE,
    HEADING,
    FOOTNOTE,
    UNKNOWN
}
