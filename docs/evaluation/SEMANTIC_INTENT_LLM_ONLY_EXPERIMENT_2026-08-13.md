# LLM-Only Semantic Intent Experiment: Evaluate Removal of Regex

**Date:** 2026-08-13
**Scope:** Expanded 128-question multilingual corpus, parser+router level, live infrastructure, with the Phase-1 safety guard implemented in `DecisionRouter.tryStructuredKnowledge`.
**Harness:** `AbstractExpandedSemanticCorpusTest` + `ExpandedSemanticCorpus{Regex,Llm}Test`; corpus: `verwaltungsassistent-web/src/test/resources/corpus/expanded-semantic-corpus.csv`.
**Raw evidence:** `verwaltungsassistent-web/target/expanded-corpus-{regex,llm}.txt`, `comparison-{regex,llm}.txt` (27-query re-run).

## Phase 1 — Safety guard (implemented)

`DecisionRouter.tryStructuredKnowledge` now requires the `intentType` to authorize a
parameter before any deterministic rule lookup:

- `TRAVEL_ALLOWANCE` → `hours` authorized
- `PROCUREMENT_THRESHOLD` → `amountEur` authorized
- `SALARY_LOOKUP` → `salaryGrade`/`salaryStep` authorized
- `GENERAL` → **no** deterministic rule may trigger from isolated parameters

5 new unit tests encode the guard (`DecisionRouterTest`); one existing test
(`shouldHandleMultiCategoryQuestion`) was updated to the new safe semantics
(mixed-category question now falls to retrieval instead of answering with an
unrelated category's rule). `distanceKm` intentionally remains unguarded: the
regex path emits `GENERAL + distanceKm` and the unit `km` is intrinsically a
travel parameter (documented decision; can be gated to `TRAVEL_ALLOWANCE` if
desired, at the cost of regex-mode mileage queries falling back to retrieval).

## 1. Corpus Size and Composition

**128 questions** (within the 120–150 target), each with expected domain,
intentType, parameters, route, and (for rule cases) decision text — ground truth
is the *structured intent*, not merely the final answer.

| Category | Count | Coverage |
|----------|-------|----------|
| TRAVEL_ALLOWANCE | 33 | 8/9/10/11/12/20/24 h, missing duration, word durations, paraphrases, long questions, typos, negation, multiple/irrelevant numbers, distance |
| SALARY_LOOKUP | 19 | EG 9b S1/S3/S5, EG 11 S4, EG 9 (knowledge gap), step/nível/échelon, "grade 9b step 5", negation, reverse lookup |
| PROCUREMENT_THRESHOLD | 29 | `8,000` / `8.000` / `8 000` / `8000` / `8,000.50` / `8.000,50`, word amounts, 499/500/850/10.000/100.000 boundaries, negation, date+amount, long questions |
| BUILDING | 12 | must NOT invoke structured knowledge |
| RETRIEVAL | 14 | document-oriented, no parameters |
| UNSUPPORTED | 9 | no relevant evidence in corpus |
| AMBIGUOUS | 12 | "Was gilt bei 12?", "Was ist mit 8.000?", "What about 8?", "How much for level 5?", "Und bei 3.000?", "Et pour 8.000 ?", ... |

## 2. Languages Tested

German 56, English 32, Portuguese 21, French 19 — semantic equivalents with
natural (not word-for-word) formulations per language.

## 3. LLM Semantic Accuracy

**114/128 = 89.1%** (with guard active).

| Class | Count |
|-------|-------|
| A completely correct | 96 |
| B correct intent, wrong parameter | 4 |
| C correct parameter, wrong intent | 3 |
| D wrong domain | 3 |
| E ambiguous correctly rejected | 9 |
| F ambiguous incorrectly forced | **3** |
| G unsupported handled correctly | 9 |
| H other | 1 |

Per language: DE 48/56 · EN 30/32 · PT 18/21 · FR 18/19.
Per category: TRAVEL 28/33 · SALARY 16/19 · PROCUREMENT 26/29 · BUILDING 12/12 ·
RETRIEVAL 14/14 · UNSUPPORTED 9/9 · AMBIGUOUS 9/12.

## 4. Regex Semantic Accuracy

**97/128 = 75.8%** (same corpus, same guard).

A: 76 · B: 16 · C: 15 · E: 12 · G: 9 — regex is perfectly safe on ambiguous
(12/12) and unsupported (9/9) queries because it extracts nothing there, but
fails systematically on parameters (B:16) and intent assignment (C:15).

## 5. LLM-Correct / Regex-Wrong Cases — 25

- **Salary steps from EN/PT/FR** (`step`/`nível`/`échelon`, "level", "faixa") — 7 cases; regex silently defaults to Stufe 3 → confidently wrong salary.
- **Thousands separators** `8,000` / `8 000` / `120,000` / `120 000` — 5 cases; regex matches the fragment `000 euros` → `amountEur=0.0` → forced "Kein formelles Verfahren" (conf 0.98).
- **Amounts in words** (`achttausend`, `eight thousand`, `oito mil`, `huit mille`) — 4 cases; regex extracts nothing.
- **Durations in words** (`zwölf`, `twelve`, `douze`) — 4 cases; regex extracts nothing.
- Typo tolerance ("12 Studen"), mixed travel+cost question, distance intent typing — 5 further cases.

## 6. Regex-Correct / LLM-Wrong Cases — 8

- `Tagegeld`/`diária` misread as salary (SALARY_LOOKUP) → deterministic answer missed, safe fallback to retrieval — 3 cases (TRAV-DE-007 "Tagegeld bei Abwesenheit von 11 Stunden?", TRAV-DE-016 "…gelten die neuen Sätze…", TRAV-PT-005 "…em serviço…").
- German decimal-comma amount `8.000,50` → LLM produced **800050.0** → forced "Öffentliche Ausschreibung / EU-weit" (wrong decision, conf 0.98) — 1 case (PROC-DE-003).
- Grade field pollution (`EG 9 nível 3` written into `salaryGrade`) — 1 case (SAL-PT-004, safe: no lookup triggered).
- **Ambiguous amount questions forced** — 3 cases (see §7).

## 7. Ambiguous-Query Safety Results

Guard effect, demonstrated: the original defect class is **fixed** — in the
27-query production re-run, `AMBIG-EN "What applies at 12?"` now yields
`GENERAL` + retrieval (27/27 LLM, previously 26/27). All hours/step-shaped
ambiguous queries (9) are safely rejected.

**Residual failure class (F, 3 cases):** when the ambiguous question *is shaped
like an amount* and the LLM co-hallucinates the authorizing intent:

| Query | LLM produced | Result |
|-------|--------------|--------|
| "Was ist mit 8.000?" | `PROCUREMENT_THRESHOLD`, amountEur=8000, **domain=null** | forced "Direktauftrag" |
| "Und bei 3.000?" | `PROCUREMENT_THRESHOLD`, amountEur=3000, **domain=null** | forced "Direktauftrag" |
| "Et pour 8.000 ?" | `PROCUREMENT_THRESHOLD`, amountEur=8000, **domain=null** | forced "Direktauftrag" |

The intentType guard cannot catch these — the intent itself is hallucinated.
**Decisive observation:** all 66 *correct* rule triggers carried a non-null
matching domain (HR 12 / PROCUREMENT 26 / TRAVEL 28), while all 3 forced
ambiguous cases carried `domain=null`. A domain-coherence gate (rule trigger
requires a non-null domain matching the intent's domain) eliminates all 3 F
cases with **zero impact** on the 66 correct triggers — at the cost of blocking
regex-mode rule triggers (regex never produces domains), which is why it must
be mode-aware (LLM-only) or applied after regex retirement.

## 8. Parameter Extraction Results

| Parameter | LLM | Regex |
|-----------|-----|-------|
| hours (numeric, all languages) | 100% | 100% (via `h`-prefix accident for PT/FR) |
| hours in words (zwölf/twelve/doze/douze) | 4/4 | 0/4 |
| salaryGrade | correct incl. `grade 9b`→`9b` (1 prefix-drop miss); 1 field pollution | exact `EG N` pattern only |
| salaryStep | 19/19 incl. `step/nível/échelon/5e échelon` | only German `Stufe`; silent default 3 otherwise (7 forced-wrong salaries) |
| amountEur (separators) | 24/26 | 18/26 (0.0-fragment bug ×6) |
| amountEur in words | 4/4 | 0/4 |
| negation ("nicht X sondern Y") | picks the correct number but merges it (850→8500, 1 case); travel negation correct (6.0) | always takes the first number (2 forced-wrong) |
| multiple/irrelevant numbers | correct in 5/5 | mixed: last-write-wins intent (1 forced-wrong) |

## 9. Number-Format Results

| Format | Regex | LLM |
|--------|-------|-----|
| `8.000 Euro` (DE/PT dot) | ✓ | ✓ |
| `8,000 euros` (EN comma) | **0.0 → forced wrong** | ✓ |
| `8 000 euros` (PT/FR space) | **0.0 → forced wrong** | ✓ |
| `8000 euros` | ✓ | ✓ |
| `8.000,50 Euro` (DE) | ✓ 8000.50 | **800050 → forced wrong** |
| `8,000.50 euros` (EN) | **50.0 → forced wrong** | 8000.0 (decimals truncated; decision coincidentally right) |
| `120,000` / `120 000` | **0.0 → forced wrong** | ✓ |
| word amounts | no extraction | ✓ 4/4 |
| negation amounts | first number → wrong band | merged number → wrong band (1) |

Regex: 8 forced-wrong number-format answers. LLM: 2 forced-wrong
(`8.000,50`→Öffentliche Ausschreibung; `850`→8500 wrong band) + 1 truncation
with coincidentally right decision. Neither parser is clean on mixed
thousands+decimal formats; the LLM's failure is narrower and confined to
German decimal-comma style (prompt-fixable).

## 10. Multilingual Portability Results

Demonstrated, not assumed:

- The LLM path contains **zero language-specific code**: one English prompt,
  dynamic domain list (`Domain.allClassifiable()`), same JSON output schema —
  yet DE/EN/PT/FR reach 85.7%–94.7% per-language accuracy, including French
  (94.7%) where nothing French exists in the codebase.
- The regex path is German+English by construction; its PT/FR successes are
  accidents (`h` is a prefix of `horas`/`heures`; `km` is universal) and its
  failures follow the language's number conventions (EN comma, FR space).
- Legacy `DomainClassifier` (German YAML) still misclassifies non-German input;
  the authoritative LLM domain bypasses it in LLM mode.
- The remaining non-portable component is presentation: `AiService.buildExplanationPrompt`
  is hardcoded German (unchanged, per constraints).

## 11. Latency Comparison

| Metric | Regex | LLM |
|--------|-------|-----|
| Semantic parser | ~0 ms | cold **5815 ms**; warm avg **1648–1720 ms** (median ~1.5–1.6 s) |
| Routing incl. parse (128-query corpus) | avg 5 ms | avg 1326 ms |
| E2E rule path (27-query re-run) | avg ~14.0 s | avg 13.5 s |
| E2E retrieval path (27-query re-run) | avg ~164 s | avg 138.6 s |
| 128-query parser+router corpus run | 1 s | 386 s |

Confirms the known baselines (warm ~1.7 s, cold ~5.7 s, rule ~14 s,
retrieval ~139–164 s). Parser overhead ≈ 12% of the rule path, ≈ 1% of the
retrieval path. Same GPU (P5000, parser shares the resident 7b verifier).

## 12. Full Regression Result

| Suite | Result |
|-------|--------|
| platform-ai-runtime (incl. 5 new guard tests) | **306/306** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| 27-query production comparison, regex mode | **22/27** (unchanged) |
| 27-query production comparison, LLM mode | **27/27** (AMBIG-EN fixed) |
| Expanded 128-query corpus | regex 97/128 · LLM 114/128 |

No test was changed to improve numbers; the only test edit accompanies the
authorized guard behavior change (multi-category question now retrieval-safe).

## 13. Remaining Weaknesses

1. **3 forced deterministic answers on amount-shaped ambiguous questions**
   (F class) — intent+parameter co-hallucination; fix identified: mode-aware
   domain-coherence gate (evidence in §7).
2. **German decimal-comma format** `8.000,50` → 800050 → forced wrong decision —
   prompt fix (extend NUMBER FORMAT guidance) or post-parse sanity check.
3. **`Tagegeld`/`diária` → SALARY_LOOKUP misreads** (3 cases) — missed
   deterministic answers, safe fallback; prompt glossary fix ("Tagegeld is not salary").
4. Negation amounts merged (850→8500) — prompt fix.
5. `grade 9b` → grade field without `EG` prefix (1 lookup miss, safe) — normalize
   in parser response handling.
6. Regex-specific (relevant only while regex runs): 0.0-fragment amount bug,
   silent step-3 default, first-number-wins negation, word-number blindness.
7. Pre-existing, out of scope: retrieval grounding strictness, hardcoded German
   output prompt, German-only DomainClassifier YAML.

## 14. Recommendation

**B — keep regex only as an emergency fallback — with two preconditions, else D.**

The evidence does **not** support full removal (C) today: 3 ambiguous queries
were still forced into deterministic answers and 2 amount queries produced
forced wrong decisions (acceptance criterion 2/3 not met). The evidence does
**not** support keeping regex as the primary interpreter (A): regex is 13.3
points less accurate (75.8% vs 89.1%), produced **17 forced-wrong deterministic
answers** in this corpus (7 silent step-3 defaults, 6 zero-amount bugs, 2
negation, 1 reverse-lookup, 1 mixed-intent) vs the LLM's 5, and is structurally
unable to serve DE/EN/PT/FR without per-language code.

The path to B:

1. Implement the **mode-aware domain-coherence gate** in `DecisionRouter`
   (when the LLM parser is active, a rule trigger additionally requires a
   non-null domain matching the intent's domain) — evidence: eliminates all 3
   F cases with zero impact on 66 correct triggers. Regex fallback mode keeps
   today's behavior.
2. Extend the LLM parser prompt for German decimal-comma amounts and add a
   small glossary ("Tagegeld/diária/per diem is TRAVEL_ALLOWANCE, not salary").
3. Re-run this exact corpus (harness and 128-question CSV remain in the test
   suite) — expected: 3 F → 0, and the acceptance criteria are then met.

Until (1) is implemented, the honest status is **D (more testing required)**
for the default flip; the flag stays opt-in (`platform.ai.ollama.semantic-intent.enabled`),
the regex parser stays as both fallback and current default. Nothing was
removed, and the experiment is fully reversible.

If, instead, the team accepts the documented risk profile for a specific
multilingual deployment, the LLM parser with the Phase-1 guard alone is already
deployable there: 89.1% intent accuracy, 27/27 on the production comparison,
no forced answers on hours-shaped ambiguity, and a 25:8 correctness advantage
over regex.

## Architectural Principle — Outcome

The separation held: the LLM transforms natural language into `StructuredIntent`;
the deterministic core authorizes parameters against the intent, selects the
rule or retrieval, and produces the decision. The guard moved the last
authorization decision into the deterministic layer — exactly one step remains
(domain coherence) to make that authorization complete.
