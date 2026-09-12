# I don't want the AI to answer every question

My system has a sentence that appears often:

"Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor."

The knowledge base does not contain enough information for this question.

This sentence appears when the verification step finds that no retrieved passage actually supports the claims of the answer. When this happens, the draft answer is thrown away. Not edited. Replaced. The citations are cleared. The user sees the sentence above. The rejected claims stay in the log with the reasons.

People ask me: why would you build a system that refuses to answer? Because in this domain a wrong answer is worse than no answer. A confident, cited, wrong answer is the worst case, because nobody checks an answer that looks right. And the refusal is not a suggestion in the prompt. It is enforced in the code: if no claim is supported, there is no answer.

There is a second reason the refusal is useful. Every refusal points to a gap in the knowledge base. A procedure that was not ingested. An exception case that is missing. When the system says "insufficient information", someone can find the missing document, add it, and the question works next week. A confident wrong answer gives no such signal. It just quietly reduces trust.

I want to be careful with claims here. The refusal does not make the system hallucination-free. The verification step is itself a language model and can be wrong. What the boundary gives me is a direction: errors tend to produce silence instead of confident nonsense. A system that answers too little can be improved by adding documents. A system that answers too much cannot.

The registration question from the second post is the best example. The system still refuses it today, because the document that describes the exception is not in the corpus. That is correct behavior, and it tells me exactly which document to look for.

Background: [Production RAG: How to Reduce Hallucinations](https://www.boldare.com/blog/how-to-build-a-production-rag-system/) — Boldare, on confidence gates and refusal as production practice.

Part 6 of "Things I learned building RAG".
