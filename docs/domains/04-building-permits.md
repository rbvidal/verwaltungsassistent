# Domain: Building Permits (Bauordnung)

## Purpose

Support building permit officers in determining permit requirements, applicable regulations, required documentation, and procedural steps for construction projects.

## Governing Regulations

| Regulation | Scope |
|-----------|-------|
| BauGB | Baugesetzbuch — federal planning law |
| BauNVO | Baunutzungsverordnung — land use ordinance |
| MBO / LBO | Musterbauordnung / Landesbauordnung (state-specific) |
| BauO Bln | Bauordnung Berlin |
| BauVorlV | Bauvorlagenverordnung |
| VwVfG | Administrative procedure law |
| ENEV / GEG | Energy efficiency regulations |

## Required Structured Knowledge

### Project Type Classifications
- **Verfahrensfrei**: no permit needed (garden shed < 30 m³, fence < 2 m, etc.)
- **Genehmigungsfreistellung**: in Bebauungsplan area, meets all criteria
- **Vereinfachtes Verfahren**: residential buildings, simple commercial
- **Vollverfahren**: complex projects, special buildings (Sonderbauten)
- **Bauvoranfrage**: pre-application inquiry (Vorbescheid)

### Threshold Rules
- Carport/Garage: typically verfahrensfrei up to 30 m² (Berlin), but check Bebauungsplan
- Abstandsflächen: 0.4 × building height (varies by state)
- Grundflächenzahl (GRZ): typically 0.4 in residential areas
- Geschossflächenzahl (GFZ): varies by Bebauungsplan

### Required Documents by Project Type
- Lageplan, Bauzeichnungen, Baubeschreibung, Statik, Energieausweis
- Abstandsflächenberechnung, Stellplatznachweis
- Entwässerungsplan, Brandschutznachweis (for Sonderbauten)

## Deterministic Rules (Status)

| Rule | Status |
|------|--------|
| Verfahrensfreiheit check by project type and size | ❌ |
| Abstandsfläche calculation (0.4H) | ❌ |
| GRZ/GFZ compliance check | ❌ |
| Required documents checklist by project type | ❌ |
| Neighbor notification requirements | ❌ |

## Expected Decision Output
```yaml
decision:
  requiresPermit: true
  procedure: "Vereinfachtes Baugenehmigungsverfahren"
  requiredDocuments:
    - "Lageplan (Maßstab 1:500)"
    - "Bauzeichnungen (Grundrisse, Ansichten, Schnitte)"
    - "Baubeschreibung"
    - "Abstandsflächenberechnung"
    - "Stellplatznachweis"
  governingRegulation: "BauO Bln §63"
  authority: "Bauaufsichtsamt Berlin"
  estimatedProcessingTime: "3 Monate"
```
