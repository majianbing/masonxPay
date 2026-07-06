# MasonXPay RAG Support Assistant Plan

Stable architecture shared by all AI capabilities lives in [AI capabilities](../architecture/ai-capabilities.md). Keep this file focused on the RAG assistant phase plan, implementation notes, and open decisions.

MasonXPay is large enough that users need a product and developer assistant for setup, connector configuration, dashboard workflows, SDK usage, routing policies, subscriptions, rails, ledger concepts, and operational troubleshooting. This assistant is separate from the payment operations agent. It answers questions from approved knowledge sources and does not touch payment execution or configuration mutation.

## Product Boundary

The RAG assistant may:

- Answer documentation-backed usage questions.
- Explain how MasonXPay concepts fit together.
- Cite source documents and route users to relevant pages.
- Summarize setup, SDK, connector, dashboard, routing, subscription, rail, and ledger guidance.
- Refuse or narrow questions when approved sources do not contain enough evidence.

The RAG assistant may not:

- Authorize, decline, route, capture, refund, or otherwise move funds.
- Create, publish, or mutate routing rules, connectors, payment links, subscriptions, invoices, webhooks, API keys, ledger entries, or rail payments.
- Read raw production logs, raw database rows, provider payloads, webhook bodies, secrets, credentials, card data, unredacted PII, or customer payment payloads.
- Bypass tenant scope, TEST/LIVE mode scope, RBAC, or platform-admin boundaries.
- Return documentation scoped to a role the requester does not hold. Retrieval must filter candidate chunks by the requesting user's `audience`/role before ranking, so that platform-admin or operator guidance cannot surface in a merchant user's answer.
- Present archived or planning material as current behavior when stable architecture or engineering docs disagree.

## Relationship To The Operations Agent

The RAG assistant and operations agent should share provider-agnostic model configuration, deployment modes, prompt/version audit, and evaluation infrastructure where practical. They must not share authority.

| Capability | Primary data | Output | Mutation authority |
|---|---|---|---|
| RAG assistant | Approved docs/help chunks | Answer with citations | None |
| Operations agent | Redacted telemetry and policy metadata | Explanation and proposal draft | None |
| Deterministic validators | Typed proposals/config | Validation result | No direct mutation |
| Gateway services | Payment/routing domain state | Approved config changes | Yes, through existing services |

## Knowledge Sources

Initial allowlist:

- `README.md`
- `docs/architecture/**`
- `docs/engineering/**`
- `docs/planning/**`, tagged as planning and ranked below stable architecture
- `sdk/server/README.md`
- `sdk/browser/README.md`
- `dashboard/README.md`
- Generated API documentation when added
- Dashboard route/help metadata when added

Excluded by default:

- `docs/archive/**`, unless explicitly requested and clearly labeled historical
- `.env*`, secrets, private deployment notes, logs, benchmark result payloads, generated build output, database dumps, and any production/exported operational data
- Raw source code as a broad ingestion source. Targeted controller/DTO/API summaries may be generated later through an allowlisted doc-generation job.

## Retrieval Metadata

Every chunk should carry metadata that lets the system rank stable, current guidance above noisy planning material:

- `source_path`
- `source_type`: architecture, engineering, planning, sdk, dashboard, api, roadmap, archive
- `stability`: stable, active-plan, historical
- `module`: gateway, dashboard, server-sdk, browser-sdk, rail, rail-simulator, virtual-account, common, contracts
- `audience`: merchant, developer, platform-admin, operator
- `last_indexed_at`
- `git_commit`
- `heading_path`

The answer generator should cite source paths and headings. If retrieved chunks conflict, stable architecture and engineering docs win over planning docs, and archive docs must be labeled historical.

Retrieval must also be filtered by the requesting user's role. The gateway facade resolves the caller's `audience` (merchant, developer, platform-admin, operator) and passes it to the AI service, which restricts the candidate set to chunks the caller is entitled to. Role filtering is applied before ranking, never as a post-hoc answer redaction.

## Service Architecture

Recommended runtime:

```text
dashboard chat UI
  -> gateway-service AI facade
       -> ai-service answer endpoint
            -> retrieval pipeline
            -> vector database
            -> model provider adapter
```

The gateway service remains the policy gate. It authenticates users, enforces merchant and role scope, chooses deployment mode, applies rate limits, records product audit events, and calls the AI service with an allowed-source policy. The AI service should live at top-level `ai-service/`, not under `backend/`, because it is a Python coprocessor rather than a Maven module. It owns RAG internals: chunking, embeddings, retrieval, prompt templates, model/provider adapters, redacted model-call audit, feedback, and eval runs.

Use a standalone vector database for local and production deployments. Qdrant is the default candidate because it has a simple Docker path, metadata filtering, Python support, and a focused vector-store operational model. Weaviate and Milvus remain comparison candidates during the bakeoff.

## Framework Investigation

Do not lock in LangChain, LangGraph, or LlamaIndex before a small bakeoff. The framework must remain an internal implementation detail behind MasonXPay-owned request/response contracts.

Evaluate:

- LlamaIndex for ingestion, chunking, retrieval, citations, and RAG observability.
- LangChain/LangGraph for agentic workflows and possible shared use with the operations agent.
- Thin handwritten retrieval/orchestration code for maximum control if framework overhead is not justified.

Scoring criteria:

- Citation accuracy.
- Refusal behavior for unsupported or sensitive questions.
- Stale planning-doc conflict handling.
- Metadata filtering quality.
- Latency and cost.
- Code clarity and testability.
- Framework lock-in and dependency risk.
- Observability, tracing, and eval support.

## Phases

| # | Item | Status | Detail |
|---|---|---|---|
| RAG0 | **Safety and scope model** | [ ] | Define allowed sources, excluded sources, roles, sensitive-data refusals, and docs-only first-version boundary. |
| RAG1 | **Vector database foundation** | [ ] | Add standalone vector DB to local Docker and deployment docs; define collection schema, metadata indexes, retention, backups, and security requirements. |
| RAG2 | **Ingestion pipeline** | [ ] | Chunk approved docs, attach metadata, compute embeddings, upsert to vector DB, and track source git commit/version. |
| RAG3 | **Answer API** | [ ] | Add AI service endpoint and gateway facade for question answering with citations, confidence/refusal fields, correlation IDs, role/`audience`-filtered retrieval, and rate limits. The facade carries a self-contained per-tenant request/token budget so RAG can ship without waiting on the deferred Phase 15 platform-wide rate limiter. |
| RAG4 | **Dashboard assistant UI** | [ ] | Add a read-only assistant surface with citations, source links, feedback controls, and clear unsupported-answer states. |
| RAG5 | **Framework bakeoff** | [ ] | Compare LlamaIndex, LangChain/LangGraph, and thin custom orchestration against a shared Qdrant-backed golden-question set. |
| RAG6 | **Evals and auditability** | [ ] | Build golden usage questions, citation checks, sensitive-data refusal tests, stale-doc conflict tests, model/provider comparison reports, and prompt/template versioning. |
| RAG7 | **Production hardening** | [ ] | Add auth between gateway and AI service, vector DB auth/TLS where supported, request budgets, provider fallbacks, no-external-AI mode, alerting, and operational runbooks. |

## Golden Question Set

Seed eval questions should cover:

- How to run MasonXPay locally with Docker.
- How to configure a TEST connector.
- How to create and share a payment link.
- How hosted checkout differs from embedded checkout.
- How to use the browser SDK and server SDK.
- How TEST/LIVE mode isolation works.
- How routing rules differ from route policies.
- Why cross-provider fallback requires a portable instrument or re-authorization.
- How subscription retry and dunning work.
- What data is forbidden in logs and AI evidence.
- A merchant-role question must not surface platform-admin- or operator-only guidance (role/`audience` filter check).
- How provider webhooks are verified.
- What UNKNOWN rail state means.
- How VA ledger trial balance and account statements should be interpreted.

## Open Decisions

- Default vector DB: Qdrant unless the framework bakeoff shows a stronger reason for Weaviate or Milvus.
- Embedding provider and model selection for local, external-redacted, external-restricted, and internal-only modes.
- Whether chat history is stored, and if so whether it is tenant-scoped, retention-limited, and excluded from future training/indexing.
- Whether source-code-derived API summaries are generated during CI or by a manual docs-indexing job.
- How docs freshness is surfaced in the UI when the running service version and indexed git commit diverge.
