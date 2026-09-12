package reasoning.search.api;

import reasoning.search.model.CitationReference;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;

/** Service for generating citation references from chunks and retrieval candidates. */
public interface CitationService {
    /** Builds a citation reference from a document chunk. */
    CitationReference citationFor(DocumentChunk chunk);

    /** Returns the citation embedded in a retrieval candidate. */
    CitationReference citationFor(RetrievalCandidate candidate);
}
