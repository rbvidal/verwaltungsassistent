# Verwaltungsassistent Security Hardening — Bericht

Erstellt: 16.08.2026 · Basis: verifizierte Befunde aus der Sicherheitsarchitektur-Prüfung (docs/security/Verwaltungsassistent-Sicherheitsarchitektur.md)

**Wichtiger Hinweis:** Während dieser Aufgabe lief parallel eine VM-Image-Generierung. Es wurden keine Prozesse, VMs, Ollama-Instanzen oder VM-Dateien dieses Tasks angefasst; auf ressourcenintensive Laufzeit-Verifikation (Browser + LLM-Pipeline) wurde bewusst verzichtet.

| Status | Bedeutung |
|---|---|
| FIXED | behoben und durch fokussierte Tests verifiziert |
| PARTIALLY FIXED | behoben; Rest bleibt deployment-/weiterentwicklungsabhängig |
| NOT IMPLEMENTED | bewusst nicht umgesetzt (Begründung unten) |
| FUTURE WORK | als Weiterentwicklung dokumentiert |
| RUNTIME VERIFICATION DEFERRED | Laufzeit-Prüfung wegen laufender VM-Image-Generierung verschoben |

## Befunde im Überblick

| Finding | Before | After | Verification | Status |
|---|---|---|---|---|
| USER_LOGIN_FAILED geht durch Transaktions-Rollback verloren | Audit-Emission in der Login-Transaktion | Emission in eigener Transaktion (REQUIRES_NEW); Ereignis inkl. IP persistiert | `SecurityVerificationTest.wrongPassword_failsAndFailedLoginAuditPersistsWithIp` | **FIXED** |
| Fall-Detail/-Unteraktionen ohne Eigentümer-Prüfung | UUID reichte für Zugriff auf fremde Fälle | Zentraler `CaseAccessGuard`: ADMIN alle, Besitzer eigene, fremde → 403, unbekannt → 404; gilt für Detail, Tabs, Notizen, Checkliste, Phase, Dokument-Verknüpfungen, Entscheidungsanalyse inkl. Polling, Archivieren | `CaseOwnershipTest` (13 Tests: Matrix A/B/ADMIN/Anonym, GET+POST+DELETE) | **FIXED** |
| Cookies ohne SameSite/Secure/HttpOnly-Konfiguration | nur Name gesetzt | Session-Cookie: HttpOnly=true, SameSite=Lax, Secure über `COOKIE_SECURE` (Standard aus für HTTP-Demo, an hinter HTTPS-Proxy); CSRF-Cookie: SameSite=Lax, Secure ebenso | `CookieHardeningTest`, `SecurityVerificationTest.securityHeaders_arePresent` | **FIXED** (Secure = deployment-abhängig, bewusst) |
| X-Content-Type-Options deaktiviert | `disable()` | nosniff aktiv (Default); X-Frame-Options DENY unverändert | `SecurityVerificationTest.securityHeaders_arePresent` | **FIXED** |
| Kein Brute-Force-Schutz am Login | unbegrenzte Fehlversuche | Zeitbasierte Sperre: 5 Fehlversuche / 10 min → 10 min Sperre; konfigurierbar (`platform.auth.login.*`, ENV LOGIN_MAX_FAILURES / LOGIN_FAILURE_WINDOW / LOGIN_LOCKOUT_DURATION); Erfolg setzt zurück; Sperr-Ablehnungen mit Grund `too_many_attempts` auditiert; keine dauerhafte Sperre möglich | `LoginAttemptGuardTest` (7 Unit-Tests), `SecurityVerificationTest.bruteForce_repeatedFailuresBlock_loginRecoversAfterLockout` | **FIXED** |
| HTTPS/TLS fehlt in der Demo | HTTP | Reverse-Proxy-Modell dokumentiert inkl. nginx-Beispiel (Anhang A der Sicherheitsarchitektur); Anwendung terminiert bewusst kein TLS; `COOKIE_SECURE`-Schalter für Produktion | Dokumentation + Konfigurationsstruktur | **PARTIALLY FIXED** (Zertifikate = Betriebsaufgabe) |
| Entwicklungs-Standard-Secrets | `JWT_SECRET`-Default im Code | Startup-Warnung bei bekanntem Dev-Secret; Konfigurationsdokumentation: Produktion setzt JWT_SECRET/DB-/Neo4j-Passwörter per Umgebung, nichts wird in JAR/VM-Image gebacken | Code-Warnung `SecurityConfiguration.warnOnDevelopmentSecret` | **PARTIALLY FIXED** (Werte selbst = Betriebsaufgabe) |
| Verschlüsselung at rest / Backups | nicht vorhanden | Bewusst NICHT als App-Verschlüsselung umgesetzt; Dokumentation (Abschnitt 11/12/17 der Sicherheitsarchitektur): verschlüsselte Datenträger/Volumes + verschlüsselte Backups als Betriebsaufgabe (P1), Option B (AES-256-GCM je Dokument) nur bei hohem Schutzbedarf | Dokumentation | **NOT IMPLEMENTED** (Entscheidung dokumentiert; keine App-Verschlüsselung, da Infrastruktur-Verschlüsselung der pragmatische erste Schritt ist) |
| Passwort-Zurücksetzung | nicht vorhanden (zuvor im FAQ fälschlich behauptet) | FAQ korrigiert: existiert nicht; als P2-Weiterentwicklung dokumentiert | Dokumentationskorrektur | **FUTURE WORK** |
| „Rekonstruierbarkeit" von KI-Antworten überbewertet | Audit speichert Strategie/Konfidenz, Verifikationsdetails nur im Log | Formulierung präzisiert: vollständige Verifikations-Nachvollziehbarkeit = Weiterentwicklung | Dokumentationskorrektur | **FUTURE WORK** |

## Fokussierte Tests (ausgeführt, ohne Live-AI/Ollama)

Alle grün (Zusammenfassung je Klasse):

| Testklasse | Anzahl | Abdeckung |
|---|---|---|
| `SecurityVerificationTest` | 17 | Anonym-Umleitung, Rollen-Matrix, CSRF, Login-Erfolg/-Fehlschlag inkl. Audit+IP, Sperr-/Disabled-/Gelöscht-Konto, Logout-Audit, Brute-Force inkl. Erholung, Security-Header |
| `CaseOwnershipTest` | 13 | Detail + Unteraktionen: Besitzer erlaubt, Fremde 403, ADMIN erlaubt, Anonym 302, unbekannt 404, POST/DELETE |
| `LoginAttemptGuardTest` | 7 | Schwelle, Zeitfenster, Ablauf, Reset, Pruning, Isolation je E-Mail |
| `CookieHardeningTest` | 1 | XSRF-TOKEN mit SameSite=Lax |
| Bestehende Regressionstests | 69 | CaseControllerTest (23), CaseDetailControllerTest (21), DecisionAnalysisProgressTest (7), CasesLayoutRegressionTest (4), AssistantProgressLifecycleTest (8), CorpusEvaluationProgressTest (6) — alle an das neue Eigentümer-Modell angepasst und grün |

Gesamt: **107 fokussierte Tests, 0 Fehlschläge.** Keine Live-AI-Klassen ausgeführt (EkpP0CorrectnessTest, EkpPipelineEndToEndTest, EkpCorePipelineRepairTest, DefaultModeSmokeTest u. a. — bewusst ausgelassen).

## Runtime Verification

**RUNTIME VERIFICATION DEFERRED** — die parallel laufende VM-Image-Generierung belegt Ressourcen; ein vollständiger App-+Ollama-Start wurde bewusst nicht durchgeführt. Zu verifizieren (Folgeaufgabe nach Abschluss der VM-Generierung):

1. Live-Login mit Fehlversuchen → USER_LOGIN_FAILED in der Audit-UI sichtbar (mit IP).
2. Live-Brute-Force-Sperre und Ablauf.
3. Browser: XSRF-TOKEN-Cookie mit SameSite=Lax, VA_SESSION mit HttpOnly (Secure nur über HTTPS — dort prüfbar).
4. Live-Fall-Zugriff als zweiter Benutzer → 403-Seite/Fehlermeldung statt Daten.
5. `X-Content-Type-Options: nosniff` und `X-Frame-Options: DENY` im Browser.
6. Startup-Log: Entwicklungsschlüssel-Warnung.

## Dateien (geändert/neu)

**platform-audit**
- `PersistentAuditService` — emit() mit REQUIRES_NEW (beide Varianten)

**platform-auth**
- `AuthProperties` — login.max-failures / failure-window / lockout-duration + DEV_JWT_SECRET-Konstante
- `LoginAttemptGuard` (neu) — In-Memory-Sperre, zeitbasiert
- `AuthService` — Guard-Anbindung: Block-Prüfung, Zählung, Reset, Sperr-Audit mit Grund
- `SecurityConfiguration` — Startup-Warnung bei Dev-Secret
- Test: `LoginAttemptGuardTest` (neu), `AuthServiceEdgeCaseTest` (Konstruktor angepasst)

**verwaltungsassistent-web**
- `SecurityConfig` — X-Content-Type-Options nosniff (disable entfernt), CSRF-Cookie-Customizer (SameSite=Lax, Secure über `platform.security.cookie-secure`)
- `application.yml` — Session-Cookie http-only/same-site/secure(ENV), `platform.security.cookie-secure`, `platform.auth.login.*`
- `security/CaseAccessGuard` (neu) — zentrale Objekt-Ebene-Prüfung
- `CaseController`, `CaseDetailController`, `DecisionWorkspaceController` — Guard in allen Fall-Endpunkten inkl. Unteraktionen
- Tests: `CaseOwnershipTest` (neu), `CookieHardeningTest` (neu), `SecurityVerificationTest` (aktualisiert: Audit-Fix, Brute-Force, Header), `CaseControllerTest`/`CaseDetailControllerTest`/`DecisionAnalysisProgressTest` (Stubs an Eigentümer-E-Mail angepasst)

**docs/security**
- `Verwaltungsassistent-Sicherheitsarchitektur.md` + `.pdf` — Abschnitte 4, 5, 7, 9, 10, 19, 20, 21 aktualisiert, Anhang A (TLS/nginx-Beispiel) ergänzt, FAQ-Übertreibungen korrigiert (Passwort-Reset, Rekonstruierbarkeit)
- `security-hardening-report.md` (diese Datei)

**Nicht angefasst:** KI-/RAG-Pipeline, Retrieval, Verifikation, Grounding, Regeln, Fortschrittsanzeige, Fine-Tuning, deployer/VM-Image-Dateien, prompts, LinkedIn/Whitepaper/QA-Dokumente.

## Verbleibende Sicherheitslücken (bekannt, dokumentiert)

- TLS: Deployment-Aufgabe (Reverse Proxy, Zertifikate) — Konfiguration vorbereitet, Zertifikate offen.
- Verschlüsselung at rest + Backups: Betriebsaufgabe (P1) — bewusst keine App-Verschlüsselung.
- Passwort-Zurücksetzung, MFA, CSP, Audit-Retention/Alarmierung: Weiterentwicklung (P2/P3).
- Rate-Limiting ist In-Memory pro Instanz (kein verteilter Schutz); für Multi-Instanz-Betrieb wäre eine externe Schicht nötig (dokumentierte Einschränkung).
- Dokumentliste bleibt für alle angemeldeten Benutzer sichtbar (Ein-Organisations-Modell, bewusst).
