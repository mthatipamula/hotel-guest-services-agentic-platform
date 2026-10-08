# Hotel Guest Services Agentic Platform

A distributed multi-agent platform for hotel guest services and operations (fictional brand: Aurora Hotels & Resorts),
built with Java 21, Spring Boot 3.5, Spring AI 1.1, Vertex AI (Gemini + embeddings) and PostgreSQL + pgvector.
Runs locally with Docker and deploys to Google Cloud Run with Cloud SQL.

## What is in it

20 agents across 4 network zones:

| Service | Zone | Agents | How they are reached |
|---|---|---|---|
| `guest-ops-orchestrator` | core | 12: triage router, safety guard, reservation, guest profile, room assignment, housekeeping, maintenance, billing, service recovery, policy advisor, dining, response composer | in-process |
| `revenue-agents` | revenue-net | 4: pricing, upsell, demand forecast, group sales | A2A (JSON-RPC, Agent Cards) |
| `partner-agents` | partner-net | 3: ground transport, local experiences, spa | A2A, no guest identity data |
| `eval-runner` | eval | 1: quality judge (LLM-as-a-judge) | in-process |

Supporting services:

- `agent-registry`: agent registry (A2A card verification, heartbeats, discovery by skill), MCP registry,
  governance policies (tool allowlists, allowed callers, token budgets, data classification, kill switch) and audit trail.
- `hotel-mcp-server`: hotel data tools over MCP (streamable HTTP) and RAG over hotel policies (Vertex embeddings in pgvector).
- `libs/agent-commons`: shared agent runtime, A2A client/server, guardrails, governance enforcement, token budgets.

## Constructs

1. Agent registry: `services/agent-registry`
2. Ragas and agent evaluation: `services/eval-runner` (faithfulness, answer relevancy, context precision/recall; routing, tool recall, approvals, guardrails)
3. Guardrails: deterministic input/output checks plus an LLM safety agent (`libs/agent-commons/.../guardrails`, `SafetyGuard`)
4. Governance: per-agent policies, fail-closed checks, kill switch, audit (`GovernanceEnforcer`, registry)
5. Token management: per-request and daily budgets, model tiering, ledger with cost estimates (`TokenBudget`, `TokenLedger`)
6. MCP registry: tool servers discovered at runtime (`McpToolsResolver`)
7. Agent-to-agent communication: A2A v0.3 subset (`commons/a2a`)
8. LLM-as-a-judge: Spring AI `FactCheckingEvaluator` and `RelevancyEvaluator` plus a rubric, judged by `gemini-2.5-pro`

## Run locally

Prerequisites: Java 21, Docker, the gcloud CLI, and a Google Cloud project with the Vertex AI API enabled.

```bash
gcloud auth application-default login      # once
export GCP_PROJECT_ID=your-project-id
./scripts/run-local.sh                      # Postgres + 5 services; console on http://localhost:8080
./scripts/run-evals.sh                      # golden-dataset evaluation (needs the services running)
./scripts/run-local.sh stop
```

Use `ORCHESTRATOR_PORT=8085 ./scripts/run-local.sh` if port 8080 is taken. Logs are in `.run/<service>.log`.

## Deploy to Google Cloud

Deploys the five services to Cloud Run (partner agents in a second region to model an external network), the
eval runner as a Cloud Run job, and Postgres 16 + pgvector on Cloud SQL. Images are built by Cloud Build, so
nothing is built on your machine. Full details: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

### Prerequisites
- A Google Cloud project with billing enabled, and the gcloud CLI logged in as a project owner.
- Settings live in [`deploy/gcp/config.env`](deploy/gcp/config.env) (project, regions, Cloud SQL tier, image tag,
  public or private console). Any value can be overridden with an environment variable.

### Steps
```bash
gcloud auth login
export PROJECT_ID=your-project-id

./deploy/gcp/01-setup.sh      # one-time: APIs, Artifact Registry, Cloud SQL, secrets, service accounts (~10 min)
./deploy/gcp/02-build.sh      # Cloud Build: jars + 6 container images (~3 min)
./deploy/gcp/03-deploy.sh     # Cloud Run services, least-privilege IAM, eval job (~10 min)
./deploy/gcp/status.sh        # service URLs and registry summary (expect 19 agents active)
```

All scripts are safe to re-run. To ship a new version, build and deploy with a new tag:
`TAG=v3 ./deploy/gcp/02-build.sh && TAG=v3 ./deploy/gcp/03-deploy.sh`.

### Use it
| Task | Command |
|---|---|
| Open the console (public URL) | printed at the end of `03-deploy.sh` |
| Open the console privately (`PUBLIC_UI=false`) | `./deploy/gcp/open-console.sh` then http://localhost:8080 |
| Admin key for approvals and the agent kill switch | `gcloud secrets versions access latest --secret guestops-console-admin-key --project $PROJECT_ID \| pbcopy` |
| Run the evaluation suite | `./deploy/gcp/run-evals.sh` (results in the console's Evaluations tab) |
| Reload the demo hotel data | `./deploy/gcp/reset-demo-data.sh` |

### Security on GCP
- Services call each other with Google-signed ID tokens; each has its own service account with `roles/run.invoker`
  only on the services it calls. Only the console can be public.
- On a public console, admin actions require the admin key and chat is rate limited (6 requests/minute per client),
  on top of each agent's daily token budget.

### Cost
Cloud SQL `db-f1-micro` is the main fixed cost (about $10/month while it exists). Cloud Run scales to zero when
idle, and Vertex AI is pay per request (typically under a cent per multi-agent request).

## Tear down on Google Cloud

Deletes everything the deploy scripts created: Cloud Run services and job, the Cloud SQL instance (**all data**),
Artifact Registry images, secrets and service accounts. It asks you to type the project id to confirm.

```bash
./deploy/gcp/teardown.sh
```

APIs stay enabled and cost nothing. If you created a project just for this platform, deleting the whole project
also removes everything:

```bash
gcloud projects delete your-project-id
```
