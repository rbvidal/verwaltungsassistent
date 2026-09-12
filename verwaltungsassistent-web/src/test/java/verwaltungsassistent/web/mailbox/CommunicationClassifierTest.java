package verwaltungsassistent.web.mailbox;

import org.junit.jupiter.api.Test;

import static verwaltungsassistent.web.mailbox.CommunicationClassifier.CommunicationType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2C.3b: deterministische Kommunikations-Klassifikation — semantische
 * Einordnung ohne LLM. Entscheidend: „Danke, das war schon alles" →
 * requiresResponse=false; Folgefrage → requiresResponse=true.
 */
class CommunicationClassifierTest {

    @Test
    void closingGratitude_requiresNoResponse() {
        var c = CommunicationClassifier.classify("Wohngeld – Nachfrage",
                "Vielen Dank für die Information, das war schon alles. Auf Wiedersehen!");
        assertFalse(c.requiresResponse(), "abschließende Dank-Nachricht braucht keine Antwort");
        assertFalse(c.requiresAdministrativeWork());
        assertEquals("NONE", c.responseMode());
        assertEquals(CommunicationType.OTHER, c.communicationType());
    }

    @Test
    void closingGratitude_withoutClosingPhrase_isStillAQuestion() {
        // "Vielen Dank" als Höflichkeitsfloskel ist KEINE Erledigung.
        var c = CommunicationClassifier.classify("Wohngeld – Nachfrage",
                "Guten Tag, ich habe eine Frage zum Wohngeld. Vielen Dank im Voraus!");
        assertTrue(c.requiresResponse());
        assertEquals(CommunicationType.QUESTION, c.communicationType());
    }

    @Test
    void followUpQuestion_requiresResponse() {
        var c = CommunicationClassifier.classify("AW: [WS-1A2B3C4D] Wohngeld – Nachfrage",
                "Eine Frage habe ich noch: Muss ich die Unterlagen persönlich einreichen?");
        assertTrue(c.requiresResponse());
        assertEquals(CommunicationType.FOLLOW_UP, c.communicationType());
        assertTrue("EMPLOYEE_RESPONSE".equals(c.responseMode())
                        || "ACKNOWLEDGEMENT".equals(c.responseMode()),
                "Antwort-Modus ist gesetzt");
    }

    @Test
    void application_isRecognized() {
        var c = CommunicationClassifier.classify("Gewerbeanmeldung",
                "ich möchte zum 1. Oktober ein Gewerbe anmelden.");
        assertEquals(CommunicationType.APPLICATION, c.communicationType());
        assertTrue(c.requiresResponse());
    }

    @Test
    void informationRequest_isRecognized() {
        var c = CommunicationClassifier.classify("Wohngeld – Nachfrage",
                "Welche Unterlagen benötige ich für den Wohngeldantrag?");
        assertEquals(CommunicationType.INFORMATION_REQUEST, c.communicationType());
        assertTrue(c.requiresResponse());
    }

    @Test
    void complaint_isRecognized() {
        var c = CommunicationClassifier.classify("Beschwerde",
                "Ich möchte mich über die lange Wartezeit beschweren.");
        assertEquals(CommunicationType.COMPLAINT, c.communicationType());
        assertTrue(c.requiresResponse());
    }

    @Test
    void plainStatement_isOtherButRequiresWork() {
        var c = CommunicationClassifier.classify("Terminvereinbarung", "Bitte senden Sie mir den Termin.");
        assertEquals(CommunicationType.OTHER, c.communicationType());
        assertTrue(c.requiresResponse(), "auch einfache Anliegen können Verwaltungsarbeit bedeuten");
        assertTrue(c.requiresAdministrativeWork());
    }
}
