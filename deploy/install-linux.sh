#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# One-shot installation of the Verwaltungsassistent on Ubuntu 22.04+
# (cloud VM or local machine) using Docker.
#
#   bash deploy/install-linux.sh [--no-restore] [--skip-build]
#
# Steps: install Docker if missing → build the application image → start
# PostgreSQL, Qdrant, Neo4j and Ollama (models download in the background)
# → restore the bundled demo data set → start the app and print the URL.
#
#   --no-restore   start with the deterministic in-process demo seed
#                  instead of the bundled demo data set
#   --skip-build   reuse an already built application image
# ─────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NO_RESTORE=false
SKIP_BUILD=false
for arg in "$@"; do
    case "$arg" in
        --no-restore) NO_RESTORE=true ;;
        --skip-build) SKIP_BUILD=true ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)"; exit 1 ;;
    esac
done

say() { printf '\n==> %s\n' "$*"; }

if [ "$(id -u)" -ne 0 ]; then
    say "Root privileges are required for the Docker installation — re-running with sudo ..."
    exec sudo -E bash "$0" "$@"
fi

# ── Docker ──
if ! command -v docker >/dev/null 2>&1; then
    say "Installing Docker (official convenience script) ..."
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl >/dev/null
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker >/dev/null 2>&1 || true
    if [ -n "${SUDO_USER:-}" ]; then
        usermod -aG docker "$SUDO_USER" || true
        echo "    User '$SUDO_USER' added to the docker group (takes effect after re-login)."
    fi
else
    say "Docker is already installed."
fi
docker compose version >/dev/null 2>&1 \
    || { echo "ERROR: Docker Compose v2 is missing (package docker-compose-plugin)."; exit 1; }

# ── Configuration ──
if [ ! -f .env ]; then
    say "Creating .env from .env.example ..."
    cp .env.example .env
fi
DOCKER_GID="$(stat -c '%g' /var/run/docker.sock 2>/dev/null || echo 0)"
sed -i.bak "s|^DOCKER_GID=.*|DOCKER_GID=$DOCKER_GID|" .env && rm -f .env.bak

# ── GPU detection ──
if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
    if docker info 2>/dev/null | grep -qi nvidia; then
        say "NVIDIA GPU detected — Ollama will use it."
        export COMPOSE_FILE="compose.yaml:docker-compose.gpu.yml"
    else
        echo
        echo "NOTE: An NVIDIA GPU is present, but the NVIDIA Container Toolkit is not."
        echo "      Ollama will run on the CPU (usable, but slow for 14B models)."
        echo "      GPU enablement: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html"
    fi
fi

# ── Build the application image ──
if [ "$SKIP_BUILD" = false ]; then
    say "Building the application image (first build downloads Maven dependencies, several minutes) ..."
    docker compose build app
fi

# ── Start infrastructure + model download ──
say "Starting PostgreSQL, Qdrant and Neo4j ..."
docker compose up -d --wait postgres qdrant neo4j

PROFILES="$(grep -E '^COMPOSE_PROFILES=' .env 2>/dev/null | cut -d= -f2- || true)"
if [ -n "${PROFILES//[[:space:]]/}" ]; then
    say "Starting Ollama and downloading the AI models (runs in the background, several GB) ..."
    docker compose up -d ollama ollama-models || true
else
    say "Ollama runs externally (COMPOSE_PROFILES is empty) — skipping the container."
fi

# ── Demo data + application ──
if [ "$NO_RESTORE" = true ]; then
    say "Starting the application with the deterministic demo seed (no data restore) ..."
    docker compose up -d
    APP_PORT="$(grep -E '^APP_PORT=' .env 2>/dev/null | cut -d= -f2- || true)"
    APP_PORT="${APP_PORT:-8081}"
    printf 'Waiting for the application'
    for _ in $(seq 1 80); do
        if curl -sf "http://localhost:$APP_PORT/login" >/dev/null 2>&1; then echo " — ready."; break; fi
        printf '.'; sleep 3
    done
else
    say "Restoring the bundled demo data set ..."
    bash deploy/restore-demo-data.sh
fi

# ── Summary ──
APP_PORT="$(grep -E '^APP_PORT=' .env 2>/dev/null | cut -d= -f2- || true)"
APP_PORT="${APP_PORT:-8081}"
PUBLIC_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
cat <<EOF

────────────────────────────────────────────────────────────
  Installation complete.

  Open:     http://${PUBLIC_IP:-<server-ip>}:$APP_PORT
  (make sure port $APP_PORT is open in your cloud firewall/security group)

  Sign-in:
    admin@verwaltungsassistent.local          / admin123       (ADMIN)
    superadmin@verwaltungsassistent.local     / NcDn++2026$$  (maintenance)
    user@verwaltungsassistent.local           / user1234      (USER)
    demo01..demo20@verwaltungsassistent.local / demo1234      (demo staff)

  Logs:     docker compose logs -f app
  Stop:     docker compose down
────────────────────────────────────────────────────────────
EOF
