# Domain: Records Management (Schriftgutverwaltung / Aktenführung)

## Purpose
Support records managers in classifying, retaining, and disposing of municipal records according to retention schedules, file plan structures, and archival requirements.

## Governing Regulations
- BArchG / LArchG: Federal/State Archive Laws
- VwVfG: Administrative procedure (records of proceedings, § 29)
- GemHVO / KomHKV: Municipal accounting (receipt retention)
- HGB § 257 / AO § 147: Commercial and tax retention periods
- E-Government-Gesetz (EGovG): Electronic records management
- DOMEA / DOMEA 2.1: Document Management and Electronic Archiving standard
- ISO 15489: Records management standard

## Required Structured Knowledge

### Retention Periods by Record Type
| Record Type | Retention | Regulation |
|------------|-----------|------------|
| Verträge (contracts) | 10 years after end | § 147 AO, § 257 HGB |
| Personalakten (personnel files) | 5 years after exit | TV-L |
| Bauakten (building permits) | Permanent | LArchG |
| Rechnungen (invoices) | 10 years | § 147 AO |
| Vergabeakten (procurement files) | 5 years (EU), 3 years (national) | GWB |
| Sitzungsprotokolle (meeting minutes) | Permanent | GemO |
| Sozialakten (social welfare files) | 10 years after last action | SGB X |
| Steuerunterlagen (tax documents) | 10 years | § 147 AO |
| Kassenbelege (cash receipts) | 10 years | § 147 AO |

### File Plan Structure (Aktenplan)
- Pattern: XX.YY.ZZZ (Hauptgruppe.Gruppe.Untergruppe)
- 0: Allgemeine Verwaltung
- 1: Finanzen
- 2: Personal
- 3: Recht und öffentliche Sicherheit
- 4: Bildung und Kultur
- 5: Soziales
- 6: Bauen und Umwelt
- 7: Wirtschaft und Verkehr
- 8: Gesundheit und Sport

### Disposal Rules
| Action | Trigger | Authority |
|--------|---------|-----------|
| Aussonderung (selection) | Retention expired | Archive |
| Anbietung (offer to archive) | Selection complete | Records manager |
| Bewertung (appraisal) | Archival value assessment | Archivist |
| Vernichtung (destruction) | Offer rejected or retention expired | Records manager |
| Übernahme (transfer to archive) | Appraisal complete | Archive |

## Deterministic Rules (Status)
| Rule | Status |
|------|--------|
| Retention period by record type | ❌ |
| Archival value appraisal checklist | ❌ |
| Disposal deadline calculation | ❌ |
| File plan code validation | ❌ |
| Electronic signature requirements (eIDAS) | ❌ |

## Expected Decision Output
```yaml
decision:
  recordType: "Vergabeakte"
  retentionPeriod: "5 Jahre nach Abschluss des Vergabeverfahrens"
  retentionUntil: "2029-06-30"
  filePlanCode: "1.20.001"
  archivalValue: "Sampling — alle 10. Akte"
  disposalDate: "2030-01-01 (nach Aussonderung)"
  regulation: "GWB §§ 97-184, AV §55 LHO"
```
