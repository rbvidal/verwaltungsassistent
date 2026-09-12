# A similar document is not necessarily a relevant document

The question that broke my first version was this one:

"Eine Person möchte sich anmelden, hat aber keine Wohnungsgeberbestätigung."

A person wants to register a residence, but the landlord confirmation is missing. A normal question for a citizen office. The special case is the exception: what to do when the confirmation is missing.

My system searched the knowledge base and returned documents about registration. Anmeldung, Ummeldung, Abmeldung — registration, change of residence, deregistration. The legal bases. The authorities. The top results were all about the right topic. Any person looking at the result list would say the search worked.

None of the documents contained the exception case. The question asked about registering without the confirmation. The documents described the normal procedure, where the confirmation is required. The retrieval was perfect on the topic and useless for the question.

And then the model answered anyway. It described the normal registration procedure, added a general sentence about the confirmation from its own knowledge, cited the retrieved documents, and looked confident. The answer was not supported by any of the cited documents. I only noticed because I knew the domain and checked the sources manually.

After this I started to separate three things:

```
Similar   — the text is about the same topic as the question
Relevant  — the text concerns what the question actually asks
Evidence  — the text supports a specific claim in a specific answer
```

Vector search gives the first one. It does not give the other two. A document can be similar without being relevant. It can be relevant without being evidence. My first version treated all three as the same thing, because it had one number: the similarity score.

I changed two things after this incident. First, retrieval got more signals: exact word search, because in administrative text the exact term often matters, and later a knowledge graph for the structure. Second, everything retrieval returns is now a candidate. A candidate is not evidence. Evidence is what remains after a separate verification step checks each claim of the answer against the actual passages.

The current system answers this same question with:

"Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor."

The knowledge base does not contain enough information for this question. No answer, no citations, and the gap is recorded so someone can fix the corpus.

This incident is the reason the verification step exists. I will write about that separately.

On the embedding side, this problem is known: VentureBeat wrote this year about precision tuning quietly reducing retrieval accuracy, because semantic near-misses — negations, reversed relations, wrong numbers — get almost the same scores as correct answers. Link below.

Background: [RAG precision tuning can quietly cut retrieval accuracy by 40%](https://venturebeat.com/data/rag-precision-tuning-can-quietly-cut-retrieval-accuracy-by-40-putting-agentic-pipelines-at-risk) — VentureBeat, 2026.

Part 2 of "Things I learned building RAG".
