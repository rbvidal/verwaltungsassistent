# Domain: Travel Expenses (Reisekosten)

## Purpose

Support municipal employees and HR departments in calculating travel expense reimbursements according to BRKG/LRKG. Determine per-diem allowances, mileage reimbursement, accommodation limits, and ancillary travel costs.

## Governing Regulations

| Regulation | Scope | Authority |
|-----------|-------|-----------|
| BRKG | Bundesreisekostengesetz — federal travel expense law | Bundesministerium des Innern |
| LRKG | Landesreisekostengesetz — state-level travel expense laws | Landesinnenministerien |
| BRKGVwV | Administrative regulation to BRKG | BMI |
| EStG §3 Nr. 13, 16 | Tax treatment of travel reimbursements | BMF |

## Required Structured Knowledge

### Allowance Tables
- **BRKG domestic meal allowances** — by absence duration (✅ implemented)
- **BRKG international allowances** — by country and city
- **LRKG state-specific tables** — Berlin, Brandenburg, etc.
- **Mileage rates** — PKW (0.35 €/km), motorcycle, bicycle (✅ implemented)
- **Accommodation limits** — with/without receipt, domestic/international (✅ implemented)

### Duration-Based Rules
- < 8 hours: no meal allowance (with exceptions)
- 8–11 hours: reduced allowance (6 € domestic)
- 11–24 hours: full day allowance (12 € domestic)
- 24+ hours: full 24-hour allowance (24 € domestic)
- Arrival/departure day with overnight: 12 € regardless of hours
- Multi-day trips: per-day calculation

### Distance-Based Rules
- Kilometerpauschale PKW: 0.35 €/km (✅ implemented)
- Kilometerpauschale other motor vehicle: 0.20 €/km
- Public transport: actual costs with receipt
- Flight: actual costs, economy class unless justified
- Taxi: only with justification

### Ancillary Expenses
- Parking fees: reimbursable
- Road tolls: reimbursable
- Communication costs: reasonable business calls
- Conference fees: reimbursable with receipt

## Required Document Corpus

| Document Type | Examples | Priority |
|--------------|----------|----------|
| Travel expense law | BRKG, LRKG full text | HIGH |
| Allowance tables | BRKG Inlands-/Auslandstagegelder 2024 | HIGH |
| Mileage regulations | Kilometerpauschalenverordnung | HIGH |
| Travel policy | Dienstreiseverordnung der Kommune | MEDIUM |
| Travel forms | Dienstreiseantrag, Reisekostenabrechnung | MEDIUM |
| International per-diem tables | Auslandstagegelder nach Ländern | MEDIUM |

## Deterministic Rules

### Rule: Domestic Meal Allowance
```
Input: absenceHours (double), hasOvernightStay (boolean)
Process:
  1. Load TravelAllowanceTable for "BRKG"
  2. Call lookup(absenceHours, hasOvernightStay, "domestic")
  3. Return allowance amount + description
Output: TravelDecision(hours, allowanceEur, description, authority)
```
Status: ✅ IMPLEMENTED

### Rule: International Meal Allowance
```
Input: absenceHours (double), country (string), city (string)
Process:
  1. Load TravelAllowanceTable for "BRKG"
  2. Call lookup(hours, false, "international")
  3. Filter by country/city match
  4. Return allowance amount
Output: TravelDecision(hours, allowanceEur, category="international", description)
```
Status: ❌ NOT YET IMPLEMENTED (only Brussels entry exists)

### Rule: Mileage Reimbursement
```
Input: distanceKm (double), vehicleType (PKW | Motorcycle | Bicycle)
Process:
  1. Load TravelAllowanceTable for "BRKG"
  2. mileageRate() → get per-km rate
  3. Calculate: distance * rate
  4. Apply caps (e.g., max 150 km one-way without justification)
Output: TravelDecision(amount = distance * rate, category = "mileage")
```
Status: ✅ IMPLEMENTED

### Rule: Accommodation Allowance
```
Input: hasReceipt (boolean), isInternational (boolean)
Process:
  1. Load TravelAllowanceTable
  2. accommodationAllowance(hasReceipt) → get nightly limit
  3. If international → use international rates
Output: Accommodation result (per-night limit)
```
Status: ✅ IMPLEMENTED (domestic only)

## Retrieval Requirements

### Search Queries
- "Wie hoch ist das Tagegeld bei einer 12-stündigen Dienstreise?"
- "Kilometerpauschale für Dienstreise mit Privat-PKW 2024"
- "Übernachtungspauschale Inland mit Beleg BRKG"
- "Dienstreise Brüssel Tagegeld 2024"
- "Reisekostenabrechnung für mehrtägige Dienstreise"

### Retrieval Strategy
- Primary: RULE_ENGINE (deterministic allowance lookup)
- Fallback: HYBRID_RETRIEVAL (for international rates, special cases)

## Expected Decision Outputs
```yaml
decision:
  allowance: "Tagegeld 12 € + Übernachtung 80 €"
  hours: 13
  overnight: true
  regulation: "BRKG, gültig ab 01.01.2024"
  confidence: 0.99
```
