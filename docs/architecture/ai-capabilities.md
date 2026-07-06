# AI Capabilities

MasonXPay has two planned AI surfaces with different authority, data access, and users:

- The RAG support assistant answers product, integration, and operational usage questions from approved documentation and help content.
- The payment operations agent investigates telemetry incidents, explains impact, and drafts routing-policy proposals for deterministic validation and human approval.

Both surfaces are advisory. Neither surface may execute payment decisions, directly mutate payment configuration, bypass tenant/RBAC checks, or receive sensitive payment data.

## RAG Support Assistant

The RAG assistant is a read-only knowledge assistant. It should answer questions such as how to configure connectors, create payment links, use SDKs, understand subscriptions, interpret route policies, and troubleshoot common dashboard flows.

The assistant retrieves from approved knowledge sources:

- root README and public setup docs
- durable architecture docs
- engineering guides
- SDK READMEs and generated API references
- dashboard route/help metadata
- explicitly approved planning docs with stability metadata

Planning and archive material must be tagged so it cannot override stable architecture or current engineering guidance. Answers must cite source documents. If retrieval evidence is insufficient or conflicting, the assistant should say so instead of inventing behavior.

The RAG assistant must not ingest or retrieve production logs, raw database rows, provider payloads, webhook bodies, credentials, API keys, card data, customer PII, or ledger/payment records. Later user-specific troubleshooting may use narrow Java-owned APIs that return safe, scoped metadata, but the first production version should be docs-only.

## Authority Boundary

AI may:

- answer documentation-backed usage questions
- analyze telemetry
- explain incidents
- recommend routing-policy changes
- draft safe configuration updates

AI may not:

- authorize or decline payments
- route payments directly
- directly mutate routing rules
- bypass tenant scope, RBAC, validators, or approval
- receive secrets, raw payment payloads, card data, provider credentials, webhook signatures, private keys, tokens, or unredacted PII

Deterministic validators and human approval remain between AI output and production routing changes. Runtime routing executes explicit, versioned configuration only.

## Evidence Policy

Model-bound evidence must be narrow and purpose-built. Prefer provider aliases, country, currency, card brand, payment method type, coarse region, aggregated success rate, latency, retry/failover counts, error-code metrics, incident IDs, and redacted trace references.

Do not send raw database rows by default. Do not let models call arbitrary SQL, arbitrary HTTP, filesystem-like tools, or broad write-capable tools.

For RAG, source ingestion must be allowlisted. The vector index should store only approved documents, chunk metadata, embeddings, citations, and optional evaluation artifacts. It must not become a shadow operational data store.

For the operations agent, model-bound evidence must be redacted before crossing the Java-to-AI-service boundary. The Java gateway remains responsible for tenant scope, RBAC, approval state, route-policy persistence, and all payment-domain invariants.

## Deployment Modes

The architecture should support:

- `external-redacted`: hosted models with redacted and aggregated evidence
- `external-restricted`: hosted models with stricter enterprise controls and explicitly approved limited context
- `internal-only`: self-hosted or internal model gateway with policy-filtered context
- `disabled`: no model calls; deterministic detection, validation, dashboard review, and rollback still work

## Audit And Evaluation

Log model provider, model name, prompt/template version, redacted evidence references, model output, validator result, approver, and final applied config version. Run evaluations for incident classification, recommendation quality, policy validation, and explanation clarity before changing default models or prompts.

The RAG assistant should also log source versions, chunk IDs, retrieved citations, refusal reasons, model/provider, prompt/template version, latency, and feedback signals. Evaluation sets should include golden usage questions, citation checks, stale-doc conflict cases, and refusal tests for sensitive-data questions.

## Service Boundary

An optional Python AI service is acceptable for RAG, model orchestration, embeddings, and evaluation workflows. It belongs at the repository root as `ai-service/`, not inside the Java `backend/` Maven reactor. The Java gateway must remain the policy gate for user identity, merchant scope, RBAC, model deployment mode, and any payment-domain mutation.

Recommended boundary:

```text
dashboard
  -> gateway-service
       -> ai-service
            -> model providers
            -> vector database
```

The dashboard should not call the AI service directly for protected workflows. The AI service may own AI-specific state such as document chunks, embeddings, prompt versions, redacted model-call audit logs, eval runs, and assistant conversations if policy allows. It must not own authoritative payment, connector, routing, ledger, approval, tenant, or credential state.

## References

- [Security boundaries](security-boundaries.md)
- [Routing and orchestration](routing-orchestration.md)
- [RAG assistant plan](../planning/rag-assistant-plan.md)
- [Payment operations agent plan](../planning/payment-operations-agent-plan.md)
