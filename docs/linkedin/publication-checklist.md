# Publication Checklist

Checked against every article on 2026-08-15. Re-check any article you edit before publishing.

| Check | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 |
|---|---|---|---|---|---|---|---|---|---|
| Is the opening interesting? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Does it get to the point quickly? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Is it short enough (≤900 words)? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Does it contain a real observation? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Does it sound like a person, not an AI? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Understandable without previous articles? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Avoids unnecessary technical detail? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Avoids marketing language? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Avoids exaggerated claims? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Technical statements match the implementation? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Future features marked as future? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| External references accurate? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — |
| Would someone stop scrolling to read this? | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

## Per-article notes

**01** — No implementation details disclosed beyond the general pipeline shape. The "working prototype within a week" phrasing describes the early development phase (documented in the project history); keep the time frame vague if you prefer.

**02** — The Wohnungsgeberbestätigung story is the system's real development history (the incident that motivated the verification boundary). Exact German refusal sentence is quoted — correct wording. The three-part distinction (similar/relevant/evidence) matches the architecture.

**03** — The fusion weights are presented as an engineering starting point, explicitly not optimal. The article stays qualitative about the keyword leg — if you ever extend it with a full-text-search comparison, remember the current implementation is simple pattern matching, not BM25. Domain factor described qualitatively (a "small factor"), no exact number published.

**04** — Graph enrichment noise and the two-hop cap are real. The graph sketch is conceptual (generic node types in the implementation). "The graph doesn't do legal reasoning" is accurate.

**05** — The 7B = 100% / 14B = 96.3% benchmark is the real development experiment (controlled entailment corpus, both models on one 16 GB workstation GPU). "Twice as fast" is approximate but matches the measured ~2.2 s vs ~4.8 s. The claim "researchers have shown embedding checks fail where entailment holds" refers to the groundlens project — keep the link if you keep the sentence.

**06** — Refusal behavior, cleared citations, and the audit trail of rejected claims all match the implementation. "Errors tend to produce silence" is the fail-closed design, stated as a bias, not a guarantee.

**07** — All rule values are public law (BRKG: 6/12/24 € brackets). The 3,500 € procurement story describes a development observation; if asked for details, the correct bracket is "direct award with procurement note" — don't publish the exact thresholds unless you intend to. The salary-step guard and domain-coherence guard are real code changes in the project history.

**08** — "16 GB workstation GPU" matches the demo machine without naming it. The Fraunhofer and BärGPT references are real and current (2026). No deployment internals (ports, hosts, images) disclosed.

**09** — Reflection only; all referenced facts (128-question intent corpus, entailment benchmark, 100%/96.3%) come from the development records and earlier posts. Uncertainties are stated explicitly (fusion weights, keyword search, single-pass retrieval, verification as control).

## Before publishing — final pass

- [ ] Re-read the article out loud once. If any sentence feels like a brochure, rewrite it.
- [ ] Verify every number still matches the current implementation (weights, benchmark results, model names).
- [ ] Check the links in "Further reading" still resolve.
- [ ] Confirm the series footer is present and consistent.
- [ ] Confirm no internal identifiers, ports, hosts, or credentials slipped in (none present at generation time).
