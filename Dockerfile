# ─────────────────────────────────────────────────────────────
# Verwaltungsassistent — municipal decision assistant (Verwaltungsassistent)
#
# Multi-stage build: Maven builder → minimal JRE 21 runtime.
# The build context is the repository root. Everything the build
# does NOT need (backups, demo data, docs, benchmarks) is excluded
# via .dockerignore to keep the context small.
#
# Build:  docker compose -f compose.yaml build     (or: docker build -t va-app .)
# Run:    docker compose -f compose.yaml up -d
# ─────────────────────────────────────────────────────────────

# ── Build stage ──
FROM maven:3.9-eclipse-temurin-21-alpine@sha256:1744c98bba593f0093506be57894c6254888e7adb88bf8cbaf1fed7ee8d6a804 AS builder
WORKDIR /build
COPY . .
# -am builds the platform modules verwaltungsassistent-web depends on.
# Tests run in CI, not in the image build.
RUN mvn -B -pl verwaltungsassistent-web -am package -DskipTests

# ── Runtime stage ──
FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699 AS runtime
# docker-cli: the admin "Datensicherung/Datenwiederherstellung" feature
# drives the host Docker daemon (docker exec/cp/run) through the mounted
# /var/run/docker.sock.
RUN apk add --no-cache wget docker-cli && \
    addgroup -g 1001 -S app && adduser -u 1001 -S -G app app

WORKDIR /app
COPY --from=builder /build/verwaltungsassistent-web/target/verwaltungsassistent-web-*.jar app.jar
RUN mkdir -p /app/uploads /app/backups && chown -R app:app /app

USER app
EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
