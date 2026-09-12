#!/usr/bin/env bash
# Verwaltungsassistent Appliance — backup
# Backs up the data that CANNOT be rebuilt from source documents:
#   PostgreSQL (documents metadata, users, cases, audit)
#   Qdrant (snapshot of the vector collection)
#   Neo4j (graph dump)
#   uploaded documents + corpus + configuration
# Derived indexes (chunks/vectors/graph) can also be rebuilt from the
# source PDFs via the ingestion pipeline — but restoring a snapshot is
# faster and keeps audit/case data consistent.
#
# Usage: verwaltungsassistent-backup.sh [backup-dir]   (default /var/lib/verwaltungsassistent/backups)
set -euo pipefail

DEST="${1:-/var/lib/verwaltungsassistent/backups}"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="$DEST/$STAMP"
mkdir -p "$OUT"

log() { printf '[verwaltungsassistent-backup] %s\n' "$*"; }

# PostgreSQL — logical dump
log "PostgreSQL dump ..."
docker exec va-postgres pg_dump -U verwaltungsassistent -d verwaltungsassistent -Fc -f /tmp/verwaltungsassistent.dump
docker cp va-postgres:/tmp/verwaltungsassistent.dump "$OUT/va-postgres.dump"
docker exec va-postgres rm /tmp/verwaltungsassistent.dump

# Qdrant — snapshot API
log "Qdrant snapshot ..."
COLLECTION="${QDRANT_COLLECTION:-mda_chunks}"
curl -s -X POST "http://localhost:6333/collections/$COLLECTION/snapshots" >/dev/null
SNAP=$(curl -s "http://localhost:6333/collections/$COLLECTION/snapshots" | grep -oE '"name":"[^"]+"' | head -1 | sed 's/"name":"//;s/"//')
curl -s "http://localhost:6333/collections/$COLLECTION/snapshots/$SNAP" -o "$OUT/qdrant-$COLLECTION.snapshot"

# Neo4j — database dump
log "Neo4j dump ..."
docker exec va-neo4j neo4j-admin database dump neo4j --to-path=/tmp
docker cp va-neo4j:/tmp/neo4j.dump "$OUT/neo4j.dump"
docker exec va-neo4j rm /tmp/neo4j.dump

# files
log "Files (uploads, corpus, config) ..."
tar -C /var/lib/verwaltungsassistent -czf "$OUT/uploads.tar.gz" uploads
tar -C /var/lib/verwaltungsassistent -czf "$OUT/corpus.tar.gz" corpus
tar -C /etc -czf "$OUT/verwaltungsassistent-config.tar.gz" verwaltungsassistent

log "backup written: $OUT"
echo "$OUT"
