#!/usr/bin/env bash
# Verwaltungsassistent Appliance — application upgrade (JAR replacement)
#
#   copy new JAR → verify checksum → new release directory →
#   switch current symlink → restart Verwaltungsassistent → health check →
#   keep previous release for rollback
#
# Usage:  verwaltungsassistent-upgrade.sh <new-jar> [--expected-sha256 <hash>]
#         verwaltungsassistent-rollback.sh          (restore previous release)
set -euo pipefail

RELEASES=/opt/verwaltungsassistent/releases
CURRENT=/opt/verwaltungsassistent/current
APP_URL="${Verwaltungsassistent_APP_URL:-http://localhost:8081}"
NEW_JAR="${1:?usage: verwaltungsassistent-upgrade.sh <new-jar> [--expected-sha256 <hash>]}"
EXPECTED_SHA=""
[ "${2:-}" = "--expected-sha256" ] && EXPECTED_SHA="${3:-}"

log() { printf '[verwaltungsassistent-upgrade] %s\n' "$*"; }

[ -f "$NEW_JAR" ] || { echo "ERROR: $NEW_JAR not found"; exit 1; }
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run as root"; exit 1; }

VERSION=$(basename "$NEW_JAR" .jar)
VERSION=$(echo "$VERSION" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+.*' | head -1 || echo "$VERSION")
NEW_DIR="$RELEASES/$VERSION"

# ── 1. checksum ──
ACTUAL_SHA=$(sha256sum "$NEW_JAR" | cut -d' ' -f1)
if [ -n "$EXPECTED_SHA" ] && [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "ERROR: checksum mismatch (expected $EXPECTED_SHA, got $ACTUAL_SHA)"; exit 1
fi
log "checksum ok: $ACTUAL_SHA"

# ── 2. new release directory ──
if [ -d "$NEW_DIR" ]; then
  echo "ERROR: release $VERSION already exists"; exit 1
fi
mkdir -p "$NEW_DIR"
cp "$NEW_JAR" "$NEW_DIR/verwaltungsassistent.jar"
chown -R verwaltungsassistent:verwaltungsassistent "$NEW_DIR"
echo "$ACTUAL_SHA" > "$NEW_DIR/verwaltungsassistent.jar.sha256"

# ── 3. switch symlink + restart ──
OLD=$(readlink -f "$CURRENT" || true)
ln -sfn "$NEW_DIR" "$CURRENT"
systemctl restart verwaltungsassistent

# ── 4. health check ──
ok=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$APP_URL/login" || true)
  if [ "$code" = "200" ]; then ok=1; break; fi
  sleep 10
done

if [ "$ok" != "1" ]; then
  log "health check FAILED — rolling back to $OLD"
  ln -sfn "$OLD" "$CURRENT"
  systemctl restart verwaltungsassistent
  code=$(curl -s -o /dev/null -w '%{http_code}' "$APP_URL/login" || true)
  [ "$code" = "200" ] && log "rollback ok: $OLD" || log "ROLLBACK ALSO FAILED — manual action required"
  exit 1
fi

log "upgrade complete: $CURRENT -> $NEW_DIR (previous release kept: $OLD)"
