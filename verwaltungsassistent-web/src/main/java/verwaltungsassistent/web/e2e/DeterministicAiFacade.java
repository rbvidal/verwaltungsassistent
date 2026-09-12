package verwaltungsassistent.web.e2e;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceDossier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministische E2E-Antwort hinter der bestehenden {@link AiFacade}-
 * Schnittstelle (playwright-Profil): liefert eine feste, plausible deutsche
 * Antwort mit Belegen und Grounding. Die Workflow- und Daten-Weitergabe
 * (Analyse → Belege → Planung → Empfehlung) wird getestet — nicht das
 * Sprachmodell. Keine Ollama-Aufrufe, keine Nichtdeterminismen.
 */
public class DeterministicAiFacade implements AiFacade {

    @Override
    public AiResponse answer(AiRequest request) {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        Instant now = Instant.now();
        ReasonedAnswer answer = new ReasonedAnswer(
                "Die Gewerbeausübung ist gemäß § 14 GewO anzuzeigen. Die Anzeige muss die Angaben "
                        + "über die beabsichtigte Tätigkeit, den Standort und die Person enthalten. "
                        + "Bitte ergänzen Sie die fehlenden Nachweise, damit die Bearbeitung fortgesetzt "
                        + "werden kann. (Deterministische E2E-Antwort)",
                List.of(
                        new SourceCitation(docId, chunkId, 1, "Gewerbeordnung (GewO)", 3, 120, 210,
                                "§ 14 GewO – Anzeigepflicht der Gewerbeausübung", 0.93, SourceCitation.SourceTier.PRIMARY),
                        new SourceCitation(UUID.randomUUID(), UUID.randomUUID(), 1,
                                "Verwaltungsassistent – Bearbeitungshandbuch", 1, 10, 40,
                                "Bearbeitungsschritte Gewerbeanmeldung", 0.80, SourceCitation.SourceTier.SUPPORTING)),
                List.of(),
                null,
                new SourceDossier(Map.of(), List.of("GewO § 14"), List.of(), 0.90,
                        "Vollständig – Quellen vorhanden"),
                new ConfidenceProfile(0.93, 0.90, 0.92, 0.90, 0.92,
                        "Deterministische E2E-Antwort (Stub)"),
                true, 0.88, false);
        InferenceMetadata metadata = new InferenceMetadata("deterministic-e2e", "stub",
                now, now, "e2e", "e2e", "stub", "HYBRID", List.of(chunkId.toString()), 0.92);
        return new AiResponse(answer, metadata);
    }
}
