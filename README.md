Copyright © 2026 Mahesh Thatipamula. All rights reserved.

This repository contains proprietary software. No license is granted to use, copy, modify, or distribute the source code without prior written permission.

# Hotel Guest Services Agentic Platform

A distributed multi-agent platform for hotel guest services and operations (fictional brand: Aurora Hotels & Resorts),
built with Java 21, Spring Boot 3.5, Spring AI 1.1, Vertex AI (Gemini + embeddings) and PostgreSQL + pgvector.

> Status: work in progress on the `feature/agentic-platform` branch. Dockerfiles, GCP deployment scripts and full
> documentation are still to come.

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

Prerequisites: Java 21, Docker, `gcloud auth application-default login`, Vertex AI API enabled.

```bash
export GCP_PROJECT_ID=your-project-id
./scripts/run-local.sh          # Postgres + 5 services; console on http://localhost:8080
./scripts/run-evals.sh          # golden-dataset evaluation
./scripts/run-local.sh stop
```


