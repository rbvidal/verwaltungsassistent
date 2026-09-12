#!/usr/bin/env bash
# Verwaltungsassistent Appliance — corpus import
# Imports PDFs into the application through the EXISTING ingestion
# pipeline (POST /documents/upload). Never writes into PostgreSQL,
# Qdrant or Neo4j directly.
#
# Usage: verwaltungsassistent-corpus-import.sh [PDF-DIR]      (default /var/lib/verwaltungsassistent/corpus/documents)
#        SKIP_EXISTING=0 verwaltungsassistent-corpus-import.sh ...
set -euo pipefail

APP_URL="${Verwaltungsassistent_APP_URL:-http://localhost:8081}"
ADMIN_EMAIL="${Verwaltungsassistent_ADMIN_EMAIL:-admin@verwaltungsassistent.local}"
ADMIN_PASSWORD="${Verwaltungsassistent_ADMIN_PASSWORD:-admin123}"   # demo appliance seed user
PDF_DIR="${1:-/var/lib/verwaltungsassistent/corpus/documents}"
SKIP_EXISTING="${SKIP_EXISTING:-1}"

command -v curl >/dev/null || { echo "curl required"; exit 1; }

log() { printf '[corpus-import] %s\n' "$*"; }

# ── login (CSRF via meta tag + X-XSRF-TOKEN header — the app's own mechanism) ──
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES" "$COOKIES.form"' EXIT
curl -s -c "$COOKIES" "$APP_URL/login" -o "$COOKIES.form"
CSRF=$(grep -o 'name="_csrf" content="[^"]*"' "$COOKIES.form" | sed 's/.*content="//;s/"//' | head -1)
curl -s -b "$COOKIES" -c "$COOKIES" -o /dev/null -X POST "$APP_URL/login" \
  -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "email=$ADMIN_EMAIL" \
  --data-urlencode "password=$ADMIN_PASSWORD"
if ! curl -s -b "$COOKIES" -o /dev/null -w '%{http_code}' "$APP_URL/documents" | grep -q 200; then
  echo "ERROR: login failed for $ADMIN_EMAIL"; exit 1
fi
log "authenticated as $ADMIN_EMAIL"

# existing titles for --skip-existing
EXISTING=""
if [ "$SKIP_EXISTING" = "1" ]; then
  EXISTING=$(curl -s -b "$COOKIES" "$APP_URL/documents" \
    | grep -oE '<a href="/documents/[0-9a-f-]{36}">[^<]+' | sed 's/.*>//' | sort -u || true)
fi

imported=0; skipped=0; failed=0
for pdf in "$PDF_DIR"/*.pdf; do
  [ -e "$pdf" ] || continue
  title=$(basename "$pdf" .pdf)
  if [ -n "$EXISTING" ] && echo "$EXISTING" | grep -qF "$title"; then
    log "skip (already present): $title"; skipped=$((skipped+1)); continue
  fi
  curl -s -b "$COOKIES" -c "$COOKIES" "$APP_URL/documents/upload" -o "$COOKIES.form"
  CSRF=$(grep -o 'name="_csrf" content="[^"]*"' "$COOKIES.form" | sed 's/.*content="//;s/"//' | head -1)
  code=$(curl -s -b "$COOKIES" -o /dev/null -w '%{http_code}' -X POST "$APP_URL/documents/upload" \
    -H "X-XSRF-TOKEN: $CSRF" \
    -F "file=@$pdf;type=application/pdf" \
    -F "title=$title" \
    -F "category=POLICY_DOCUMENT")
  if [ "$code" = "200" ] || [ "$code" = "302" ]; then
    log "uploaded: $title"; imported=$((imported+1))
  else
    log "FAILED ($code): $title"; failed=$((failed+1))
  fi
  sleep 1
done

# ── wait for ingestion to finish ──
log "waiting for ingestion pipeline (PDF-Extraktion → Chunking → Embeddings → Qdrant → Neo4j) ..."
pending=1
for i in $(seq 1 120); do
  pending=$(curl -s -b "$COOKIES" "$APP_URL/documents" | grep -oE '(INGESTION_PENDING|INGESTING)' | wc -l || true)
  if [ "$pending" = "0" ]; then break; fi
  sleep 20
done
if [ "${pending:-0}" != "0" ]; then
  log "WARNING: ingestion still running after timeout (check /documents)"
else
  log "ingestion finished"
fi

log "imported=$imported skipped=$skipped failed=$failed"
exit $failed
