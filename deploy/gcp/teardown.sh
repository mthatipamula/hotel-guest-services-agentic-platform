#!/usr/bin/env bash
# Deletes everything the deploy scripts created (Cloud Run services and job, Cloud SQL, images, secret,
# service accounts). Cloud SQL deletion destroys all data.
source "$(dirname "$0")/common.sh"
read -r -p "Delete the guestops deployment from project $PROJECT_ID? Type the project id to confirm: " answer
[ "$answer" = "$PROJECT_ID" ] || { echo "Aborted."; exit 1; }
for svc in $SERVICES; do
  gcloud run services delete "$svc" --project "$PROJECT_ID" --region "$(region_of "$svc")" --quiet || true
done
gcloud run jobs delete eval-runner --project "$PROJECT_ID" --region "$REGION" --quiet || true
gcloud sql instances delete "$SQL_INSTANCE" --project "$PROJECT_ID" --quiet || true
gcloud artifacts repositories delete "$REPO" --location "$REGION" --project "$PROJECT_ID" --quiet || true
gcloud secrets delete "$DB_PASSWORD_SECRET" --project "$PROJECT_ID" --quiet || true
for svc in $SERVICES eval-runner; do
  gcloud iam service-accounts delete "$(sa_of "$(sa_name "$svc")")" --project "$PROJECT_ID" --quiet || true
done
echo "Teardown complete. (APIs stay enabled; project IAM bindings for deleted accounts are cleaned up by GCP.)"
