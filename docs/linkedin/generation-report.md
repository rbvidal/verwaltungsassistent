# LinkedIn Series — Generation Report

Generated 2026-08-15. Research, writing and documentation only — no application source code modified.

## Number of articles selected: 9

Chosen after researching the current (2026) discourse and the project history. Nine is enough to cover the full arc — naive plan → retrieval surprises → structure → checking → refusing → routing → deployment → reflection — without padding. Each article must carry one distinct idea plus at least one real, verifiable observation from the project; no filler articles were created. The prompt's suggested topic list (~11 ideas) was merged where the ideas overlapped ("similar ≠ relevant" and "retrieved ≠ evidence" became one flagship article, since they are two stages of the same incident).

## Intended audience

- Software engineers and AI practitioners who have built or are considering RAG systems
- Technically curious managers and potential customers in public administration
- People evaluating AI for government/regulated use

Written for a scrolling phone reader: opening lines decide whether the post is read; every post must land its main point without prior series knowledge.

## Publication sequence

1. **RAG looked easier before I built one** — frame: the napkin pipeline works in a week and fails where trust matters; generation was never the hard part.
2. **A similar document is not necessarily a relevant document** — the flagship: the Wohnungsgeberbestätigung incident; Similar ≠ Relevant ≠ Evidence.
3. **Why I use more than vector search** — three retrieval mechanisms, explicit fusion as a measurable engineering choice, candidate hygiene.
4. **When a knowledge graph helps** — when relationships beat similarity; honest about enrichment noise; two-hop cap.
5. **I use a second LLM to check the first one** — plausible ≠ supported; the 7B-beats-14B benchmark surprise; "who verifies the verifier" answered honestly.
6. **I don't want the AI to answer every question** — refusal as a code-enforced, designed outcome and a corpus-gap signal.
7. **Why I don't let the LLM decide everything** — rule-first routing; the real salary-step incident; versioned tables instead of prompts.
8. **Why I run the models locally** — data residency, control, model independence; German sovereign-AI context; honest trade-offs.
9. **The model is the easy part** — reflection: the work is finding, selecting, verifying, refusing; four lessons for past me.

Cadence: two per week (~5 weeks). Articles 1 and 2 in the first week; article 2 can be published standalone if engagement on 1 is weak.

## Diagrams

- Article 2: Similar → Relevant → Evidence (3-step chain with definitions)
- Article 3: implicit in text — no standalone diagram (kept as prose to avoid diagram fatigue)
- Article 4: tiny graph sketch (procedure → legal basis / authority / related procedure)
- Article 6: no diagram (the refusal story carries itself; a branch diagram would duplicate article 2's chain)

All diagrams are monospace ASCII blocks sized for LinkedIn.

## External references used

1. Lewis et al. 2020, RAG paper (article 1)
2. VentureBeat 2026 "RAG precision tuning… −40%" (article 2)
3. OpenSearch "Vectors vs. hallucinations" (article 3)
4. Microsoft Research GraphRAG paper (article 4)
5. TigerGraph "GraphRAG vs Vector RAG" (article 4)
6. ACL 2026 "Teaching Language Models to Check Grounded Claim Factuality" (article 5)
7. groundlens (Hugging Face) — embedding-geometry vs entailment checks (article 5)
8. Boldare "Production RAG" (article 6)
9. Fraunhofer FOKUS LLM study (article 8)
10. BärGPT GitHub (article 8)

Full URLs are in `series-plan.md`. Each article carries at most two references, mentioned naturally.

## Claims that should be double-checked before publication

1. **Article 5, verifier model roles.** The article now states the current setup correctly (14B default for writing and checking, 7B as validated rollback). If the verifier default changes before publication (it is a configuration value), update this sentence.
2. **Article 5, "about twice as fast".** Rounded from the measured development experiment (≈2.2 s vs ≈4.8 s per verification batch). Verify against the experiment report if you want the exact figures.
3. **Article 8, "16 GB workstation GPU".** Correct for the demo machine. Do not upgrade this to a production-capacity claim.
4. **Articles 2/6, exact German refusal sentence.** Verified against the current source. Re-verify if the fail-closed wording is ever changed.
5. **All law values in article 7** (BRKG 6/12/24 € brackets) — public law, verified against the registered rule tables (effective 2024-01-01). Re-check if the tables are updated.
6. **External links** — all were verified live during research (August 2026). Re-check that they still resolve on publication day.

## Articles that could reveal too much about the product/architecture

- **Article 2** reveals the existence and exact wording of the fail-closed answer and the general shape of the verification boundary. Deliberate — it is the strongest hook — but it is the most "inside" post. Approved for publication as-is because it describes *behavior* (visible to any demo user), not implementation.
- **Article 3** reveals the fusion weighting concept with approximate numbers. The prompt's guidance explicitly permits this ("I started with a 40/40/20 weighting… it's an engineering choice"). No other configuration values are disclosed.
- **Article 5** reveals benchmark results (100%/96.3%) and the two model sizes. Public open models, development measurements — approved. Model *names* are deliberately not mentioned anywhere in the series.
- No article discloses: source code, database/collection names, ports, hosts, internal class names, prompt templates, credentials, or deployment details. Verified by automated scan of all article files.

## Revision 2 — full rewrite (same day, per author request)

The articles were rewritten from scratch on 2026-08-15. The first drafts were used as a source of facts only; no rhetorical structure or wording was carried over. Changes:

- Voice changed to plain, informal, coffee-talk English — direct statements, short sentences, occasional non-native phrasing ("This I cannot say about a language model", "Almost is the problem"). No LinkedIn-copywriting patterns: no dramatic openings, no rhetorical questions for effect, no numbered lessons, no "the interesting part / the hard part" phrasing.
- Titles simplified: article 9 retitled "The model is the easy part" (new file `09-model-is-the-easy-part.md`); articles 1–5 titles simplified.
- Diagrams reduced to two (articles 2 and 4); articles 3 and 6 carry their points in prose.
- References reduced: OpenSearch only in article 3, Boldare only in article 6, Sinequa and Redgate dropped from the posts.
- Verwaltungsassistent is no longer mentioned by name in any post.
- Automated scan of the final texts: zero occurrences of the banned phrase list, zero unsourced "industry" claims, 344–467 words per article.

## Authenticity measures applied

- Only project-documented events are used (Wohnungsgeberbestätigung incident, salary-step silent default, 7B/14B verifier benchmark, 128-question intent corpus, graph enrichment noise, corpus expansion, air-gapped deployment).
- One initially drafted incident (a procurement miscomputation) was replaced during the claims audit because it was not supported by project records — replaced with the documented salary-step default incident. The rewrite keeps the salary-step story only.
- Structure varies across posts; no repeated template.
- No corporate vocabulary; first person throughout; uncertainty stated explicitly ("I am still not sure about…", "I am not convinced…", "a starting point, not a result").
