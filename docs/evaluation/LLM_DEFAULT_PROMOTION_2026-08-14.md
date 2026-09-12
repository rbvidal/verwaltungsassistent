# LLM Semantic Intent — Promotion to Default Production Path

**Date:** 2026-08-14
**Scope:** Flip the semantic-intent default to the LLM parser; verify the complete
application, the safety gate, the rollback override, and the full test suite.
Regex parser retained as emergency fallback.

## 1. Exact Configuration Change

`verwaltungsassistent-web/src/main/resources/application.yml` (and the mirrored
test configuration, see §11):

```yaml
platform:
  ai:
    ollama:
      semantic-intent:
        enabled: ${SEMANTIC_INTENT_ENABLED:true}
```

- Default: **true** → LLM semantic parser is the primary parser.
- Override: `SEMANTIC_INTENT_ENABLED=false` (environment) or the equivalent
  property → immediate rollback to the regex path. No Java hardcoding — the
  mode is selected by the same `@ConditionalOnProperty` that always governed it.
- `RegexSemanticIntentParser` is unchanged and still registered.

## 2. Application Startup Result

The packaged application (`verwaltungsassistent-web-1.0.0-RC2.jar`) started
successfully with the default configuration against real infrastructure:

- `Started VerwaltungsassistentApplication in 10.9 s` (Tomcat, port 8081)
- Spring condition report: `SemanticIntentConfig#llmSemanticIntentParser matched
  (@ConditionalOnProperty platform.ai.ollama.semantic-intent.enabled=true)` —
  the LLM parser bean is created from the application.yml default.
- HTTP surface live: `GET /login` → 200, `GET /dashboard` → 302 (security active).
- The DecisionRouter constructor/wiring issue from the gate experiment did not
  recur (the `@Autowired` 4-arg constructor resolves correctly in the real app).

## 3. Full Infrastructure Status

| Component | State |
|-----------|-------|
| PostgreSQL (`va-postgres` container) | up (app ran with dev profile/H2 — see §11) |
| Qdrant (`mda-qdrant`) | up, `mda_chunks` collection, 634 vectors |
| Neo4j (`mda-neo4j`) | up (HTTP + bolt), retrieval routes GRAPH_REASONING |
| Ollama | up — generator `qwen2.5:14b`, verifier/parser `qwen2.5:7b` (claim_batch), embeddings `nomic-embed-text` |

## 4. German Smoke Tests (default mode, full pipeline E2E)

| Query | Intent / domain / params | Result |
|-------|--------------------------|--------|
| "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?" | TRAVEL_ALLOWANCE / TRAVEL / hours=12 | **Tagegeld 12 €** (RULE_ENGINE) |
| "Wie hoch ist das Gehalt für EG 9b Stufe 3?" | SALARY_LOOKUP / HR / EG 9b, 3 | **4117.53 €** (RULE_ENGINE) |
| "Welche Vergabeart gilt für einen Auftrag über 8.000 Euro?" | PROCUREMENT_THRESHOLD / PROCUREMENT / 8000 | **Direktauftrag** (RULE_ENGINE) |

## 5. EN/PT/FR Smoke Tests (default mode)

| Query | Intent / domain / params | Result |
|-------|--------------------------|--------|
| EN "What is the meal allowance for a 12-hour business trip?" | TRAVEL_ALLOWANCE / TRAVEL / 12 | **Tagegeld 12 €** |
| PT "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?" | TRAVEL_ALLOWANCE / TRAVEL / 12 | **Tagegeld 12 €** |
| FR "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?" | TRAVEL_ALLOWANCE / TRAVEL / 12 | **Tagegeld 12 €** |
| EN "What is the salary for EG 9b step 5?" | SALARY_LOOKUP / HR / EG 9b, 5 | **4480.00 €** |
| EN "Which procurement procedure applies to a contract of 8,000 euros?" (comma thousands) | PROCUREMENT_THRESHOLD / PROCUREMENT / 8000 | **Direktauftrag** |

Language inference: de/en/pt/fr identified correctly on all four travel rows.

## 6. Deterministic Rule Results

All 8 rule-family smoke queries routed RULE_ENGINE with a coherent domain and
the correct authoritative value. Every rule trigger satisfied the full safety
boundary: coherent domain + authorized intent + required parameters.

## 7. Retrieval Result

"Welche Baugenehmigung benötige ich für ein Einfamilienhaus in Berlin?" →
GRAPH_REASONING, **15 source citations**, no forced rule. (grounded=false on
retrieval answers is the pre-existing strict-grounding behavior, unchanged.)

## 8. Ambiguous-Question Safety Result

"What applies at 12?" (default mode, full pipeline) → **GRAPH_REASONING, no
Tagegeld** — this run the LLM produced GENERAL/no params; the domain-coherence
gate deterministically guarantees the same outcome even if the LLM produces
TRAVEL_ALLOWANCE + hours + domain=null (unit-tested in the 319-test runtime
suite). Unsupported question: GRAPH_REASONING, no forced answer.

## 9. Rollback-to-Regex Result

- Real app restarted with `SEMANTIC_INTENT_ENABLED=false` → started successfully
  (12.2 s); condition report: `llmSemanticIntentParser: Did not match …
  found different value in property 'platform.ai.ollama.semantic-intent.enabled'`
  → primary parser = regex.
- `RollbackModeSmokeTest` (2/2): regex parser instance verified, DE travel
  question answered through the legacy path (Tagegeld 12 €, full E2E).
- Default restored: app restarted without the override → condition `matched`
  again (LLM parser primary). One-flag rollback confirmed in both directions.

## 10. Full Regression Result

| Suite | Result |
|-------|--------|
| platform-ai-runtime | **319/319** |
| DefaultModeSmokeTest (12: parser selection + 11-question matrix) | **12/12** |
| RollbackModeSmokeTest | **2/2** |
| EkpVerifierCorrectnessAuditTest | **20/20** |
| SemanticIntentBenchmarkTest | **4/4** |
| SemanticRoutingEquivalenceTest | **5/5** |
| SemanticDomainPropagationTest | **5/5** |
| **Total** | **367/367 green** |

No test was modified to obtain a green result.

## 11. Unexpected Behavior

1. **Test-classpath config shadowing (found and fixed):** the first default-mode
   smoke run failed because `src/test/resources/application.yml` shadows the main
   `application.yml` on the test classpath and did not carry the new property —
   the test context silently selected the regex parser. Fixed by mirroring the
   production default in the test configuration. The real application (jar)
   never had this issue (condition report proved `matched` from the start).
   Lesson: whenever the production default changes, the test configuration must
   be kept in sync.
2. **PostgreSQL schema gap (pre-existing, unchanged):** the real `verwaltungsassistent` database
   has no tables and the app has no Flyway migrations, so the full app run used
   the dev profile (H2) — exactly as in every prior runtime validation. Qdrant,
   Neo4j and both LLMs were the real services. This deployment gap predates this
   task and was not touched (no architectural changes allowed).

## Conclusion

The LLM semantic-intent parser is now the default production path:
`semantic-intent.enabled=true`, overridable by environment variable. The
complete application starts with real infrastructure, all 11 smoke questions
behave correctly (8 deterministic rule answers with coherent domains, ambiguous
and unsupported questions safe, retrieval works), the safety gate holds, the
one-flag rollback to the regex path works in both directions, and the full
regression is green at 367/367. Regex remains intact as the emergency fallback.
