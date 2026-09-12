# municipal-llm-benchmark-suite

Reusable, model- and GPU-agnostic benchmark suite for the German municipal
"Verwaltungsassistent" assistant. It evaluates an LLM in the two roles that
matter for an evidence-grounded assistant:

1. **Verifier** - given a *claim* plus *evidence passages*, return
   `SUPPORTED` / `CONTRADICTED` / `UNSUPPORTED`.
2. **Generator** - given a realistic *citizen e-mail* plus a *PDF
   attachment*, produce the administrative answer.

The suite is **standalone**: no dependency on the production application, no
database, no ingestion pipeline. It talks only to an **Ollama** server.

- Same cases, PDFs, prompts, protocol and scoring on every machine.
- The model is supplied on the command line; nothing in the code names a
  specific model.
- GPU telemetry is collected independently via `nvidia-smi` when available.

---

## 1. What the suite tests

- **Verifier benchmark** (45 cases): factual-consistency verification with
  deliberately hard cases - negation, modal verbs (`darf`, `muss`, `kann`,
  `darf nicht`, `muss nicht`), conditions (`sofern`, `unabhängig`, `nur
  wenn`), numbers, dates, deadlines, legal references incl. *wrong*
  paragraphs, partial evidence, multi-passage combination, unsupported
  portions of plausible claims, "can vs must", "may vs must", "not required
  vs required", absolute/"never" claims, subtle contradictions, and claims
  where the evidence is *insufficient* rather than contradictory.
- **Generator benchmark** (32 cases): end-to-end generation from an e-mail
  plus a real PDF attachment, with deterministic scoring of factual
  correctness, completeness, evidence use, hallucination, insufficiency and
  contradiction handling, dates/numbers/legal references, citations and
  conciseness.

## 2. Verifier vs generator - difference

| | Verifier | Generator |
|---|---|---|
| Input | claim + evidence passages (text) | citizen e-mail + PDF attachment |
| Model task | verdict `SUPPORTED/CONTRADICTED/UNSUPPORTED` | produce the administrative answer |
| Input format | `data/verifier/cases.jsonl` | `data/generator/case-NNN/{email.txt, attachment.pdf, case.json}` |
| Scoring | verdict vs explicit expected verdict | deterministic criteria from `case.json` |
| PDF extraction | n/a | real extraction (pdftotext / pypdf / pymupdf), failures recorded separately |
| Prompt risk | expected verdict never in prompt | expected facts never in prompt |

Both preserve **raw model responses** for every case.

## 3. Directory structure

```
municipal-llm-benchmark-suite/
├── README.md
├── VERSION.txt
├── data/
│   ├── verifier/
│   │   └── cases.jsonl                # 45 verifier cases (explicit verdicts + adversarial flag)
│   └── generator/
│       ├── case-001/  email.txt  attachment.pdf  case.json
│       ├── case-002/  ...
│       └── ... (32 cases)
├── scripts/
│   ├── run_verifier_benchmark.sh      # thin wrapper (python3/python fallback)
│   ├── run_verifier_benchmark.py      # engine (stdlib only)
│   ├── run_generator_benchmark.sh
│   └── run_generator_benchmark.py
├── lib/
│   └── bench_common.py                # ollama client, pdf extraction, telemetry, output helpers
├── tools/
│   ├── build_verifier_data.py         # regenerates data/verifier/cases.jsonl
│   ├── build_generator_data.py        # regenerates data/generator (needs pymupdf only for building)
│   └── validate_suite.py              # integrity validation (json/pdf/phrases)
├── reports/
│   └── README.md
├── docs/
│   ├── benchmark-protocol.md
│   ├── scoring.md
│   └── model-comparison.md
└── examples/
    └── smoke-outputs/                 # sample run outputs from the smoke tests
```

## 4. Prerequisites

- **Ollama** running locally (default `http://localhost:11434`; override with
  `--ollama-url` or the `OLLAMA_URL` environment variable) with the model(s)
  you want to test pulled (`ollama pull qwen3.6:27b` etc.).
- **Python 3.8+** (standard library only - no pip packages required to run).
  PDF extraction prefers `pdftotext` from poppler-utils; if absent it falls
  back to `pypdf` or `pymupdf` if installed. If **no** extractor is present,
  generator cases are recorded as pipeline failures, never silently scored.
- (Optional) `nvidia-smi` for GPU telemetry. Without it, the run continues
  and records that telemetry is unavailable.

## 5. Run the verifier benchmark

```bash
./scripts/run_verifier_benchmark.sh \
  --model qwen3.6:27b \
  --data ./data/verifier \
  --output ./reports/verifier/qwen36-27b
```

## 6. Run the generator benchmark

```bash
./scripts/run_generator_benchmark.sh \
  --model qwen3.6:27b \
  --data ./data/generator \
  --output ./reports/generator/qwen36-27b
```

## 7. Command-line parameters (both runners)

| Option | Default | Meaning |
|---|---|---|
| `--model` | *(required)* | Ollama model tag, e.g. `qwen3.6:27b`, `deepseek-r1:14b`, `qwen2.5:7b` |
| `--data` | *(required)* | input dir: verifier dir containing `cases.jsonl`, or generator dir with `case-*` dirs |
| `--output` | *(required)* | new run dir; refused if it exists non-empty unless `--force` |
| `--ollama-url` | `http://localhost:11434` | Ollama base URL (or env `OLLAMA_URL`) |
| `--num-ctx` | `8192` | context window |
| `--temperature` | `0.0` | sampling temperature |
| `--think` | off | request model reasoning (`think: true`) for models that support it |
| `--keep-alive` | `30m` | Ollama model keep-alive |
| `--limit` | all | only run the first N cases (smoke tests) |
| `--seed-cases` | - | comma-separated case ids to run only |
| `--timeout` | `300` | per-request timeout in seconds |
| `--telemetry-interval` | `2.0` | GPU telemetry poll interval (s) |
| `--self-test` | off | offline plumbing test with a deterministic fake model (no Ollama) |
| `--force` | off | overwrite an existing output directory |
| (generator only) `--judge-model` | off | optional separate Ollama model for qualitative judging (see docs/scoring.md) |

The scripts are thin wrappers; the Python engines accept the same options,
so `python3 scripts/run_verifier_benchmark.py ...` works identically.

## 8. Output files

Each run creates its own directory (never overwrites an earlier run unless
`--force`):

```
reports/verifier/3090-qwen36-27b/
├── results.jsonl     # per-case: id, category, adversarial, expected, predicted,
│                     # correctness, raw response, timing, token counts, errors
├── summary.json      # machine-readable summary (see below)
├── gpu.csv           # nvidia-smi samples (name/VRAM/util/temp/power), if available
├── README.txt        # human-readable summary
└── raw/<case-id>.json  # full prompt + response per case
```

`summary.json` includes: model, GPU, timestamp, host, Ollama version,
benchmark version, total/completed cases, execution errors, parse errors,
accuracy (overall/normal/adversarial), confusion matrix, incorrect-case list
(verifier) or status distribution plus answer/hallucination/insufficiency/
contradiction/citation rates (generator), mean/median latency, mean/median
generation tok/s, mean output tokens, total wall time.

## 9. Scoring methodology

Deterministic first; no expected value ever enters a prompt. Details in
[docs/scoring.md](docs/scoring.md):

- **Verifier**: predicted verdict (robustly parsed from the raw JSON answer)
  is compared with the explicitly stored `expected` verdict *after*
  inference. Confusion matrix includes a `PARSE_ERROR` class.
- **Generator**: per-case structured criteria in `case.json`
  (`required_facts`, `forbidden_claims`, `contradicted_traps`, honesty
  markers, citation markers) are checked against the folded answer text.
  Statuses: `fully-correct`, `partially-correct`, `incorrect`,
  `unsupported-hallucinated`, `correct-insufficient-handled`,
  `insufficient-handled-but-hallucinated`,
  `incorrect-insufficient-not-handled`,
  `fully-correct-contradiction-handled`.
- An optional LLM judge (`--judge-model`) evaluates qualitative dimensions
  and is written to `judge.jsonl`, clearly separated from the deterministic
  summary.

## 10. Compare two models

Run the same command with different `--model`/`--output` values:

```bash
./scripts/run_verifier_benchmark.sh --model qwen3.6:27b   --data ./data/verifier  --output ./reports/verifier/qwen36-27b
./scripts/run_verifier_benchmark.sh --model deepseek-r1:14b --data ./data/verifier --output ./reports/verifier/deepseek-r1-14b
./scripts/run_generator_benchmark.sh --model qwen3.6:27b   --data ./data/generator --output ./reports/generator/qwen36-27b
./scripts/run_generator_benchmark.sh --model deepseek-r1:14b --data ./data/generator --output ./reports/generator/deepseek-r1-14b
```

Then compare `summary.json` / `README.txt` of both output dirs - identical
cases, PDFs, emails, prompts, protocol and scoring; only the model differs.
[docs/model-comparison.md](docs/model-comparison.md) explains what to look at
and how to build a table across GPUs (e.g. RTX 3090 vs 4090 vs P5000).

## 11. Run on another GPU / server

1. Copy the suite to the machine.
2. Install Ollama and pull the models.
3. (Recommended) install poppler-utils (`pdftotext`).
4. Run the same commands as above with the desired `--model` and a fresh
   `--output`. GPU telemetry appears automatically if `nvidia-smi` exists;
   otherwise the report notes telemetry as unavailable.

## 12a. Line endings (Windows vs Linux)

All text files in this archive are stored with **LF** line endings so the
scripts run unchanged on Linux. If you ever regenerate files on Windows and
see `/usr/bin/env: 'bash'`, convert the scripts with:
`sed -i 's/$//' scripts/*.sh scripts/*.py`

## 12. Data integrity and reproduction

- `tools/validate_suite.py` validates every JSON/JSONL file, every PDF (opens
  + extractable), that required facts occur in the PDF text, that trap
  phrases occur in the e-mail, and that verifier cases are well-formed.
- `tools/build_verifier_data.py` and `tools/build_generator_data.py`
  regenerate the data deterministically from the sources embedded in the
  scripts (generator PDFs need `pymupdf` only at build time).
- Quick smoke tests: append `--limit 2 --self-test` (offline) or
  `--limit 2` against a real model.

Version: see [VERSION.txt](VERSION.txt).
