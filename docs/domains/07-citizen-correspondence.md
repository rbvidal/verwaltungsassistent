# Domain: Citizen Correspondence (Bürgerkorrespondenz)

## Purpose
Support case workers in drafting legally sound responses to citizen inquiries, applications, and complaints. Ensure compliance with administrative procedure law, response deadlines, and data protection requirements.

## Governing Regulations
- VwVfG: Verwaltungsverfahrensgesetz (administrative procedure)
- VwGO: Verwaltungsgerichtsordnung (administrative court procedure)
- BDSG / LDSG: Data protection laws
- E-Government-Gesetz (EGovG)
- OZG: Onlinezugangsgesetz
- IFG / UIG / VIG: Freedom of information / environmental / consumer information acts

## Required Structured Knowledge

### Response Deadline Tables
| Request Type | Statutory Deadline | Regulation |
|-------------|-------------------|------------|
| Simple inquiry | 1 month | § 25 VwVfG |
| Complex application | 3 months | § 42a VwVfG |
| IFG request | 1 month (extendable) | § 7 IFG |
| Widerspruch (appeal) | 3 months | § 73 VwGO |
| Petition | response "within reasonable time" | Art. 17 GG |

### Correspondence Templates
- Empfangsbestätigung (acknowledgment of receipt)
- Zwischenbescheid (interim notice)
- Anhörungsschreiben (hearing letter per § 28 VwVfG)
- Ablehnungsbescheid (rejection notice with legal remedies)
- Bewilligungsbescheid (approval notice with conditions)
- Widerspruchsbescheid (appeal decision)

### Legal Requirements per Letter Type
- Rechtsbehelfsbelehrung (legal remedies instruction)
- Begründungspflicht (duty to state reasons, § 39 VwVfG)
- Anhörung before adverse decision (§ 28 VwVfG)
- Zustellungsnachweis requirements
- Electronic delivery rules (§ 3a VwVfG, EGovG)

## Retrieval Requirements
- "Muster Ablehnungsbescheid Baugenehmigung mit Rechtsbehelfsbelehrung"
- "Frist für IFG-Auskunftsersuchen Berlin"
- "Was muss in einer Rechtsbehelfsbelehrung stehen?"
- "Elektronische Zustellung nach EGovG Voraussetzungen"

## Deterministic Rules (Status)
| Rule | Status |
|------|--------|
| Statutory deadline by request type | ❌ |
| Required sections by letter type | ❌ |
| Legal remedies text auto-generation | ❌ |
| Jurisdiction determination (Widerspruchsbehörde) | ❌ |
| Fee calculation (Verwaltungsgebühren) | ❌ |

## Expected Decision Output
```yaml
decision:
  letterType: "Ablehnungsbescheid"
  requiredSections:
    - "Verfügender Teil (Tenor)"
    - "Begründung (§ 39 VwVfG)"
    - "Rechtsbehelfsbelehrung (§ 73 VwGO)"
    - "Kostenentscheidung"
  deadline: "2024-12-15"
  legalRemedy: "Widerspruch innerhalb 1 Monats bei [Behörde]"
  fee: "50,00 € gemäß VwGebO"
```
