#!/usr/bin/env bash
# Builds the jars and the six container images in Cloud Build (nothing is built on your machine).
source "$(dirname "$0")/common.sh"
cd "$HERE/../.."
log "Building images $IMAGE_BASE/*:$TAG with Cloud Build"
gcloud builds submit --config cloudbuild.yaml --project "$PROJECT_ID" \
  --substitutions "_REGION=$REGION,_REPO=$REPO,_TAG=$TAG"
log "Images pushed. Next: ./deploy/gcp/03-deploy.sh"
