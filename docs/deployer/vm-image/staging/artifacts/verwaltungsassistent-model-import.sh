#!/usr/bin/env bash
# Verwaltungsassistent Appliance — Ollama model installation
# Model weights are a SEPARATE deployable asset; they are NOT part of
# the VM image. Install them with this script (internet required) or
# copy a prepared model store to /usr/share/ollama/.ollama/models.
#
# Usage: verwaltungsassistent-model-import.sh [model ...]
#        (default: the models configured in /etc/verwaltungsassistent/verwaltungsassistent.env)
set -euo pipefail

MODELS=("$@")
if [ "${#MODELS[@]}" -eq 0 ]; then
  # shellcheck disable=SC1091
  . /etc/verwaltungsassistent/verwaltungsassistent.env
  MODELS=("$OLLAMA_CHAT_MODEL" "$OLLAMA_VERIFIER_MODEL" "$OLLAMA_EMBEDDING_MODEL")
fi

log() { printf '[verwaltungsassistent-model-import] %s\n' "$*"; }
for m in "${MODELS[@]}"; do
  log "pulling $m ..."
  ollama pull "$m"
  log "$m installed"
done
ollama list
log "done — restart not required; the application uses the models on the next request"
