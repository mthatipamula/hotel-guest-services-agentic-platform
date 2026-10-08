#!/usr/bin/env bash
# Deploys the five services to Cloud Run and the eval runner as a Cloud Run job.
#
# Security model on GCP:
#  - Every service requires IAM (no unauthenticated access). Services call each other with Google-signed
#    ID tokens (SERVICE_AUTH_MODE=GOOGLE_ID_TOKEN); each service account is granted roles/run.invoker only
#    on the services it actually calls.
#  - partner-agents runs in a different region ($PARTNER_REGION) to model an external partner network.
#  - Services scale to zero when idle (cost ~0); the registry tolerates missing heartbeats for 7 days.
#  - --no-cpu-throttling keeps CPU while an instance is alive so heartbeats and registration retries run
#    in the background (instance-based billing; idle instances still shut down after ~15 minutes).
source "$(dirname "$0")/common.sh"

DEPLOYER=$(gcloud config get-value account 2>/dev/null)
REGISTRY_URL=$(url_of agent-registry)
MCP_URL=$(url_of hotel-mcp-server)
ORCH_URL=$(url_of guest-ops-orchestrator)

common_env() {  # service
  echo "SERVICE_AUTH_MODE=GOOGLE_ID_TOKEN,GCP_PROJECT_ID=$PROJECT_ID,GCP_LOCATION=$VERTEX_LOCATION,PUBLIC_BASE_URL=$(url_of "$1"),REGISTRY_URL=$REGISTRY_URL"
}
db_env() {  # database
  echo "DB_URL=jdbc:postgresql:///$1?cloudSqlInstance=$SQL_CONNECTION&socketFactory=com.google.cloud.sql.postgres.SocketFactory,DB_USER=$DB_USER,DB_POOL_SIZE=4"
}

deploy() {  # service extra-env [extra flags...]
  local svc=$1 env=$2; shift 2
  log "Deploying $svc to $(region_of "$svc")"
  gcloud run deploy "$svc" --project "$PROJECT_ID" --region "$(region_of "$svc")" \
    --image "$IMAGE_BASE/$svc:$TAG" --service-account "$(sa_of "$(sa_name "$svc")")" \
    --no-allow-unauthenticated --memory 1Gi --cpu 1 --cpu-boost --no-cpu-throttling \
    --min-instances 0 --max-instances 3 --concurrency 40 --timeout 300 \
    --set-env-vars "^@^$(common_env "$svc" | tr ',' '@')@$(echo "$env" | tr ',' '@')" "$@" --quiet
}

deploy agent-registry "$(db_env agentregistry),STALE_AFTER_SECONDS=604800" \
  --set-secrets "DB_PASSWORD=$DB_PASSWORD_SECRET:latest"
deploy hotel-mcp-server "$(db_env hoteldata),RESET_DEMO_DATA=false" \
  --set-secrets "DB_PASSWORD=$DB_PASSWORD_SECRET:latest"
deploy revenue-agents "MCP_FALLBACK_URL=$MCP_URL"
deploy partner-agents "UNUSED=0"
deploy guest-ops-orchestrator "$(db_env guestops),MCP_FALLBACK_URL=$MCP_URL" \
  --set-secrets "DB_PASSWORD=$DB_PASSWORD_SECRET:latest,CONSOLE_ADMIN_KEY=guestops-console-admin-key:latest" --timeout 600

log "Least-privilege invoker grants (who may call whom)"
invoker() {  # target caller-service-account
  gcloud run services add-iam-policy-binding "$1" --project "$PROJECT_ID" --region "$(region_of "$1")" \
    --member "$2" --role roles/run.invoker --quiet >/dev/null
  echo "  $2 -> $1"
}
sa() { echo "serviceAccount:$(sa_of "$(sa_name "$1")")"; }
# Everyone registers with and reads policies from the registry.
for c in hotel-mcp-server revenue-agents partner-agents guest-ops-orchestrator eval-runner; do invoker agent-registry "$(sa "$c")"; done
# Hotel data tools: orchestrator and revenue agents only (partners never touch hotel data).
invoker hotel-mcp-server "$(sa guest-ops-orchestrator)"
invoker hotel-mcp-server "$(sa revenue-agents)"
# A2A agents: called by the orchestrator; the registry fetches their Agent Cards to verify registration.
for t in revenue-agents partner-agents; do
  invoker "$t" "$(sa guest-ops-orchestrator)"
  invoker "$t" "$(sa agent-registry)"
done
# The eval runner calls the orchestrator's API.
invoker guest-ops-orchestrator "$(sa eval-runner)"
# You (the deployer) can open the console and reset demo data.
invoker guest-ops-orchestrator "user:$DEPLOYER"
invoker hotel-mcp-server "user:$DEPLOYER"
if [ "$PUBLIC_UI" = "true" ]; then
  echo "  WARNING: making the console public (allUsers). Anyone with the URL can use it."
  invoker guest-ops-orchestrator "allUsers"
fi

log "Waiting 90s for IAM grants to propagate"
sleep 90

log "Rolling new revisions so services register now that IAM grants exist"
for svc in hotel-mcp-server revenue-agents partner-agents guest-ops-orchestrator; do
  gcloud run services update "$svc" --project "$PROJECT_ID" --region "$(region_of "$svc")" \
    --update-env-vars "DEPLOYED_AT=$(date +%s)" --quiet >/dev/null
  echo "  $svc restarted"
done

log "Eval runner (Cloud Run job)"
gcloud run jobs deploy eval-runner --project "$PROJECT_ID" --region "$REGION" \
  --image "$IMAGE_BASE/eval-runner:$TAG" --service-account "$(sa_of gops-eval)" \
  --memory 1Gi --cpu 1 --task-timeout 3600 --max-retries 0 \
  --set-secrets "CONSOLE_ADMIN_KEY=guestops-console-admin-key:latest" \
  --set-env-vars "SERVICE_AUTH_MODE=GOOGLE_ID_TOKEN,GCP_PROJECT_ID=$PROJECT_ID,GCP_LOCATION=$VERTEX_LOCATION,REGISTRY_URL=$REGISTRY_URL,ORCHESTRATOR_URL=$ORCH_URL,EVAL_OUTPUT_DIR=/tmp/eval-reports" \
  --quiet

log "Deployed"
cat <<MSG
  Console:                  $ORCH_URL  (public only if PUBLIC_UI=true)
  Admin key (approvals):    gcloud secrets versions access latest --secret guestops-console-admin-key --project $PROJECT_ID
  Open it locally with:     ./deploy/gcp/open-console.sh   -> http://localhost:8080
  Run evaluations:          ./deploy/gcp/run-evals.sh
  Check agent registration: ./deploy/gcp/status.sh
MSG
