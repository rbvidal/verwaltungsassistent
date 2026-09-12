#!/usr/bin/env bash
# ============================================================================
# Verwaltungsassistent — build-vm-image.sh
#
# Builds the Verwaltungsassistent application and produces a reproducible VM deployment
# bundle (tar.gz) that can be applied to a fresh Ubuntu 22.04 VM via
# docs/deployer/install-vm.sh.
#
# The bundle contains ONLY runtime artifacts:
#   /opt/neoquanta/app/     executable Spring Boot jar + compose files
#   /opt/neoquanta/config/  application.yml + application-demo.yml templates
#   /opt/neoquanta/data/    uploads/ with the three authoritative demo PDFs
#   /opt/neoquanta/bin/     start/stop/status/health scripts
#   /opt/neoquanta/systemd/ neoquanta.service
# plus the installer docs/deployer/install-vm.sh.
#
# NO source code, .git, tests or build artifacts are included.
# Run from the repository root.
# ============================================================================
set -eu

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STAGE="$REPO_ROOT/target/vm-staging"
ROOT="$STAGE/opt/neoquanta"
BUNDLE="$REPO_ROOT/target/verwaltungsassistent-vm-bundle.tar.gz"
WEB="$REPO_ROOT/verwaltungsassistent-web"
JAR="$WEB/target/verwaltungsassistent-web-1.0.0-RC2.jar"

step() { printf '\n== [%s] ==\n' "$1"; }
fail() { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }

cd "$REPO_ROOT"

# ── 1. Build the application (no tests) ──────────────────────────────────────
step "Building Verwaltungsassistent application"
mvn -pl verwaltungsassistent-web -am install -DskipTests -q \
    || fail "Maven build failed"
[ -f "$JAR" ] || fail "Boot jar not found: $JAR"

# ── 2. Fresh staging tree ────────────────────────────────────────────────────
step "Staging deployment tree"
rm -rf "$STAGE"
mkdir -p "$ROOT"/{app,config,data/uploads,bin,systemd,logs}

# ── 3. Runtime artifacts only ────────────────────────────────────────────────
cp "$JAR" "$ROOT/app/verwaltungsassistent.jar"
cp "$REPO_ROOT/docker-compose.yml" "$ROOT/app/docker-compose.yml"
cp "$REPO_ROOT/docker-compose-prod.yml" "$ROOT/app/docker-compose-prod.yml"

# Configuration templates (no secrets are baked in — the systemd unit sets
# JWT_SECRET and other credentials via environment variables).
cp "$WEB/src/main/resources/application.yml" "$ROOT/config/application.yml"
cp "$WEB/src/main/resources/application-demo.yml" "$ROOT/config/application-demo.yml"

# Initial demo data: the three authoritative German PDFs. These are normal
# documents processed by the existing ingestion pipeline after first boot.
for pdf in BRKG.pdf AV-55-LHO-Berlin.pdf BauO-Bln.pdf; do
    [ -f "$WEB/uploads/$pdf" ] || fail "Demo PDF missing: $WEB/uploads/$pdf"
    cp "$WEB/uploads/$pdf" "$ROOT/data/uploads/$pdf"
done
cp "$WEB/uploads/README_DEMO_DATA.txt" "$ROOT/data/uploads/" 2>/dev/null || true

# ── 4. Operations scripts + systemd unit ─────────────────────────────────────
cat > "$ROOT/bin/start.sh" <<'EOF'
#!/usr/bin/env bash
# Start Verwaltungsassistent via systemd (safe to call repeatedly).
set -u
if systemctl is-active --quiet neoquanta; then
    echo "Verwaltungsassistent: already RUNNING"
else
    sudo systemctl start neoquanta || { echo "Verwaltungsassistent: FAILED to start"; exit 1; }
    echo "Verwaltungsassistent: started"
fi
EOF
cat > "$ROOT/bin/stop.sh" <<'EOF'
#!/usr/bin/env bash
set -u
sudo systemctl stop neoquanta || { echo "Verwaltungsassistent: FAILED to stop"; exit 1; }
echo "Verwaltungsassistent: stopped"
EOF
cat > "$ROOT/bin/status.sh" <<'EOF'
#!/usr/bin/env bash
# Concise status overview — application + infrastructure + GPU.
set -u
APP_PORT="${VA_PORT:-8081}"
printf 'Verwaltungsassistent:        %s\n' "$(systemctl is-active neoquanta 2>/dev/null || echo UNKNOWN)"
PG=$(docker inspect --format '{{.State.Health.Status}}' va-postgres 2>/dev/null || echo missing)
[ "$PG" = "healthy" ] && printf 'PostgreSQL:  READY\n' || printf 'PostgreSQL:  %s\n' "$PG"
curl -sf --max-time 3 http://localhost:6333/collections >/dev/null 2>&1 \
    && printf 'Qdrant:      READY\n' || printf 'Qdrant:      NOT READY\n'
curl -sf --max-time 3 http://localhost:7474 >/dev/null 2>&1 \
    && printf 'Neo4j:       READY\n' || printf 'Neo4j:       NOT READY\n'
curl -sf --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1 \
    && printf 'Ollama:      READY\n' || printf 'Ollama:      NOT READY\n'
nvidia-smi >/dev/null 2>&1 && printf 'GPU:         AVAILABLE\n' || printf 'GPU:         UNAVAILABLE\n'
curl -sf --max-time 3 "http://localhost:$APP_PORT/login" >/dev/null 2>&1 \
    && printf 'Web:         http://<server>:%s (UP)\n' "$APP_PORT" \
    || printf 'Web:         NOT reachable on port %s\n' "$APP_PORT"
EOF
cat > "$ROOT/bin/health.sh" <<'EOF'
#!/usr/bin/env bash
# Deep health check: returns 0 only when everything is ready.
set -u
APP_PORT="${VA_PORT:-8081}"
curl -sf --max-time 5 "http://localhost:$APP_PORT/login" >/dev/null || { echo "Verwaltungsassistent web not ready"; exit 1; }
[ "$(docker inspect --format '{{.State.Health.Status}}' va-postgres 2>/dev/null)" = "healthy" ] \
    || { echo "PostgreSQL not healthy"; exit 1; }
curl -sf --max-time 5 http://localhost:6333/collections >/dev/null || { echo "Qdrant not ready"; exit 1; }
curl -sf --max-time 5 http://localhost:7474 >/dev/null || { echo "Neo4j not ready"; exit 1; }
curl -sf --max-time 5 http://localhost:11434/api/tags >/dev/null || { echo "Ollama not ready"; exit 1; }
echo "ALL READY"
EOF
chmod +x "$ROOT/bin/"*.sh

cat > "$ROOT/systemd/neoquanta.service" <<'EOF'
[Unit]
Description=NeoQuanta Verwaltungsassistent Verwaltungsassistent
After=docker.service network-online.target
Wants=docker.service

[Service]
Type=simple
User=verwaltungsassistent
Group=verwaltungsassistent
WorkingDirectory=/opt/neoquanta
Environment=SPRING_PROFILES_ACTIVE=demo
Environment=SPRING_CONFIG_ADDITIONAL_LOCATION=/opt/neoquanta/config/
Environment=VA_PORT=8081
Environment=APP_UPLOAD_DIR=/opt/neoquanta/data/uploads
# Demo credentials for the local infrastructure (postgres/neo4j/ollama).
# Replace JWT_SECRET with a real secret outside the immutable bundle.
Environment=POSTGRES_PASSWORD=verwaltungsassistent
Environment=NEO4J_PASSWORD=password
Environment=JWT_SECRET=demo-jwt-secret-change-me
ExecStart=/usr/bin/java -Xmx1g -jar /opt/neoquanta/app/verwaltungsassistent.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/neoquanta/logs/verwaltungsassistent.log
StandardError=append:/opt/neoquanta/logs/verwaltungsassistent.log

[Install]
WantedBy=multi-user.target
EOF

# ── 5. Produce the bundle ────────────────────────────────────────────────────
step "Creating bundle"
cp "$REPO_ROOT/docs/deployer/install-vm.sh" "$STAGE/install-vm.sh"
tar -C "$STAGE" -czf "$BUNDLE" opt install-vm.sh
ls -la "$BUNDLE"

# ── 6. Security/content verification of the staging tree ─────────────────────
step "Verifying staged content"
if find "$STAGE" -name ".git" -o -name "*.java" -o -name "*.class" -o -name "pom.xml" | grep -q .; then
    fail "Staging tree contains source/build artifacts"
fi
printf '   staged tree is source-free: OK\n'
find "$STAGE" -type f | sed "s|$STAGE||" | sort | head -30
printf '\nBundle: %s\n' "$BUNDLE"
exit 0
