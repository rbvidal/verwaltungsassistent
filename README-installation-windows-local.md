# Installation — Windows (local)

This guide installs the complete Verwaltungsassistent on a Windows machine using Docker
Desktop. The whole product — application, databases and the AI runtime — is started by a
single script, and the bundled demo data set is restored automatically.

Everything runs in Docker; Java, Maven or a database installation on the host are **not**
required. Ollama is used from the container stack, or — if already installed on the host —
directly from there.

---

## 1. Requirements

| | Minimum | Recommended |
|---|---|---|
| OS | Windows 10/11 64-bit | Windows 11 |
| Docker | Docker Desktop with the WSL2 backend | current version |
| CPU / RAM | 8 cores, 16 GB (CPU-only, slower AI answers) | 32 GB |
| GPU | optional | NVIDIA GPU with a current driver for WSL2 — `qwen2.5:14b` needs ~10 GB VRAM; CPU-only works but is slower |
| Disk | 40 GB free | 60 GB+ |
| Optional | [Ollama for Windows](https://ollama.com/download) already installed | |

Install Docker Desktop first if needed: <https://www.docker.com/products/docker-desktop/>
(WSL2 backend; enable it during setup).

## 2. Download the code

On the GitHub page of the repository: **Code → Download ZIP**, then extract the ZIP to a
folder of your choice (e.g. `C:\verwaltungsassistent`).

> Alternatively clone the repository with Git. No deploy key or GitHub account is required
> beyond normal access to the repository.

## 3. Install

Double-click **`deploy\install-windows.bat`** — or run it from a command prompt:

```bat
cd C:\verwaltungsassistent
deploy\install-windows.bat
```

The script performs the following steps:

1. starts Docker Desktop if it is not running and waits for it;
2. creates `.env` from `.env.example`;
3. detects an **Ollama running on the host** (`http://localhost:11434`) and uses it —
   in that case no models are downloaded again; otherwise the containerized Ollama
   downloads the configured models in the background;
4. builds the application image (first build: several minutes);
5. starts the databases, restores the bundled demo data set and starts the application;
6. opens the browser at <http://localhost:8081>.

When it finishes, the window shows the URL and all sign-in accounts.

## 4. Sign in

| Account | Password | Role |
|---|---|---|
| `admin@verwaltungsassistent.local` | `admin123` | ADMIN — oversight (read-mostly) |
| `superadmin@verwaltungsassistent.local` | `NcDn++2026$$` | ADMIN + SUPERADMIN — maintenance (data backup/restore, demo reset). Not listed on the login page. |
| `user@verwaltungsassistent.local` | `user1234` | USER — caseworker |
| `demo01@verwaltungsassistent.local` … `demo20@verwaltungsassistent.local` | `demo1234` | Demo staff accounts |

## 5. AI models

If a host Ollama is detected, its models are used directly — make sure the required models
are present:

```bat
ollama pull qwen2.5:14b
ollama pull nomic-embed-text
```

Otherwise Ollama runs inside the stack and downloads the models on first start (about
10 GB) — watch the progress with:

```bat
docker logs -f va-ollama-models
```

Set the model names in `.env` (`OLLAMA_CHAT_MODEL`, `OLLAMA_VERIFIER_MODEL`,
`OLLAMA_EMBEDDING_MODEL`). On CPU-only machines use `qwen2.5:7b` for chat and verifier.

The application also works without Ollama — casework, e-mails, retrieval and administration
remain functional; only AI answers are unavailable.

## 6. The bundled demo data set

`deploy\demo-data\verwaltungsassistent-demo-backup-2026-09-07.tar.gz` contains the fully prepared demo
state: PostgreSQL dump (documents, e-mails, cases, analyses, users), Neo4j graph dump,
Qdrant vector snapshot and the uploaded files (corpus, photos, attachments).

The install script restores it automatically. To re-run the restore at any time:

```bat
deploy\restore-demo-data.bat
```

The script verifies all checksums before restoring. After a restore it sets
`DEMO_STARTUP_RESET=false` in `.env` so the restored state is preserved on restarts.

| `DEMO_STARTUP_RESET` | Behaviour on every application start |
|---|---|
| `false` (after a restore) | keep the current database state |
| `true` (fresh install without restore) | deterministic in-process demo seed (cases, e-mails, geo register) |

## 7. Operations

Run these from the repository folder (or use the Docker Desktop dashboard):

```bat
docker compose ps                  REM status
docker compose logs -f app         REM application log
docker compose down                REM stop (data remains in the volumes)
docker compose up -d               REM start again
docker compose up -d --build       REM rebuild + start after updating the code
```

**Update to a newer version:** replace the extracted folder with the new ZIP content — but
keep your `.env` — and run `docker compose up -d --build`.

**Backups:** `superadmin@verwaltungsassistent.local` → Administration → *Datensicherung*. The files are
stored in the Docker volume `va_backups`; copy them out with:

```bat
docker run --rm -v va_backups:/b -v "%CD%":/out alpine cp -a /b/. /out/
```

**Complete reset:** `docker compose down -v` removes all data volumes; the next install or
restore starts from scratch.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Docker Desktop not found` / not ready | Install/start Docker Desktop and wait until it reports "Engine running". |
| `port is already allocated` for 8081, 5432, 6333, 7474 | Another stack is still running: stop it first — `docker compose down` in its folder, `docker stop va-postgres va-neo4j va-qdrant` (this stack) or `docker stop va-postgres mda-neo4j mda-qdrant` (the legacy local dev stack) — or change `APP_PORT` in `.env`. |
| WSL2 errors | Run `wsl --update` in an admin PowerShell and restart Docker Desktop. |
| Model download seems stuck | It is several GB. Check `docker logs va-ollama-models`. |
| Answers are very slow | CPU-only model execution. Use a host Ollama with GPU, or switch to `qwen2.5:7b` in `.env`. |
| Browser shows "connection refused" | The app is still starting (first start after a build can take 1–2 minutes). Check `docker compose logs -f app`. |
| Docker Desktop restarted / PC rebooted | `docker compose up -d` in the repository folder — the data in the volumes is preserved. |
