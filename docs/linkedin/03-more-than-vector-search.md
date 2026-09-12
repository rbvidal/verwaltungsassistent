# Why I use more than vector search

After the incident from the last post, I added a keyword search next to the vector search and looked at the differences.

They find different things. If the question contains an exact legal term — Tagegeld, for example, the daily travel allowance — the keyword search returns the passages where the term appears. Some of these passages sat far down in the vector ranking, because the vector search compares meaning, not words. The other way around: the vector search finds passages that answer the question with different words. The keyword search cannot find those at all.

In administrative text the exact term is often the answer. Thresholds, form names, paragraph numbers. If the term is missing, the passage is often not relevant, no matter how similar the meaning is.

Later I added a third mechanism, a knowledge graph, for a different blindness — the structure. I write about that in the next post.

Now I had three result sets and the problem of combining them. I started with a fixed weighted sum: 40 percent keyword, 40 percent vector, 20 percent a confidence component from indexing. I do not present this as optimal. It is a starting point, and it sits in configuration, not in code. I can change it and measure. The important property is not the weights. It is that every candidate keeps its scores per mechanism, so I can inspect why a candidate is in the list.

Other things that cost me time:

Duplicates. The same passage appears in two or three result sets. If I just concatenate, a document that appears everywhere takes many positions in the list. That rewards big, on-topic documents, not documents that answer the question. I merge per passage, then keep only the best passage per document.

Domain. Questions have a domain — procurement, HR, travel. Documents have one too. A procurement question can be semantically close to a travel document. A small factor for the domain pushes the wrong domain down, without hard filtering. Simple and explainable.

Compression. The model does not get everything retrieval found. It gets a small package: a few documents, short excerpts. This improved the answers more than any model change I tried.

What helped most was not one clever mechanism. It was that the retrieval decision became inspectable. For every candidate I can see which mechanism found it and what score it got. Before that, debugging retrieval was guessing.

Background: the OpenSearch team has a good post on why vector search alone is not enough for grounding — [Vectors vs. hallucinations](https://opensearch.org/blog/vectors-vs-hallucinations-how-opensearch-keeps-genai-grounded/).

Part 3 of "Things I learned building RAG".
