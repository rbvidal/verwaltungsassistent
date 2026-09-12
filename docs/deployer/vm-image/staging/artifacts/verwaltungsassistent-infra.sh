#!/usr/bin/env bash
# Verwaltungsassistent Appliance — infrastructure container lifecycle
# Sources /etc/verwaltungsassistent/verwaltungsassistent.env so docker-compose can interpolate
# secrets, then delegates to docker compose.
#
# Usage: verwaltungsassistent-infra.sh [up -d|down|ps|logs|restart]
set -euo pipefail
set -a
# shellcheck disable=SC1091
. /etc/verwaltungsassistent/verwaltungsassistent.env
set +a
docker compose -f /opt/verwaltungsassistent/current/docker-compose-appliance.yml "$@"
