# The model is the easy part

Before I started, I thought the generation would be the difficult part of the project. It was not. A model with a prompt and some retrieved text produces good German answers on day one. The parts that took the time were around the generation.

Finding the right information. Three mechanisms — vector, keyword, graph — because each finds different things, and combining them without letting one document flood the list took many iterations.

Deciding what is relevant. The registration question from the second post: the search returned on-topic documents that did not answer the question. That incident changed the architecture.

Selecting the evidence. Rank the candidates, adjust for domain, cap per document, compress to short excerpts. The model gets a small curated package. This improved the answers more than any model change.

Detecting unsupported claims. Split the answer into claims, check each against the evidence with a second model, record the verdicts.

Deciding not to answer. The refusal boundary, enforced in code.

What I would keep if I started again. Explicit decisions everywhere — the system got better when I turned implicit choices into small, measurable steps: the fusion, the domain adjustment, the presentation boundary. You cannot improve what you cannot see. Refuse by default: errors should cost answers, not trust. Separate knowledge from the model: facts live in the knowledge base and the rule tables, the model brings language and task behavior. I can update knowledge without retraining, and replace the model without rebuilding the knowledge layer. And measure with real corpora — the two numbers I trust most are boring: a 128-question intent corpus and a controlled entailment benchmark. Every change I kept, I kept because of them.

What I am still not sure about. The fusion weights are a starting point, not a result. The keyword search is simple — maybe too simple for a big corpus. The retrieval is single-pass, so some questions will always be missed. The verification is a control, not a proof. I can live with these, because each of them fails in a visible way: a refusal, a log entry, a benchmark number.

The project stopped being about making the model answer. It became about building the system around the model that I can check. That is the part I would write about, if someone asks what actually took the time.

Part 9 of "Things I learned building RAG".
