# Controlled Production-Path Comparison: Regex vs LLM Semantic Intent

**Date:** 2026-08-13
**Scope:** Verwaltungsassistent `verwaltungsassistent-web` runtime application against live infrastructure
**Harness:** `AbstractSemanticIntentProductionComparisonTest` + `SemanticIntentProductionComparisonRegexTest` (RUN A) / `SemanticIntentProductionComparisonLlmTest` (RUN B)
**Raw evidence:** `verwaltungsassistent-web/target/comparison-regex.txt`, `verwaltungsassistent-web/target/comparison-llm.txt`

---

## 1. Test Corpus

27 queries covering all 14 required categories. Language-equivalent sets for the
same semantic question where the category permits.

| # | ID | Lang | Category |
|---|----|------|----------|
| 1-4 | TRAVEL-12H (mandated equivalents) | DE/EN/PT/FR | Deterministic travel, with parameters |
| 5-6 | TRAVEL-24H | DE/EN | Deterministic travel (boundary: 24h table entry) |
| 7-11 | SALARY S3/S5 | DE/EN/PT/FR | Deterministic salary, grade+step extraction |
| 12-16 | PROC 8000/85000 | DE/EN/PT/FR | Deterministic procurement, incl. thousands separators (`.`, `,`, space) |
| 17-19 | DIST 120 km | DE/EN/FR | Numeric extraction (distance) |
| 20-22 | BUILDING | DE/EN/PT | Document retrieval |
| 23-24 | UNSUPPORTED | DE/EN | Unsupported question |
| 25-26 | AMBIGUOUS ("Was gilt bei 12?" / "What applies at 12?") | DE/EN | Ambiguous question |
| 27 | NOPARAM travel | DE | No parameters |

The four mandated travel equivalents were included verbatim:
DE "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?" ·
EN "What is the meal allowance for a 12-hour business trip?" ·
PT "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?" ·
FR "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?"
— expected structured meaning `domain=TRAVEL, intent=TRAVEL_ALLOWANCE, hours=12`, route `RULE_ENGINE`.

## 2. Infrastructure / Configuration

- Application: `VerwaltungsassistentApplication` (in-process Spring context, same wiring as production)
- Generation model: `qwen2.5:14b` (Ollama, localhost:11434)
- Verifier model: `qwen2.5:7b` (claim_batch strategy) — also the LLM parser model
- Embeddings: `nomic-embed-text` (768-dim), Qdrant collection `mda_chunks` (634 vectors, live)
- Neo4j live → retrieval queries route `GRAPH_REASONING`
- PostgreSQL container up; test DB = H2 dev profile (as in all prior runtime harnesses); 2 building chunks seeded for keyword retrieval
- GPU: Quadro P5000, 16 GB VRAM (~6.6 GB used during runs, both models co-resident)
- Both Ollama models were **unloaded before each run** so both runs pay symmetric cold starts.
- Only intentional difference between runs: `platform.ai.ollama.semantic-intent.enabled=false` (RUN A) vs `true` (RUN B).

## 3. Regex Results (RUN A, 1580s)

**22/27 PASS, 5 FAIL.**

| Query | Failure |
|-------|---------|
| SALARY-EN-S5 "EG 9b step 5" | `step` ignored → default Stufe 3 → **4117.53 € instead of 4480.00 €** |
| SALARY-PT-S5 "EG 9b nível 5" | `nível` ignored → default Stufe 3 → 4117.53 € (wrong) |
| SALARY-FR-S5 "EG 9b échelon 5" | `échelon` ignored → default Stufe 3 → 4117.53 € (wrong) |
| PROC-EN-8000 "8,000 euros" | regex matches fragment `000 euros` → **amountEur=0.0** → decision "Kein formelles Verfahren" (wrong, conf 0.98) |
| PROC-FR-8000 "8 000 euros" | same fragment match → amountEur=0.0 → "Kein formelles Verfahren" (wrong, conf 0.98) |

Notable accidental regex successes:
- PT/FR travel 12h passed because the English unit `h` is a **prefix of `horas`/`heures`** — no Portuguese/French patterns exist.
- EN salary S3 passed because the ignored `step 3` coincides with the hardcoded default step 3.

## 4. LLM Results (RUN B, 1479s)

**26/27 PASS, 1 FAIL.**

| Query | Failure |
|-------|---------|
| AMBIG-EN "What applies at 12?" | LLM extracted `hours=12.0` with `intentType=GENERAL` → router used the bare parameter → **forced RULE_ENGINE "Tagegeld 12 €"** on an ambiguous question |

All four mandated travel equivalents converged to `TRAVEL / TRAVEL_ALLOWANCE / {hours=12.0} / RULE_ENGINE / Tagegeld 12 €`.
All five regex failures were handled correctly by the LLM (`salaryStep=5` from `step/nível/échelon`; `amountEur=8000` from `8,000` and `8 000`).

Minor semantic noise (inert today): LLM returned `queryType=INCREASE` on both unsupported procurement questions; `intentType=PROCUREMENT_THRESHOLD` without an amount on UNSUP-DE. Neither triggers any rule lookup.

## 5. Per-Query Differences (A–F classification)

| Class | Meaning | Queries |
|-------|---------|---------|
| **A** | Both correct | **21** — incl. 3 accidental regex successes: TRAVEL-PT-12H, TRAVEL-FR-12H (`h`-prefix), SALARY-EN-S3 (default-step coincidence) |
| **B** | LLM correct / regex wrong | **5** — SALARY-EN-S5, SALARY-PT-S5, SALARY-FR-S5, PROC-EN-8000, PROC-FR-8000 |
| **C** | Regex correct / LLM wrong | **1** — AMBIG-EN (LLM forced deterministic answer; regex stayed on retrieval) |
| D | Both wrong | 0 |
| E | Different routing, equivalent result | 0 |
| F | Infrastructure/test failure | 0 |

**Regex: 22/27 (81.5%) · LLM: 26/27 (96.3%).**

The regex failures are *systematic parameter-extraction defects*: non-German step words are
silently ignored and non-dot thousands separators produce `amountEur=0.0` — both yield
**confidently wrong deterministic decisions** (confidence 0.98–0.99, no verification runs on
the rule path). The 0.0-amount defect is number-format-based, not language-based: German
space-separated amounts ("8 000 Euro", DIN 5008) would trigger it too.

The single LLM failure is a *safety* failure: over-extraction on an ambiguous question forces
a deterministic answer with no evidence. On the rule path nothing verifies the decision.

## 6. DE/EN/PT/FR Comparison

| | Regex | LLM |
|--|-------|-----|
| DE | correct on all deterministic queries (German patterns) | correct on all |
| EN | travel/distance correct; **step words ignored; comma thousands broken (0.0 €)** | correct on all |
| PT | travel correct **only accidentally** (`h`⊂`horas`); dot thousands fine; **step words ignored** | correct on all |
| FR | travel correct **only accidentally** (`h`⊂`heures`); distance fine (`km`); **space thousands broken (0.0 €); step words ignored** | correct on all |

Legacy `DomainClassifier` (German-keyword YAML, drives retrieval planning in regex mode):
EN travel → GENERAL (0.00), PT travel → **PROCUREMENT** (misclassified), FR → GENERAL (0.00),
PT building → GENERAL (0.00). Consequence observed at runtime: `RETR-PT-BUILDING` planned
with `primaryDomain=GENERAL` in regex mode vs authoritative `BUILDING` in LLM mode, and the
regex-mode answer misidentified Portuguese as French ("französischen Bauantrag").

## 7. Cases Where LLM Improved

1. Salary step extraction from EN/PT/FR (`step`/`nível`/`échelon`) — 3 queries.
2. Thousands separators `,` and space → correct 8000.00 — 2 queries.
3. Authoritative `domain` on **all 27 queries** (regex always `null`), propagated to `RetrievalPlan`.
4. Correct retrieval planning domain for PT building (BUILDING vs GENERAL).
5. Semantically correct intent typing for distance queries (TRAVEL_ALLOWANCE vs GENERAL).
6. Answer quality on PT building: correctly understood the question (regex mode called it French).

## 8. Cases Where Regex Improved

1. AMBIG-EN: regex extracted nothing → honest retrieval answer; LLM forced Tagegeld 12 €.
2. Determinism: regex is reproducible, zero-latency, no model dependency.

## 9. Cases Where Both Failed

None.

## 10. Latency Comparison

| Metric | Regex (RUN A) | LLM (RUN B) |
|--------|---------------|-------------|
| Parser | ~0 ms | **cold 5725 ms**; warm n=26 avg **1686 ms**, median 1597, min 1070, max 2432 |
| Routing incl. parse | avg 19 ms (median 2) | avg 1849 ms (median 1618) |
| E2E rule path | n=19 avg **14.0 s** (median 14.0, max 22.0 = first query incl. 14B cold load) | n=20 avg **13.4 s** (median 12.9, max 22.5) |
| E2E retrieval path | n=8 avg **164.1 s** (max 240.4) | n=7 avg **158.7 s** (max 174.2) |
| Total run | 1580 s | **1479 s** |

Parser overhead vs total application latency: **~1.6 s on a ~13.4 s rule path (≈12%)** and
**~1.6 s on a ~159 s retrieval path (≈1%)**. Total run time was equal within noise (LLM run
was actually 101 s faster — retrieval variance dominates).

Baseline check: prior known values (parser warm 0.8–1.2 s, cold 5.6 s; rule E2E 12–26 s;
retrieval 117–155 s) are **confirmed in order of magnitude** — warm parser measured slightly
higher (avg 1.7 s) and retrieval max slightly higher (240 s) under today's load.

## 11. GPU / Model Utilization

- Quadro P5000 (16 GB). During runs ~6.6 GB VRAM used; qwen2.5:14b + qwen2.5:7b co-resident.
- The LLM parser **shares the existing verifier model (7b)** — enabling LLM mode adds **no
  additional GPU footprint** in deployments that already run verification. In a hypothetical
  rule-only deployment (no retrieval/verification), regex mode could avoid loading the 7b
  entirely; LLM mode would require it.
- Peak utilization observed at 95% during the semantic test suite.

## 12. Full Regression

All existing tests re-run after the comparison, no test changes made:

| Suite | Result |
|-------|--------|
| platform-ai-runtime | **301/301** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| **Total** | **335/335 green** |

## 13. Multilingual / Core Portability Assessment

**Question:** if the municipal application were deployed in another country and another
language, which parts of the semantic interpretation pipeline would require code changes,
configuration changes, or neither?

Demonstrated by this experiment (not assumed):

| Layer | Change needed? | Evidence |
|-------|----------------|----------|
| Semantic intent parser (LLM) | **Neither** — no code, no config per language | Same English prompt produced correct `domain/intent/params` in DE/EN/PT/FR (26/27 incl. FR where zero French code exists) |
| Semantic intent parser (regex) | **Code** per language | German/English-only patterns; PT/FR work only via the `h`-prefix and `km` coincidences; EN `,` and FR space thousands broken → every new language needs new unit-word patterns |
| Domain list for LLM prompt | Config (already dynamic) | `Domain.allClassifiable()` — registered domains only |
| DomainClassifier (legacy fallback / regex-mode retrieval planning) | **Config** (translated YAML) | German keywords; misclassifies EN/PT/FR at runtime (EN travel → GENERAL 0.00, PT travel → PROCUREMENT) |
| Knowledge tables (TV-L/BRKG/AV §55) | **Content** (application-level, not core) | Registered by `MunicipalKnowledgeInitializer` in the municipal module; the core registry/router is table-generic |
| Output/presentation layer | **Code or config** | `AiService.buildExplanationPrompt` is hardcoded German ("Sprache: deutsche Kommunalverwaltung"); EN/PT/FR queries received German answers on the rule path in both runs |
| Verifier (claim-batch) | Neither | Ran cleanly on EN/PT/FR retrieval queries in both modes |

**Conclusion:** with the LLM parser enabled, the core semantic interpretation requires **no
language-specific code or regex additions** — the structured intent/domain/parameters emerge
from one language-neutral prompt. The remaining localization surface is concentrated in the
domain-classifier YAML (config), the knowledge-table content (application content), and the
hardcoded German output prompt (code/config). The regex parser, by contrast, is inherently
per-language code and does not generalize.

## 14. Recommendation on Default Mode

**Option C — keep regex as the default for the current German municipal deployment, and
enable the LLM semantic parser (`platform.ai.ollama.semantic-intent.enabled=true`) for
multilingual deployments — with one defect fixed before any global default flip.**

Reasoning from the evidence:

- **Semantic accuracy:** LLM 26/27 vs regex 22/27. Regex's 5 failures are systematic and
  produce confidently wrong deterministic decisions (0.98–0.99) with no verification on the
  rule path. LLM's 1 failure is safety-relevant (forced deterministic answer on an ambiguous
  question).
- **Multilingual robustness:** LLM is the only general solution — regex covers DE (+EN
  partially) and passes PT/FR only by accident.
- **Latency:** +1.6 s ≈ 12% of the rule path, ≈ 1% of the retrieval path — negligible against
  total application latency. Cold start +5.7 s once per session.
- **GPU/resources:** no additional requirement — the parser shares the resident 7b verifier
  model.
- **Fallback:** regex retained as fallback; LLM parser degrades to empty intent →
  DomainClassifier path on failure. Rollback = one flag.
- **Operational complexity:** minimal — one existing config flag.

**Blocking item for Option B (LLM default):** the AMBIG-EN over-extraction. The router
honors bare numeric parameters regardless of `intentType`. A minimal guard in
`DecisionRouter.tryStructuredKnowledge` — honor `hours` only when
`intentType==TRAVEL_ALLOWANCE`, `amountEur` only for `PROCUREMENT_THRESHOLD`,
`salaryGrade` only for `SALARY_LOOKUP` — blocks the forced answer without affecting the
regex path (which always sets matching intent types). With that guard, the LLM path would
have scored 27/27 in this corpus and becomes the stronger default candidate.

Not implemented in this task (per experiment constraints): the guard above, output-language
externalization, and the default flip. The harness (`SemanticIntentProductionComparison{Regex,Llm}Test`)
remains in the test suite for future re-runs of this comparison.
