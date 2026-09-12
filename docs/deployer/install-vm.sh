#!/usr/bin/env bash
# ============================================================================
# Verwaltungsassistent — install-vm.sh
#
# Applies the verwaltungsassistent-vm-bundle.tar.gz (built by build-vm-image.sh) to a fresh
# Ubuntu 22.04 VM and provisions its runtime dependencies:
#   base packages -> JRE 21 -> Docker + compose -> Ollama + demo models
#   -> extract bundle to /opt/neoquanta -> systemd service
#   -> start infrastructure (existing compose files) -> start Verwaltungsassistent
#   -> deep health check.
#
# The bundle contains the BUILT application and runtime configuration —
# NO source code and NO build step happens on the VM.
#
# Idempotent: safe to re-run; existing installations, models, containers
# and data are detected and preserved. Never wipes PostgreSQL/Qdrant/Neo4j.
#
# Usage:  sudo bash install-vm.sh /path/to/verwaltungsassistent-vm-bundle.tar.gz
# ============================================================================
set -u

BUNDLE="${1:-}"
INSTALL_ROOT="/opt/neoquanta"

step() { printf '\n== [%s] ==\n' "$1"; }
fail() { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }
ok()   { printf '   OK: %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

if [ "$(id -u)" -ne 0 ]; then fail "Run as root: sudo bash install-vm.sh <bundle>"; fi
[ -n "$BUNDLE" ] && [ -f "$BUNDLE" ] || fail "Usage: sudo bash install-vm.sh /path/to/verwaltungsassistent-vm-bundle.tar.gz"

CHAT_MODEL="${OLLAMA_CHAT_MODEL:-qwen2.5:14b}"
VERIFIER_MODEL="${OLLAMA_VERIFIER_MODEL:-qwen2.5:14b}"
EMBED_MODEL="${OLLAMA_EMBEDDING_MODEL:-nomic-embed-text}"
APP_PORT="${VA_PORT:-8081}"

# ── 1. Base packages + Java 21 runtime ───────────────────────────────────────
step "Base packages + Java 21 runtime"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg >/dev/null || fail "base packages failed"
if have java && java -version 2>&1 | grep -q 'version "21'; then
    ok "Java 21 present"
else
    if apt-get install -y -qq openjdk-21-jre-headless >/dev/null 2>&1; then
        ok "openjdk-21-jre-headless installed"
    else
        TAR=/tmp/temurin21.tar.gz
        curl -fsSL -o "$TAR" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse" \
            || fail "Could not download Temurin 21 JRE"
        mkdir -p /opt/jre21 && tar -xzf "$TAR" -C /opt/jre21 --strip-components=1
        update-alternatives --install /usr/bin/java java /opt/jre21/bin/java 2100 \
            || fail "update-alternatives failed"
        ok "Temurin 21 JRE installed"
    fi
fi
java -version 2>&1 | grep -q 'version "21' || fail "Java 21 verification failed"

# ── 2. Docker + compose ──────────────────────────────────────────────────────
step "Docker Engine + Compose"
if have docker; then
    ok "Docker present"
else
    apt-get install -y -qq docker.io docker-compose-v2 >/dev/null || fail "Docker install failed"
    systemctl enable --now docker >/dev/null || fail "Docker start failed"
    ok "Docker installed"
fi
docker info >/dev/null 2>&1 || fail "Docker daemon unreachable"
docker compose version >/dev/null 2>&1 || fail "docker compose missing"

# ── 3. Ollama + demo models ──────────────────────────────────────────────────
step "Ollama + demo models"
if have ollama; then
    ok "Ollama present"
else
    curl -fsSL https://ollama.com/install.sh | sh >/dev/null 2>&1 || fail "Ollama install failed"
    ok "Ollama installed"
fi
systemctl enable --now ollama >/dev/null 2>&1 || true
for i in $(seq 1 30); do curl -sf --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1 && break; sleep 2; done
curl -sf --max-time 5 http://localhost:11434/api/tags >/dev/null || fail "Ollama API unreachable"
for model in "$CHAT_MODEL" "$VERIFIER_MODEL" "$EMBED_MODEL"; do
    if ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$model"; then
        ok "Model present: $model"
    else
        printf '   Pulling %s ...\n' "$model"
        ollama pull "$model" >/dev/null 2>&1 || fail "Model pull failed: $model"
        ok "Model pulled: $model"
    fi
done

# ── 4. Extract the deployment bundle (application + config + data) ───────────
step "Installing Verwaltungsassistent to $INSTALL_ROOT"
mkdir -p "$INSTALL_ROOT/logs"
tar -xzf "$BUNDLE" -C / || fail "Bundle extraction failed"
[ -f "$INSTALL_ROOT/app/verwaltungsassistent.jar" ] || fail "Bundle incomplete: jar missing"
ok "bundle extracted"

# Dedicated non-root application user
id -u verwaltungsassistent >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin verwaltungsassistent
chown -R verwaltungsassistent:verwaltungsassistent "$INSTALL_ROOT" || fail "chown failed"
ok "user verwaltungsassistent ready"

# ── 5. Infrastructure containers (existing compose files, data preserved) ────
step "Infrastructure: PostgreSQL + Qdrant + Neo4j"
docker compose -f "$INSTALL_ROOT/app/docker-compose.yml" up -d postgres >/dev/null \
    || fail "postgres start failed"
docker compose -f "$INSTALL_ROOT/app/docker-compose-prod.yml" up -d qdrant neo4j >/dev/null \
    || fail "qdrant/neo4j start failed"
for i in $(seq 1 60); do
    S=$(docker inspect --format '{{.State.Health.Status}}' va-postgres 2>/dev/null || echo missing)
    [ "$S" = "healthy" ] && break
    sleep 3
done
[ "${S:-}" = "healthy" ] || fail "PostgreSQL not healthy"
ok "PostgreSQL healthy"
for svc in "http://localhost:6333/collections:Qdrant" "http://localhost:7474:Neo4j"; do
    url="${svc%%:*}"; name="${svc##*:}"
    READY=0
    for i in $(seq 1 40); do curl -sf --max-time 3 "$url" >/dev/null 2>&1 && { READY=1; break; }; sleep 3; done
    [ "$READY" = "1" ] || fail "$name not ready"
    ok "$name ready"
done

# ── 6. systemd service ───────────────────────────────────────────────────────
step "systemd service"
cp "$INSTALL_ROOT/systemd/neoquanta.service" /etc/systemd/system/neoquanta.service
systemctl daemon-reload
systemctl enable neoquanta >/dev/null || fail "systemctl enable failed"
systemctl restart neoquanta || fail "service start failed"
ok "neoquanta.service enabled"

# ── 7. Deep health check ─────────────────────────────────────────────────────
step "Health check"
READY=0
for i in $(seq 1 60); do
    if curl -sf --max-time 3 "http://localhost:$APP_PORT/login" >/dev/null 2>&1; then
        READY=1; break
    fi
    sleep 5
done
[ "$READY" = "1" ] || fail "Verwaltungsassistent web not ready — check journalctl -u neoquanta"
bash "$INSTALL_ROOT/bin/health.sh" || fail "Deep health check failed"
ok "ALL READY"

# ── 8. Summary ───────────────────────────────────────────────────────────────
step "Installation complete"
printf '   Verwaltungsassistent:        RUNNING (systemd: neoquanta)\n'
printf '   Web:         http://<server-ip>:%s\n' "$APP_PORT"
printf '   Demo users:  admin@verwaltungsassistent.local / admin123 | user@verwaltungsassistent.local / user1234\n'
printf '   Logs:        journalctl -u neoquanta -f | /opt/neoquanta/logs/verwaltungsassistent.log\n'
printf '   Status:      /opt/neoquanta/bin/status.sh\n'
printf '   Data:        %s/data (uploads) + Docker volumes (PostgreSQL/Qdrant/Neo4j)\n' "$INSTALL_ROOT"
printf '\nThe three demo PDFs are staged in %s/data/uploads/ as INITIAL DEMO DATA.\n' "$INSTALL_ROOT"
printf 'Upload them once via Dokumente -> Hochladen to trigger the existing ingestion pipeline.\n'
exit 0
