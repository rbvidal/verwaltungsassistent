# Comparing models and GPUs

The suite exists to answer two questions:

1. Which model is better at verification/generation for this municipal task?
2. Does the ranking change when the hardware (GPU) changes?

## Same model, different GPU

Run the identical command on each machine. Only `--output` differs so runs do
not collide; `--model` stays the same:

```bash
# RTX 3090 (Ubuntu)
./scripts/run_verifier_benchmark.sh  --model qwen3.6:27b --data ./data/verifier  --output ./reports/verifier/3090-qwen36-27b
./scripts/run_generator_benchmark.sh --model qwen3.6:27b --data ./data/generator --output ./reports/generator/3090-qwen36-27b

# P5000 (Windows or Linux)
./scripts/run_verifier_benchmark.sh  --model qwen3.6:27b --data ./data/verifier  --output ./reports/verifier/p5000-qwen36-27b
./scripts/run_generator_benchmark.sh --model qwen3.6:27b --data ./data/generator --output ./reports/generator/p5000-qwen36-27b
```

Cases, PDFs, emails, prompts, protocol and scoring are identical; only the
GPU (and Ollama version) differ. Compare:

- `summary.json` accuracy numbers should match within sampling noise (they
  measure the model, not the GPU). Large accuracy differences on the same
  model usually indicate a quantization/context issue or a different Ollama
  build - investigate before trusting either run.
- `mean/median generation tok/s`, `mean latency`, `mean output tokens` and
  `total wall time` differ by GPU - this is the hardware comparison.
- `gpu.csv` shows VRAM usage, utilization, temperature and power, i.e. how
  comfortably the model fits (e.g. 27B Q4 on a P5000 16 GB vs on an RTX 3090
  24 GB).

## Same GPU, different model

```bash
./scripts/run_verifier_benchmark.sh  --model qwen3.6:27b    --data ./data/verifier --output ./reports/verifier/qwen36-27b
./scripts/run_verifier_benchmark.sh  --model deepseek-r1:14b --data ./data/verifier --output ./reports/verifier/deepseek-r1-14b
./scripts/run_generator_benchmark.sh --model qwen3.6:27b    --data ./data/generator --output ./reports/generator/qwen36-27b
./scripts/run_generator_benchmark.sh --model deepseek-r1:14b --data ./data/generator --output ./reports/generator/deepseek-r1-14b
```

Keep the same runtime parameters (especially `--num-ctx`, `--temperature`,
`--think`); reasoning models may need `--think` (or Ollama's default) and a
larger `--num-ctx` - document the parameters you used in the report title.

## Suggested comparison table

| Model | GPU | Verifier acc (all) | acc normal | acc adv | Gen answer % | halluc. rate | insuff. handled | contradiction handled | tok/s | mean latency |
|---|---|---|---|---|---|---|---|---|---|---|

Populate it from each run's `README.txt` / `summary.json`.

## Reading the results critically

- **Overall accuracy** on the verifier is the headline number, but watch
  `adversarial accuracy` and the confusion matrix: a model can look strong
  while failing exactly the traps (wrong paragraphs, modal strength,
  absolute claims).
- **Generator**: prefer high `answer_correctness` WITH low
  `hallucination_unsupported_rate`. A model that answers more often but
  hallucinates more is not better. `insufficient_handled` and
  `contradiction_handled` show whether the model knows when to refuse and
  when to override the citizen.
- Token/s and latency numbers include prompt processing; for long PDF
  attachments `mean_output_tokens` will be smaller than the context that was
  processed. If you need pure decode speed, compare `eval_duration_ms` in
  `results.jsonl`.

## Reproducibility caveats

- Temperature 0.0 reduces but does not eliminate sampling variance; run each
  configuration two or three times if small differences matter.
- Ollama version and quantization can change both quality and speed - record
  them (the suite stores the version automatically).
- First request after a cold model load includes load time; `--keep-alive`
  (default 30m) keeps the model warm between cases.
