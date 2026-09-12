# Benchmark protocol

This document defines the exact protocol both benchmarks follow so that runs
on different machines, GPUs and models remain comparable.

## 1. Fixed inputs (never model-dependent)

- All case files (verifier `cases.jsonl`, generator `case-NNN/*`) are static.
- System prompts are compiled into the runner code and never change between
  models.
- No expected verdict, expected fact, forbidden claim or scoring criterion is
  ever included in any prompt. Scoring data is read only after inference.
- "Adversarial" is an explicit boolean in every case record. It is never
  inferred from missing fields, filenames or ordering.

## 2. Request construction

### Verifier

- System prompt: defines the role (factual verifier for municipal claims),
  the three verdicts, and the instruction to answer with a single JSON object
  `{"verdict": ..., "reason": ...}`.
- User message: `BEHAUPTUNG:` + claim, then `BELEGE:` + numbered evidence
  passages.
- The prompt never contains `expected`, `adversarial` or the case id.

### Generator

- System prompt: municipal staff role; ground answer only in the attachment;
  citizen assertions in the e-mail are not evidence; do not invent facts;
  state explicitly when the attachment does not contain the answer; in case
  of conflict the attachment wins; name the source; German.
- User message: `BÜRGER-E-MAIL:` + email text, then `ANLAGE (PDF, extrahierter
  Text aus attachment.pdf, N Seite(n), N Zeichen, Extraktion: <method>):`
  followed by the extracted text, then the task line.
- The prompt never contains any `case.json` scoring field.

## 3. PDF extraction

The generator runner extracts text from the real `attachment.pdf` in the same
general way a production intake would:

1. `pdftotext -enc UTF-8 <file> -` (poppler-utils), else
2. `pypdf`, else
3. `pymupdf`.

For every case the extraction method, page count (when determinable) and
extracted character count are recorded. If extraction fails, the case is
recorded as a **pipeline failure** (`pipeline_error`) - the model is not
asked and never silently scored as wrong.

## 4. Inference call

- Single non-streaming `/api/chat` request per case.
- Options sent: `num_ctx` (default 8192), `temperature` (default 0.0),
  `keep_alive` (default 30m); `think: true` only when `--think` is given.
- Recorded per call: prompt eval count, output token count, prompt eval
  duration, eval duration, total duration, load duration, wall time and
  tokens/s (`eval_count / eval_duration`).

## 5. Raw output preservation

- The complete raw model response is stored in `results.jsonl` and in
  `raw/<case-id>.json` together with the exact messages that were sent.
- Failed calls (network/timeout/model errors) and malformed responses
  (parse errors) are kept and counted separately; they are never deleted or
  rewritten.

## 6. Scoring timing

- Scoring happens strictly after inference, from the stored raw response.
- Verifier verdicts are extracted without any access to the expected value.
- Generator scoring uses only the deterministic criteria in `case.json`.

## 7. Telemetry

- While the run is active, a background sampler calls
  `nvidia-smi --query-gpu=name,memory.used,memory.total,utilization.gpu,
  temperature.gpu,power.draw --format=csv,noheader,nounits` every
  `--telemetry-interval` seconds and writes `gpu.csv`.
- If `nvidia-smi` is not available the run continues and the summary states
  that telemetry is unavailable.

## 8. Metadata recorded in every summary

benchmark name + version, model, timestamp (UTC), host (platform, hostname,
CPU, Python version), Ollama version, model details when `/api/tags`
provides them, GPU name/availability, `num_ctx`, `temperature`, `think`,
judge model (generator, when used).

## 9. Reproducibility notes

- Deterministic temperature 0.0 is the default; Ollama sampling is still not
  bit-reproducible across runs. For stable comparisons run the suite with the
  same parameters and, if desired, several seeds/runs and compare
  distributions (see model-comparison.md).
- The output directory guard prevents accidental overwrite of earlier runs;
  use `--force` only when you mean it.
