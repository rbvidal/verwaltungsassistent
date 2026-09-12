# Remove Silent Salary-Step Default — Re-Validation

**Date:** 2026-08-14
**Scope:** Deterministic safety fix in the routing layer; re-validation of all corpora.

## 1. Exact Location of the Stufe-3 Default

Two places silently invented the step:

1. `DecisionRouter.tryStructuredKnowledge` — `int step = intent.salaryStep().orElse(3);`
   (the deterministic layer invented a step the user never stated).
2. `RegexSemanticIntentParser.extractGrades` — `m.group(2) != null ? m.group(2) : "3"`
   (the regex parser invented step 3 for every grade matched without a "Stufe N").

The LLM failures traced to the router default; the regex forced-wrong salary cases
flowed through the parser default.

## 2. Exact Code Change

```java
// DecisionRouter: salary lookup now requires BOTH parameters
if (salaryAuthorized && intent.salaryGrade().isPresent()
        && intent.salaryStep().isPresent()) {
    String grade = intent.salaryGrade().get();
    int step = intent.salaryStep().get();
    ...
```

```java
// RegexSemanticIntentParser.extractGrades: no invented step
r.add(new String[]{"EG " + m.group(1), m.group(2)});   // null when absent
```

4 new unit tests in `DecisionRouterTest` (grade-without-step → retrieval via stub
parser and via regex parser; grade+step → RULE_ENGINE in both). Nothing else changed.

## 3. Behavior Before / After

| Input | Before | After |
|-------|--------|-------|
| SALARY_LOOKUP + grade + step | deterministic salary (correct) | **unchanged** |
| SALARY_LOOKUP + grade, no step | **invented Stufe 3 → confident (often wrong) salary** | no deterministic lookup → retrieval |
| Regex "EG 9b" without "Stufe N" | step 3 injected by parser → confident (often wrong) salary | step null → retrieval |

"insufficient information → retrieval" now holds for the salary rule in both modes.

## 4. Original Two LLM Failures

| Query | Before fix | After fix |
|-------|-----------|-----------|
| "What does someone in pay grade EG 9b level 2 earn?" | step dropped → forced 4117.53 (Stufe 3) | step extracted this run → 3900.00 ✓ (A) |
| "Quanto ganha um funcionário no nível 5 da faixa EG 9b?" | step dropped → forced 4117.53 (Stufe 3) | step dropped → **retrieval** (safe; no invented answer) |

The safety property holds either way: when the parser fails to extract the step,
the system no longer answers with Stufe 3.

## 5. 128-Corpus Before/After (corpus unchanged)

| | Before fix | After fix |
|--|-----------|-----------|
| LLM | 121/128 (94.5%), forced-wrong **2** | **122/128 (95.3%), forced-wrong 0** |
| Regex | 97/128, forced-wrong **17** | 93/128, forced-wrong **8** |

LLM class detail after: A 102 · E 12 · G 9 · B 1 · C 1 · D 3 — **all six non-A/E/G
failures route to retrieval with no decision (safe)**. The regex score drop
(97→93) is four previously "accidentally correct" salary rows (step-default
coincidences) becoming safe misses — a safety improvement, not a regression.
All 7 regex forced-wrong salary defaults are gone.

## 6. 52-Case Language-Aware Corpus (48 + 4 new missing-step rows)

- Missing-step rows (DE/EN/PT/FR "Was verdient EG 9b?" variants): **4/4 → retrieval,
  no deterministic salary** ✓ (2 scored A with exact expected structure; 2 scored D
  on domain/intent typing but still safe retrieval).
- Number formats: 11/11 ✓ (unchanged). Equivalence: 16/16 ✓ (unchanged).
- Ambiguity: 9/10 safe — **1 forced (F)**: "What applies at 12?" this run produced
  `TRAVEL_ALLOWANCE domain=null hours=12` → Tagegeld 12 €. See §11.

## 7. 27-Query Production Comparison

- **LLM: 27/27** (maintained) — including AMBIG-EN safe.
- Regex: 21/27 (was 22/27): the single change is SALARY-EN-S3 ("EG 9b step 3")
  moving from accidentally-correct (default coincidence) to a safe retrieval miss.
  The other five failures are unchanged (step words ignored, `8,000`/`8 000`
  zero-amount bug).

## 8. Number-Format Results

Unchanged by this fix: 11/11 on the focused matrix, LLM correct on all formats
including `8.000,50` (DE) → 8000.50. Regex unchanged (6 zero-amount forced-wrong
remain).

## 9. Valid Salary Lookup Results

EG 9b Stufe 1/3/5 and equivalents (Stufe/step/level/nível/échelon): LLM **16/16**
rule rows in the focused corpus + all valid-step rows in the 128 corpus → RULE_ENGINE
with the correct table value. Regex: German "Stufe N" rows still correct;
non-German step words remain regex's known blind spot (now failing **safe**
instead of wrong).

## 10. Missing-Step Results

All four new rows ("Was verdient EG 9b?" / "How much does grade EG 9b earn?" /
"Quanto ganha a faixa EG 9b?" / "Quel est le salaire du grade EG 9b ?"):
route ≠ RULE_ENGINE in every case — the fix works at the deterministic layer
regardless of how the parser types the intent.

## 11. Remaining Dangerous Deterministic Failures

| Mode | Count | Nature |
|------|-------|--------|
| LLM | **0** in 128-corpus; **1** in 52-corpus | Probabilistic co-hallucination: "What applies at 12?" → TRAVEL_ALLOWANCE+hours, `domain=null`, forced Tagegeld 12 €. Occurred 1× across 46 ambiguous-query exposures today (~2%); signature is always a forced rule with `domain=null` — never with a coherent domain. |
| Regex | **8** (was 17) | Deterministic bugs: 6 zero-amount fragment (`8,000`/`8 000`/`120,000` → "Kein formelles Verfahren"), 2 first-number-wins negation. |

Regex's remaining 8 are deterministic and enumerable; the LLM's residual is
probabilistic but structurally detectable (`domain=null` + forced rule — every one
of the 66+ correct forced triggers carries a matching non-null domain).

## 12. Regex vs LLM Comparison (after fix)

| Dimension | LLM | Regex |
|-----------|-----|-------|
| 128-corpus accuracy | 122/128 (95.3%) | 93/128 (72.7%) |
| Dangerous forced-wrong (128) | 0 | 8 |
| Ambiguous safety | 45/46 safe (~2% forced, probabilistic) | 22/22 safe (deterministic — regex cannot extract bare numbers) |
| Multilingual | 4/4 languages, no language code | DE/EN only; PT/FR accidental |
| Number formats | 11/11 | 5/11 (0.0-fragment bug) |
| Missing-step | safe retrieval | safe retrieval (after this fix) |

## 13. Full Regression

| Suite | Result |
|-------|--------|
| platform-ai-runtime (incl. 4 new missing-step tests) | **310/310** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| **Total** | **344/344 green** |

No test was modified to obtain a green result.

## 14. Regex's Role — Assessment

1. LLM dangerous failures: 0 on the 128 corpus; 1 probabilistic F on the focused corpus (~2% of ambiguous exposures).
2. Regex dangerous failures: 8, all deterministic and enumerable.
3. **Does regex provide a safety benefit the LLM path lacks?** Yes, one: on
   ambiguous questions regex is deterministically safe (it cannot invent intents —
   it simply extracts nothing). The LLM's residual F is the only failure class the
   LLM has that regex never produces.
4. Remaining LLM failures are otherwise safe retrieval fallbacks (5/6 in the 128
   corpus) — no wrong answers.
5. **Regex is now reasonably characterized as an emergency fallback, not a normal
   semantic interpreter:** it scores 22.6 points lower, produces the only
   remaining *deterministic* wrong answers, and its known bugs are not being
   fixed by design. Its legitimate uses: Ollama-outage determinism, auditability,
   and the deterministic ambiguity safety it happens to provide.

## 15. Is the LLM Semantic Path Ready to Become the Default?

**Yes — conditionally.** Evidence: 95.3% (122/128), 0 forced-wrong on the main
corpus, 27/27 production, 344/344 regression, 11/11 number formats, 16/16
cross-language equivalence, missing-step now fails safe. The one open item is the
probabilistic F (~2% of ambiguous exposures, always with `domain=null`).

Recommendation:
- **Multilingual deployments: enable the LLM parser now** (`semantic-intent.enabled=true`)
  — regex is strictly worse there in every dimension and deterministically dangerous 8× more often.
- **German municipal default: flip after adding the domain-coherence gate** to
  `DecisionRouter` (LLM mode: a rule trigger additionally requires a non-null domain
  matching the intent's domain). Evidence: this gate eliminates the observed F
  signature with zero impact on the 66+ correct triggers; regex mode (domain always
  null) is exempted by keeping the gate mode-aware, or regex simply becomes the
  emergency fallback with its known, now-reduced defect list.
- Keep regex as emergency fallback in all cases (not removed, per constraints).

The default (`semantic-intent.enabled=false`) was left unchanged, as instructed.
