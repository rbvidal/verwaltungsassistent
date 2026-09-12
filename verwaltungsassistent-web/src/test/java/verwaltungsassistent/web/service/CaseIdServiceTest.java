package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2C.3b: Vorgangsnummer (WS-XXXXXXXX) als kanonischer externer
 * Konversations-Schlüssel — deterministischer Parser + deterministisches
 * Aktenzeichen für Mailbox-Intake-Vorgänge.
 */
class CaseIdServiceTest {

    @Test
    void parseCaseId_extractsBracketedCodeFromSubject() {
        assertEquals(Optional.of("WS-1A2B3C4D"),
                CaseIdService.parseCaseId("AW: [WS-1A2B3C4D] Ihre Frage zum Wohngeld"));
    }

    @Test
    void parseCaseId_acceptsBareCodeAndLowercase() {
        assertEquals(Optional.of("WS-AB12CD34"),
                CaseIdService.parseCaseId("Re: ws-ab12cd34 Nachfrage"));
        assertEquals(Optional.of("WS-ABCDEF01"),
                CaseIdService.parseCaseId("AW: WS-abcdef01 Frage"));
    }

    @Test
    void parseCaseId_rejectsMissingOrMalformedCodes() {
        assertEquals(Optional.empty(), CaseIdService.parseCaseId("Frage zum Wohngeld"));
        assertEquals(Optional.empty(), CaseIdService.parseCaseId("AW: [VG-WOH-123445-A] Frage"));
        assertEquals(Optional.empty(), CaseIdService.parseCaseId("AW: [WS-12] Frage"));
        assertEquals(Optional.empty(), CaseIdService.parseCaseId("AW: [WS-1234567G] Frage"));
        assertEquals(Optional.empty(), CaseIdService.parseCaseId(null));
    }

    @Test
    void parseCaseId_firstOfMultipleCodesWins() {
        assertEquals(Optional.of("WS-11111111"),
                CaseIdService.parseCaseId("[WS-11111111] und [WS-22222222]"));
    }

    @Test
    void deterministicCaseCode_isStableAndFormatConform() {
        String first = CaseIdService.deterministicCaseCode("Wohngeld – Nachfrage zu meinem Antrag");
        String second = CaseIdService.deterministicCaseCode("Wohngeld – Nachfrage zu meinem Antrag");
        assertEquals(first, second, "deterministisch — über Resets identisch");
        assertTrue(first.matches("WS-[0-9A-F]{8}"), "Format der bestehenden Vorgangsnummern");
        assertTrue(!CaseIdService.deterministicCaseCode("Gewerbe – Anmeldung")
                .equals(CaseIdService.deterministicCaseCode("Wohngeld – Anmeldung")),
                "verschiedene Betreffe → verschiedene Nummern");
        assertTrue(CaseIdService.deterministicCaseCode(null).matches("WS-[0-9A-F]{8}"));
    }
}
