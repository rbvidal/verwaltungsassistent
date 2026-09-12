# Date/Time Convention

Concise canonical convention for all date/time representations in the platform.

## Machine / API / storage / LLM — ISO-8601

- **DATE** (LocalDate): `yyyy-MM-dd` — e.g. `2026-08-20`
- **TIMESTAMP** (Instant): ISO-8601 — e.g. `2026-08-20T18:15:32.123Z`
- Jackson serializes `LocalDate`/`Instant` as ISO-8601 (`spring.jackson.serialization.write-dates-as-timestamps: false`, pinned in `application.yml`).
- LLM prompts use ISO dates/timestamps only (never German formats).

## German UI — human presentation at the UI boundary

- **DATE**: `dd.MM.yyyy` — e.g. `20.08.2026`
- **DATETIME**: `dd.MM.yyyy HH:mm` — e.g. `20.08.2026 20:15`
- Central formatter: `verwaltungsassistent.web.util.DateTimeFormats` (web module). Presentation formatting happens only here / in templates — machine data is never converted to presentation strings.

## Java types reflect semantics

- `LocalDate` — date-only concepts: validFrom, validUntil, effectiveFrom, effectiveUntil, publishedAt, supersededAt.
- `Instant` — absolute event timestamps: createdAt, updatedAt, completedAt.
- No time components in date-only fields; validFrom/validUntil are DATE intervals (boundary rule: `validUntil == asOf` is valid).
