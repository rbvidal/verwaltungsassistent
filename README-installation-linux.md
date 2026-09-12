# Installation — Linux (cloud Ubuntu 22.04)

This guide installs the complete Verwaltungsassistent on a fresh Ubuntu 22.04 (or newer)
server using Docker. The whole product — application, databases and the AI runtime — is
started by a single script, and the bundled demo data set is restored automatically.

No GitHub deploy key is needed: the repository is downloaded as a ZIP file and everything
runs from the extracted folder.

---

## 1. Requirements

| | Minimum | Recommended |
|---|---|---|
| OS | Ubuntu 22.04 LTS | Ubuntu 22.04 LTS |
| CPU / RAM | 4 vCPU, 16 GB (CPU-only, slower AI answers) | 8+ vCPU, 32 GB |
| GPU | optional | NVIDIA GPU with a current driver — `qwen2.5:14b` needs ~10 GB VRAM; CPU-only works but is slower |
| Disk | 40 GB free | 60 GB+ |
| Network | Port **8081** reachable (open in the cloud firewall/security group) | |

The install script installs Docker (including the Compose plugin) if Docker is missing; if
Docker is already present without the Compose plugin, it stops with a hint — install
`docker-compose-plugin` first.
The AI models are downloaded on first start (about 10 GB); without a GPU the 14B model is
usable but slow — see [AI models](#5-ai-models).

## 2. Download the code

On the GitHub page of the repository: **Code → Download ZIP**, or on the server:

```bash
curl -LO https://github.com/rbvidal/verwaltungsassistent/archive/refs/heads/master.zip
unzip master.zip
cd verwaltungsassistent-master
```

> Alternatively clone the repository with Git. No deploy key or GitHub account is required
> beyond normal access to the repository.

## 3. Install

```bash
bash deploy/install-linux.sh
```

The script performs the following steps (it re-runs itself with `sudo` when needed):

1. installs Docker (official convenience script) if it is not present;
2. creates `.env` from `.env.example` and stores the Docker socket group;
3. detects an NVIDIA GPU (and uses `docker-compose.gpu.yml` when the NVIDIA Container
   Toolkit is available);
4. builds the application image (first build: several minutes);
5. starts PostgreSQL, Qdrant, Neo4j and Ollama — the AI models are downloaded in the
   background (several GB);
6. restores the bundled demo data set;
7. starts the application and prints the URL and all sign-in accounts.

Options:

| Flag | Meaning |
|---|---|
| `--no-restore` | do not restore the bundled demo data; start with the deterministic in-process demo seed instead |
| `--skip-build` | reuse an already built application image |

When it finishes, the output looks like:

```
  Open:     http://<server-ip>:8081

  Sign-in:
    admin@verwaltungsassistent.local          / admin123       (ADMIN)
    superadmin@verwaltungsassistent.local     / NcDn++2026$$  (maintenance)
    user@verwaltungsassistent.local           / user1234      (USER)
    demo01..demo20@verwaltungsassistent.local / demo1234      (demo staff)
```

## 4. Sign in

| Account | Password | Role |
|---|---|---|
| `admin@verwaltungsassistent.local` | `admin123` | ADMIN — oversight (read-mostly) |
| `superadmin@verwaltungsassistent.local` | `NcDn++2026$$` | ADMIN + SUPERADMIN — maintenance (data backup/restore, demo reset). Not listed on the login page. |
| `user@verwaltungsassistent.local` | `user1234` | USER — caseworker |
| `demo01@verwaltungsassistent.local` … `demo20@verwaltungsassistent.local` | `demo1234` | Demo staff accounts |

## 5. AI models

Ollama runs inside the stack and downloads the configured models on first start.
The download runs in a separate one-shot container; you can watch it:

```bash
docker logs -f va-ollama-models
```

Default models (set in `.env`):

| Setting | Default | Purpose |
|---|---|---|
| `OLLAMA_CHAT_MODEL` | `qwen2.5:14b` | answer generation |
| `OLLAMA_VERIFIER_MODEL` | `qwen2.5:14b` | evidence verification (safety boundary) |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | embeddings (vector search) |

**CPU-only hosts** should switch to a smaller chat model, e.g.:

```bash
sed -i 's/^OLLAMA_CHAT_MODEL=.*/OLLAMA_CHAT_MODEL=qwen2.5:7b/' .env
sed -i 's/^OLLAMA_VERIFIER_MODEL=.*/OLLAMA_VERIFIER_MODEL=qwen2.5:7b/' .env
docker compose up -d ollama-models
```

**Using an existing Ollama** (e.g. on the host or another machine) instead of the container:

```bash
# in .env:
COMPOSE_PROFILES=                       # do not start the containerized Ollama
OLLAMA_BASE_URL=http://host.docker.internal:11434
```

Note: the application works without Ollama as well — retrieval, casework, e-mails and
administration remain fully functional; only AI answers are unavailable.

## 6. The bundled demo data set

`deploy/demo-data/verwaltungsassistent-demo-backup-2026-09-07.tar.gz` contains the fully prepared demo
state: PostgreSQL dump (documents, e-mails, cases, analyses, users), Neo4j graph dump,
Qdrant vector snapshot and the uploaded files (corpus, photos, attachments).

The install script restores it automatically. To re-run the restore at any time:

```bash
bash deploy/restore-demo-data.sh
```

The script verifies all checksums before restoring. After a restore it sets
`DEMO_STARTUP_RESET=false` in `.env` so the restored state is preserved on restarts.

| `DEMO_STARTUP_RESET` | Behaviour on every application start |
|---|---|
| `false` (after a restore) | keep the current database state |
| `true` (fresh install without restore) | deterministic in-process demo seed (cases, e-mails, geo register) |

## 7. Operations

```bash
docker compose ps                  # status
docker compose logs -f app         # application log
docker compose down                # stop (data remains in the volumes)
docker compose up -d               # start again
docker compose up -d --build       # rebuild + start after updating the code
```

**Update to a newer version of the repository:** replace the extracted folder with the new
ZIP content — but keep your `.env` — and run `docker compose up -d --build`.

**Backups:** `superadmin@verwaltungsassistent.local` → Administration → *Datensicherung*. The backup files
are stored in the Docker volume `va_backups`; copy them out with:

```bash
docker run --rm -v va_backups:/b -v "$PWD":/out alpine cp -a /b/. /out/
```

**Complete reset:** `docker compose down -v` removes all data volumes; the next install or
restore starts from scratch.

**Debug access (loopback only):** PostgreSQL on `localhost:5432`, Qdrant on
`localhost:6333`, Neo4j Browser on `http://localhost:7474` — never exposed publicly.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `port is already allocated` | Another service uses port 8081 (or the loopback DB ports). Change `APP_PORT` in `.env` and re-run `docker compose up -d`. |
| The page does not open from outside | Port 8081 is not open in the cloud firewall/security group of the VM. |
| Model download seems stuck | It is several GB. Check `docker logs va-ollama-models`; the first start can take a while on slow connections. |
| Answers are very slow | CPU-only installation with the 14B model. Switch to `qwen2.5:7b` (see above) or use a GPU host. |
| `permission denied` on docker commands | The user was just added to the `docker` group — log out and in again, or use `sudo`. |
| Qdrant snapshot restore fails | The stack pins Qdrant `v1.15.5` because the bundled snapshot was created by 1.15.5. Do not upgrade that image without re-creating the snapshot. |
| Application not ready after restore | `docker compose logs app` — usually a database that is still starting; the restore script already waits for the databases. |
