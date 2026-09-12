# RAG looked easier before I built one

When I started this project, the plan was simple. Take documents, extract the text, cut it into small pieces, make embeddings of the pieces, embed the question, search for the closest pieces, give them to a language model with the question. Done. This is the standard RAG pipeline. Every tutorial describes it like this.

I had it running in one week. I used German administrative documents — travel expense rules, procurement rules, citizen service procedures from Berlin — and asked questions. Most answers were correct. The German was good. For a demo, it looked finished.

Then I started checking the answers more carefully. Not because I had a plan, but because I knew the domain and something felt wrong. I found three problems.

The first: vector search finds similar text. It does not find answers. A question about an exception to a registration rule finds every document about registration. The documents are similar to the question. They do not contain the answer. The model then answers anyway, using what it knows, and cites the documents it received. The answer looks right. It is not.

The second: I could not tell which part of an answer came from the documents and which part came from the model itself. The citations were real documents. Some of them did not support the sentence they were attached to. This is worse than no citations, because it looks checked.

The third: some questions have an exact answer, and the model is the wrong tool for them. "What is the daily allowance for a 12-hour business trip?" has one correct answer, defined by law. A language model answers this correctly most of the time. Most of the time is not good enough here.

So the simple pipeline stayed, but it became only one part of a bigger system. Before generation, the system now does retrieval with three different mechanisms, ranks the candidates, and computes deterministic rules for the questions with exact answers. After generation, a separate model checks the claims of the answer against the evidence. If the evidence does not support the answer, the system does not show it.

I will describe these parts in the next posts. Not because they are clever. Each of them exists because something went wrong, and I can point at the incident.

One note from the beginning: generation was never the problem. The problem was everything around it.

Background: Lewis et al., [Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks](https://arxiv.org/abs/2005.11401) (2020) — the original paper, still the best starting point.

Part 1 of "Things I learned building RAG".
