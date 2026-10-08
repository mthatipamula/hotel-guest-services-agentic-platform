# Deploying to Google Cloud

## Target architecture

| Component | GCP service | Region | Notes |
|---|---|---|---|
| guest-ops-orchestrator (12 agents + console) | Cloud Run | us-central1 | IAM-protected; open with `open-console.sh` |
| agent-registry | Cloud Run | us-central1 | Agent + MCP registry, governance, audit |
| hotel-mcp-server | Cloud Run | us-central1 | MCP tools + policy RAG |
| revenue-agents (4 agents, A2A) | Cloud Run | us-central1 | Separate service and service account |
| partner-agents (3 agents, A2A) | Cloud Run | **us-east1** | Different region: models an external partner network |
| eval-runner (judge agent) | Cloud Run **job** | us-central1 | Runs the golden dataset on demand |
| Databases | Cloud SQL Postgres 16 + pgvector | us-central1 | `agentregistry`, `hoteldata`, `guestops` |
| Models | Vertex AI | us-central1 | Gemini 2.5 Flash-Lite / Flash / Pro, text-embedding-005 |
| Images | Artifact Registry + Cloud Build | us-central1 | Built in the cloud, not on your machine |
| DB password | Secret Manager | - | Mounted as `DB_PASSWORD` |

### Security
- No service allows unauthenticated access. Services call each other with **Google-signed ID tokens**
  (`SERVICE_AUTH_MODE=GOOGLE_ID_TOKEN`), checked by Cloud Run IAM before the request reaches the app.
- **One service account per service**, with `roles/run.invoker` only on the services it calls:
  partners cannot call the hotel data server; only the orchestrator can call A2A agents.
- Application-level governance (registry policies) still applies on top of IAM.
- **Public console** (`PUBLIC_UI=true`, the default in `config.env`): only the orchestrator is opened to
  `allUsers`. Admin actions (approve/reject, agent suspend/activate, publishing eval reports, clearing
  conversations) require the `X-Admin-Key` header; the key is in Secret Manager
  (`gcloud secrets versions access latest --secret guestops-console-admin-key`) and the UI asks for it once.
  Chat is rate limited to 6 requests per minute per client (`CHAT_REQUESTS_PER_MINUTE`), on top of each
  agent's daily token budget. Set `PUBLIC_UI=false` to keep the console private and use `open-console.sh`.
- Cloud SQL is reached through the Cloud SQL Java connector (IAM-authorised, encrypted); the password is
  in Secret Manager.

### Cost (approximate, idle)
- Cloud SQL `db-f1-micro`: roughly $10/month plus storage. This is the main fixed cost.
- Cloud Run: scales to zero, so close to $0 when idle; Java cold starts take about 10-20 s.
- Vertex AI: pay per request (a typical multi-agent request is about 5-20k tokens, under 1 cent).
- Remove everything with `./deploy/gcp/teardown.sh`.

## Steps

```bash
gcloud auth login
gcloud config set project YOUR_PROJECT_ID

./deploy/gcp/01-setup.sh      # APIs, Artifact Registry, Cloud SQL, secret, service accounts (one-time)
./deploy/gcp/02-build.sh      # Cloud Build: jars + 6 images
./deploy/gcp/03-deploy.sh     # Cloud Run services, IAM grants, eval job
./deploy/gcp/status.sh        # URLs and registry summary (expect 19-20 agents)
./deploy/gcp/open-console.sh  # console on http://localhost:8080 through an authenticated proxy
./deploy/gcp/run-evals.sh     # golden-dataset evaluation as a Cloud Run job
```

Settings (region, Cloud SQL tier, image tag, public console) are in `deploy/gcp/config.env` and can be
overridden with environment variables, e.g. `TAG=v2 ./deploy/gcp/02-build.sh && TAG=v2 ./deploy/gcp/03-deploy.sh`.

### Differences from local
| Concern | Local | Cloud Run |
|---|---|---|
| Service auth | shared bearer token | Google ID tokens + IAM |
| Demo data | reloaded at every start | seeded once; reset with `reset-demo-data.sh` |
| Heartbeat staleness | 90 s | 7 days (services scale to zero and stop heartbeating) |
| Database | Docker Postgres on :5433 | Cloud SQL via the Java connector |

### Production hardening not done in this demo
Private networking (internal ingress + Serverless VPC Access / Direct VPC egress, private IP for Cloud SQL),
Identity-Aware Proxy for staff sign-in, Cloud SQL high availability and backups, minimum instances to avoid
cold starts, and Cloud Armor in front of a public endpoint.
