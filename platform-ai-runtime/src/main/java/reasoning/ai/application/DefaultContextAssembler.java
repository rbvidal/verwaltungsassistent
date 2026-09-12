package reasoning.ai.application;

import reasoning.ai.api.ContextAssembler;
import reasoning.ai.api.FindingHierarchyService;
import reasoning.ai.api.ObjectiveAnalysisService;
import reasoning.ai.api.SourceOrchestrationService;
import reasoning.ai.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles a prompt context including an {@link EvidencePackage} built from
 * retrieval results. The evidence package replaces raw source lists in the
 * prompt with structured, numbered evidence items.
 */
@Component
public class DefaultContextAssembler implements ContextAssembler {

    private final ObjectiveAnalysisService objectiveAnalysisService;
    private final FindingHierarchyService findingHierarchyService;
    private final SourceOrchestrationService sourceOrchestrationService;
    private final EvidencePackageBuilder evidencePackageBuilder;

    public DefaultContextAssembler(ObjectiveAnalysisService objectiveAnalysisService,
                                    FindingHierarchyService findingHierarchyService,
                                    SourceOrchestrationService sourceOrchestrationService,
                                    EvidencePackageBuilder evidencePackageBuilder) {
        this.objectiveAnalysisService = objectiveAnalysisService;
        this.findingHierarchyService = findingHierarchyService;
        this.sourceOrchestrationService = sourceOrchestrationService;
        this.evidencePackageBuilder = evidencePackageBuilder;
    }

    private static String systemInstruction(String userLanguage) {
        String languageRule = AnswerLanguage.germanDefault(userLanguage)
                ? "- Sie schreiben in der Sprache einer deutschen Kommunalverwaltung."
                : "- Sie antworten auf " + AnswerLanguage.displayName(userLanguage)
                    + ". Die gesamte Antwort muss in dieser Sprache formuliert sein.";
        String languageEmphasis = AnswerLanguage.germanDefault(userLanguage)
                ? ""
                : "WICHTIG: Die gesamte Antwort muss auf " + AnswerLanguage.displayName(userLanguage)
                    + " verfasst sein — alle Überschriften und Sätze.\n\n";
        return """
            Sie sind ein Entscheidungsassistent für die deutsche Kommunalverwaltung.

            Ihre Aufgabe ist es, Sachbearbeiterinnen und Sachbearbeitern fundierte,
            evidenzbasierte Entscheidungsgrundlagen zu liefern.

            GRUNDREGELN:
            - Sie arbeiten AUSSCHLIESSLICH mit den bereitgestellten Beweisstücken.
            - Sie führen KEIN eigenes juristisches oder administratives Wissen ein.
            - Sie erfinden KEINE Vorschriften, Paragraphen, Beträge oder Verfahren.
            - Sie zitieren wörtlich aus den Beweisstücken.
            - Sie benennen Widersprüche, statt sie zu verschweigen.
            - Bei unzureichender Evidenz sagen Sie das klar und deutlich.
            %s

            %sSie sind KEIN Chatbot. Sie sind ein Verwaltungsassistent.
            """.formatted(languageRule, languageEmphasis);
    }

    @Override
    public PromptContext assemble(AiRequest request, RetrievalContext retrievalContext) {
        return assemble(request, retrievalContext, null);
    }

    @Override
    public PromptContext assemble(AiRequest request, RetrievalContext retrievalContext,
                                  String userLanguage) {
        List<AnalysisObjective> objectives = objectiveAnalysisService.classify(request.question());
        FindingHierarchy hierarchy = findingHierarchyService.buildHierarchy(request.question(), objectives);
        SourceDossier dossier = sourceOrchestrationService.buildDossier(
                retrievalContext.sources(), request.question());

        // Build the evidence package from retrieval results. The lexical
        // coverage inside the builder judges the same text the retrieval used
        // (case topic for case analyses, full question otherwise) — never the
        // instruction boilerplate wrapped around it.
        EvidencePackage evidencePackage = evidencePackageBuilder.build(
                request.retrievalQueryOrQuestion(), retrievalContext.sources(), request.asOf());

        return new PromptContext(
                systemInstruction(userLanguage),
                request.question(),
                retrievalContext,
                request.context().messages(),
                objectives,
                hierarchy,
                dossier,
                request.retrievalScope(),
                evidencePackage);
    }
}
