# Structured-Knowledge Architecture — Closure Note (Stages 1–6)

Concise final-state record. Companion: [ADR-007 knowledge model](adr/adr-007-knowledge-model.md),
[date/time convention](conventions/date-time-convention.md).

## Architecture

Rule-first deterministic decisions over document-derived structured knowledge; hybrid
retrieval/RAG remains for narrative questions.

```
document → ingestion (chunks/FTS/Qdrant) → optional LLM extraction (scheduled worker,
strict JSON, fence normalization) → validation (structural, provenance, numeric-support,
temporal, batched entailment) → structured_knowledge_items (ACTIVE/CANDIDATE/REJECTED/
SUPERSEDED, provenance) → KnowledgeRegistry → generic RULE/THRESHOLD/TABLE/EXCEPTION
evaluators → DecisionRouter → deterministic decision (documentId@version) | legacy fallback | RAG
```

- ACTIVE-only execution; unknown temporal applicability → CANDIDATE (never ACTIVE).
- Canonical facts: `amount, hours, distanceKm, salaryGrade, salaryStep, mode`; BigDecimal,
  LE/LT explicit, currency as semantic data; no German/locale logic in the core.
- Conflicts (multiple matching thresholds/tables/rules) → explicit INDETERMINATE; legacy
  fallback is intentional and load-bearing.
- Live extraction proven end-to-end with qwen2.5:14b on AV §55 LHO, BRKG, BMG, PAuswG.
- `RuleEngine` (dead code) removed in Stage 3F; legacy tables/initializer/router loops retained.

## Procurement Stage-6 conclusion

Legacy procurement removal is **NOT safe** with the current corpus. The authoritative AV §55 LHO
text lacks the legacy demo table's fine band structure, and extraction repeatedly produced
overlapping 0-based bands (see Stage 6A–6C reports). The generic THRESHOLD evaluator is capable
of the required non-overlapping representation; the gap is corpus/extraction, not an evaluator
defect. Do not remove the legacy procurement path with the current corpus. Removal requires a
richer authoritative procurement corpus able to represent the full decision space, followed by
live equivalence evidence.

## Retained extraction-contract rule (Stage 6B)

`THRESHOLD-BANDS` in KnowledgeExtractionPrompt: bounds must be lückenlos (exhaustive) and
non-overlapping within one item, top band open (`max:null`). Retained as a general extraction
improvement consistent with the validator's non-overlap rule.

## Known limitations

- **Corpus**: current PAuswG lacks the fee schedule (relocated in the 2026 amendment); BRKG
  rates largely reference-based (EStG); no travel/salary documents in the live corpus.
- **Extraction-quality**: unit-role misassignment (source-supported numbers in the wrong
  semantic role — prompt-mitigated); threshold band guidance not always followed by the model.
- **Architectural**: no specificity mechanism for nested threshold overlaps (safe
  conflict→legacy bridge); typed DecisionResult variants; accommodation query qualifiers pending.

## Future work (only if product requirements justify)

1. Required for product: none blocked by this work; RAG + legacy fallback remain fully functional.
2. Optional: per-domain legacy removal after live equivalence evidence (procurement first, only
   with a richer corpus); query-understanding qualifiers (accommodation/overnight); presentation
   rendering cleanup (€ via web layer).
3. Do not pursue: further procurement corpus work or extraction tuning unless a concrete product
   requirement provides a richer authoritative procurement corpus.
