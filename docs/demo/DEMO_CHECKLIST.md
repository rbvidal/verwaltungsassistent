# Demo Checklist — Verwaltungsassistent

**For**: Municipal employee demonstration (non-technical audience)
**Target audience**: Sachbearbeiter, Amtsleiter, IT-Beauftragte einer Kommunalverwaltung

---

## Before the Demo (30 minutes)

### 1. Start Services
```bash
# Ensure Ollama is running
ollama serve
# Verify it responds
curl http://localhost:11434/api/tags | grep qwen2.5:14b
```

### 2. Verify Model
```bash
# The configured model must be pulled and available
ollama list | grep "qwen2.5:14b"
# If missing:
ollama pull qwen2.5:14b
```

### 3. Verify Database
```bash
# Start PostgreSQL (or use H2 dev profile for demo)
# For dev profile (H2 in-memory, no external DB needed):
export SPRING_PROFILES_ACTIVE=dev
```

### 4. Seed Test Data
**CRITICAL**: Case creation is not yet available through the UI. Pre-seed data using the REST API or a database script.

Minimum demo data:
- 2-3 workspaces (cases) in different phases
- 1 case with attached documents (PDFs of municipal regulations)
- 2-3 indexed document chunks for retrieval to work

Example API calls (requires JWT token from platform-api):
```
POST /api/workspaces  → create case with name, description, type
POST /api/documents   → upload document
POST /api/documents/{id}/index → index for search
```

### 5. Start Application
```bash
cd verwaltungsassistent-web
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# Wait for "Started VerwaltungsassistentApplication"
# Verify: http://localhost:8081/login returns login page
```

### 6. Verify Full Pipeline
```bash
# Login and test the decision workspace with a seeded case
# Open http://localhost:8081/login
# Login: admin@verwaltungsassistent.local / admin123
# Navigate to a case, click "Entscheidung vorbereiten", click "Analyse starten"
# Verify a structured decision package appears within 20 seconds
```

### 7. Prepare Demo Environment
- [ ] Close all other browser tabs
- [ ] Set browser zoom to 100%
- [ ] Set browser language to German
- [ ] Use a 1920x1080 or higher resolution
- [ ] Disable browser auto-fill/auto-complete (or be ready to explain it)
- [ ] Disable notifications
- [ ] Have a backup: incognito window with a second pre-logged-in session

---

## During the Demo

### Recommended Click Sequence (Scripted Walkthrough)

**Total expected duration: 8-12 minutes**

| Step | Action | What to Say | Expected Time |
|------|--------|------------|---------------|
| 1 | Open `http://localhost:8081/login` | "Das ist die Anmelde-Seite des Verwaltungsassistenten." | 0:30 |
| 2 | Login with demo credentials | "Ich melde mich mit meinen Zugangsdaten an." | 0:15 |
| 3 | **Dashboard** — point out metrics | "Das Dashboard zeigt den aktuellen Stand der Fallbearbeitung. Hier sehen Sie alle wichtigen Kennzahlen auf einen Blick." | 1:00 |
| 4 | Click "Fälle" in navigation | "Über die Navigation gelangen Sie zur Übersicht aller Fälle." | 0:15 |
| 5 | **Case List** — show search/filter | "Sie können Fälle nach Status filtern oder nach Namen durchsuchen." | 0:45 |
| 6 | Click on a pre-seeded case | "Ich öffne einen Beispiel-Fall aus der Bauverwaltung." | 0:15 |
| 7 | **Case Detail** — show tabs | "Jeder Fall enthält eine Übersicht, Dokumente, eine Zeitleiste, Notizen und Checklisten." | 1:00 |
| 8 | Click "Dokumente" tab | "Hier sehen Sie alle mit dem Fall verknüpften Dokumente." | 0:30 |
| 9 | Click "Dokument anhängen" | "Sie können jederzeit weitere Dokumente hinzufügen." | 0:30 |
| 10 | **AVOID**: "In Assistant öffnen" | (links to placeholder page — skip this button) | — |
| 11 | Click "Entscheidung vorbereiten" | "Das Kernstück des Verwaltungsassistenten ist die KI-gestützte Entscheidungsvorbereitung." | 0:15 |
| 12 | **Decision Workspace** — explain | "Das System analysiert alle Dokumente, Ereignisse und anwendbaren Vorschriften." | 0:30 |
| 13 | Click "Analyse starten" | "Ich starte jetzt die Analyse. Das System durchsucht alle relevanten Dokumente und wendet die kommunalen Vorschriften an." | 0:10 |
| 14 | ⏳ WAIT 8-12 seconds | "Die Analyse dauert einige Sekunden. Das System prüft Vergaberegeln, Bauvorschriften, Tarifverträge — alle relevanten Regelwerke der Kommune." | 0:10 |
| 15 | **Decision Package** appears | "Hier sehen Sie das Ergebnis: eine strukturierte Empfehlung mit zentralen Feststellungen, Handlungsschritten, zitierten Belegen und anwendbaren Vorschriften." | 2:00 |
| 16 | Scroll through findings | "Die KI zeigt, welche Vorschriften relevant sind, und erklärt die Entscheidungsgrundlage." | 1:00 |
| 17 | Point out human review notice | "Wichtig: Die KI-Empfehlung ist eine EntscheidungsHILFE. Die endgültige Entscheidung trifft immer der zuständige Sachbearbeiter." | 0:30 |
| 18 | Click "← Zurück zum Fall" | "Nach der Analyse kehren Sie zum Fall zurück und können die nächsten Schritte einleiten." | 0:10 |
| 19 | Click "Dashboard" in header | "Zurück zur Übersicht." | 0:10 |
| 20 | Click "Abmelden" | "Zum Abschluss melde ich mich ab." | 0:10 |

### Navigation to AVOID during the demo
- **"Dokumente"** in header → placeholder ("folgt in einer späteren Phase")
- **"Wissen"** → placeholder
- **"Assistant"** → placeholder
- **"Entscheidungen"** → placeholder
- **"Profil"** → placeholder
- **"Neuer Fall"** button → 404 (not implemented)
- **"Dokument hochladen"** on dashboard → 404
- **"In Assistant öffnen"** on case detail → placeholder

### Talking Points (German)

1. **Was ist der Verwaltungsassistent?**
   "Der Verwaltungsassistent ist ein KI-gestütztes System zur Entscheidungsunterstützung in der Kommunalverwaltung. Er analysiert Ihre Dokumente, erkennt anwendbare Vorschriften und erstellt strukturierte Entscheidungsempfehlungen."

2. **Warum KI in der Verwaltung?**
   "Kommunale Vorschriften sind komplex und ändern sich häufig. Der Verwaltungsassistent hilft Sachbearbeitern, die richtigen Regelungen zu finden und anzuwenden — schneller und mit weniger Fehlern."

3. **Datenschutz und Sicherheit**
   "Alle Daten bleiben lokal. Die KI läuft auf Ihrer eigenen Infrastruktur — es werden keine Daten an externe Dienste gesendet."

4. **Menschliche Kontrolle**
   "Die KI ersetzt keine Entscheidungen. Sie bereitet Informationen auf und macht Vorschläge. Die endgültige Entscheidung trifft immer ein Mensch."

---

## If Something Fails

### Ollama not responding
**Symptom**: "Analyse starten" produces no result or error message
**What to say**: "Lassen Sie mich kurz prüfen, ob der KI-Dienst verfügbar ist." 
**Recovery**:
```bash
# Check if Ollama is running
curl http://localhost:11434/api/tags
# If not, restart:
ollama serve &
```
**Fallback**: Explain the architecture — "Der KI-Dienst läuft als lokaler Service. In einer Produktionsumgebung würde dieser automatisch überwacht und neu gestartet werden."

### Empty data / no cases visible
**Symptom**: Dashboard shows all zeros, case list is empty
**What to say**: "Für diese Demo habe ich einige Beispieldaten vorbereitet. Lassen Sie mich kurz prüfen..."
**Recovery**: Pre-seeded data should have been loaded. If not, explain the data ingestion pipeline conceptually.

### Page returns 500 error
**Symptom**: "500 — Interner Serverfehler" page appears
**What to say**: "Das System hat einen unerwarteten Zustand erreicht. In einer Produktionsumgebung würde dieser Fehler protokolliert und an das Support-Team gemeldet werden. Lassen Sie mich die Seite neu laden."
**Recovery**: Navigate back to `/dashboard` and continue from there.

### Slow AI response (>30 seconds)
**Symptom**: "Analyse starten" takes very long
**What to say**: "Bei der ersten Analyse muss das KI-Modell geladen werden. In einer Produktionsumgebung ist das Modell dauerhaft im Speicher, sodass Analysen sofort starten."
**Recovery**: Wait. If >60 seconds, refresh the page and try again.

### General fallback
If anything goes wrong that can't be fixed in 30 seconds, switch to a slide deck or architecture diagram and explain the system conceptually. Never show stack traces or terminal output to a municipal audience.

---

## After the Demo

- [ ] Clear browser session data (logout may have been tested with demo credentials)
- [ ] Stop the application: `Ctrl+C` in terminal
- [ ] (Optional) Stop Ollama: `ollama stop` or leave running for follow-up
- [ ] Collect audience questions and feedback
- [ ] Note which features generated the most interest for future prioritization

---

## Demo Configuration Summary

| Setting | Value |
|---------|-------|
| Application port | 8081 |
| Profile | `dev` (H2 in-memory database) |
| Demo user | `admin@verwaltungsassistent.local` / `admin123` |
| AI model | `qwen2.5:14b` (Ollama) |
| Ollama URL | `http://localhost:11434` |
| Browser | Any modern browser (Chrome/Firefox/Edge) |
| Screen resolution | 1920x1080 or higher |
