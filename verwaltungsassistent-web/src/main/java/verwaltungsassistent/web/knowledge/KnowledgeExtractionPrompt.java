package verwaltungsassistent.web.knowledge;

/**
 * Builds the strict-JSON structured-knowledge extraction prompt.
 *
 * <p>The LLM is an extractor, not a decision maker: it may only turn text
 * explicitly present in the supplied chunk into structured items. Narrative
 * or explanatory content is not forced into executable structure — it stays
 * ordinary RAG evidence.
 */
public final class KnowledgeExtractionPrompt {

    private KnowledgeExtractionPrompt() {
    }

    public static String build(String documentTitle, String documentType, String chunkText) {
        return """
                Extrahiere strukturiertes Wissen aus dem folgenden Dokument-Auszug.

                ANWEISUNGEN:
                - Extrahiere NUR Informationen, die im Text ausdruecklich enthalten sind.
                - Erfinde KEINE Werte, Grenzen, Betraege, Daten oder Rechtsvorschriften.
                - NUMERISCHE REGELN (streng):
                  - Erfinde NIE eine Zahl. Jeder Zahlenwert (min/max/op-Wert/Zeilenwert)
                    muss IM AUSZUG explizit vorkommen.
                  - Wandle NIE einen Satz in eine Grenze um ("20 Cent pro km" ist ein Satz,
                    KEINE 20-km-Grenze). Einheiten (EUR, EUR/km, Cent/km, km, Stunden,
                    Prozent) bleiben getrennt und werden nie ineinander umgerechnet.
                  - Multipliziere/rechne NIE Werte aus dem Text zusammen, es sei denn der
                    Text nennt das Ergebnis ausdruecklich.
                  - Erfinde NIE fehlende Unter-/Obergrenzen; offene Grenzen sind "max":null.
                  - Wenn ein Zahlenwert nicht explizit im Text steht, lasse das Feld weg
                    oder lasse das Item weg — niemals raten.
                - SEMANTISCHE ROLLE VON ZAHLEN (streng):
                  - Ein Zahlenwert darf in THRESHOLD-bounds NUR dann verwendet werden, wenn der
                    Text ihn AUSDRUECKLICH als Grenze des ausgewerteten Fakts definiert.
                  - Ein Geldbetrag wie "Hoechstbetrag", "hoechstens X Euro", "maximal",
                    "Obergrenze", "Betrag auf X Euro festsetzen" ist eine GELDGRENZE/-SATZ,
                    NICHT automatisch eine Grenze von km, Stunden, Menge oder anderer Fakten.
                  - Ein Satz wie "20 Cent je Kilometer" ist ein SATZ (Euro/km, Cent/km),
                    KEINE Kilometer-Grenze und KEIN Kilometer-Wert.
                  - Leite NIE eine Beziehung zwischen verschiedenen Einheiten ab
                    (EUR <-> km, EUR/km <-> km, Cent/km <-> km, EUR <-> Stunden).
                  - min und max eines THRESHOLD muessen Grenzen DERSELBEN semantischen Groesse sein.
                  - Wenn eine Zahl nicht sicher in die richtige semantische Rolle einordenbar ist,
                    lasse das strukturierte Item weg (oder waehle RULE/DEFINITION mit Text) —
                    NIE ein falsches THRESHOLD erzwingen.
                  - BEISPIEL (RICHTIG): Text "hoechstens jedoch 130 Euro. ... Hoechstbetrag auf 150 Euro festsetzen"
                    → KEIN THRESHOLD mit max=130 oder max=150 (das waeren Kilometer-Grenzen);
                    → statt dessen RULE: {"condition":"Benutzung eines Kraftfahrzeuges",
                       "consequence":"20 Cent je Kilometer, hoechstens 130 Euro; oberste Bundesbehoerde kann 150 Euro festsetzen"}
                - Trenne extrahierbares strukturiertes Wissen (Tabellen, Schwellenwerte, Regeln,
                  Definitionen, Ausnahmen, Geltungszeitraeume) von rein erklaerendem/erzaehlendem Text.
                  Erzaehlender Text wird NICHT zu strukturiertem Wissen.
                - Erlaubte kind-Werte: "TABLE", "THRESHOLD", "RULE", "DEFINITION", "EXCEPTION", "EFFECTIVE_DATE".
                - domain ist eine generische Kategorie (z. B. "PROCUREMENT", "TRAVEL", "HR", "ENVIRONMENT").
                - key ist die Identitaet, die der Text selbst angibt (z. B. der Name der Vorschrift).
                - payload ist kind-abhaengig strukturiert:
                    THRESHOLD: {"bounds":[{"min":0,"max":10000,"outcome":"...","requirements":["..."]}]}
                    THRESHOLD-BANDS: Wenn der Text mehrere Wertgrenzen fuer DENSELBEN Sachverhalt
                    nennt, erstelle EIN Item mit LUECKENLOSEN, NICHT UEBERLAPPENDEN bounds
                    (min/max aufsteigend; das Ende einer Bande ist der Anfang der naechsten;
                    die oberste Bande ist offen mit "max":null).
                    BEISPIEL: "bis 10 000 ... von 10 000 bis 100 000 ... ab 100 000"
                    → [{"min":0,"max":10000,"outcome":"..."},{"min":10000,"max":100000,"outcome":"..."},
                       {"min":100000,"max":null,"outcome":"..."}]
                    TABLE (Lookup-Tabelle mit Spalten, Zeilen, optionalem lookup/result):
                      {"columns":["hoursMin","hoursMax","allowanceEur"],
                       "rows":[[8,11,6],[11,24,12],[24,null,24]],
                       "lookup":{"hoursMin":"hours","hoursMax":"hours"},
                       "result":["allowanceEur"]}
                      {"columns":["salaryGrade","salaryStep","monthlyAmount"],
                       "rows":[["EG 9a",3,4117.53]],
                       "lookup":{"salaryGrade":"salaryGrade","salaryStep":"salaryStep"},
                       "result":["monthlyAmount"]}
                      lookup-Spalten MUSSEN aus den columns stammen; lookup-Fakten NUR aus der
                      kanonischen Liste (amount, hours, distanceKm, salaryGrade, salaryStep);
                      Zahlen IMMER als JSON-Zahlen ohne Waehrungszeichen.
                    DEFINITION/EXCEPTION/EFFECTIVE_DATE: {"text":"..."}
                - RULE MUSS GENAU zwei Felder haben: "condition" (wann gilt die Regel)
                  und "consequence" (was folgt aus der Bedingung).
                  RULE darf NIE {"text": ...} verwenden.
                  ZUSAETZLICH: wenn die Bedingung einen EINDEUTIGEN Zahlenvergleich enthaelt,
                  fuege "predicates" hinzu (Array generischer Praedikate):
                    {"fact":"amount","op":"LE","value":100000,"currency":"EUR"}
                  fact MUSS aus dieser KANONISCHEN Liste stammen (keine anderen Namen):
                    "amount"      — der geldwerte Betrag der Entscheidung
                                  (deutsche Wortlaute "Auftragswert", "Kosten", "Preis",
                                  "geschätzter Auftragswert" usw. werden IMMER zu "amount")
                    "hours"       — Dauer in Stunden
                    "distanceKm"  — Entfernung in Kilometern
                    "salaryGrade" — Entgeltgruppe (z.B. "EG 9a")
                    "salaryStep"  — Stufe (1..6)
                    "mode"        — kategoriale Auspraegung (String-Gleichheit); erlaubte Werte:
                                  "standard", "overnight", "withReceipt", "flat"
                                  ("Übernachtung"/"Anreise"/"Abreise" → "overnight",
                                   "mit Beleg" → "withReceipt", "pauschal"/"ohne Beleg" → "flat",
                                   normale Tagesdienstreise → "standard")
                  Jeder andere fact-Name ist UNZULAESSIG; dann predicates weglassen.
                  op: EQ|NE|LT|LE|GT|GE.
                  "bis zu X" bedeutet op LE (inklusiv), "unter X" bedeutet op LT (exklusiv).
                  value ist IMMER eine JSON-Zahl (kein Tausenderpunkt, kein Komma).
                  currency (optional, semantisches Datenfeld): "EUR" nur wenn der Text die
                  Waehrung explizit nennt.
                  KEINE Praedikate, wenn der Vergleich mehrdeutig ist oder andere Fakten braucht —
                  die Regel bleibt dann gueltiges Wissen, ist aber nicht deterministisch ausfuehrbar.
                  BEISPIEL RULE:
                    {"kind":"RULE","domain":"PROCUREMENT","key":"AV §55 LHO",
                     "payload":{"condition":"Auftragswert unter 10.000 Euro",
                                "predicates":[{"fact":"amount","op":"LE","value":10000}],
                                "consequence":"Direktauftrag mit Vergabevermerk"},
                     "effectiveFrom":"2024-01-01","confidence":0.9}
                  Wenn der Text nur erzaehlend ist und sich nicht als condition/consequence
                  darstellen laesst, verwende DEFINITION oder EXCEPTION (payload.text) oder
                  extrahiere nichts — niemals RULE mit {"text": ...}.
                - effectiveFrom/effectiveUntil NUR wenn im Text explizit genannt, sonst null.
                - confidence: 0.0-1.0, wie sicher der Text die Aussage stuetzt.

                ANTWORTFORMAT: NUR ein JSON-Objekt, keine weiteren Texte:
                {"items":[{"kind":"...","domain":"...","key":"...","payload":{...},
                           "effectiveFrom":"YYYY-MM-DD","effectiveUntil":"YYYY-MM-DD","confidence":0.95}]}
                Falls nichts extrahierbar ist: {"items":[]}

                DOKUMENT: %s (%s)

                AUSZUG:
                %s
                """.formatted(documentTitle, documentType, chunkText);
    }
}
