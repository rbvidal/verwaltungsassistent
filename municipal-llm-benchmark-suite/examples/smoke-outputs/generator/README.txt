Municipal LLM benchmark - generator run
==================================================
benchmark version : 1.0.0
timestamp         : 2026-09-07T13:18:38+00:00
model             : qwen2.5:7b
judge model       : off
host              : Windows-11-10.0.26200-SP0 (DESKTOP-5981MAQ)
GPU               : Quadro P5000 (telemetry available: True)
ollama version    : None

total cases       : 2
completed cases   : 2
pipeline failures : 0
execution errors  : 0
extraction        : {'ok': 2, 'failed': 0, 'methods': {'pdftotext': 2}}

Status distribution:
  fully-correct-contradiction-handled           1
  partially-correct                             1

answer correctness (full + insufficient-handled): 50.0 %
normal correctness            : 0.0 %
adversarial correctness       : 100.0 %
hallucination/unsupported rate: 0.0 %
insufficient handled          : None %
contradiction handled         : 100.0 %
citation ok (where required)  : 100.0 %
mean latency                  : 7.38 s
median latency                : 7.3805 s
mean tok/s                    : 34.17
median tok/s                  : 34.17
mean out tokens               : 150.5
total wall time               : 14.97 s

Files: results.jsonl (raw responses preserved), summary.json, gpu.csv,
       raw/<case-id>.json
