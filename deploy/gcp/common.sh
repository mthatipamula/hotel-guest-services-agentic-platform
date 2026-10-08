# Shared helpers for the deploy scripts.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.env
source "$HERE/config.env"
: "${PROJECT_ID:?Set PROJECT_ID or run: gcloud config set project <id>}"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
SQL_CONNECTION="$PROJECT_ID:$REGION:$SQL_INSTANCE"
IMAGE_BASE="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO"

region_of() { [ "$1" = "partner-agents" ] && echo "$PARTNER_REGION" || echo "$REGION"; }
# Cloud Run's deterministic URL, known before the service exists, so each service can advertise its own URL.
url_of() { echo "https://$1-$PROJECT_NUMBER.$(region_of "$1").run.app"; }
sa_of() { echo "$1@$PROJECT_ID.iam.gserviceaccount.com"; }
sa_name() {
  case "$1" in
    agent-registry) echo "gops-registry" ;;
    hotel-mcp-server) echo "gops-mcp" ;;
    revenue-agents) echo "gops-revenue" ;;
    partner-agents) echo "gops-partner" ;;
    guest-ops-orchestrator) echo "gops-orchestrator" ;;
    eval-runner) echo "gops-eval" ;;
  esac
}
log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
