#!/usr/bin/env bash
# Shows service URLs and what the agent registry knows (uses your identity token).
source "$(dirname "$0")/common.sh"
TOKEN=$(gcloud auth print-identity-token)
for svc in $SERVICES; do
  printf '%-24s %s\n' "$svc" "$(gcloud run services describe "$svc" --project "$PROJECT_ID" --region "$(region_of "$svc")" \
    --format='value(status.url)' 2>/dev/null || echo 'not deployed')"
done
echo
echo "Registry summary (via the orchestrator):"
curl -s -H "Authorization: Bearer $TOKEN" "$(url_of guest-ops-orchestrator)/api/console/summary"; echo
