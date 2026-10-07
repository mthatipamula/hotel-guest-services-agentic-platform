#!/usr/bin/env bash
# Runs the golden-dataset evaluation against a running orchestrator and publishes the report to it.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GCP_PROJECT_ID:?Set GCP_PROJECT_ID}"
./gradlew -q :services:eval-runner:bootJar
ORCHESTRATOR_URL=${ORCHESTRATOR_URL:-http://localhost:${ORCHESTRATOR_PORT:-8080}} \
  java -jar services/eval-runner/build/libs/eval-runner.jar "$@"
