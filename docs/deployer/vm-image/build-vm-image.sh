#!/usr/bin/env bash
# ============================================================================
# Verwaltungsassistent Ubuntu 22.04 VM Appliance — reproducible image build (Windows host)
#
# Pipeline:
#   1. artifacts: copy the built JAR + appliance files into staging/artifacts
#   2. seed ISO:  cloud-init NoCloud (pycdlib) with the build SSH key
#   3. HTTP server: serves staging/artifacts to the VM (10.0.2.2:8000)
#   4. provisioning boot:  QEMU (TCG) boots the Ubuntu 22.04 cloud image
#      with the seed; cloud-init installs and configures the appliance and
#      powers off when done.
#   5. verification boot:  boots the provisioned image WITHOUT seed; the
#      operator runs verify-image.sh over SSH (see verify step below) and
#      powers the guest off (ACPI).
#   6. compaction: qemu-img convert → verwaltungsassistent-ubuntu-22.04.qcow2 + manifest
#
# Requirements on the build host:
#   QEMU (qemu-img + qemu-system-x86_64), Python 3 with pycdlib, curl,
#   OpenSSH client (ssh/scp), Maven-built JAR.
#
# Usage: bash build-vm-image.sh provision|verify|compact
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QEMU_DIR="/c/Program Files/qemu"
QEMU_IMG="$QEMU_DIR/qemu-img.exe"
QEMU_SYS="$QEMU_DIR/qemu-system-x86_64.exe"
STAGING="$HERE/staging"
ARTIFACTS="$STAGING/artifacts"
BASE_IMG="$STAGING/ubuntu-22.04-cloudimg-base.qcow2"
BASE_RAW="$STAGING/ubuntu-22.04-cloudimg-base.raw"
WORK_IMG="$STAGING/verwaltungsassistent-ubuntu-22.04-work.raw"
FINAL_IMG="$HERE/verwaltungsassistent-ubuntu-22.04.qcow2"
SEED_ISO="$STAGING/seed.iso"
KEY="$STAGING/build-key"
APP_VERSION="1.0.0"
ZSTD="${ZSTD:-zstd}"
[ -x "$ZSTD" ] || ZSTD="$STAGING/zstd-bin/zstd-v1.5.6-win64/zstd.exe"
APP_PORT_HOST=8082
SSH_PORT_HOST=2222
RAM_MB=12288
SMP=8

die() { echo "ERROR: $*" >&2; exit 1; }

ensure_tools() {
  [ -x "$QEMU_IMG" ] || die "QEMU not found at $QEMU_DIR (winget install SoftwareFreedomConservancy.QEMU)"
  command -v python >/dev/null || die "python required"
  python -c "import pycdlib" 2>/dev/null || die "pycdlib missing (pip install pycdlib)"
  command -v ssh >/dev/null || die "OpenSSH client required"
}

# ── 1. artifacts ──────────────────────────────────────────────────────────────
prepare_artifacts() {
  echo "[build] preparing artifacts ..."
  local jar="$HERE/../../../verwaltungsassistent-web/target/verwaltungsassistent-web-1.0.0-RC2.jar"
  [ -f "$jar" ] || die "JAR not built: $jar"
  cp "$jar" "$ARTIFACTS/verwaltungsassistent.jar"

  # corpus archive (demo corpus, staged into /var/lib/verwaltungsassistent/corpus)
  local corpus_src="$HERE/../../../verwaltungsassistent-web/uploads"
  local corpus_tmp="$STAGING/corpus-stage"
  rm -rf "$corpus_tmp"; mkdir -p "$corpus_tmp/documents" "$corpus_tmp/manifest"
  cp "$corpus_src"/BRKG.pdf "$corpus_src"/AV-55-LHO-Berlin.pdf "$corpus_src"/BauO-Bln.pdf \
     "$corpus_src"/ServicePortal-*.pdf "$corpus_tmp/documents/" 2>/dev/null || true
  cp "$HERE/../../../docs/deployer/buergeramt-corpus-manifest.txt" "$corpus_tmp/manifest/" 2>/dev/null || true
  "$ZSTD" --version | head -1
  # image-internal demo corpus: 3 representative PDFs only
  mkdir -p "$corpus_tmp/demo/documents" "$corpus_tmp/demo/manifest"
  cp "$corpus_src"/BRKG.pdf "$corpus_src"/AV-55-LHO-Berlin.pdf "$corpus_src"/BauO-Bln.pdf "$corpus_tmp/demo/documents/" 2>/dev/null || true
  cp "$HERE/../../../docs/deployer/buergeramt-corpus-manifest.txt" "$corpus_tmp/demo/manifest/" 2>/dev/null || true
  tar --use-compress-program "$ZSTD" -cf "$ARTIFACTS/corpus.tar.zst" -C "$corpus_tmp/demo" documents manifest
  # separate replaceable corpus package: full demo corpus (18 PDFs) + manifest
  tar --use-compress-program "$ZSTD" -cf "$HERE/verwaltungsassistent-demo-corpus-1.0.0.tar.zst" -C "$corpus_tmp" documents manifest
  sha256sum "$HERE/verwaltungsassistent-demo-corpus-1.0.0.tar.zst" | tee "$HERE/SHA256SUMS.corpus"
  echo "  image corpus: 3 PDFs -> corpus.tar.zst | separate package: verwaltungsassistent-demo-corpus-1.0.0.tar.zst ($(ls "$corpus_tmp/documents" | wc -l) PDFs)"

  # build SSH key (build-time only; removed from the image before distribution)
  cp "$KEY.pub" "$ARTIFACTS/build-key.pub"
  if [ ! -f "$KEY" ]; then
    ssh-keygen -t ed25519 -f "$KEY" -N "" -C "verwaltungsassistent-build" -q
  fi

  python "$HERE/seed/seed-iso.py" \
    "$HERE/seed/user-data" "$HERE/seed/meta-data" "$KEY.pub" "$SEED_ISO"
  # NoCloud seed via HTTP (SMBIOS ds=nocloud;s=URL) — robust under QEMU
  mkdir -p "$STAGING/seed"
  python - "$HERE/seed/user-data" "$KEY.pub" "$STAGING/seed/user-data" <<'PYEOF'
import sys
data = open(sys.argv[1], encoding="utf-8").read()
pubkey = open(sys.argv[2], encoding="utf-8").read().strip()
open(sys.argv[3], "w", encoding="utf-8").write(data.replace("BUILDKEY_PLACEHOLDER", pubkey))
PYEOF
  cp "$HERE/seed/meta-data" "$STAGING/seed/meta-data"
  # unique instance-id per run so cloud-init re-runs on every provisioning boot
  sed -i "s/^instance-id:.*/instance-id: verwaltungsassistent-build-$(date +%s)/" "$STAGING/seed/meta-data"
  echo "[build] artifacts + seed (ISO + HTTP) ready"
}

# ── 2. HTTP artifact server ───────────────────────────────────────────────────
serve() {
  cd "$STAGING"
  python -m http.server 8000 --bind 0.0.0.0
}

qemu_base_args() {
  "$QEMU_SYS" -machine q35 -accel "${ACCEL:-whpx}" -m "$RAM_MB" -smp "$SMP" \
    -drive file="$WORK_IMG",if=virtio,format=raw \
    -netdev user,id=n1,hostfwd=tcp:127.0.0.1:${SSH_PORT_HOST}-:22,hostfwd=tcp:127.0.0.1:${APP_PORT_HOST}-:8081 \
    -device virtio-net-pci,netdev=n1 \
    -nographic -no-reboot
}

# ── 3. provisioning boot ──────────────────────────────────────────────────────
provision() {
  ensure_tools
  prepare_artifacts
  echo "[build] converting base image to fully-allocated raw (avoids sparse-tail read errors on Windows QEMU) ..."
  "$QEMU_IMG" convert -O raw "$BASE_IMG" "$BASE_RAW"
  cp "$BASE_RAW" "$WORK_IMG"
  echo "[build] resizing work disk to 30G (cloud-init growpart expands the root partition) ..."
  "$QEMU_IMG" resize "$WORK_IMG" 30G
  echo "[build] starting HTTP server (artifacts) ..."
  ( serve > "$STAGING/http.log" 2>&1 & echo $! > "$STAGING/http.pid" )
  sleep 3
  echo "[build] provisioning boot (QEMU/TCG, ${RAM_MB}MB, ${SMP} vCPU) — this takes a while ..."
  qemu_base_args -smbios "type=1,serial=ds=nocloud;s=http://10.0.2.2:8000/seed/"
  local rc=$?
  kill "$(cat "$STAGING/http.pid")" 2>/dev/null || true
  echo "[build] provisioning boot finished (QEMU exit=$rc)"
  [ "$rc" -eq 0 ] || die "provisioning boot failed"
}

# ── 4. verification boot ──────────────────────────────────────────────────────
verify_boot() {
  ensure_tools
  [ -f "$WORK_IMG" ] || die "work image missing — run provision first"
  echo "[verify] starting HTTP server (models + artifacts) ..."
  ( serve > "$STAGING/http.log" 2>&1 & echo $! > "$STAGING/http.pid" )
  sleep 3
  echo "[verify] seedless boot — SSH: verwaltungsassistent@localhost:${SSH_PORT_HOST} (key $KEY)"
  qemu_base_args
  kill "$(cat "$STAGING/http.pid")" 2>/dev/null || true
}

# ── 5. compaction ─────────────────────────────────────────────────────────────
compact() {
  ensure_tools
  [ -f "$WORK_IMG" ] || die "work image missing"
  echo "[build] compacting to $FINAL_IMG ..."
  "$QEMU_IMG" convert -O qcow2 -c "$WORK_IMG" "$FINAL_IMG"
  sha256sum "$FINAL_IMG" | tee "$HERE/SHA256SUMS"
  "$QEMU_IMG" info "$FINAL_IMG" | head -8
}

case "${1:-}" in
  artifacts) ensure_tools; prepare_artifacts ;;
  provision) provision ;;
  verify)    verify_boot ;;
  compact)   compact ;;
  *) echo "usage: $0 artifacts|provision|verify|compact"; exit 2 ;;
esac
