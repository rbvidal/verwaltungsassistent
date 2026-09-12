#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Restore the bundled demo data set into the Docker stack
# (compose.yaml): PostgreSQL dump, Neo4j graph dump, Qdrant
# vector snapshot and the upload storage files.
#
#   bash deploy/restore-demo-data.sh
#
# After the restore, DEMO_STARTUP_RESET is set to false in .env so the
# restored state survives restarts, and the whole stack is started.
#
# Overridable via environment / .env:
#   BACKUP_ARCHIVE, APP_PORT, POSTGRES_PORT, QDRANT_PORT,
#   PG_CONTAINER, NEO4J_CONTAINER, NEO4J_VOLUME, UPLOADS_VOLUME
# ─────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck disable=SC1091
if [ -f .env ]; then set -a; . ./.env; set +a; fi

BACKUP_ARCHIVE="${BACKUP_ARCHIVE:-deploy/demo-data/verwaltungsassistent-demo-backup-2026-09-07.tar.gz}"
BACKUP_NAME="$(basename "$BACKUP_ARCHIVE" .tar.gz)"

APP_PORT="${APP_PORT:-8081}"
QDRANT_PORT="${QDRANT_PORT:-6333}"
PG_CONTAINER="${PG_CONTAINER:-va-postgres}"
NEO4J_CONTAINER="${NEO4J_CONTAINER:-va-neo4j}"
NEO4J_IMAGE="${NEO4J_IMAGE:-neo4j:5.26-community}"
NEO4J_VOLUME="${NEO4J_VOLUME:-va_neo4j_data}"
UPLOADS_VOLUME="${UPLOADS_VOLUME:-va_uploads}"
POSTGRES_DB="${POSTGRES_DB:-verwaltungsassistent}"
POSTGRES_USER="${POSTGRES_USER:-verwaltungsassistent}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-mda_chunks}"

# Plain "docker compose": picks compose.yaml (preferred filename) and honors
# COMPOSE_FILE, e.g. when install-linux.sh adds docker-compose.gpu.yml.
COMPOSE=(docker compose)

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

set_env() {
    touch .env
    if grep -q "^$1=" .env; then
        sed -i.bak "s|^$1=.*|$1=$2|" .env && rm -f .env.bak
    else
        printf '%s=%s\n' "$1" "$2" >> .env
    fi
    # .env was sourced above, so the shell environment would otherwise take
    # precedence over the file for docker compose interpolation.
    export "$1=$2"
}

[ -f "$BACKUP_ARCHIVE" ] || die "Backup archive not found: $BACKUP_ARCHIVE"
docker compose version >/dev/null 2>&1 || die "Docker Compose (v2) not found."

say "Starting PostgreSQL, Qdrant and Neo4j ..."
"${COMPOSE[@]}" up -d --wait postgres qdrant neo4j

say "Extracting backup archive ..."
# RESTORE_TMP_DIR is an escape hatch (e.g. a Windows path when running this
# script from Git-Bash, where /tmp paths cannot be bind-mounted into Docker).
TMP="${RESTORE_TMP_DIR:-$(mktemp -d)}"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$BACKUP_ARCHIVE" -C "$TMP"
BUNDLE="$TMP/$BACKUP_NAME"

say "Verifying checksums ..."
( cd "$BUNDLE" && grep -v '\./SHA256SUMS$' SHA256SUMS | sha256sum -c --quiet - )

say "Restoring PostgreSQL ..."
"${COMPOSE[@]}" stop app >/dev/null 2>&1 || true
docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\";" \
    -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\";"
docker exec -i "$PG_CONTAINER" pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --no-owner --no-privileges < "$BUNDLE/postgres/verwaltungsassistent.dump"

say "Restoring Neo4j graph (offline load) ..."
"${COMPOSE[@]}" stop neo4j >/dev/null 2>&1 || true
docker run --rm -v "$NEO4J_VOLUME:/data" -v "$BUNDLE/neo4j:/backup:ro" "$NEO4J_IMAGE" \
    neo4j-admin database load neo4j --from-path=/backup --overwrite-destination=true
"${COMPOSE[@]}" start neo4j >/dev/null

say "Restoring Qdrant vector snapshot ..."
SNAP="$(ls "$BUNDLE"/qdrant/*.snapshot | head -1)"
curl -sf -X DELETE "http://localhost:$QDRANT_PORT/collections/$QDRANT_COLLECTION" >/dev/null || true
curl -sf -X POST \
    "http://localhost:$QDRANT_PORT/collections/$QDRANT_COLLECTION/snapshots/upload?priority=snapshot&wait=true" \
    -F "snapshot=@$SNAP" >/dev/null
POINTS="$(curl -s "http://localhost:$QDRANT_PORT/collections/$QDRANT_COLLECTION" \
    | grep -o '"points_count":[0-9]*' | head -1 | cut -d: -f2)"
[ -n "${POINTS:-}" ] && [ "$POINTS" -gt 0 ] || die "Qdrant snapshot restore failed (points_count=$POINTS)."
echo "    Qdrant collection '$QDRANT_COLLECTION': $POINTS points"

say "Restoring upload storage ..."
docker run --rm -v "$UPLOADS_VOLUME:/target" -v "$BUNDLE/storage:/src:ro" alpine \
    sh -c 'cp -a /src/. /target/ && chown -R 1001:1001 /target'

say "Preserving the restored state (DEMO_STARTUP_RESET=false) ..."
set_env DEMO_STARTUP_RESET false

say "Starting the application ..."
"${COMPOSE[@]}" up -d

printf '    Waiting for the application'
for _ in $(seq 1 80); do
    if curl -sf "http://localhost:$APP_PORT/login" >/dev/null 2>&1; then
        echo " — ready."
        printf '\n────────────────────────────────────────────────────────────\n'
        printf '  Verwaltungsassistent is running:  http://localhost:%s\n' "$APP_PORT"
        printf '\n  Sign-in:\n'
        printf '    admin@verwaltungsassistent.local      / admin123        (ADMIN)\n'
        printf '    superadmin@verwaltungsassistent.local / NcDn++2026$$   (maintenance)\n'
        printf '    user@verwaltungsassistent.local       / user1234       (USER)\n'
        printf '    demo01..demo20@verwaltungsassistent.local / demo1234   (demo staff)\n'
        printf '────────────────────────────────────────────────────────────\n\n'
        exit 0
    fi
    printf '.'
    sleep 3
done
echo
die "Application did not become ready. Check: docker compose -f compose.yaml logs app"
