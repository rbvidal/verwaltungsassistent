# When a knowledge graph helps

For a long time I was not sure about the knowledge graph.

The idea: extract entities and relationships from the documents and store them as a graph. At question time, walk the graph from the found documents to connected things. The extraction step is an LLM pass over every document. It is slow, it costs tokens, and it makes noise. I saw this in my own data: concepts that were too general, relationships between documents that had nothing useful in common. For some time the graph made the candidate list worse, not better.

But there is a class of questions where the graph is the only mechanism that can help. Example: a citizen asks which documents are needed for an identity card application. The text search finds the identity card procedure. Good. But the complete answer involves the related passport procedure and the legal bases both procedures cite. The question does not contain those words. No similarity search will rank them, because the connection is not in the text. It is in the structure.

```
Procedure document ── cites ──────► Legal basis
        │
        ├── handled by ───────────► Authority
        │
        └── related to ───────────► Related procedure
```

Two decisions I made after experiments:

The traversal stays shallow. I go at most two hops from the found documents. More than that, the neighborhood explodes and the noise comes back. I saw this in my own tests.

The graph is a complement, not a competitor. Graph hits give a small boost to candidates the text search already found. When the graph is the only mechanism that finds something, I am careful — that often means the extraction made a weak connection.

The graph is built once, at document ingestion, and it ages. When a document changes, the graph must change. In my system the ingestion re-runs the enrichment for that document. If I exchange the enrichment model later, I can re-enrich the whole corpus without touching the retrieval code.

One thing the graph does not do in my system: legal reasoning. It does not know which regulation has priority over another. It stores structure. The reasoning happens in the pipeline around it.

I am still not convinced the enrichment quality is good enough. The graph helps on some questions and costs on others. I keep it because the structure questions matter in this domain, and because the two-hop rule keeps the noise under control.

Background: Microsoft Research, [From Local to Global: A Graph RAG Approach to Query-Focused Summarization](https://arxiv.org/abs/2404.16130) (2024), and a practical comparison at [TigerGraph: GraphRAG vs Vector RAG](https://www.tigergraph.com/blog/graphrag-vs-vector-rag/).

Part 4 of "Things I learned building RAG".
