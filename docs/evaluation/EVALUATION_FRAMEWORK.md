# Evaluation Framework — Municipal Decision Assistant

**Version**: 1.0
**Date**: 2026-08-07

## Overview

This framework defines the methodology for measuring the quality of the municipal decision assistant across four dimensions: retrieval quality, rule accuracy, reasoning quality, and decision package completeness.

---

## 1. Gold-Standard Questions

### Procurement Domain

| # | Question | Expected Retrieval | Expected Rule | Expected Authority | Expected Recommendation |
|---|----------|-------------------|---------------|-------------------|------------------------|
| P1 | "Wir müssen Büromaterial für 8.500 € beschaffen. Welches Verfahren?" | AV §55 LHO | Procurement threshold: 1.000-10.000 € → Direktauftrag | AV §55 LHO Berlin | Direktauftrag mit Vergabevermerk und drei Vergleichsangeboten |
| P2 | "Darf ein IT-Auftrag über 18.000 € direkt vergeben werden?" | AV §55 LHO | Procurement threshold: 10.000-100.000 € → Beschränkte Ausschreibung | AV §55 LHO Berlin | Beschränkte Ausschreibung, Ex-post-Veröffentlichung ab 25.000 € |
| P3 | "Welche Wertgrenzen gelten für Bauleistungen nach AV §55 LHO?" | AV §55 LHO | Threshold overview: Bauleistung 0-20k Direktauftrag, 20k-200k Beschränkte Ausschreibung | AV §55 LHO Berlin | Übersicht der Wertgrenzen für Bauleistungen |
| P4 | "Auftrag über 250.000 € für Straßenbau — EU-weit oder national?" | VgV, GWB, AV §55 LHO | EU threshold check: 100.000+ → EU-weit | VgV, EU 2014/24/EU | Öffentliche Ausschreibung / EU-weites Verfahren |
| P5 | "Rahmenvertrag für IT-Dienstleistungen 45.000 € — Verfahren?" | AV §55 LHO, VgV | 10.000-100.000 → Beschränkte Ausschreibung | AV §55 LHO | Beschränkte Ausschreibung, Ex-post-Veröffentlichung |

### Travel Expenses Domain

| # | Question | Expected Retrieval | Expected Rule | Expected Authority | Expected Recommendation |
|---|----------|-------------------|---------------|-------------------|------------------------|
| T1 | "Dienstreise von 9:00 bis 21:00 Uhr — wieviel Tagegeld?" | BRKG | Travel allowance: ≥11h → 12 € | BRKG | Tagegeld 12 € (Abwesenheit über 11 Stunden) |
| T2 | "Dienstreise 250 km mit Privat-PKW — Kilometergeld?" | BRKG | Mileage: 0.35 €/km × 250 = 87.50 € | BRKG | Kilometerpauschale: 87.50 € |
| T3 | "Übernachtung in Berlin mit Hotelbeleg — Höchstbetrag?" | BRKG | Accommodation: with receipt → 80 € | BRKG | Übernachtung mit Beleg: 80 € |
| T4 | "Dreitägige Dienstreise nach Brüssel — Tagegeld?" | BRKG | International: Brussels 24h → 47 €/day | BRKG | Tagegeld: 3 × 47 € = 141 € |
| T5 | "Anreisetag 14:00, Abreisetag 12:00, 2 Übernachtungen — Berechnung?" | BRKG | Mixed: arrival day 12 €, full day 24 €, departure day 12 € | BRKG | Anreisetag 12 € + voller Tag 24 € + Abreisetag 12 € = 48 € + 2 × 80 € Übernachtung |

### HR Domain

| # | Question | Expected Retrieval | Expected Rule | Expected Authority | Expected Recommendation |
|---|----------|-------------------|---------------|-------------------|------------------------|
| H1 | "Was verdient ein Sachbearbeiter EG 9a Stufe 3?" | TV-L 2025 | Salary lookup: EG 9a S3 → 3.900 € | TV-L, TdL | 3.900 € monatlich (TV-L 2025) |
| H2 | "EG 9 Stufe 3: Was ist die Gehaltserhöhung von 2024 auf 2025?" | TV-L 2024+2025 | Increase: 3600→3800 = +200 € (5.56%) | TV-L | Erhöhung von 3.600 € auf 3.800 € (+200 €, +5.56%) |
| H3 | "EG 11 Stufe 3 nach TV-L — aktuelles Gehalt?" | TV-L 2025 | Salary lookup: EG 11 S3 → 4.875,49 € | TV-L, TdL | 4.875,49 € monatlich |
| H4 | "EG 13 Stufe 3 — Gehalt und wieviel Urlaubsanspruch?" | TV-L 2025, UrlVO | Salary: EG 13 S3 → 5.467,76 €; Vacation: 30 Tage standard | TV-L, UrlVO | 5.467,76 €, 30 Tage Urlaub |

### Building Permits Domain

| # | Question | Expected Retrieval | Expected Rule | Expected Authority | Expected Recommendation |
|---|----------|-------------------|---------------|-------------------|------------------------|
| B1 | "Brauche ich für einen Carport (25 m²) in Berlin eine Baugenehmigung?" | BauO Bln | Verfahrensfreiheit: Carport ≤ 30 m² → verfahrensfrei (mit Einschränkungen) | BauO Bln § 63 | Verfahrensfrei, aber Abstandsflächen prüfen und Bebauungsplan beachten |
| B2 | "Welche Unterlagen für Bauantrag Einfamilienhaus?" | BauO Bln, BauVorlV | Document checklist by project type | BauVorlV | Lageplan, Bauzeichnungen, Baubeschreibung, Statik, Energieausweis |
| B3 | "Abstandsfläche bei 6 m Gebäudehöhe in Berlin?" | BauO Bln | Abstandsfläche: 0.4 × 6 m = 2.40 m | BauO Bln § 6 | Mindestens 2,40 m Abstandsfläche |

---

## 2. Evaluation Metrics

### Retrieval Quality

| Metric | Target | Measurement |
|--------|--------|------------|
| Precision@5 | ≥ 0.70 | Relevant docs in top 5 / 5 |
| Recall@10 | ≥ 0.60 | Relevant docs found / total relevant in corpus |
| MRR (Mean Reciprocal Rank) | ≥ 0.80 | 1/rank of first relevant result |
| Retrieval latency | < 500 ms | Time from query to results |

### Rule Accuracy

| Metric | Target | Measurement |
|--------|--------|------------|
| Rule Trigger Rate | ≥ 0.85 | Correct rule triggered / total queries |
| Rule Result Correctness | ≥ 0.95 | Correct deterministic result / total rule hits |
| Rule Coverage | ≥ 0.50 | Queries answerable by rules / total queries |

### Grounding Quality

| Metric | Target | Measurement |
|--------|--------|------------|
| Citation Accuracy | ≥ 0.80 | Citations supporting claims / total citations |
| Grounding Score | ≥ 0.60 | Grounded answers / total answers |
| Source Coverage | ≥ 0.50 | Coverage score ≥ 0.5 / total responses |

### Reasoning Quality

| Metric | Target | Measurement |
|--------|--------|------------|
| Hallucination Rate | ≤ 0.10 | Fabricated claims / total claims |
| Recommendation Relevance | ≥ 0.80 | Human-judged relevance (scale 1-5) |
| Completeness | ≥ 0.70 | All required sections present / 7 sections |

### Decision Package Quality

| Metric | Target | Measurement |
|--------|--------|------------|
| Section Completeness | ≥ 0.85 | Sections with content / 7 sections |
| Authority Citations | ≥ 1.0 per package | Average authorities cited per decision |
| Evidence Items | ≥ 2.0 per package | Average evidence items per decision (when available) |
| Human Approval Ready | ≥ 0.80 | Packages with Freigabe section complete |

### Latency

| Metric | Target | Measurement |
|--------|--------|------------|
| Rule Engine latency | < 10 ms | Time to rule evaluation |
| Retrieval latency | < 500 ms | Time to search results |
| LLM inference | < 15 s | Time to AI response |
| Total pipeline | < 20 s | End-to-end from query to decision package |

---

## 3. Test Scenarios

### Scenario 1: Procurement — Office Supplies
```
Question: "Büromaterial für 4.200 € beschaffen — welches Verfahren?"
Expected:
  - Domain: PROCUREMENT
  - Strategy: RULE_ENGINE
  - Decision: Direktauftrag
  - Requirements: Vergabevermerk, drei Vergleichsangebote
  - Authority: AV §55 LHO Berlin
  - Confidence: ≥ 0.95
```

### Scenario 2: Travel — Multi-Day Conference Trip
```
Question: "Drei Tage Konferenz in München mit Übernachtung — Tagegeld?"
Expected:
  - Domain: TRAVEL
  - Strategy: RULE_ENGINE
  - Decision: 3 × 24 € Tagegeld + 3 × 80 € Übernachtung
  - Authority: BRKG
  - Confidence: ≥ 0.95
```

### Scenario 3: HR — Salary Progression
```
Question: "Gehaltserhöhung EG 10 Stufe 2 von 2024 auf 2025?"
Expected:
  - Domain: HR
  - Strategy: RULE_ENGINE
  - Decision: Von 4.000 € auf 4.231,36 € (+231,36 €, +5.78%)
  - Authority: TV-L, TdL
  - Confidence: ≥ 0.95
```

### Scenario 4: Building — Carport Inquiry
```
Question: "Carport mit 20 m² Grundfläche in Berlin — Genehmigung?"
Expected:
  - Domain: BUILDING
  - Strategy: HYBRID_RETRIEVAL (no deterministic rule yet)
  - Evidence: BauO Bln § 63 (Verfahrensfreiheit)
  - Recommendation: Verfahrensfrei wenn Bebauungsplan eingehalten
  - Confidence: ≥ 0.60
```

### Scenario 5: Cross-Domain — Conference Procurement
```
Question: "Konferenzraum für 60 Personen mieten, Catering 2.800 € — kombiniertes Verfahren?"
Expected:
  - Domain: PROCUREMENT
  - Strategy: RULE_ENGINE for procurement + HYBRID_RETRIEVAL for event regulations
  - Decision: Lieferung/Dienstleistung 2.800 € → Direktauftrag mit Vergabevermerk
  - Evidence: AV §55 LHO, event-specific regulations
```

---

## 4. Running the Evaluation

### Prerequisites
- Application running with `dev` profile
- Ollama running with `qwen2.5:14b` model
- Test documents indexed (procurement regulations, BRKG, TV-L table)

### Execute Evaluation
```bash
# Run the evaluation test suite
mvn test -pl verwaltungsassistent-web -Dtest=EvaluationTest -Dsurefire.failIfNoSpecifiedTests=false

# Or test individual scenarios via curl
curl -X POST http://localhost:8081/cases/{caseId}/decision/analyze?debug=true
```

### Collect Metrics
1. Retrieval metrics: From PipelineProfiler debug output
2. Rule accuracy: Compare DecisionRouter output to gold standard
3. Grounding quality: Check grounded flag + coverage score
4. Hallucination: Manual review of AI claims vs. cited sources
5. Latency: From PipelineProfiler timing data

---

## 5. Baseline Results (to be populated)

| Domain | Questions | Rule Accuracy | Retrieval P@5 | Grounding | Latency (ms) |
|--------|----------|--------------|---------------|-----------|-------------|
| Procurement | 5 | — | — | — | — |
| Travel | 5 | — | — | — | — |
| HR | 4 | — | — | — | — |
| Building | 3 | — | — | — | — |
| Cross-domain | 1 | — | — | — | — |

---

## 6. Continuous Evaluation

Each sprint, execute the full gold-standard question set and record results in `docs/evaluation/results/YYYY-MM-DD.md`. Track regression by comparing to the previous sprint baseline.

### Regression Thresholds
- Rule accuracy drop > 5% → BLOCKER
- Retrieval P@5 drop > 10% → HIGH
- Hallucination rate increase > 5% → CRITICAL
- Latency increase > 50% → MEDIUM
