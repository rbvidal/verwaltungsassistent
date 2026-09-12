# Domain: Grants (Zuwendungswesen)

## Purpose
Support grant officers in determining eligibility, calculating amounts, checking compliance with funding conditions, and generating grant decision documents.

## Governing Regulations
- BHO/LHO §§ 23, 44: Zuwendungen (grants and subsidies)
- VV-BHO/LHO: Administrative regulations for grants
- ANBest-P/G/K: Allgemeine Nebenbestimmungen for project/institutional/corporate grants
- EU State Aid Rules (Art. 107-109 TFEU)
- De-minimis Regulation (EU 1407/2013)
- Specific funding program guidelines (Förderrichtlinien)

## Required Structured Knowledge

### Grant Types
- **Projektförderung**: project-based, time-limited
- **Institutionelle Förderung**: institutional, ongoing
- **Fehlbedarfsfinanzierung**: deficit funding
- **Anteilsfinanzierung**: proportional funding
- **Festbetragsfinanzierung**: fixed amount
- **Vollfinanzierung**: full funding (rare, requires justification)

### Funding Rate Tables
- Standard municipal co-funding: 60-90% depending on program
- De-minimis threshold: 300.000 € over 3 fiscal years
- Eligible cost categories (Personalkosten, Sachkosten, Reisekosten, Overhead)

### Compliance Rules
- Zweckbindungsfrist: purpose-binding period (typically 5-25 years for buildings)
- Verwendungsnachweis deadline: usually 6 months after project end
- Mittelabruf: drawdown schedule
- Besserstellungsverbot: prohibition on preferential treatment of grant recipients
- Vergaberecht compliance for grant-funded procurement

## Deterministic Rules (Status)
| Rule | Status |
|------|--------|
| De-minimis threshold check (300k over 3 years) | ❌ |
| Funding rate by program and project type | ❌ |
| Eligible cost category validation | ❌ |
| Zweckbindungsfrist calculation | ❌ |
| Verwendungsnachweis deadline tracking | ❌ |

## Expected Decision Output
```yaml
decision:
  grantType: "Projektförderung — Anteilsfinanzierung"
  maxAmount: 150.000 €
  fundingRate: "80% der zuwendungsfähigen Ausgaben"
  deMinimisOK: true
  requiredDocuments:
    - "Projektbeschreibung mit Kosten- und Finanzierungsplan"
    - "De-minimis-Erklärung"
    - "Vergaberechtliche Prüfung"
  purposeBinding: "15 Jahre (Baumaßnahme)"
  authority: "Senatsverwaltung für Stadtentwicklung"
```
