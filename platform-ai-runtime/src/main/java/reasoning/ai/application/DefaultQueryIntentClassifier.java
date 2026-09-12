package reasoning.ai.application;

import reasoning.ai.api.QueryIntentClassifier;
import reasoning.ai.model.QueryIntent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Classifies query intent by matching keywords against predefined intent patterns.
 */
@Component
public class DefaultQueryIntentClassifier implements QueryIntentClassifier {

    private static final Map<QueryIntent, Set<String>> INTENT_PATTERNS = Map.of(
            QueryIntent.INDEX_INSPECTION, Set.of(
                    "does your index contain", "is there information in the index",
                    "what does the index contain", "is the corpus",
                    "is there material", "do you have information",
                    "do you have documents", "what documents do you have",
                    "does the system contain", "is stored in the",
                    "what topics", "which documents are",
                    // German: index inspection
                    "welche dokumente sind", "welche informationen",
                    "enthält der index", "sind dokumente",
                    "was ist im index"),
            QueryIntent.CORPUS_DISCOVERY, Set.of(
                    "what do you know about", "what can you tell me about",
                    "what areas of", "what topics are covered",
                    "what references", "which documents are available",
                    "what documents are in the", "show me all documents",
                    // German: corpus discovery
                    "was wissen sie über", "was können sie mir",
                    "welche bereiche", "welche themen",
                    "zeige mir alle"),
            QueryIntent.DOCUMENT_RESEARCH, Set.of(
                    "what does", "what is", "explain",
                    "definition", "meaning of",
                    "tell me about section", "tell me about reference",
                    // German: document research
                    "was ist", "was bedeutet", "erkläre",
                    "definition von", "was versteht man",
                    "was besagt"),
            QueryIntent.DOCUMENT_LOOKUP, Set.of(
                    "find me", "show me the document",
                    "where is the document", "locate",
                    "search for the document",
                    // German: document lookup
                    "finde mir", "zeige mir das dokument",
                    "wo ist das dokument", "suche nach"),
            QueryIntent.SOURCE_ANALYSIS, Set.of(
                    "analyze this source", "what does this document prove",
                    "what does the email", "what does the letter",
                    "is this sufficient evidence",
                    // German: source analysis
                    "analysiere diese quelle", "was beweist dieses dokument",
                    "ist das ausreichend"),
            QueryIntent.WORKSPACE_ANALYSIS, Set.of(
                    "analyze my workspace", "my documents",
                    "what should I do about my", "given my situation",
                    // German: workspace analysis
                    "analysiere meinen arbeitsbereich", "meine dokumente",
                    "was soll ich tun", "in meiner situation"),
            QueryIntent.QUESTION_ANSWERING, Set.of(
                    "what can i do", "what should i do", "what are my rights",
                    "am i allowed", "can i",
                    "how to proceed", "next steps", "procedure",
                    "what happens if", "is it legal",
                    "how do", "how does", "how is", "how are", "how can",
                    "what is the", "what are the", "which documents",
                    // German: question answering — common municipal decision patterns
                    "was kann ich", "was soll ich", "was sind meine rechte",
                    "darf ich", "kann ich",
                    "wie gehe ich vor", "nächste schritte", "verfahren",
                    "was passiert wenn", "ist es rechtens",
                    "welches", "welche", "welcher", "gilt",
                    "wie beantrage", "wie hoch", "wie lange",
                    "wie viele", "ab wann", "unter welchen",
                    "was muss ich", "welche voraussetzungen",
                    "wann ist", "wann darf", "wann muss",
                    "ist ein", "ist eine", "gibt es",
                    "brauche ich", "benötige ich")
    );

    @Override
    public QueryIntent classify(String query) {
        String lower = query.toLowerCase().trim();

        int bestScore = 0;
        QueryIntent bestIntent = QueryIntent.QUESTION_ANSWERING;

        for (var entry : INTENT_PATTERNS.entrySet()) {
            int score = 0;
            for (String pattern : entry.getValue()) {
                if (lower.contains(pattern)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIntent = entry.getKey();
            }
        }

        return bestIntent;
    }
}
