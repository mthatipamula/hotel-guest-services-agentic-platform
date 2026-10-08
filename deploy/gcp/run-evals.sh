#!/usr/bin/env bash
# Runs the golden-dataset evaluation as a Cloud Run job; results appear in the console's Evaluations tab.
source "$(dirname "$0")/common.sh"
gcloud run jobs execute eval-runner --project "$PROJECT_ID" --region "$REGION" --wait
echo "Done. See the Evaluations tab, or logs: gcloud run jobs executions list --job eval-runner --region $REGION"
