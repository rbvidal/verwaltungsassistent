# Verwaltungsassistent Ubuntu 22.04 VM Appliance

Bootbares VM-Image (QCOW2) mit der Verwaltungsassistent-/Verwaltungsassistent-Anwendung
als Laufzeit-Appliance für Kommunalkunden. Das Image enthält **keinen
Quellcode, keine Build-Werkzeuge und keine Secrets**.

## Getrennte Artefakte (nicht vermischen)

| Artefakt | Inhalt | Ersetzbar durch |
|----------|--------|-----------------|
| VM-Image (`verwaltungsassistent-ubuntu-22.04.qcow2`) | Ubuntu 22.04 + Java 21 + Docker + Ollama + Appliance-Mechanik | Neuaufbau |
| Anwendungs-JAR | eine Version der Anwendung | `verwaltungsassistent-upgrade.sh` (ohne Image-Neuaufbau) |
| Korpus (`verwaltungsassistent-demo-corpus-<v>.tar.zst`) | autoritative Dokumente (separates Paket: 18 PDFs) | `verwaltungsassistent-corpus-import.sh` / neues Korpus-Archiv |
| Modellgewichte (Ollama-Modelle) | qwen2.5:7b, nomic-embed-text, (optional 14b) | `verwaltungsassistent-model-import.sh` |
| Kundendaten | `/var/lib/verwaltungsassistent/**` | Backup/Restore |

## Verzeichnisstruktur

```
/opt/verwaltungsassistent/
    releases/1.0.0/verwaltungsassistent.jar   ← JAR-Versionen
    current -> releases/1.0.0/                ← aktive Version (Symlink)
    bin/                                      ← verwaltungsassistent-*.sh Werkzeuge

/etc/verwaltungsassistent/
    application-production.yml                ← externe App-Konfiguration
    verwaltungsassistent.env                                  ← Secrets (0640 root:verwaltungsassistent)

/var/lib/verwaltungsassistent/                               ← PERSISTENTE KUNDENDATEN
    corpus/documents/                        ← autoritative PDFs
    corpus/manifest/                         ← Korpus-Provenienz
    uploads/                                 ← App-Uploads (APP_UPLOAD_DIR)
    postgres/                                ← PostgreSQL-Volume
    qdrant/                                  ← Qdrant-Storage
    neo4j/                                   ← Neo4j-Graph
    backups/                                 ← verwaltungsassistent-backup.sh-Ausgaben

/var/log/verwaltungsassistent/verwaltungsassistent.log                       ← Anwendungslog
```

Der Datenpfad ist über die App-Konfiguration (`APP_UPLOAD_DIR`) und die
Container-Bind-Mounts dokumentiert; alle persistenten Daten liegen unter
`/var/lib/verwaltungsassistent` — **nie** unter `/opt/verwaltungsassistent/releases`.

### Demo-Korpus im Image

Das Basis-Image enthält bewusst nur **3 repräsentative PDFs**
(Bundesreisekostengesetz, AV zu § 55 LHO Berlin, Bauordnung für Berlin),
die über die echte Ingestion-Pipeline indexiert sind (PostgreSQL-Chunks,
Qdrant-Vektoren, Neo4j-Graph). Das **vollständige Demo-Korpus**
(18 PDFs) wird als separates Paket `verwaltungsassistent-demo-corpus-1.0.0.tar.zst`
ausgeliefert und mit `verwaltungsassistent-corpus-import.sh` importiert — der Import
nutzt ausschließlich die bestehende Ingestion-Pipeline.

## Dienste

| Komponente | Bereitstellung | Port (nur 127.0.0.1) |
|------------|----------------|------------------------|
| Verwaltungsassistent-Anwendung | systemd `verwaltungsassistent.service` (User `verwaltungsassistent`, nicht root) | 8081 |
| PostgreSQL 16 (pgvector) | Docker `va-postgres` | 5432 |
| Qdrant | Docker `verwaltungsassistent-qdrant` | 6333 |
| Neo4j 5 Community | Docker `va-neo4j` | 7687 |
| Ollama | Host-Dienst `ollama.service` | 11434 |

Firewall (ufw): eingehend nur 22/tcp (SSH) und 8081/tcp (Verwaltungsassistent).
SSH: nur Schlüssel, kein Root-Login, keine Passwort-Authentifizierung.

## Demo-Zugang (ausdrücklich Demo!)

Das Image wird mit dem Profil `demo` betrieben; beim ersten Start legt
die Anwendung die Demo-Benutzer an:

- `admin@verwaltungsassistent.local` / `admin123` (ADMIN)
- `user@verwaltungsassistent.local` / `user1234` (USER)

Für den Kundeneinsatz sind eigene Benutzer und das Rotieren der Secrets
in `/etc/verwaltungsassistent/verwaltungsassistent.env` (JWT_SECRET, POSTGRES_PASSWORD, NEO4J_PASSWORD)
vorgesehen. **Keine Produktions-Secrets sind im Image enthalten** — die
Werte im Image sind beim Build zufällig generiert.

## Updates

### Anwendungs-Update (JAR)
```bash
sudo /opt/verwaltungsassistent/bin/verwaltungsassistent-upgrade.sh /tmp/verwaltungsassistent-1.1.0.jar [--expected-sha256 <hash>]
```
Prüft Checksum, legt `releases/1.1.0` an, schaltet `current` um, startet
Verwaltungsassistent neu, prüft `/login`; bei Fehlschlag automatischer Rollback auf die
vorherige Version. Kein Image-Neuaufbau nötig.

### Korpus-Update
```bash
# 1. neues Korpus ablegen (ersetzt nichts automatisch!)
sudo mkdir -p /var/lib/verwaltungsassistent/corpus/documents
sudo tar --zstd -xf verwaltungsassistent-demo-corpus-1.1.0.tar.zst -C /var/lib/verwaltungsassistent/corpus
# 2. über die bestehende Ingestion-Pipeline importieren
sudo /opt/verwaltungsassistent/bin/verwaltungsassistent-corpus-import.sh /var/lib/verwaltungsassistent/corpus/documents
```
Wichtig: Die abgeleiteten Strukturen (Chunks, Embeddings, Qdrant-Vektoren,
Neo4j-Graph) entstehen ausschließlich über die Ingestion-Pipeline des
Applications — es werden keine Datenbanken/Vektoren von Hand geschrieben.
Ein reiner Austausch der PDF-Dateien allein aktualisiert Qdrant/Neo4j
**nicht**; dafür ist der Import-Schritt zwingend. Die vorherige
Korpus-Version vor dem Überschreiben separat sichern
(`verwaltungsassistent-backup.sh` erledigt das).

### Modell-Update
```bash
sudo /opt/verwaltungsassistent/bin/verwaltungsassistent-model-import.sh            # Modelle aus /etc/verwaltungsassistent/verwaltungsassistent.env
sudo /opt/verwaltungsassistent/bin/verwaltungsassistent-model-import.sh qwen2.5:14b   # oder gezielt
```
Modellgewichte sind ein separates Artefakt und nicht Teil des Images.

### Konfigurations-Update
`/etc/verwaltungsassistent/application-production.yml` bzw. `/etc/verwaltungsassistent/verwaltungsassistent.env` anpassen
und `sudo systemctl restart verwaltungsassistent`.

### OS-Updates
```bash
sudo apt update && sudo apt upgrade -y   # Ubuntu-Sicherheitsupdates
```

## Backup / Restore

`sudo /opt/verwaltungsassistent/bin/verwaltungsassistent-backup.sh [ziel]` sichert:

- PostgreSQL (`pg_dump` — Benutzer, Fälle, Dokument-Metadaten, Audit)
- Qdrant (Snapshot der Collection `mda_chunks`)
- Neo4j (`neo4j-admin database dump`)
- `uploads/`, `corpus/`, `/etc/verwaltungsassistent/`

Nicht aus PDFs rekonstruierbar sind: Benutzer/Konten, Fälle und deren
Phasen, Audit-Historie und Notizen (PostgreSQL). Chunks/Vektoren/Graph
lassen sich aus dem Korpus über die Ingestion-Pipeline neu aufbauen —
ein Snapshot-Restore ist aber schneller und konsistenter.

Restore-Überblick:
1. `docker cp` des PostgreSQL-Dumps in `va-postgres` → `pg_restore`.
2. Qdrant-Snapshot unter `/var/lib/verwaltungsassistent/qdrant/snapshots` ablegen und über
   die Snapshot-API der Collection wiederherstellen.
3. Neo4j-Dump in `va-neo4j` kopieren → `neo4j-admin database load`.
4. `uploads/` und `corpus/` entpacken, `/etc/verwaltungsassistent` wiederherstellen,
   Dienste neu starten.

## Datenbank-Migrationen (bekannte Einschränkung)

Die Demo-/Anwendungskonfiguration nutzt `hibernate.ddl-auto: update`.
Es gibt **keine** Flyway-/Liquibase-Migrationen. Ein JAR-Upgrade kann
daher Schemaänderungen implizit anwenden — ein dokumentierter
Downgrade-Pfad existiert nicht. Vor einem Upgrade mit Schemaänderung:
Backup einspielen (siehe oben) und den Rollback über die vorherige
Release-Version testen.

## Build-Reproduzierbarkeit

- Basis: offizielles Ubuntu-22.04-Cloud-Image (jammy-server-cloudimg).
- Provisionierung: cloud-init NoCloud-Seed (ISO, `seed/seed-iso.py`),
  einmalig beim Build; das ausgelieferte Image bootet ohne Seed.
- Orchestrierung: `build-vm-image.sh` (`artifacts | provision | verify | compact`).
- Boot-Test: QEMU mit WHPX (Windows Hypervisor Platform) auf dem
  Build-Host; Fallback TCG (`ACCEL=tcg`) für Umgebungen ohne WHPX.
  Verifikation über SSH mit `verify-image.sh` im Gast.
- Build-Schlüssel (`staging/build-key`) wird vor Auslieferung aus dem
  Image entfernt; das verteilte Image enthält keine Build-Zugänge.

## Sicherheits-Baseline

Implementiert: dedizierter nicht-root-User `verwaltungsassistent`; systemd-Hardening
(NoNewPrivileges, PrivateTmp, ProtectSystem=strict, ProtectHome,
ReadWritePaths nur Datenpfade); ufw mit nur 22/8081 eingehend;
SSH nur Schlüssel ohne Root; Secrets 0640 root:verwaltungsassistent; keine Quellen,
keine Build-Werkzeuge, keine Dev-Dienste im Image.

Empfohlen (kundenspezifisch, nicht im Basis-Image): eigene SSH-Schlüssel,
Rotation der generierten Secrets, TLS-Terminierung vor der App,
Netzwerksegmentierung.

Nicht implementiert: SELinux (Ubuntu nutzt AppArmor — bleibt aktiv);
ein AppArmor-Sonderprofil für den Java-Prozess wurde bewusst nicht
erzwungen, um die Wartbarkeit nicht zu gefährden.

## Ports & Zugriff

- SSH: 22 (Schlüssel `~/.ssh/id_*` des Betreibers)
- Verwaltungsassistent-Web-UI: 8081 (`http://<vm-ip>:8081/login`)
- Alle Datenbank-Ports sind an 127.0.0.1 gebunden.
