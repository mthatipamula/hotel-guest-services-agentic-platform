#!/usr/bin/env bash
# One-time project setup: APIs, Artifact Registry, Cloud SQL (Postgres 16 + pgvector), the DB password
# secret, and one service account per service (least privilege). Safe to re-run.
source "$(dirname "$0")/common.sh"

log "Enabling APIs in $PROJECT_ID"
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com aiplatform.googleapis.com secretmanager.googleapis.com iam.googleapis.com \
  --project "$PROJECT_ID"

log "Artifact Registry repository $REPO"
gcloud artifacts repositories describe "$REPO" --location "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1 \
  || gcloud artifacts repositories create "$REPO" --repository-format docker --location "$REGION" \
       --description "Hotel guest ops agent images" --project "$PROJECT_ID"

log "Cloud SQL instance $SQL_INSTANCE ($SQL_TIER, Postgres 16) - takes 5-10 minutes the first time"
if ! gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --edition ENTERPRISE \
    --tier "$SQL_TIER" --region "$REGION" --storage-size 10 --storage-auto-increase \
    --no-backup --project "$PROJECT_ID" || true
fi
echo "  waiting for the instance to be RUNNABLE..."
until [ "$(gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" --format='value(state)')" = "RUNNABLE" ]; do
  sleep 15
done
for db in agentregistry hoteldata guestops; do
  gcloud sql databases describe "$db" --instance "$SQL_INSTANCE" --project "$PROJECT_ID" >/dev/null 2>&1 \
    || gcloud sql databases create "$db" --instance "$SQL_INSTANCE" --project "$PROJECT_ID"
done

log "Database user and password secret"
if ! gcloud secrets describe "$DB_PASSWORD_SECRET" --project "$PROJECT_ID" >/dev/null 2>&1; then
  PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
  printf '%s' "$PASSWORD" | gcloud secrets create "$DB_PASSWORD_SECRET" --data-file=- \
    --replication-policy automatic --project "$PROJECT_ID"
fi
PASSWORD=$(gcloud secrets versions access latest --secret "$DB_PASSWORD_SECRET" --project "$PROJECT_ID")
if gcloud sql users list --instance "$SQL_INSTANCE" --project "$PROJECT_ID" --format='value(name)' | grep -qx "$DB_USER"; then
  gcloud sql users set-password "$DB_USER" --instance "$SQL_INSTANCE" --password "$PASSWORD" --project "$PROJECT_ID"
else
  gcloud sql users create "$DB_USER" --instance "$SQL_INSTANCE" --password "$PASSWORD" --project "$PROJECT_ID"
fi

log "Console admin key secret (protects approvals and the kill switch on a public console)"
if ! gcloud secrets describe guestops-console-admin-key --project "$PROJECT_ID" >/dev/null 2>&1; then
  openssl rand -hex 16 | tr -d '\n' | gcloud secrets create guestops-console-admin-key --data-file=- \
    --replication-policy automatic --project "$PROJECT_ID"
fi

log "Service accounts (one per service)"
for svc in $SERVICES eval-runner; do
  name=$(sa_name "$svc")
  gcloud iam service-accounts describe "$(sa_of "$name")" --project "$PROJECT_ID" >/dev/null 2>&1 \
    || gcloud iam service-accounts create "$name" --display-name "guestops $svc" --project "$PROJECT_ID"
done

grant() { gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$(sa_of "$1")" \
  --role "$2" --condition None --quiet >/dev/null; echo "  $1 -> $2"; }
# Gemini / embeddings
for n in gops-mcp gops-revenue gops-partner gops-orchestrator gops-eval; do grant "$n" roles/aiplatform.user; done
# Cloud SQL (via the Java connector) and the DB password secret
for n in gops-registry gops-mcp gops-orchestrator; do
  grant "$n" roles/cloudsql.client
  gcloud secrets add-iam-policy-binding "$DB_PASSWORD_SECRET" --member "serviceAccount:$(sa_of "$n")" \
    --role roles/secretmanager.secretAccessor --project "$PROJECT_ID" --quiet >/dev/null
done
for n in gops-orchestrator gops-eval; do
  gcloud secrets add-iam-policy-binding guestops-console-admin-key --member "serviceAccount:$(sa_of "$n")" \
    --role roles/secretmanager.secretAccessor --project "$PROJECT_ID" --quiet >/dev/null
done
for n in $(for s in $SERVICES eval-runner; do sa_name "$s"; done); do grant "$n" roles/logging.logWriter; done

log "Cloud Build permissions (default compute service account builds and pushes images)"
BUILD_SA="$PROJECT_NUMBER-compute@developer.gserviceaccount.com"
for role in roles/artifactregistry.writer roles/logging.logWriter roles/storage.objectViewer; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$BUILD_SA" --role "$role" \
    --condition None --quiet >/dev/null
done

log "Setup complete. Next: ./deploy/gcp/02-build.sh"
