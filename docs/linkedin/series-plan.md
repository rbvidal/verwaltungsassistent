# Series Plan — "Things I learned building RAG"

LinkedIn article series based on building the Enterprise Knowledge Reasoning Platform (Verwaltungsassistent) / Verwaltungsassistent.

## Research summary (August 2026)

The discourse around enterprise RAG in 2026 has moved decisively in the direction this project already took, which is exactly why the series is worth writing — it can speak from *experience* about problems the industry is only now naming:

- **Retrieval, not the model, is now the acknowledged bottleneck.** OpenSearchCon 2026 reframed hallucinations as "a retrieval problem rather than an LLM limitation." Pure vector search is widely reported as insufficient — missing exact terms and returning "semantically adjacent but factually wrong" results. Hybrid BM25+vector fusion is the emerging baseline.
- **Similarity ≠ relevance is being discovered the hard way.** VentureBeat reported that precision-tuning embedding models can quietly cut general retrieval accuracy by 40%, because structural near-misses (negation flips, reversed relations) get near-identity scores. Cross-encoders "work in the lab and break under real query volumes."
- **GraphRAG went mainstream, with measured trade-offs.** A published 2026 hybrid stack (vector + BM25 + 2-hop graph traversal) reported 86.9% overall accuracy vs 65.0% for vector-only on a 12K-doc corpus — and vanilla GraphRAG actually *loses* to vector-only on single-hop queries. The community's hard-won rules ("two-hop max", "bypass the graph for single-fact lookups") match choices made independently in this project.
- **Verification is the frontier topic.** 2026 ACL work reframes grounded claim checking as reading-comprehension with test-taking strategies; the groundlens project publicly retracted inflated results and showed embedding-geometry checks fail exactly where entailment checks hold (confabulations); industry practice is converging on per-claim entailment verdicts (entailed / contradicted / insufficient) as the reliable signal.
- **Fail-closed behavior is becoming an industry recommendation**, not an outlier: production guides now explicitly advise confidence gates, refusals, and escalation ("I don't have enough verified information") over always-answering, and citation-grounding is called "the single most powerful hallucination reducer."
- **German public administration has a sovereign-AI moment.** Fraunhofer FOKUS (ÖFIT) published a federal study on local/sovereign LLM deployment; Berlin's BärGPT is an open-source RAG assistant for administration; sovereign models (Soofi S 30B, ELMOD-2.7B) and on-premise-capable offers (Bundesdruckerei Assistent.iQ, CIB with Ollama/vLLM endpoints) are concrete 2026 reality.

**Overused topics to avoid:** "What is RAG?", "What is a vector database?", generic "Top 10 RAG tips", database-vs-database comparisons, "our product is great" posts. **Underserved angles where this project has something concrete:** the retrieved≠relevant≠evidence chain, verification with a second model (including the counterintuitive 7B-beats-14B result), refusal as a designed outcome, rule-first routing, and the practical reality of running the whole stack locally on one workstation GPU.

## Target audience

- Software engineers and AI practitioners who have built (or are considering) a RAG system
- Technically curious managers and potential customers in public administration
- People evaluating AI for government/regulated use

Every article must work for a scrolling phone reader: the opening decides whether they stop; the main point must land without prior knowledge of the series.

## Series structure: 9 articles

Nine, not twenty. Each article earns its place with one distinct idea and at least one real observation from the project. The arc moves from the initial naive plan → the first surprise → the retrieval layer → structure → checking → refusing → routing → deployment → reflection.

| # | File | Title | Key idea | Expected reader | Diagram |
|---|---|---|---|---|---|
| 1 | `01-rag-looked-easier.md` | RAG looked easier before I built one | The napkin pipeline (PDFs → embeddings → vector search → LLM) works in a week and fails exactly where trust matters; generation was never the hard part | Anyone considering RAG | no |
| 2 | `02-similar-not-relevant.md` | A similar document is not necessarily a relevant document | The Wohnungsgeberbestätigung incident: excellent retrieval, zero answers | Everyone — flagship article | yes (Similar ≠ Relevant ≠ Evidence) |
| 3 | `03-more-than-vector-search.md` | Why I use more than vector search | Three retrieval mechanisms find three different things; explicit fusion weights as an engineering choice, not a law of nature | Engineers | no |
| 4 | `04-knowledge-graph.md` | When a knowledge graph helps | Relationships matter when the answer is about structure ("which legal bases does this procedure cite"), not wording; honest about enrichment noise | Engineers, architects | yes (tiny graph sketch) |
| 5 | `05-second-llm-verifier.md` | I use a second LLM to check the first one | Plausible ≠ supported; the 7B-beats-14B benchmark surprise; "who verifies the verifier" answered honestly | Engineers, AI practitioners | no |
| 6 | `06-refusing-to-answer.md` | I don't want the AI to answer every question | Refusal is a designed, code-enforced outcome — and a corpus-gap signal, not a failure | Everyone; potential customers | no |
| 7 | `07-rules-over-llm.md` | Why I don't let the LLM decide everything | Arithmetic with legal force belongs in versioned tables, not in prompts; the salary-without-step story | Engineers, decision makers | no |
| 8 | `08-local-models.md` | Why I run the models locally | Data residency, control, model independence; the German sovereign-AI context; honest about trade-offs | Decision makers, IT leads | no |
| 9 | `09-model-is-the-easy-part.md` | The model is the easy part | Reflection: the real work is finding, selecting, verifying and refusing; what I'd tell past me | Everyone — closer | no |

**Order:** as listed. Article 2 is the hook; articles 5–7 carry the distinctive architecture; article 9 closes. Articles are independent — each works without the others.

## Series identity

- Series name (footer on every post): *Things I learned building RAG*
- Footer on every post: "Part N of \"Things I learned building RAG\"."
- Verwaltungsassistent is not mentioned by name in any post; the series is about the problems, not the product.

## References used

Per article, 1–2 links at the end, mentioned naturally ("For anyone interested in the underlying idea, …"). No academic citation style.

1. Lewis et al. (2020), "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks" — https://arxiv.org/abs/2005.11401
2. OpenSearch, "Vectors vs. hallucinations: How OpenSearch keeps GenAI grounded" — https://opensearch.org/blog/vectors-vs-hallucinations-how-opensearch-keeps-genai-grounded/
3. VentureBeat (2026), "RAG precision tuning can quietly cut retrieval accuracy by 40%…" — https://venturebeat.com/data/rag-precision-tuning-can-quietly-cut-retrieval-accuracy-by-40-putting-agentic-pipelines-at-risk
4. Sinequa, "Enterprise Search Relevance: The Bottleneck Behind Every AI" — https://www.sinequa.com/resources/blog/how-intelligent-search-improves-the-search-experience/
5. Microsoft Research (2024), "From Local to Global: A Graph RAG Approach to Query-Focused Summarization" — https://arxiv.org/abs/2404.16130
6. TigerGraph, "GraphRAG vs Vector RAG: Which Wins for Enterprise AI?" — https://www.tigergraph.com/blog/graphrag-vs-vector-rag/
7. Ye, Santos-Rodriguez & Simpson (ACL 2026), "Teaching Language Models to Check Grounded Claim Factuality with Human Test-Taking Strategies" — https://aclanthology.org/2026.acl-long.1468/
8. groundlens (Hugging Face) — embedding-geometry grounding checks and the public retraction — https://huggingface.co/groundlens
9. Boldare, "Production RAG: How to Reduce Hallucinations" — https://www.boldare.com/blog/how-to-build-a-production-rag-system/
10. Redgate Simple Talk, "How to stop AI hallucinations in enterprise RAG systems" — https://www.red-gate.com/simple-talk/ai/how-to-stop-ai-hallucinations-in-enterprise-rag-systems-a-complete-guide/
11. Fraunhofer FOKUS (ÖFIT), "Study on large language models in federal administration" — https://www.fokus.fraunhofer.de/en/newsroom/news/LLMs-in-federal-administration.html
12. BärGPT — open-source AI assistant for Berlin's public administration (CityLAB/Tech Foundation Berlin) — https://github.com/technologiestiftung/baergpt

## Ground rules applied in every article

- 500–900 words, most ~600–750; phone-scrollable opening; no corporate language (no "revolutionary", "cutting-edge", "seamless", "leveraging").
- First person, informal; only events supported by the actual project history (benchmarks, test corpora, commit history, deployment reports).
- No credentials, internal URLs, ports, or configuration values beyond what is public law (BRKG rates) or already public in the technical white paper.
- No claim of optimality: weights, thresholds and model choices are presented as measurable engineering choices.
- Future work (fine-tuning experiments, multi-round retrieval, full-text search upgrades) is explicitly marked as not-yet-done where mentioned.
