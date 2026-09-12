# I use a second LLM to check the first one

After generation, my system does something that surprises people: it checks the answer with a second model.

The generator writes the answer. Then I split the answer into its claims — the system has a fixed answer format, so the sections become the claims. For each claim, the second model gets the claim and the evidence passages and must decide: does the evidence support the claim, contradict it, or is it not enough? I call the three verdicts, roughly, supported, contradicted, unknown. The check is per claim and per passage, not one judgment for the whole answer.

Why a second model? The generator has one job: write a plausible answer. Asking the same model to check its own work is not independent. The checker has a narrow job, and I can test it separately.

So I built a benchmark for exactly this judgment: entailment, contradiction, insufficient evidence, wrong numbers, paraphrases — in German and English. Then I ran two model sizes against it: a 7B and a 14B open model.

The 7B got 100 percent. The 14B got 96.3 percent. And the 7B was about twice as fast — around 2.2 against 4.8 seconds per batch, on my workstation GPU.

I did not expect this. Bigger is not always better. It depends on the task. Generation benefits from the 14B — better German, better structure. So the 14B writes. For verification, the 7B is my validated option; in the current configuration the 14B also checks, and the 7B stays as rollback, one configuration value away. The benchmark is what makes that rollback safe.

The important design point is the direction of errors. If verification fails, or the model output is unclear, the claim counts as not supported. Not supported means the answer is not presented. A wrong "supported" verdict is dangerous. A wrong "not supported" verdict only withholds an answer. So I bias the system toward withholding.

People ask: who verifies the verifier? Honest answer: nobody. The verifier is a control, not a proof. What I have: a benchmark, recorded reasons for every verdict, and the fail-closed direction. If someone needs formal guarantees, that is a different project.

Background: [Teaching Language Models to Check Grounded Claim Factuality](https://aclanthology.org/2026.acl-long.1468/) (ACL 2026), and the [groundlens](https://huggingface.co/groundlens) project, which shows embedding-based checks fail exactly where entailment checks work.

Part 5 of "Things I learned building RAG".
