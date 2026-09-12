# Domain: Budget (Haushaltswesen)

## Purpose
Support budget officers in commitment approvals, budget code lookups, and expenditure compliance checks against the municipal budget plan.

## Governing Regulations
- BHO / LHO: Bundeshaushaltsordnung / Landeshaushaltsordnung
- GemHVO: Gemeindehaushaltsverordnung
- KomHKV: Kommunale Haushalts- und Kassenverordnung
- AV §9 LHO: Commitment authority delegations

## Required Structured Knowledge

### Commitment Authority Limits
| Role | Max Amount | Co-Sign Required Above |
|------|-----------|----------------------|
| Sachbearbeiter | 1.000 € | 500 € |
| Sachgebietsleiter | 5.000 € | 2.500 € |
| Amtsleiter | 50.000 € | 25.000 € |
| Dezernent | 250.000 € | 100.000 € |
| Bürgermeister | 1.000.000 € | 500.000 € |
| Rat | Unlimited | — |

### Budget Codes (Produktsachkonten)
- Pattern: XX.XX.XX / YYYYYYYY (Produkt / Sachkonto)
- Investment vs. operational expenditure codes
- Earmarked funds (Zweckgebundene Mittel)
- Budget carryover rules (Haushaltsausgabereste)

### Fiscal Year Rules
- Jährlichkeitsprinzip: annuality principle
- Haushaltsausgabereste: carryover limits
- Überplanmäßige/Außerplanmäßige Ausgaben: approval chain
- Sperrvermerke: spending freezes

## Deterministic Rules (Status)
| Rule | Status |
|------|--------|
| Commitment authority by role and amount | ❌ |
| Budget code validation | ❌ |
| Fiscal year compliance | ❌ |
| Over-budget approval escalation | ❌ |

## Expected Decision Output
```yaml
decision:
  requiresApproval: true
  approver: "Amtsleiter"
  coSigner: "Kämmerer"
  budgetCode: "12.34.01 / 54321000"
  available: true
  carryover: false
  regulation: "AV §9 LHO i.V.m. §39 GemHVO"
```
