#!/usr/bin/env bash
# Reloads the demo hotel data (dates relative to today) in Cloud SQL.
source "$(dirname "$0")/common.sh"
curl -s -X POST -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  "$(url_of hotel-mcp-server)/admin/demo-data/reset"; echo
