package reasoning.mailbox.api;

import java.util.List;

/**
 * Transportneutrale Abstraktion eines Mailbox-Transports (Phase 2C.2, als
 * eigenes API-Artefakt seit Phase 2C.13). Ein Connector holt NUR normalisierte
 * Nachrichten — Priorität, Fall-Matching, KI-Analyse, Zuordnung und
 * Empfehlung bleiben außerhalb (bestehende Anwendungs-Logik).
 *
 * <p>Die Schnittstelle ist bewusst FETCH-ONLY: Es gibt keine Send-/Reply-/
 * Forward-Operationen und niemals outbound Kommunikation (das System ist
 * dauerhaft air-gapped und inbound-only). Implementierungen (z. B. die lokale
 * GreenMail-Demo) stellen ausschließlich eingehende Nachrichten bereit.</p>
 */
public interface MailboxConnector {

    /**
     * Holt alle aktuell im Postfach liegenden Nachrichten. Die Idempotenz
     * stellt die Anwendungs-Schicht her (Deduplizierung über Message-ID) —
     * der Connector selbst kennt keine Verarbeitungshistorie.
     */
    List<IncomingMessage> fetchNewMessages();
}
