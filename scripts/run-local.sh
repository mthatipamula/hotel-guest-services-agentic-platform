#!/usr/bin/env bash
# Builds all services and starts them locally against the Postgres container.
# Each service runs from its own copy of its jar, so rebuilding never disturbs running services.
#
#   export GCP_PROJECT_ID=your-project      (required: Vertex AI for Gemini and embeddings)
#   gcloud auth application-default login   (once)
#   ./scripts/run-local.sh                  start everything
#   ./scripts/run-local.sh stop             stop everything
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_DIR=.run
mkdir -p "$RUN_DIR"
ORCHESTRATOR_PORT=${ORCHESTRATOR_PORT:-8080}

stop() {
  for f in "$RUN_DIR"/*.pid; do
    [ -e "$f" ] || continue
    kill "$(cat "$f")" 2>/dev/null || true
    rm -f "$f"
  done
  echo "Stopped."
}

if [ "${1:-}" = "stop" ]; then stop; exit 0; fi
: "${GCP_PROJECT_ID:?Set GCP_PROJECT_ID to your Google Cloud project id}"
export GCP_LOCATION=${GCP_LOCATION:-us-central1}
export SERVICE_SHARED_TOKEN=${SERVICE_SHARED_TOKEN:-local-dev-token-change-me}

stop >/dev/null
docker compose up -d db
until docker compose exec -T db pg_isready -U guestops >/dev/null 2>&1; do sleep 1; done

./gradlew -q bootJar
start() {  # name port [extra env...]
  local name=$1 port=$2; shift 2
  cp "services/$name/build/libs/$name.jar" "$RUN_DIR/$name.jar"
  env PORT="$port" PUBLIC_BASE_URL="http://localhost:$port" "$@" \
    java -jar "$RUN_DIR/$name.jar" > "$RUN_DIR/$name.log" 2>&1 &
  echo $! > "$RUN_DIR/$name.pid"
  for _ in $(seq 1 90); do
    if curl -sf "http://localhost:$port/actuator/health" >/dev/null; then echo "  $name up on :$port"; return; fi
    sleep 1
  done
  echo "  $name failed to start; see $RUN_DIR/$name.log"; exit 1
}

echo "Starting services..."
start agent-registry 8090
start hotel-mcp-server 8091
start revenue-agents 8092
start partner-agents 8093
start guest-ops-orchestrator "$ORCHESTRATOR_PORT"
echo
echo "Operations console: http://localhost:$ORCHESTRATOR_PORT"
echo "Agent registry:     http://localhost:8090/api/registry/agents"
echo "Run evaluations:    ./scripts/run-evals.sh"
