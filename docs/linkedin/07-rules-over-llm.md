# Why I don't let the LLM decide everything

Some questions have exactly one correct answer.

"How much is the daily allowance for a 12-hour business trip?" In Germany this is defined by law: 8 to 11 hours gives 6 euros, 11 to 24 hours gives 12 euros, a full 24 hours gives 24 euros. A 12-hour trip: 12 euros. This is a lookup, not reasoning.

A language model answers this correctly almost every time. Almost is the problem.

In the first version, every question went through retrieval and generation. The travel question worked in demos. Then I watched the system answer a salary question: the user asked about a pay grade, but did not say which seniority step. The system assumed a step and answered with a precise monthly amount. The number looked official. It was a guess.

I removed this. Now a salary rule only fires when the question contains both the grade and the step. If the step is missing, the rule does not fire, and the question goes to retrieval. Inventing a default for missing information is exactly what a careful clerk would not do.

The architecture is now rule-first. The system first asks: is there a deterministic answer? If a structured table can answer — travel allowances, procurement thresholds, salary tables — the system computes the answer and the language model only explains it, with the legal basis attached. The prompt tells the model explicitly: the decision is already made, do not choose a different procedure, do not calculate.

The tables are not prompts. They are versioned data with effective dates. When the law changes, I replace the table. Nothing is retrained, nothing is re-prompted. And because the tables are data, I can test them: for every bracket, the lookup is right. This I cannot say about a language model.

One more guard: a rule only fires when the domain of the question matches the domain of the rule. "12 hours" somewhere in a question does not trigger the travel table if the question is about procurement. I added this after seeing rules fire on the wrong questions.

The LLM does the parts it is good at: understanding loosely written questions, writing explanations, generating answers from evidence. What it does not do is decide, when a decision can be computed. My rule: if the answer is defined exactly, compute it. If it must be composed from evidence, retrieve, generate, verify.

Part 7 of "Things I learned building RAG".
