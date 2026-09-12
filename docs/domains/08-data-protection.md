# Domain: Data Protection (Datenschutz)

## Purpose
Support data protection officers in assessing data processing legality, conducting data protection impact assessments (DPIA), handling data subject requests, and ensuring GDPR/DSGVO compliance.

## Governing Regulations
- DSGVO / GDPR: Datenschutz-Grundverordnung (EU 2016/679)
- BDSG: Bundesdatenschutzgesetz
- LDSG: State data protection laws
- JI-Richtlinie: Law enforcement data protection directive
- TTDSG: Telecommunications-Telemedia Data Protection Act
- E-Privacy Directive 2002/58/EC

## Required Structured Knowledge

### Processing Lawfulness Bases (Art. 6 DSGVO)
- Art. 6(1)(a): Consent
- Art. 6(1)(b): Contract performance
- Art. 6(1)(c): Legal obligation
- Art. 6(1)(d): Vital interests
- Art. 6(1)(e): Public interest / official authority
- Art. 6(1)(f): Legitimate interests (with balancing test)

### Data Subject Rights
| Right | Article | Response Deadline |
|-------|---------|-------------------|
| Access (Auskunft) | Art. 15 | 1 month |
| Rectification (Berichtigung) | Art. 16 | 1 month |
| Erasure (Löschung) | Art. 17 | 1 month |
| Restriction (Einschränkung) | Art. 18 | 1 month |
| Portability (Datenübertragbarkeit) | Art. 20 | 1 month |
| Objection (Widerspruch) | Art. 21 | 1 month |

### Special Category Data (Art. 9 DSGVO)
- Race/ethnicity, political opinions, religious beliefs
- Trade union membership, genetic/biometric data, health data
- Sex life/sexual orientation, criminal convictions (Art. 10)

### DPIA Thresholds
- Systematic and extensive profiling
- Large-scale special category data
- Systematic monitoring of public areas
- New technologies with high risk
- Required since 25.05.2018 for all high-risk processing

## Deterministic Rules (Status)
| Rule | Status |
|------|--------|
| Lawfulness basis assessment by processing purpose | ❌ |
| DPIA necessity check (thresholds) | ❌ |
| Data subject request deadline tracking | ❌ |
| Retention period calculation by document type | ❌ |
| Data breach notification deadline (72h, Art. 33) | ❌ |

## Expected Decision Output
```yaml
decision:
  processingAllowed: true
  lawfulBasis: "Art. 6(1)(e) DSGVO — öffentliche Aufgabe"
  dpiaRequired: false
  specialCategoryData: false
  dataSubjectRights:
    - "Auskunft (Art. 15)"
    - "Berichtigung (Art. 16)"
    - "Löschung (Art. 17)"
  retentionPeriod: "10 Jahre nach Verfahrensabschluss"
  breachNotificationDeadline: "72 Stunden ab Kenntnis (Art. 33)"
```
