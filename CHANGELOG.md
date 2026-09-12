# Changelog

All notable changes to the Verwaltungsassistent project.

## 1.0.0-RC2 — 2026-09-11

### Added
- **Self-contained Docker installation** — `compose.yaml` brings up the application, PostgreSQL
  (pgvector), Qdrant, Neo4j and Ollama (with automatic model download); `Dockerfile` builds the
  application from source; GPU override for NVIDIA hosts.
- **Install and restore scripts** — `deploy/install-linux.sh`, `deploy/install-windows.bat`,
  `deploy/restore-demo-data.sh` / `.bat` with checksum-verified restore of the bundled demo state.
- **Bundled demo data** — `deploy/demo-dataset/` (documents, e-mails, attachments, geo photos) and a
  prepared demo backup (`deploy/demo-data/verwaltungsassistent-demo-backup-2026-09-07.tar.gz`).
- **Documentation** — installation guides for Linux and Windows, a PDF edition of this README,
  third-party notices and the updated technical white paper (v1.1).

### Changed
- **Package namespace** — all Java packages and Maven coordinates now use the neutral
  `verwaltungsassistent` namespace.
- **Technical white paper (v1.1)** — the answer-boundary chapter now describes the deterministic
  evidence gate: the final grounded/insufficient decision is computed from question-anchored
  evidence, while the verifier independently flags contradictions.

### Notes
- The application code (retrieval, verification, grounding, workflow) is unchanged from the
  development baseline of this release.
