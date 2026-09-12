#!/usr/bin/env bash
# Verwaltungsassistent Appliance — first-boot provisioning (cloud-init, build time only)
# Downloads the application artifacts from the build host HTTP server
# (http://10.0.2.2:8000 under QEMU user networking) and assembles the
# appliance layout. Runs once; the resulting image carries no source,
# no credentials and no build keys.
set -euxo pipefail

HTTP_BASE="${BUILD_HTTP_BASE:-http://10.0.2.2:8000/artifacts}"
export DEBIAN_FRONTEND=noninteractive

# ── build-time SSH access FIRST (so the builder can debug interactively) ──
# The build key is removed from the image AFTER verification, before distribution.
# The operator user is created here deterministically — independent of
# cloud-init's users module (which failed on repeated runs).
id ubuntu >/dev/null 2>&1 || useradd -m -s /bin/bash -d /home/ubuntu -g ubuntu ubuntu
usermod -aG sudo,adm ubuntu
passwd -l ubuntu
id verwaltungsassistent >/dev/null 2>&1 || useradd --system --create-home --home-dir /opt/verwaltungsassistent --shell /bin/bash verwaltungsassistent
curl -fsSL --retry 8 --retry-delay 5 "$HTTP_BASE/build-key.pub" -o /tmp/build-key.pub
for u in ubuntu verwaltungsassistent; do
  home=$(getent passwd "$u" 2>/dev/null | cut -d: -f6 || true)
  [ -n "$home" ] || continue
  mkdir -p "$home/.ssh"
  grep -qF "$(cat /tmp/build-key.pub)" "$home/.ssh/authorized_keys" 2>/dev/null \
    || cat /tmp/build-key.pub >> "$home/.ssh/authorized_keys"
  chmod 700 "$home/.ssh"; chmod 600 "$home/.ssh/authorized_keys"
  chown -R "$u:$u" "$home/.ssh"
done
echo "ubuntu ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/90-verwaltungsassistent-build
chmod 440 /etc/sudoers.d/90-verwaltungsassistent-build

# ── packages ──
apt-get update -y
apt-get install -y --no-install-recommends \
    docker.io docker-compose-v2 curl jq ufw openssh-server ca-certificates \
    zstd unzip gpg
# Java 21 (Spring Boot 3.3 target); Temurin fallback if not in Ubuntu repos
if ! apt-get install -y --no-install-recommends openjdk-21-jre-headless; then
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb jammy main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -y
  apt-get install -y --no-install-recommends temurin-21-jre
fi
java -version

# ── Ollama (host service; models are a separate asset) ──
# Prefer the build-host copy when complete; otherwise download with resume.
curl -s -o /dev/null -w '%{http_code}' "$HTTP_BASE/ollama-linux-amd64.tar.zst" -r 0-0 >/dev/null 2>&1 || true
curl -fsSL --retry 3 --retry-delay 5 -o /tmp/ollama.head "$HTTP_BASE/ollama-linux-amd64.tar.zst" -r 0-0   && [ "$(stat -c%s /tmp/ollama.head 2>/dev/null || echo 0)" -gt 0 ]   && curl -fsSL --retry 5 --retry-delay 5 "$HTTP_BASE/ollama-linux-amd64.tar.zst" -o /tmp/ollama.tar.zst   && [ "$(stat -c%s /tmp/ollama.tar.zst)" -gt 1200000000 ]   || curl -L -C - --retry 20 --retry-delay 10 --max-time 7200        -o /tmp/ollama.tar.zst https://ollama.com/download/ollama-linux-amd64.tar.zst
tar -C /usr --zstd -xf /tmp/ollama.tar.zst
id ollama >/dev/null 2>&1 || useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama
cat > /etc/systemd/system/ollama.service <<'UNIT'
[Unit]
Description=Ollama Service
After=network-online.target

[Service]
ExecStart=/usr/bin/ollama serve
User=ollama
Group=ollama
Restart=always
RestartSec=3
Environment="PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
Environment="OLLAMA_HOST=127.0.0.1:11434"

[Install]
WantedBy=default.target
UNIT
systemctl daemon-reload
systemctl enable --now ollama

# ── users and directories ──
useradd --system --create-home --home-dir /opt/verwaltungsassistent --shell /bin/bash verwaltungsassistent || true
mkdir -p /opt/verwaltungsassistent/releases/1.0.0 /opt/verwaltungsassistent/bin /etc/verwaltungsassistent
mkdir -p /var/lib/verwaltungsassistent/{uploads,corpus/documents,corpus/manifest,postgres,qdrant,neo4j,backups}
mkdir -p /var/log/verwaltungsassistent

# ── download artifacts from build host ──
for f in verwaltungsassistent.jar docker-compose-appliance.yml \
         application-production.yml verwaltungsassistent.service verwaltungsassistent-corpus-import.sh \
         verwaltungsassistent-upgrade.sh verwaltungsassistent-model-import.sh verwaltungsassistent-backup.sh verify-image.sh \
         verwaltungsassistent-infra.sh corpus.tar.zst; do
  curl -fsSL --retry 5 --retry-delay 5 "$HTTP_BASE/$f" -o "/tmp/$f"
done
sha256sum /tmp/verwaltungsassistent.jar

# ── layout ──
mv /tmp/verwaltungsassistent.jar /opt/verwaltungsassistent/releases/1.0.0/verwaltungsassistent.jar
ln -sfn /opt/verwaltungsassistent/releases/1.0.0 /opt/verwaltungsassistent/current
cp /tmp/docker-compose-appliance.yml /opt/verwaltungsassistent/current/docker-compose-appliance.yml
cp /tmp/application-production.yml /etc/verwaltungsassistent/application-production.yml
cp /tmp/verwaltungsassistent.service /etc/systemd/system/verwaltungsassistent.service
for s in verwaltungsassistent-corpus-import.sh verwaltungsassistent-upgrade.sh verwaltungsassistent-model-import.sh verwaltungsassistent-backup.sh verify-image.sh verwaltungsassistent-infra.sh; do
  cp "/tmp/$s" /opt/verwaltungsassistent/bin/"$s"; chmod +x /opt/verwaltungsassistent/bin/"$s"
done

# ── secrets (generated at build time — demo appliance) ──
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
PG_PW=$(openssl rand -hex 16)
NEO4J_PW=$(openssl rand -hex 16)
cat > /etc/verwaltungsassistent/verwaltungsassistent.env <<EOF
JWT_SECRET=$JWT_SECRET
POSTGRES_PASSWORD=$PG_PW
NEO4J_PASSWORD=$NEO4J_PW
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_CHAT_MODEL=qwen2.5:7b
OLLAMA_VERIFIER_MODEL=qwen2.5:7b
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
QDRANT_COLLECTION=mda_chunks
EOF
chmod 640 /etc/verwaltungsassistent/verwaltungsassistent.env
chgrp verwaltungsassistent /etc/verwaltungsassistent/verwaltungsassistent.env

# ── corpus (demo corpus staged into the persistent data area) ──
tar --zstd -xf /tmp/corpus.tar.zst -C /var/lib/verwaltungsassistent/corpus
chown -R verwaltungsassistent:verwaltungsassistent /var/lib/verwaltungsassistent /var/log/verwaltungsassistent /opt/verwaltungsassistent/releases /opt/verwaltungsassistent/bin

# ── docker ──
systemctl enable docker
systemctl start docker
# load images from the build-host export when available (guest uplink is
# unreliable for multi-hundred-MB pulls); otherwise pull with retries.
if curl -fsSL --retry 3 --retry-delay 5 "$HTTP_BASE/verwaltungsassistent-images.tar" -o /tmp/verwaltungsassistent-images.tar    && [ "$(stat -c%s /tmp/verwaltungsassistent-images.tar)" -gt 100000000 ]; then
  docker load -i /tmp/verwaltungsassistent-images.tar
else
  for img in pgvector/pgvector:pg16 qdrant/qdrant:latest neo4j:5-community; do
    for i in $(seq 1 10); do
      docker pull "$img" && break
      echo "retry $i: $img"
      sleep 30
    done
  done
fi
# start the infrastructure containers (helper exports verwaltungsassistent.env for interpolation)
/opt/verwaltungsassistent/bin/verwaltungsassistent-infra.sh up -d

# ── application ──
systemctl daemon-reload
systemctl enable verwaltungsassistent
systemctl start verwaltungsassistent

# ── firewall: only SSH and the application ──
ufw default deny incoming
ufw allow 22/tcp
ufw allow 8081/tcp
ufw --force enable

# ── SSH hardening: keys only, no root ──
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl enable --now ssh

# ── final marker + shutdown (ACPI) ──
echo "provisioned $(date -u +%FT%TZ)" > /var/lib/verwaltungsassistent/.provisioned
sync
poweroff
