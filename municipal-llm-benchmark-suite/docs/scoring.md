# Scoring

Scoring is **deterministic by default**. No scoring input is ever placed in a
model prompt; all comparisons run after inference against the stored raw
response. Where exact wording matters it is expressed as an explicit literal
in the data; everything else is substring presence on whitespace-folded,
lowercased text.

## Text folding

`norm_text`: lowercase, collapse all whitespace runs to single spaces. This
makes checks robust against PDF line breaks and casing, but not against
paraphrase - paraphrase sensitivity is intentional (the criteria are written
as the literal phrases that matter).

## Verifier scoring

- Verdict extraction from the raw response:
  1. JSON `"verdict": "X"` inside the response, else
  2. the first standalone token `SUPPORTED|CONTRADICTED|UNSUPPORTED`.
  Anything else => `parse_error` (counted separately, never guessed).
- `correct = (predicted == expected)`.
- Summary:
  - overall accuracy over parsed cases,
  - normal accuracy (adversarial=false),
  - adversarial accuracy (adversarial=true),
  - confusion matrix expected x predicted including a `PARSE_ERROR` column
    and row,
  - incorrect case list.

The dataset deliberately includes categories where a fluent but
non-grounded model fails: modal strength (`darf` vs `muss`, `kann` vs
`muss`, `darf nicht`, `muss nicht`), conditions (`sofern`, `unabhängig`,
`nur wenn`), wrong legal paragraph references, unsupported-but-plausible
claims, "never"/absolute claims, subtle contradictions, partial support and
insufficient-vs-contradictory distinctions.

## Generator scoring

Each `case.json` defines structured criteria. Fields:

| field | meaning |
|---|---|
| `required_facts` | literal phrases that MUST appear in the answer (grounding on the attachment) |
| `forbidden_claims` | literal phrases that must NEVER appear (hallucinations; e.g. fees/durations that are not in the attachment) |
| `contradicted_traps` | `[{phrase, label}]` - false citizen assertions from the e-mail; the answer may mention them only with a contrast marker |
| `honesty_markers` | literal phrases that signal "the attachment does not answer this" for `handling: insufficient` cases |
| `citation_markers` | literal phrases that count as naming the source for `citation_required: true` cases |
| `handling` | `answer` \| `insufficient` \| `correction` - defines the expected behavior class |

Contrast markers (used to decide whether a trap phrase is *rebutted* rather
than *repeated*): words such as `nicht, keine, kein, falsch, unrichtig,
abweichend, statt, jedoch, allerdings, tatsächlich, vielmehr, richtig,
korrekt, maßgeblich, entgegen, aber, sondern` within +/- 60 characters around
the trap phrase.

### Statuses

- `fully-correct` - all `required_facts` present, none of the `forbidden_claims`
  present, no trap repeated without contrast (for `handling: answer`).
- `fully-correct-contradiction-handled` - same, for `handling: correction`
  (the model follows the attachment and rebuts the citizen's claim).
- `partially-correct` - at least one `required_fact` present, but not all.
- `incorrect` - no `required_fact` present.
- `unsupported-hallucinated` - a forbidden claim or a repeated (non-contrasted)
  trap is present and no required fact was found.
- `correct-insufficient-handled` (`handling: insufficient`) - the answer
  states that the information cannot be determined (honesty marker) and
  contains no forbidden claim.
- `insufficient-handled-but-hallucinated` - honest about insufficiency, but
  still asserts a forbidden claim.
- `incorrect-insufficient-not-handled` - claims an answer although the
  attachment is silent.

### Summary axes (deterministic)

- `answer_correctness_pct` - (fully-correct + fully-correct-contradiction-
  handled + correct-insufficient-handled) / completed.
- normal / adversarial breakdown of the same.
- `hallucination_unsupported_rate_pct` - cases with status containing
  "hallucinated" or `unsupported-hallucinated`.
- `insufficient_handled_pct` - among `handling: insufficient` cases.
- `contradiction_handled_pct` - among `handling: correction` cases.
- `citation_ok_pct` - among `citation_required` cases where a citation marker
  appears.
- Latency / tok/s / output tokens: mean + median + total wall time.
- Pipeline failures (PDF extraction etc.) are counted separately and do not
  enter model accuracy.

## Optional LLM judge (clearly separated)

`--judge-model <tag>` asks a second model to score the qualitative
dimensions `factual_correctness, completeness, evidence_use,
no_hallucination, insufficient_handling, contradiction_handling,
dates_numbers, legal_reference, citation, conciseness` (0..1 each). The
judge output goes to `judge.jsonl` and is **never** merged into the
deterministic summary. Use it only as a secondary signal; the deterministic
criteria are authoritative for comparisons.

## Design notes

- Generator cases are graded on *what the answer must contain*, not on exact
  wording, except where wording is genuinely load-bearing (numbers, dates,
  paragraph references - stored as literals).
- Cases deliberately mix classes A-O (see README): direct answers, buried
  evidence, irrelevant email/PDF content, complementary email+PDF,
  insufficient evidence, contradictions, negation, modal language,
  dates/deadlines, numbers, legal references, multi-fact answers, plausible
  but unsupported citizen assertions, adversarial cases.
- The e-mail is *input*, never *evidence*: citizen assertions that the
  attachment does not confirm are scored as traps.
