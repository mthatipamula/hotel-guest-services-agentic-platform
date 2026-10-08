#!/usr/bin/env bash
# Opens the IAM-protected console through an authenticated local proxy: http://localhost:8080
source "$(dirname "$0")/common.sh"
PORT=${PORT:-8080}
echo "Console: http://localhost:$PORT  (Ctrl+C to stop)"
gcloud run services proxy guest-ops-orchestrator --project "$PROJECT_ID" --region "$REGION" --port "$PORT"
