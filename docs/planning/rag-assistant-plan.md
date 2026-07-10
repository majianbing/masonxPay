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

Implementation order is intentionally not the same as the table number order once bootstrap work exists. Finish the ingestion pipeline before treating the vector database foundation as complete, because stable chunk identity and source-version metadata determine whether Qdrant can be synced safely.

Current execution order:

1. Start RAG7 production hardening.
2. Defer RAG5 framework bakeoff until the RAG7 security baseline is in place.

| # | Item | Status | Detail |
|---|---|---|---|
| RAG0 | **Safety and scope model** | [x] | Complete for bootstrap: allowlisted sources, excluded sensitive data, audience filtering, docs-only first-version boundary, and refusal behavior are implemented in `ai-service/`. |
| RAG1 | **Vector database foundation** | [x] | Complete for bootstrap: Qdrant is wired into local Docker with a persistent volume and collection schema, live Qdrant-backed answer/status smoke test passed, and production auth/TLS, backup, retention, and security guidance is documented in [RAG assistant operations](../engineering/rag-operations.md). |
| RAG2 | **Ingestion pipeline** | [x] | Complete for bootstrap: approved docs are chunked with stable chunk IDs, source hashes, git commit metadata, index timestamps, deterministic local embeddings, stable Qdrant point IDs, stale-vector cleanup, manual freshness checks, and API/UI surfacing of indexed source version. |
| RAG3 | **Answer API** | [x] | Complete for bootstrap: AI service answer endpoint and gateway facade return citations, confidence, refusal fields, correlation IDs, audience-filtered retrieval, gateway-side request/token budgets, product audit events, configured AI service timeouts, and controlled unavailable responses. |
| RAG4 | **Dashboard assistant UI** | [x] | Complete for bootstrap: read-only dashboard assistant page, index metadata display, citations, unsupported-answer states, copyable source paths, source metadata badges, and dashboard production build verification are complete. |
| RAG5 | **Framework bakeoff** | [~] | In progress: LlamaIndex wired behind the existing `Retriever` interface (`RAG_VECTOR_BACKEND=llamaindex`) and evaluated against the raw retriever on the shared golden set. Both pass 7/7, but the set does not yet discriminate; with placeholder hash embeddings the framework's vector retrieval is not better than raw lexical (worse on one case). Decisive comparison needs real embeddings + a larger distractor-heavy golden set. LangChain/LangGraph not yet compared. |
| RAG6 | **Evals and auditability** | [x] | Complete for bootstrap: unit tests cover citations, sensitive-data refusal, audience filtering, deterministic embeddings, gateway facade behavior, compose config, and stable-doc-over-planning conflict handling. The local golden-question eval runner checks required citations/refusals and can write versioned model/provider/index reports. Answers/status include prompt-template, answer-policy, model-provider, and model-name metadata. AI service audit records are safe JSONL records with hashed questions, citation paths, refusal/confidence, retrieved chunk IDs, and version metadata. |
| RAG7 | **Production hardening** | [~] | In progress: gateway-to-AI bearer authentication is implemented and configurable with `AI_SERVICE_AUTH_TOKEN` plus `RAG_REQUIRE_AUTH=true`; bootstrap deployments still log warnings when auth is not required or Qdrant uses plain HTTP. Remaining: vector DB auth/TLS where supported, provider fallbacks, no-external-AI mode, alerting, and operational runbooks. |

## Implementation Notes

- 2026-07-06: Added first-pass `ai-service/` RAG implementation. The service chunks allowlisted Markdown sources, stores chunk metadata in a JSON index, supports Qdrant as the Docker vector backend, and uses deterministic local hashing vectors so no external model provider is required. The gateway exposes the merchant-scoped assistant facade, and the dashboard calls the gateway rather than the AI service directly. This is still a bootstrap RAG implementation: production embeddings, stronger ranking/evals, Qdrant auth/TLS, request budgets, and audit logging remain future work.
- 2026-07-06: Hardened RAG ingestion metadata. Chunks now carry stable IDs, stable Qdrant point IDs, source file SHA-256, index version, `last_indexed_at`, and git commit metadata. Qdrant sync deletes stale points when chunks are removed from the current allowlisted index.
- 2026-07-06: Finished bootstrap RAG2 freshness/version surfacing. Added `python -m app.ingest --check`, `/v1/rag/status`, gateway `/assistant/status`, and dashboard index metadata display.
- 2026-07-06: Validated bootstrap RAG1 runtime path with Docker. `ai-service` reported `vectorBackend=qdrant`, `/v1/rag/status` returned 458 chunks across 33 sources, and `/v1/rag/answer` returned cited answers through the live Qdrant-backed retriever.
- 2026-07-06: Finished bootstrap RAG1 production notes in [RAG assistant operations](../engineering/rag-operations.md), covering Qdrant auth/TLS, private networking, backups, retention, freshness workflow, smoke tests, and known bootstrap gaps.
- 2026-07-06: Finished bootstrap RAG3 gateway hardening. Added assistant-specific timeouts, per-merchant request/token budgets, sanitized merchant audit events, controlled `assistant_unavailable` responses, and status fallback metadata when the AI service is down.
- 2026-07-06: Finished bootstrap RAG4 dashboard verification and source UX. Removed build-time Google Fonts dependency, repaired local dashboard install links, verified `npm run build`, and replaced broken repo-doc links with copyable source cards that show source type and stability.
- 2026-07-06: Started RAG6 eval work. Added `ai-service/app/evaluate.py`, `ai-service/evals/golden_questions.json`, and focused eval tests so retrieval changes can be checked locally for expected citations, refusal behavior, and merchant audience filtering before the E2E pass.
- 2026-07-06: Finished bootstrap RAG6 auditability. Added explicit prompt-template, answer-policy, model-provider, and model-name versions; safe AI-service JSONL audit records; versioned eval report output; and stale planning-doc conflict coverage that verifies stable architecture guidance wins over active planning docs.
- 2026-07-06: Started RAG7 production hardening visibility. Added gateway and AI-service startup warnings for the current bootstrap security posture, plus `.env`/README TODO notes that ai-service must remain private until service authentication and production network controls are implemented.
- 2026-07-06: Implemented the first RAG7 hardening step. Gateway sends an optional bearer token from `AI_SERVICE_AUTH_TOKEN`, and `ai-service` requires `Authorization: Bearer <token>` on `/v1/rag/*` when `RAG_REQUIRE_AUTH=true`. Health remains unauthenticated for container checks.
- 2026-07-06: **Security fix — client-controlled `audience` privilege escalation.** The gateway assistant facade previously forwarded the caller-supplied `audience` from the request body straight to the AI service (only validating it was a known value), so a merchant-realm user could send `audience: "platform-admin"` / `"operator"` and retrieve engineering/planning chunks they are not entitled to (the exact leak the `merchant-audience-filter` golden case forbids). The gateway now resolves audience server-side: this merchant-realm endpoint always scopes to `merchant`, and `audience` was removed from `RagAnswerRequest` and the dashboard request body. Added `AiAssistantServiceTest.answer_alwaysScopesAudienceToMerchant`, which asserts the outbound AI request is always merchant-scoped. **Note for other agents: never treat a request-body `audience`/role field as an authorization scope — resolve entitlements from the authenticated principal at the gateway, before ranking, never as post-hoc answer redaction.**
- 2026-07-06: Hardened the Qdrant retriever. (1) Moved the collection sync off the query hot path: `retrieve` no longer scrolls/counts/upserts the whole collection per request; a lock-guarded `ensure_indexed()` syncs once per process, and re-indexing is an explicit operation (the `ingest` CLI's `ensure_index()` or a service restart). (2) Retrieval now over-fetches candidates (`max(limit*5, 20)`) before applying the stability/source `ranking_multiplier`, so a stable architecture/engineering chunk that scored just outside `limit` on raw vector similarity can still outrank an active-plan chunk — matching the `JsonRetriever` boost-then-truncate order.
- 2026-07-06: Decision — keep the local-only, no-external-AI baseline for now (`model_provider=local`, deterministic hashing embeddings, extractive answers; no external LLM key required). When external providers are introduced they are a **platform-operator (system-manager) credential and deployment-mode choice, not a per-tenant/merchant setting**, because the knowledge base is shared approved documentation. See Deployment Modes in [AI capabilities](../architecture/ai-capabilities.md); `disabled` remains the default fallback.
- 2026-07-10: Started RAG5 framework bakeoff (LlamaIndex). Added `ai-service/app/llamaindex_retriever.py` implementing the same `Retriever` contract (`RAG_VECTOR_BACKEND=llamaindex`), and `python -m app.evaluate --backend {json,llamaindex}`. It reuses the project's local hash embeddings via a custom LlamaIndex embedding (apples-to-apples), applies audience filtering *in-retrieval* via a `CONTAINS` metadata filter plus a defensive post-guard, and keeps the stability/source `ranking_multiplier` in owned code. **Findings:** integration is clean behind the owned contract with governance intact (audience-exclusion, refusal, citation-from-retrieval, stable-over-planning all verified in `tests/test_llamaindex_retriever.py`); on the shared golden set **both backends pass 7/7**, so the current 7-question set does not discriminate them. With identical chunks + placeholder hash embeddings, the LlamaIndex vector retriever produced different top citations in 6/7 cases and was **worse** on `webhook-signature-boundary` (top-ranked planning `roadmap.md` over authoritative `security-boundaries.md`, low confidence) — expected, since hash embeddings are a weak semantic signal. **Next:** introduce a real embedding model and expand the golden set with distractor-heavy questions before drawing a framework conclusion; then compare a LangChain/LangGraph variant. Note: `llama-index-core` upgrades transitive `pydantic` to 2.13; keep it an optional dependency until the bakeoff concludes.
- 2026-07-07: Added optional external answer generation (**generation-only**, for local model testing). New `ai-service/app/llm.py` provider seam + OpenAI adapter, wired into `KnowledgeBase.answer` behind operator-level env config `RAG_MODEL_PROVIDER` / `RAG_MODEL_NAME` / `OPENAI_API_KEY` (`OPENAI_BASE_URL` optional). `local` stays the default **and** the fallback when a call errors or the SDK/key is missing, so the no-external-AI mode is preserved. Embeddings and retrieval are unchanged (local 384-dim hashing, no re-index). Guardrails: sensitive-data/insufficient-evidence refusals are decided from retrieval **before** any model call; only approved retrieved doc excerpts + the question are sent to the provider; citations come from retrieval, never the model; `model_provider`/`model_name` in answers/audit reflect the **effective** model (external, or `local` after a fallback). Tests in `ai-service/tests/test_llm.py` cover generated-answer use, error→extractive fallback, and that refusals never call the model.

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
