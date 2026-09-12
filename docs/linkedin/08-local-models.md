# Why I run the models locally

The question I hear most often about the system: does the data go to OpenAI? No. Nothing leaves the machine.

All models run locally — generation, verification, embeddings. The databases run on the same machine. The demo runs on one workstation GPU with 16 GB of memory. The system runs without internet; only the first model download needs it.

I am not against hosted models. They are stronger at many things. But look at what my system asks models to do: turn a question into a structured intent. Write an answer from a small set of evidence passages in a fixed format. Judge whether a passage supports a claim — the task where the 7B model scored 100 percent in my benchmark. None of these tasks need a frontier model. They need discipline, control, and data that stays in the building.

The data matters. The questions show what citizens and staff are asking. The corpus is internal procedures. Sending that to an API is a policy decision, not a technical one. In Germany this is now also the legal direction: the Fraunhofer FOKUS study on federal administration found that public LLM projects are deliberately built on local or sovereign infrastructure. Berlin has an open-source RAG assistant for its administration, BärGPT, built on the same principle.

Three practical reasons for local models, from my experience:

Control. Models are versioned artifacts. I know which version answered which question. Before I switch a model, I run the evaluation benchmarks. No silent changes from a provider.

Cost shape. Local inference has no per-token billing. A slow answer costs electricity. That matters for annual budgets.

Model independence. The models are configuration, not architecture. The knowledge base does not care which model reads it. If a better open model appears, I exchange it and re-validate. If a customer wants a hosted model, the provider layer supports that too.

The honest costs: you give up frontier-scale quality, concurrent users queue on one GPU, and someone must operate the stack. For an administrative document system, I take this trade.

Background: [Study on large language models in federal administration](https://www.fokus.fraunhofer.de/en/newsroom/news/LLMs-in-federal-administration.html) — Fraunhofer FOKUS (ÖFIT), and [BärGPT](https://github.com/technologiestiftung/baergpt), the open-source assistant for Berlin's administration.

Part 8 of "Things I learned building RAG".
