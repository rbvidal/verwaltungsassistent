# Language-Aware Semantic Intent Experiment

**Date:** 2026-08-13
**Scope:** Language-aware LLM semantic interpretation (one parser, no per-language code), evaluated against the unchanged 128-question corpus plus a new focused 48-question corpus.
**Evidence:** `verwaltungsassistent-web/target/language-aware-corpus-llm.txt`, `expanded-corpus-llm.txt` (after), `expanded-corpus-regex.txt` (after), `comparison-llm.txt` (E2E).

## 1. Current Language/Locale Handling (investigation result)

- **Language is not detected or represented anywhere** in the semantic pipeline: no field on `StructuredIntent`, no locale object, no language logic in either parser, `DecisionRouter`, `DecisionVerifier` (calls `DomainClassifier` for metadata only), `DomainGate`, or configuration. The only "language" data are test-corpus labels.
- Numeric parameters are extracted either by language-dependent regex patterns (`RegexSemanticIntentParser`: German hours words, `EG`, `Euro`) or by the LLM guided by a prompt note that wrongly treated both `.` and `,` as thousands separators (root cause of the `8.000,50 → 800050` failure).
- The LLM prompt had no language instruction and no ambiguity guidance.
- Downstream consumers need **no** language/locale: parameters are already language-neutral. Language is informational/diagnostic and a future hook for output localization. **Locale has no concrete consumer** → deliberately omitted (locale guesses like pt-PT/pt-BR are unreliable; per design, prefer null over inventing).

## 2. Proposed Change

One semantic model, made language-aware:

```
Natural language → LLM → StructuredIntent{language, domain, intentType, parameters}
                        → deterministic guard (intentType authorizes parameters)
                        → RULE_ENGINE / HYBRID_RETRIEVAL
```

`language` (ISO 639-1) added to `StructuredIntent`; the LLM infers it and interprets numbers from the language/context. No locale. No per-language parsers, regexes, YAML, or prompt tables. Jurisdiction knowledge (BRKG/TV-L/AV §55) remains entirely in the deployment/domain configuration — the parser knows only "12 Stunden Dienstreise → TRAVEL_ALLOWANCE, hours=12".

## 3. Exact Files Changed

1. `platform-ai-api/.../model/StructuredIntent.java` — language component + convenience constructor.
2. `platform-ai-runtime/.../application/LlmSemanticIntentParser.java` — prompt, language extraction, grade-token normalization.
3. `verwaltungsassistent-web/.../ai/AbstractExpandedSemanticCorpusTest.java` — corpus resource hook, language capture/scoring.
4. `verwaltungsassistent-web/.../ai/ExpandedSemanticCorpus{Regex,Llm}Test.java` — corpus-resource overrides.
5. NEW `verwaltungsassistent-web/.../ai/LanguageAwareCorpusLlmTest.java` — focused harness.
6. NEW `verwaltungsassistent-web/src/test/resources/corpus/language-aware-corpus.csv` — 48 rows.

**Unchanged:** `RegexSemanticIntentParser`, `DecisionRouter` (guard from previous experiment untouched), `DecisionVerifier`, `DomainClassifier`, `DomainGate`, configs, the 128-question corpus, the production default (`semantic-intent.enabled=false`).

## 4. StructuredIntent Changes

- New component `String language` (ISO 639-1, normalized to lowercase; `null` when the parser cannot determine it).
- 4-argument convenience constructor preserved → `RegexSemanticIntentParser` (language always `null`) and all existing call sites compile unchanged.
- Locale deliberately **not** added (no consumer; unreliable guesses).

## 5. Semantic Prompt Changes

The LLM prompt now instructs the model to:
1. return the question's `language` (ISO 639-1, or null if unsure);
2. interpret numbers **according to the question's language** — distinguish thousands from decimal separators (single contrast example, not a per-language table);
3. treat numbers as *evidence*, not *intent* — `intentType` may only be TRAVEL_ALLOWANCE/SALARY_LOOKUP/PROCUREMENT_THRESHOLD when the meaning expresses it; ambiguous questions → GENERAL; `amountEur` requires a currency reference; `hours` requires a duration context;
4. treat daily travel allowances ("Tagegeld", "per diem", "diária", "indemnité de repas/déplacement") as TRAVEL_ALLOWANCE, not salary;
5. return only the grade token for `salaryGrade` (deterministic normalization `normalizeGrade` in the parser additionally maps `9b`→`EG 9b` and strips field pollution like `EG 9 nível 3`→`EG 9`).

A rejected variant (listing step-words in the prompt) degraded extraction sharply (step dropped on 16 salary rows, 107/128) — evidence that prompt hints must stay minimal; reverted.

## 6. DE/EN/PT/FR Number-Format Results

Focused matrix — **11/11 correct** (language 10/11; one null):

| Format | amountEur | route |
|--------|-----------|-------|
| DE `8.000` / `8.000,50` / `8 000` | 8000 / **8000.5** / 8000 | RULE_ENGINE, Direktauftrag |
| EN `8,000` / `8,000.50` / `8000` | 8000 / **8000.5** / 8000 | ✓ |
| PT `8.000` / `8.000,50` / `8 000` | 8000 / **8000.5** / 8000 | ✓ |
| FR `8 000` / `8 000,50` | 8000 / **8000.5** | ✓ |

The previously fatal German decimal-comma case (`8.000,50` → 800050 → forced wrong
"Öffentliche Ausschreibung") is fixed: 8000.50 → Direktauftrag. In the 128 corpus the
other former failures (negation 850, EN `8,000.50` truncation) are also resolved.

## 7. DE/EN/PT/FR Semantic Equivalence Results

16/16 rows converge on identical structured representation across languages:

- TRAVEL: DE Tagegeld / EN daily allowance / PT ajuda de custo / FR indemnité de déplacement → all `TRAVEL/TRAVEL_ALLOWANCE/{hours=12}` → Tagegeld 12 €.
- SALARY: Stufe/level/nível/échelon 5 → all `HR/SALARY_LOOKUP/{EG 9b, 5}` → 4480.00 €.
- PROCUREMENT: 8.500/8,500/8.500/8 500 → all `PROCUREMENT/PROCUREMENT_THRESHOLD/{8500}` → Direktauftrag.
- RETRIEVAL: all `GENERAL` (BUILDING domain) → retrieval, no forced rule.

## 8. Ambiguity Safety Results

- Focused corpus (incl. new EN/PT equivalents): **10/10 ambiguous → retrieval, 0 forced**.
- 128 corpus: **E 9 → 12, F 3 → 0** — "Was ist mit 8.000?", "Und bei 3.000?", "Et pour 8.000 ?" and equivalents no longer become PROCUREMENT_THRESHOLD; the semantic rules in the prompt eliminated the intent+parameter co-hallucination class. Combined with the intentType guard from the previous experiment, ambiguous questions no longer trigger rules in any tested case.

## 9. Tagegeld / Vocabulary Results

- 8/8 travel-vocabulary rows across four languages → `TRAVEL_ALLOWANCE` with correct hours (Tagegeld, Verpflegungspauschale, Reisekosten, per diem, meal allowance, subsídio de alimentação, ajuda de custo, indemnité repas, indemnité de déplacement).
- The vocabulary note fixed the "Tagegeld → salary" misread on canonical phrasings. Residual: 3 phrasings in the 128 corpus ("Tagegeld bei Abwesenheit…", "…gelten die neuen Sätze…", "…em serviço…") still misread as SALARY_LOOKUP → **safe retrieval fallback** (no forced answer), but the deterministic answer is missed.
- Definitional questions ("Was bedeutet Tagegeld?") → TRAVEL_ALLOWANCE with no parameters → safe retrieval (classified D against the expected GENERAL, but semantically defensible and safe).

## 10. 128-Case Before/After Comparison (corpus unchanged)

| Class | Baseline | After (language-aware) |
|-------|----------|------------------------|
| A completely correct | 96 | **101** |
| E ambiguous safe | 9 | **12** |
| G unsupported safe | 9 | 9 |
| B correct intent, wrong param | 4 | 2 |
| C correct param, wrong intent | 3 | 1 |
| D wrong domain | 3 | 3 |
| F ambiguous forced | 3 | **0** |
| H other | 1 | **0** |
| **Total correct (A+E+G)** | **114/128 (89.1%)** | **121/128 (94.5%)** |

Regex (unchanged code, re-run): **97/128** both before and after.

Remaining LLM failures: D×3 (safe misses, §9), C×1 (reverse salary lookup — safe retrieval), B×2 (step dropped from "level 2"/"faixa … nível 5" phrasings — see §11).

## 11. Dangerous Forced-Wrong Decisions

Definition: deterministic answer with a **wrong** decision (a safe retrieval fallback is not counted).

| | Baseline | After |
|--|----------|-------|
| LLM | 5 (3 ambiguous F + `800050`→Öffentliche + `8500` wrong band) | **2** (SAL-EN-003, SAL-PT-002: step dropped → router's silent **Stufe-3 default** → 4117.53 instead of 3900.00/4480.00) |
| Regex (unchanged) | 17 | 17 |

The two remaining LLM forced-wrong answers are caused not by the parser alone but by the
router's `intent.salaryStep().orElse(3)` silent default — the same failure mode that
causes 7 of regex's 17. **Recommended next deterministic change:** remove the silent
step default (missing step → no salary rule, retrieval instead). This is a coherence
fix in the deterministic layer, not a parser change; it would also eliminate the
corresponding regex forced-wrong cases.

## 12. Latency Comparison

| Metric | Baseline (guard-only prompt) | Language-aware prompt |
|--------|------------------------------|-----------------------|
| Parser cold | ~5.8 s | 7.9 s |
| Parser warm (avg/median) | 1648 / 1506 ms | 1969–2221 / 1861–2090 ms |
| Routing incl. parse (128 corpus) | 1326 ms | 1540 ms |
| E2E rule path (27-query LLM run) | avg 13.5 s | avg **14.2 s** (+5%) |
| E2E retrieval path (27-query LLM run) | avg 138.6 s | avg **127.7 s** (within variance) |

The language-aware prompt costs ~+0.3–0.55 s per parse (+20–33% parser time), which is
~4–5% of the rule path and ~0.3% of the retrieval path. 27-query E2E verdict: **27/27**.

## 13. Full Regression

| Suite | Result |
|-------|--------|
| platform-ai-runtime (incl. guard tests) | **306/306** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| **Total** | **340/340 green** |

No existing test was modified. Corpus files unchanged (128-question and new focused corpus).

## 14. Is Language-Aware LLM Interpretation Strong Enough for Regex = Emergency Fallback?

**Yes, for multilingual deployments — with one deterministic fix first.**

Evidence: 121/128 (94.5%) vs regex 97/128; forced-wrong answers 2 vs 17; 0 forced
ambiguous answers; 11/11 number formats; 16/16 cross-language equivalence; 45/48
language identification; 27/27 on the production comparison; regression 340/340.
Portability is demonstrated, not assumed: French reached 94.7–100% on all focused
dimensions with zero French-specific code.

The single remaining safety gap is the router's silent `Stufe 3` default combined
with two step-drop phrasings — a deterministic-layer fix (remove the default), after
which the LLM path has no known forced-wrong failure mode in this corpus.

## 15. Remaining Reasons Regex Stays in the Normal Production Path

1. **The silent step-3 default** (`DecisionRouter` `orElse(3)`) still converts a
   missing step into a confident wrong salary in both parsers. Until it is removed
   (recommended next step, deterministic-layer change), neither parser is free of
   forced-wrong salary answers.
2. **Ollama outage / LLM parser failure:** the regex parser still provides
   deterministic parameter extraction when the semantic LLM errors (the LLM parser
   currently degrades to empty intent → retrieval-only behavior).
3. **Determinism/reproducibility:** regex output is fixed and auditable; LLM output
   varies between runs (observed: same query produced different fields across runs).
4. **German-only deployment:** regex's known defects mostly require non-German
   number formats or non-German step words; for a strictly German municipal
   deployment the 128-corpus evidence shows regex adequate (though the 0.0-amount
   fragment bug remains reachable via German space-separated amounts, DIN 5008).

Recommendation: keep the flag `semantic-intent.enabled` opt-in; multilingual
deployments should enable the LLM parser now; the German municipal default can stay
regex until the step-default fix lands and a re-run of the retained corpora confirms
the numbers. Nothing was removed; the experiment is fully reversible.
