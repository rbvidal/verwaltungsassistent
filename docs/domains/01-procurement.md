# Domain: Procurement (Vergabewesen)

## Purpose

Support municipal procurement officers in determining the correct procurement procedure for any given purchase. Ensure compliance with German and EU procurement law (GWB, VgV, UVgO, VOB/A, AV §55 LHO).

## Governing Regulations

| Regulation | Scope | Authority |
|-----------|-------|-----------|
| GWB (§§ 97–184) | Kartellvergaberecht — EU thresholds | Bundeskartellamt |
| VgV | Vergabeverordnung — services/supplies above EU thresholds | BMWK |
| UVgO | Unterschwellenvergabeordnung — below EU thresholds | BMWK |
| VOB/A | Construction procurement | DIN |
| AV §55 LHO Berlin | Berlin-specific procurement thresholds | Senatsverwaltung für Finanzen |
| BHO §55 | Federal budget code procurement rules | BMF |
| EU Directives 2014/24/EU, 2014/25/EU | EU procurement framework | European Commission |

## Required Structured Knowledge

### Threshold Tables
- **AV §55 LHO Berlin** — procurement thresholds by amount and category (✅ implemented)
- **EU Thresholds (VgV/GWB)** — periodic updates from EU Official Journal
- **VOB/A Thresholds** — construction-specific thresholds
- **UVgO Categories** — sub-threshold rules by procurement category

### Approval Chains
- Delegation levels by amount: Sachbearbeiter → Amtsleiter → Dezernent → Bürgermeister → Rat
- Required signatures per procurement value band
- Co-signing requirements (Finanzverwaltung, Rechnungsprüfungsamt)

### Tender Procedures
- Direct award (Direktauftrag): amount limits, required documentation
- Restricted tender (Beschränkte Ausschreibung): minimum bidders, publication requirements
- Open tender (Öffentliche Ausschreibung): publication deadlines, EU journal requirements
- Negotiated procedure (Verhandlungsvergabe): justification requirements

### Product Category Mappings
- IT equipment → IT-Dienstleistung thresholds
- Office supplies → Lieferung thresholds
- Construction work → Bauleistung thresholds
- Consulting services → Freiberufliche Dienstleistung thresholds

## Required Document Corpus

| Document Type | Examples | Priority |
|--------------|----------|----------|
| Procurement regulations | AV §55 LHO, GWB, VgV, UVgO, VOB/A | HIGH |
| Internal procurement policies | Dienstanweisung Vergabe, Delegationsregelungen | HIGH |
| Threshold tables | EU-Schwellenwerte 2024/2025, VOB/A-Schwellenwerte | HIGH |
| Template documents | Vergabevermerk-Vorlage, Angebotsvergleich | MEDIUM |
| Past procurement records | Vergabeakten, Angebotsauswertungen | MEDIUM |
| Product category catalogs | IT-Standardkatalog, Rahmenvertragskatalog | LOW |

## Deterministic Rules

### Rule: Procurement Threshold Check
```
Input: amount (EUR), category (Lieferung/Dienstleistung | Bauleistung)
Process:
  1. Load most recent ThresholdTable for governing regulation
  2. Normalize category via ThresholdTable.normalizeCategory()
  3. Find entry where amount >= minAmount AND (maxAmount is null OR amount < maxAmount)
  4. Return procedure + requirements
Output: ProcurementDecision(procedure, requirements, authority, effectiveDate)
```
Status: ✅ IMPLEMENTED (DecisionRouter.tryProcurementLookup)

### Rule: Approval Chain Determination
```
Input: amount (EUR), department
Process:
  1. Load ApprovalChainTable for department
  2. Find highest delegation level where amount <= maxAmount
  3. Return required approvers + signature requirements
Output: ApprovalDecision(approvers, signatures, escalationPath)
```
Status: ❌ NOT YET IMPLEMENTED

### Rule: Publication Requirement Check
```
Input: procedure, amount (EUR)
Process:
  1. If amount >= EU threshold → EU Journal mandatory
  2. If amount >= 25.000 AND procedure = Restricted → Ex-post publication
  3. If procedure = Open → Ex-ante publication
Output: PublicationRequirement(journal, deadline, mandatory)
```
Status: ❌ NOT YET IMPLEMENTED

## Retrieval Requirements

### Search Queries
- "Welches Vergabeverfahren bei [amount] € für [category]?"
- "Welche Wertgrenzen gelten nach AV §55 LHO?"
- "Darf ein IT-Auftrag über [amount] € direkt vergeben werden?"
- "Welche Unterlagen werden für einen Vergabevermerk benötigt?"

### Expected Retrieval Sources
- AV §55 LHO document chunks
- GWB/VgV text chunks
- Internal procurement policy documents
- Past procurement decisions by similar amount/category

### Retrieval Strategy
- Primary: RULE_ENGINE (deterministic threshold lookup)
- Fallback: HYBRID_RETRIEVAL (for procedure details, documentation requirements)
- Graph enhancement: GRAPH_REASONING (for procurement → department → approval chain relationships)

## Expected Decision Outputs

### Procurement Decision Package
```yaml
decision:
  procedure: "Beschränkte Ausschreibung"
  requirements:
    - "Ex-post-Veröffentlichung (ab 25.000 €)"
  authority: "Senatsverwaltung für Finanzen"
  regulation: "AV §55 LHO Berlin"
  effectiveDate: "2024-01-01"
  category: "Lieferung/Dienstleistung"
  amount: 18.500,00 €
  confidence: 0.98

recommendation: |
  Der Auftrag über 18.500 € für Büromaterial fällt unter die Kategorie
  Lieferung/Dienstleistung. Gemäß AV §55 LHO Berlin ist bei Beträgen
  zwischen 10.000 € und 100.000 € eine Beschränkte Ausschreibung
  durchzuführen. Eine Ex-post-Veröffentlichung ist ab 25.000 €
  erforderlich.

missingInformation:
  - EU-Schwellenwerte für 2024/2025 prüfen
  - Fachbereichsspezifische Vergaberichtlinien prüfen

nextSteps:
  - Vergabevermerk vorbereiten
  - Mindestens drei Angebote einholen
  - Angebotsvergleich dokumentieren
  - Vergabeentscheidung durch Amtsleiter freigeben lassen
```
