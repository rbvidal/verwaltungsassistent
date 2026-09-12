# Verwaltungsassistent — VM Deployment (Ubuntu 22.04)

Production-style deployment of the Verwaltungsassistent Verwaltungsassistent as a
self-contained Ubuntu 22.04 VM installation. The VM runs the BUILT
application (Spring Boot jar) — never the source tree.

## Image / package format

- Build artifact: `target/verwaltungsassistent-vm-bundle.tar.gz` produced by
  `docs/deployer/build-vm-image.sh` (reproducible; run from the repository
  root; requires Maven + Java 21 on the BUILD machine only).
- The bundle contains ONLY runtime artifacts (verified source-free):
  application jar, configuration templates, initial demo PDFs, compose
  files, operations scripts and the systemd unit.
- The VM itself needs NO Maven and NO source code; runtime dependencies
  (JRE 21, Docker, Ollama models) are provisioned by
  `docs/deployer/install-vm.sh`.

## Installation layout

    /opt/neoquanta/
        app/           verwaltungsassistent.jar + docker-compose*.yml
        config/        application.yml, application-demo.yml (external config)
        data/          uploads/ (initial demo PDFs; runtime upload storage)
        logs/          verwaltungsassistent.log (systemd append output)
        bin/           start.sh, stop.sh, status.sh, health.sh
        systemd/       neoquanta.service

## Data directory

Configurable via the existing Spring property `app.upload-dir`; the
systemd unit sets `APP_UPLOAD_DIR=/opt/neoquanta/data/uploads`.
Verified locally: booting the jar with a custom `--app.upload-dir`
redirected document uploads into that directory (data-path test).

## Configuration

- Profile: `demo` (PostgreSQL persistence, demo users auto-created).
- External config: `--spring.config.additional-location=/opt/neoquanta/config/`
  (the systemd unit sets SPRING_CONFIG_ADDITIONAL_LOCATION).
- Secrets: NOT baked into the bundle. The systemd unit provides demo
  values for the local infrastructure (POSTGRES_PASSWORD, NEO4J_PASSWORD)
  and a placeholder JWT_SECRET — replace JWT_SECRET for anything beyond
  the demo. Model/URL settings inherit application.yml defaults
  (qwen2.5:14b generator+verifier, nomic-embed-text, localhost services).

## Environment variables

| Variable             | Default                     | Purpose                          |
|----------------------|-----------------------------|----------------------------------|
| VA_PORT            | 8081                        | web port                         |
| APP_UPLOAD_DIR       | /opt/neoquanta/data/uploads | upload/data directory            |
| POSTGRES_PASSWORD    | verwaltungsassistent                        | local postgres (demo)            |
| NEO4J_PASSWORD       | password                    | local neo4j (demo)               |
| JWT_SECRET           | demo placeholder            | REPLACE for real deployments     |
| OLLAMA_CHAT_MODEL    | qwen2.5:14b                 | generator                        |
| OLLAMA_VERIFIER_MODEL| qwen2.5:14b                 | verifier/parser                  |
| OLLAMA_EMBEDDING_MODEL| nomic-embed-text           | embeddings                       |

## External services (all INSIDE the VM)

Docker containers via the PROJECT'S existing compose files (no parallel
infrastructure): PostgreSQL (`va-postgres`, localhost:5432, named
volume), Qdrant (`mda-qdrant`, localhost:6333), Neo4j (`mda-neo4j`,
localhost:7474/7687). Ollama runs as a system service on localhost:11434.

## Ollama / models

Only the currently required models: `qwen2.5:14b` (generator + verifier +
semantic parser) and `nomic-embed-text` (embeddings). Pulled once by
install-vm.sh; persisted in the Ollama data directory (survives reboots).
No historical/unneeded models are included.

## Ports / firewall

| Port  | Service      | Exposed? |
|-------|--------------|----------|
| 8081  | Verwaltungsassistent web     | YES (firewall) |
| 22    | SSH (admin)  | as required |
| 5432/6333/7474/7687/11434 | infra | NO — VM-internal only |

Firewall (ufw) example:
    sudo ufw allow 8081/tcp
    sudo ufw allow OpenSSH
    sudo ufw enable

## First boot / initial demo corpus

After `sudo bash install-vm.sh verwaltungsassistent-vm-bundle.tar.gz` the system is fully
provisioned and Verwaltungsassistent is RUNNING (systemd). The three authoritative German
PDFs are staged in `/opt/neoquanta/data/uploads/` as INITIAL DEMO DATA.
Upload them once via the UI (Dokumente → Hochladen, or
`POST /documents/upload`) — the existing ingestion pipeline then builds
PostgreSQL metadata + Qdrant vectors + Neo4j graph. This is the normal
document processing path; no preconstructed vector/graph fixtures exist.

## Start / stop / status / logs

    /opt/neoquanta/bin/start.sh     (systemctl start neoquanta)
    /opt/neoquanta/bin/stop.sh      (systemctl stop neoquanta)
    /opt/neoquanta/bin/status.sh    (concise service overview)
    /opt/neoquanta/bin/health.sh    (deep health check; exit 0 = ALL READY)
    journalctl -u neoquanta -f      (or /opt/neoquanta/logs/verwaltungsassistent.log)

## Persistence (SAFE operations)

- Application restart / service restart / VM reboot: everything persists
  (PostgreSQL/Qdrant/Neo4j Docker volumes, /opt/neoquanta/data, Ollama
  models). Never run `docker compose down -v` or `docker volume rm`
  unless you intend to destroy data.

## Backup / restore (SAFE)

- PostgreSQL: `docker exec va-postgres pg_dump -U verwaltungsassistent verwaltungsassistent > backup.sql`
  restore with psql.
- Qdrant: snapshot via Qdrant API or copy the `mda-qdrant` volume.
- Neo4j: `neo4j-admin database dump` inside the container or copy the
  volume.
- Files: back up `/opt/neoquanta/data/`.

## Upgrade (SAFE — keeps config and data)

    sudo systemctl stop neoquanta
    # replace /opt/neoquanta/app/verwaltungsassistent.jar with the new jar
    # (config in /opt/neoquanta/config/ and data in /opt/neoquanta/data/
    #  are NOT touched)
    sudo systemctl start neoquanta
    /opt/neoquanta/bin/health.sh

An upgrade NEVER deletes the data directory or Docker volumes.

## Intentional full reset (DESTRUCTIVE)

    sudo systemctl stop neoquanta
    docker compose -f /opt/neoquanta/app/docker-compose.yml down -v postgres
    docker compose -f /opt/neoquanta/app/docker-compose-prod.yml down -v
    rm -rf /opt/neoquanta/data/uploads/*
    # optionally: rm -rf /opt/neoquanta   (full uninstall)
    # re-run install-vm.sh to rebuild from the bundle
