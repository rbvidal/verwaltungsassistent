# examples/

- `smoke-outputs/` - sample outputs of the validation smoke runs (verifier
  `qwen2.5:7b`, 3 cases; generator `qwen2.5:7b`, 2 cases) executed with the
  suite on a Quadro P5000 (Windows). They demonstrate the output structure
  (`summary.json`, `README.txt`, `gpu.csv`, `raw/<case-id>.json`) but are NOT
  benchmark results - small case counts only.

To produce your own examples:

```bash
./scripts/run_verifier_benchmark.sh  --model qwen2.5:7b --data ./data/verifier  --output /tmp/demo-verifier  --limit 3
./scripts/run_generator_benchmark.sh --model qwen2.5:7b --data ./data/generator --output /tmp/demo-generator --limit 2
```
