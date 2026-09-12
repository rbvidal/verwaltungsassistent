# Domain-Coherence Safety Gate — Implementation and Validation

**Date:** 2026-08-14
**Scope:** Final deterministic safety boundary before the LLM semantic parser can become the primary interpreter.

## 1. Exact Files Changed

1. `platform-ai-runtime/.../application/DecisionRouter.java` — the gate.
2. `platform-ai-runtime/.../unit/application/DecisionRouterTest.java` — 9 new tests.

Nothing else. No parser, prompt, classifier, retrieval, or knowledge changes.

## 2. Exact Domain-Coherence Mechanism

`DecisionRouter` gains a mode-aware gate checked at the top of
`tryStructuredKnowledge` — **before** any rule lookup:

```java
if (domainGateActive && !domainCoherent(intent)) {
    log.info("DecisionRouter: domain-coherence gate refused structured lookup"
            + " (intent={} domain={})", intent.intentType(), intent.domain());
    return null;                       // → retrieval/fallback
}
```

```java
private static String requiredDomainName(String intentType) {
    return switch (intentType) {
        case StructuredIntent.INTENT_TRAVEL_ALLOWANCE      -> "TRAVEL";
        case StructuredIntent.INTENT_PROCUREMENT_THRESHOLD -> "PROCUREMENT";
        case StructuredIntent.INTENT_SALARY_LOOKUP         -> "HR";
        default -> null;               // no deterministic rule for this intent
    };
}

private static boolean domainCoherent(StructuredIntent intent) {
    String required = requiredDomainName(intent.intentType());
    if (required == null) return false;
    return intent.domain() != null && required.equalsIgnoreCase(intent.domain().name());
}
```

**Mode handling:** the 4-arg `@Autowired` constructor activates the gate exactly
when the injected primary parser is `LlmSemanticIntentParser`
(`semanticIntentParser instanceof LlmSemanticIntentParser`). The legacy regex
parser never produces a domain and its behavior is byte-for-byte unchanged
(verified: 128-corpus regex result identical, 93/128). A 5-arg constructor
(explicit `domainGateActive`) exists for tests; no fake domain was introduced
into the regex parser.

Ordering of the deterministic boundary (all required before RULE_ENGINE):

```
domain coherence  →  intentType authorizes parameter  →  parameter present
        (new)                  (previous guard)            (no silent defaults)
```

One wiring defect was found and fixed during validation: with two public
constructors and no annotation, Spring failed to select one (context-load
failure in all web tests); `@Autowired` on the 4-arg constructor resolved it.

## 3. All Structured Intents / Domains Covered

| Intent | Required domain | Required parameters |
|--------|-----------------|---------------------|
| TRAVEL_ALLOWANCE | TRAVEL | hours (or distanceKm for mileage) |
| PROCUREMENT_THRESHOLD | PROCUREMENT | amountEur |
| SALARY_LOOKUP | HR | salaryGrade + salaryStep |
| GENERAL | — (no rule) | — |

Enumerated from the actual implementation: exactly these three rule families
exist (salary tables, travel-allowance tables incl. mileage, threshold tables).
`requiredDomainName` returns null for anything else, so an unknown intent type
can never reach a rule.

## 4. Positive Tests (gate active)

| Combination | Result |
|-------------|--------|
| TRAVEL + TRAVEL_ALLOWANCE + hours=9 | RULE_ENGINE, Tagegeld 6.00 € |
| PROCUREMENT + PROCUREMENT_THRESHOLD + amountEur=800 | RULE_ENGINE, Direktauftrag mit Genehmigung |
| HR + SALARY_LOOKUP + EG 9a + step 3 | RULE_ENGINE, 4117.53 € |

Plus representative LLM-parser runs: 16/16 focused equivalence rows and all
valid-rule rows of the 128 corpus unchanged.

## 5. Negative Cross-Domain Tests (gate active)

| Combination | Result |
|-------------|--------|
| **domain=null** + TRAVEL_ALLOWANCE + hours=12 | HYBRID_RETRIEVAL, no decision |
| PROCUREMENT + TRAVEL_ALLOWANCE + hours=12 | HYBRID_RETRIEVAL, no decision |
| TRAVEL + PROCUREMENT_THRESHOLD + amountEur=800 | HYBRID_RETRIEVAL, no decision |
| HR + TRAVEL_ALLOWANCE + hours=12 | HYBRID_RETRIEVAL, no decision |
| TRAVEL + SALARY_LOOKUP + EG 9a + step 3 | HYBRID_RETRIEVAL, no decision |
| (legacy) gate inactive + domain=null + TRAVEL_ALLOWANCE + hours | RULE_ENGINE — regex-mode behavior preserved |

## 6. The "What applies at 12?" Regression Result

The key regression test (`domainNullTravelIntentMustNotTriggerRule`) asserts:
`TRAVEL_ALLOWANCE + hours=12 + domain=null` → **not** RULE_ENGINE, no Tagegeld.
This is the exact signature of the probabilistic failure. The unit test makes
the safety property deterministic regardless of what the LLM happens to produce.

At runtime: in the gated focused-corpus run the query was safe (E); across the
four gated runs today there were **0 forced ambiguous decisions**.

## 7. 128-Corpus Result (unchanged corpus, gate active)

LLM: **121/128 (94.5%)** — A 101 · E 12 · G 9 · B 2 · C 1 · D 3.
All six non-A/E/G rows route to retrieval with **no decision** (safe).
Regex: 93/128 — identical to the pre-gate run (gate is a proven no-op in regex mode).

## 8. 52-Case Language-Aware Result (gate active)

**E: 10/10 ambiguous safe · forced-wrong 0.** Number formats 11/11, equivalence
16/16, missing-step rows 4/4 retrieval, vocabulary 8/8. Four D rows (definitional
questions and two missing-step rows typed differently) — all retrieval-safe.

## 9. 27-Query Production Result

**LLM: 27/27** (maintained through the gate).

## 10. Dangerous Deterministic Answer Count

| Corpus | LLM forced-wrong | Regex forced-wrong |
|--------|------------------|-------------------|
| 128 expanded | **0** | 8 (unchanged, pre-existing) |
| 52 language-aware | **0** | — |
| 27-query production | **0** | 5 (unchanged, pre-existing) |

**Target (LLM = 0 on all retained corpora): met.**

## 11. Safe Retrieval Fallback Count (LLM mode)

128 corpus: 6 of 6 failures are safe retrieval fallbacks (3 domain/intent typing
misses, 1 reverse-lookup, 2 step-drop misses now failing safe). Focused: 4 of 4
safe. No LLM failure in any retained corpus produces a wrong deterministic answer.

## 12. Full Regression

| Suite | Result |
|-------|--------|
| platform-ai-runtime (incl. 9 new gate tests) | **319/319** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| **Total** | **353/353 green** |

## 13. Performance Impact

None measurable. The gate is one switch and one string comparison per request.
Parser warm latency in the gated runs: 1442–2287 ms avg (same range as before);
E2E rule path avg 14.3 s; retrieval path avg 151 s — all within the established
variance bands. No new LLM calls, no new I/O.

## 14. Is the Domain-Coherence Invariant Sufficient?

**Yes, for the known failure.** Every observed dangerous forced rule carried
`domain=null`; the gate deterministically refuses any rule whose intent lacks a
matching non-null domain. The remaining theoretical path — the LLM inventing a
*fully coherent* hallucination (matching intent + domain + parameters for a
question with no such meaning) — has never been observed across ~180 corpus
exposures in four languages over multiple runs, and no static gate can reject it
without adding keywords or a second LLM call, both excluded by design. The
deterministic boundary now enforces:

```
coherent domain + authorized intent + required parameters → RULE_ENGINE
anything else → retrieval / safe fallback
```

## 15. Recommendation for the Next Deployment Step

All acceptance criteria are now met with runtime evidence: 94.5% intent accuracy,
0 dangerous deterministic answers in LLM mode, 0 forced ambiguous decisions,
27/27 production, 353/353 regression, regex unchanged as fallback.

1. **Multilingual deployments: set `platform.ai.ollama.semantic-intent.enabled=true` now.**
2. **German municipal deployment: flip the default to the LLM parser** as the
   next deployment step — the evidence supports it; keep the regex parser as the
   emergency fallback (Ollama-outage determinism, auditability), and rollback
   remains a single flag.
3. Optional follow-ups (not blockers): retrieval-path grounding strictness,
   output-language externalization (`AiService` hardcoded German prompt),
   DomainClassifier YAML for non-German languages (only needed in regex mode).

The default was left unchanged (`false`) for this task, as instructed; regex was
not removed.
