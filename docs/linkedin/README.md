# LinkedIn Series — "Things I learned building RAG"

Nine short posts about building a document reasoning system for public administration (Verwaltungsassistent / Verwaltungsassistent). Written as engineering notes, not product marketing.

## Recommended publication order

| # | File | Title | Publish |
|---|---|---|---|
| 1 | `01-rag-looked-easier.md` | RAG looked easier before I built one | Week 1 |
| 2 | `02-similar-not-relevant.md` | A similar document is not necessarily a relevant document | Week 1 |
| 3 | `03-more-than-vector-search.md` | Why I use more than vector search | Week 2 |
| 4 | `04-knowledge-graph.md` | When a knowledge graph helps | Week 2 |
| 5 | `05-second-llm-verifier.md` | I use a second LLM to check the first one | Week 3 |
| 6 | `06-refusing-to-answer.md` | I don't want the AI to answer every question | Week 3 |
| 7 | `07-rules-over-llm.md` | Why I don't let the LLM decide everything | Week 4 |
| 8 | `08-local-models.md` | Why I run the models locally | Week 4 |
| 9 | `09-model-is-the-easy-part.md` | The model is the easy part | Week 5 |

**Cadence:** two per week (e.g., Tuesday + Thursday) over ~5 weeks. The order is deliberate: 1 sets the frame, 2 is the hook with the strongest real story, 3–4 cover retrieval, 5–7 carry the distinctive architecture (verification, refusal, rules), 8 addresses the question every decision maker asks (data residency), 9 closes reflectively.

**Suggestions:**
- Every post ends with the series footer already included in the files — keep it unchanged for a consistent, unobtrusive identity.
- Post 2 works well standalone; if engagement on 1 is weak, publish 2 next anyway and cross-link backwards.
- Diagrams are included inline as monospace blocks in posts 2 and 4; on LinkedIn, keep them inside a code-style block.
- Each article stands alone; they reference each other only as "from an earlier post" without requiring prior reading.

## Files

- `series-plan.md` — research summary, audience, structure, key ideas, references
- `01-…`–`09-….md` — the articles
- `publication-checklist.md` — pre-publication verification, filled in per article
- `generation-report.md` — report on the series creation (what was checked, what to double-check)
