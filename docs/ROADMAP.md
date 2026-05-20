# MasonXPay — Product Roadmap

---

## Phase 0 — MVP ✅ (complete)

| Area | Delivered |
|---|---|
| Auth | JWT, refresh tokens, MFA (TOTP), RBAC 5 roles |
| Payment intents | Create / confirm / cancel, failover retry loop, stale reconciliation |
| Connectors | Stripe, Square, Braintree (charge, refund, sync, cancel) |
| Routing | Static rules, primary + fallback per merchant |
| Webhooks | DB-backed queue, exponential backoff, HMAC signing |
| Hosted checkout | Payment links, browser SDK, connector preview |
| Data | Billing / shipping details, AVS forwarding to all 3 providers |
| Security | AES-256-GCM credential encryption, request log redaction, webhook signature enforcement |
| Infra | Docker Compose, CloudFormation (standalone + managed), structured JSON logging |

---

## Phase 1 — Core correctness ✅ (complete)

| # | Item | Status | Why it matters |
|---|---|---|---|
| 1.1 | **Transactional outbox** | ✅ | `outbox_events` table written in same TX as intent save. `WebhookDeliveryService.processOutbox()` polls every 5s, creates `GatewayEvent` + `WebhookDelivery` rows and marks published atomically. |
| 1.2 | **Manual capture endpoint** | ✅ | `POST /payment-intents/{id}/capture` added. All 3 providers support `captureAtProvider()`. MANUAL flow: charge returns `REQUIRES_CAPTURE`, capture() transitions to `SUCCEEDED`. Cancel of `REQUIRES_CAPTURE` releases hold at provider. |
| 1.3 | **Refund amount guard** | ✅ | `RefundRepository.sumActiveByPaymentIntentId()` sums PENDING + SUCCEEDED refunds. `RefundService` rejects if `existing + requested > original`. |
| 1.4 | **Inbound webhook deduplication** | ✅ | `processed_webhook_events` table with UNIQUE(provider, provider_event_id). All 3 webhook controllers check before processing; duplicate → 200 idempotent response. |
| 1.5 | **JWT token_version on logout** | ✅ | `token_version INT` on `users` table, embedded as `tv` claim in every access token. `JwtAuthFilter` rejects tokens where `tv < user.token_version`. `AuthService.logout()` increments `token_version`. |

---

## Phase 2 — Observability ← next

No metrics, no tracing, no alerting today. Foundation that Phases 3 and 4 depend on — smart routing needs success-rate data; merchant analytics needs aggregated metrics; connector health needs measurement before it can be acted on.

| # | Item | Status | Detail |
|---|---|---|---|
| 2.1 | **Micrometer + Prometheus** | ✅ | `micrometer-registry-prometheus` added. `/actuator/prometheus` exposed. `PaymentMetrics` component: `payment.intent.confirmed` (provider/status/failure_code), `payment.charge.latency` timer (provider), `payment.intent.failover`, `payment.refund.initiated`, `payment.capture.attempted`, `payment.webhook.outbox.processed`, `payment.stale.resolved`. Instrumented in `PaymentIntentService`, `RefundService`, `WebhookDeliveryService`, `StalePendingIntentJob`. |
| 2.2 | **Trace ID propagation** | ✅ | `TraceIdFilter` (`@Order(HIGHEST_PRECEDENCE)`) accepts `X-Request-Id` or generates UUID, stores in MDC as `traceId`, echoes back in response header. V33 migration adds `trace_id VARCHAR(36)` to `payment_intents` + `gateway_logs`. `ApiRequestLoggingFilter` reads from MDC. `PaymentIntentService.create()` + `confirm()` write trace ID to `payment_intents.trace_id`. Also migrated `StalePendingIntentJob` to use outbox pattern (atomic with status change). |
| 2.3 | **Enhanced actuator health** | ✅ | `ConnectorHealthIndicator` added to `/actuator/health/connectors`. Lightweight DB read — counts ACTIVE connector accounts per provider. Status UP if any ACTIVE connectors exist, DOWN if none. No provider pinging. |
| 2.4 | **Connector health gauge** | ✅ | `ConnectorHealthService` — `@Scheduled` every 5 min, queries `payment_requests` for rolling 30-min success rate per connector. Cached in-memory `ConcurrentHashMap`, exposed as `connector.success.rate` gauge (tags: provider, account_id, label). Also drives `payment.outbox.queue.depth` and `payment.stale.processing.count` gauges used by alerting rules. No new table. |
| 2.5 | **Grafana dashboards** | ✅ | `monitor/grafana/` — auto-provisioned via `docker-compose`. Dashboard at port 3001: payment volume, success rate (global + per-connector), failure breakdown by code, charge latency p50/p95, failover rate, webhook queue depth, stale intent count. |
| 2.6 | **Alerting rules** | ✅ | `monitor/prometheus/alert_rules.yml` — 5 rules: ConnectorLowSuccessRate (<80%, 5m), HighChargeLatency (p95>5s, 5m), WebhookQueueDepthHigh (>50, 5m), StaleProcessingIntentsAccumulating (>10, 10m), HighFailoverRate (>0.5/s, 5m). |

---

## Phase 3 — Orchestration intelligence

The core value-add of an orchestration layer over direct provider integration.

| # | Item | Status | Detail |
|---|---|---|---|
| 3.1 | **Dynamic routing by success rate** | ✅ | `RoutingEngine` partitions matching rules into healthy (≥80% rolling success rate) and degraded pools — picks from healthy first. Falls back to degraded pool only if no healthy rules exist. `ConnectorHealthService.getSuccessRate(accountId)` exposes Phase 2 metric data to routing. In `weightedSelect`, degraded accounts have their weight halved so traffic naturally shifts away. |
| 3.2 | **Circuit breaker per connector** | ✅ | `ConnectorCircuitBreaker` — in-memory per-account state machine. Opens after 3 consecutive retryable failures (hard declines don't count — the card is the problem). Stays open 30s, auto-closes after cooldown. `RoutingEngine` filters open circuits from candidates; `resolveAnyAccount` + `resolveAccountForProvider` skip open accounts when alternatives exist. `PaymentIntentService.confirm()` records success/failure after each charge attempt. |
| 3.3 | **3DS2 / SCA handling** | ✅ | Two-path design: `stripe_sdk` (Stripe.js `handleNextAction()` manages 3DS2 inline) and `redirect_url` (universal iframe overlay for all redirect-based challenges — any future provider returning a URL works automatically). Backend parks PI as `REQUIRES_ACTION`, returns `providerAction` in checkout response. SDK opens a centered overlay with Cancel button; `/pay/3ds-return` page fires `postMessage` on redirect return; SDK polls `/payment-status` until settled. `StalePendingIntentJob` cleans up abandoned challenges. Connector preview includes 3DS test cards + step-by-step guide. |
| 3.4 | **More connectors** | ✅ | Mollie (EU) added: REST API v2, hosted redirect checkout via existing `redirect_url` overlay, HMAC-free webhook verified by API fetch, `test_`/`live_` API key. Razorpay (India) skipped — sandbox requires full KYC onboarding with no bypass; revisit if a test account becomes available. |
| 3.5 | **Cost-aware routing** | ✅ | Option A + Approach 2: `fixed_fee_cents` + `rate_bps` on `provider_accounts` (V35); optional `max_cost_bps` ceiling on `routing_rules` (V36). `ConnectorFeeService` computes effectiveCost = fixedFee + (amount × rateBps / 10000). `RoutingEngine.resolve()` filters the healthy pool by cost ceiling before weighted selection; falls back to unconstrained pool if all candidates exceed budget. Dashboard: fee fields on connector form, cost ceiling field on routing rule form with green badge. |

---

## Phase 4 — Merchant operations

What merchants need to run their business, not just process payments.

| # | Item | Status | Detail |
|---|---|---|---|
| 4.1 | **Merchant analytics API + dashboard** | [ ] | `GET /analytics?from=&to=&groupBy=connector\|currency\|status` — aggregates from existing tables. Dashboard page: volume, conversion rate, top failure codes, revenue over time. Powered by Phase 2 data. |
| 4.2 | **Webhook endpoint management UI** | [ ] | Backend table exists. Merchants need to configure, test, and view delivery history for their own webhook URLs from the dashboard. |
| 4.3 | **Event replay** | [ ] | Allow merchants to replay a failed/missed webhook delivery from the dashboard. |
| 4.4 | **Customer vault** | [ ] | Save and reuse payment methods (tokenised). Unlocks "charge on file", one-click checkout, and subscription use cases. Requires a `customers` + `saved_payment_methods` table. |
| 4.5 | **Disputes / chargebacks** | [ ] | Ingest dispute webhooks from providers, expose case management in dashboard, allow evidence submission back to provider. |
| 4.6 | **Merchant audit log** | [ ] | Merchant-facing log: who changed connector credentials, who issued a refund, role changes. `admin_audit_logs` exists for the platform realm; mirror it for the merchant realm. |

---

## Phase 5 — Platform maturity

| # | Item | Status | Detail |
|---|---|---|---|
| 5.1 | **Platform admin UI** | [ ] | `admin_users` table exists, zero endpoints or UI. Admin: merchant management, connector oversight, global event log. |
| 5.2 | **Rate limiting** | [ ] | Per-merchant API rate limits. Without this, one runaway integration can starve others. Implemented at filter level (Bucket4j — no Redis needed at this scale). |
| 5.3 | **API versioning strategy** | [ ] | All routes are `/v1/`. Define deprecation policy and version promotion path before breaking changes accumulate. |
| 5.4 | **Mobile SDKs** | [ ] | iOS and Android native SDKs. Browser SDK is the model; same lifecycle pattern. |
| 5.5 | **Reconciliation** | [ ] | Ingest provider settlement files / payout reports. Match against `payment_intents`. Expose discrepancies. |
| 5.6 | **Subscription / recurring billing** | [ ] | Interval-based charge schedules against vaulted payment methods (depends on 4.4). |

---

## Phase H — High-throughput payment core

This is a new scale profile that intentionally evolves beyond the MVP constraints of a single Postgres-backed gateway. The design keeps Postgres shards as the financial source of truth, uses `payment_id` / `order_id` sharding to avoid hot merchants, adds Redis only for hot-path optimization, adds Kafka for async event fan-out, and reserves OpenSearch/Elasticsearch for dashboard/search projections rather than authoritative state checks.

See [HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md](HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md).

| # | Item | Status | Detail |
|---|---|---|---|
| H0 | **Architecture baseline** | ✅ | Record the new scale profile, consistency boundaries, sharding strategy, and staged implementation plan. |
| H1 | **Logical payment sharding** | ✅ | Added ShardingSphere-JDBC, 64 logical shards for `payment_intents` and `payment_requests`, shard registry tables, local/demo backfill, Docker shard config, and shard routing tests. |
| H2 | **State machine and idempotency hardening** | ✅ | Added sharded create idempotency reservation, optimistic versioning, row-level locks for confirm/capture/cancel/refund decisions, refund available-amount validation, and ShardingSphere-safe outbox/stale-intent locks. Test coverage includes concurrent refund over-refund; confirm/capture/cancel race tests remain follow-up hardening. |
| H3 | **Kafka outbox publisher** | ✅ | Publishes committed outbox events to Kafka while preserving the DB outbox as the recovery source. Tracks Kafka publish state separately from webhook fan-out, cleans fully published outbox rows in bounded batches, creates the lifecycle topic, and exposes Kafka broker/outbox metrics in Prometheus/Grafana. Consumer idempotency and worker status handling move to H4. |
| H4 | **Async workers** | ✅ | Webhook fan-out and payment read-model projection run from independent Kafka consumer groups in Docker and the local Maven profile. Projection has controlled backfill, dashboard list/search cutover, DB idempotency/failure ledger, metrics, and alerts. Reconciliation worker split and notifications are deferred until there is a concrete production requirement. |
| H5 | **Redis hot path** | ✅ | Added Redis local/Docker service, Redisson-backed merchant/API rate limiting, payment-create idempotency route cache, provider health cache, and fail-open outage fallback metrics. Postgres remains authoritative for payment state and idempotency. |
| H5b | **Preview runtime hardening** | ✅ | Added `application-preview.yml`, `.env.preview`, and `docker-compose.preview.yml` for a production-like local stack before H6: Kafka workers and Redis hot path enabled, webhook DB poller off, projection backfill opt-in, health details hidden, and preview consumer groups. |
| H6 | **Dashboard search/read projections** | [ ] | Use OpenSearch/Elasticsearch for merchant dashboard search and views, not payment state authority. |
| H7 | **Benchmarks and failure-mode docs** | ✅ | k6 now compares Postgres-only vs optional Kafka/Redis infra, covers create/confirm/refund/get/list/idempotency flows, creates a TEST-only Mason Simulator connector through the normal provider path, supports configurable simulator PSP success rates, and feeds Prometheus/Grafana bench/payment dashboards. Fresh numeric baselines and deeper failure-mode playbooks remain follow-up hardening, not H7 blockers. |

---

## Phase AI — Assisted Payment Operations Control Plane

MasonXPay should evolve beyond a payment gateway demo into a payment operations platform with an AI-assisted control plane.

See [AI_CONTROL_PLANE_PLAN.md](AI_CONTROL_PLANE_PLAN.md).

Core safety rule: AI does not authorize, decline, or route payments directly. AI analyzes telemetry, explains incidents, recommends routing-policy changes, and can draft safe configuration updates. A deterministic validator and a human approval step remain between AI output and production routing changes. The runtime routing engine continues to execute explicit, versioned configuration only.

Model strategy: the AI control plane should be provider-agnostic. Support multiple model providers such as OpenAI/ChatGPT, Anthropic Claude, and Google Gemini behind a stable internal `AiModelProvider` interface. The dashboard should let platform admins configure available providers, credentials, default model, fallback model, cost/latency limits, and which model is allowed for each workflow stage. The selected model affects investigation and explanation quality only; it must not change payment execution semantics.

Engineering principles:

- Start with simple workflow orchestration before autonomous agents.
- Use narrow, explicit tools for telemetry reads, incident summaries, policy drafting, and validation.
- Keep all write-capable tools behind deterministic validators and human approval.
- Treat external model providers as outside the trust boundary by default; send only redacted, aggregated, policy-approved evidence.
- Keep the AI control plane useful when external models are disabled: deterministic incident detection, policy validation, human review, and rollback still work without model calls.
- Run evaluations for incident classification, recommendation quality, policy validation, and explanation clarity before changing defaults.
- Log model, prompt/template version, input evidence references, output proposal, validator result, approver, and final applied config version.
- Enforce tenant and role permissions through existing MasonXPay access control; AI may only see data the requesting user or service role is allowed to inspect.

Target flow:

```text
payment traffic simulator
  -> success-rate / latency / retry metrics
  -> provider degradation detected
  -> AI agent investigates
  -> AI agent proposes routing-policy change
  -> policy validator checks constraints
  -> human approves
  -> routing config updated
  -> deterministic routing engine executes
```

Example incident:

```text
Incident:
  Stripe SG VISA success rate dropped from 97.8% to 82.3%

Agent recommendation:
  reduce Stripe SG VISA traffic from 80% to 20%
  increase alternate SG VISA provider traffic from 20% to 80%
  keep Mastercard unchanged
  rollback automatically if alternate provider error rate > 5%
```

| # | Item | Status | Detail |
|---|---|---|---|
| AI0 | **Safety and authority model** | [ ] | Define hard boundaries: AI is advisory; validator and human approval are mandatory; deterministic routing engine is the only runtime decision maker. |
| AI1 | **Traffic simulator and incident seeds** | [ ] | Generate synthetic payment traffic, provider degradation, latency spikes, retry storms, and regional/card-brand incidents for preview and benchmark environments. |
| AI2 | **Telemetry-to-incident detector** | [ ] | Convert success-rate, latency, retry, failover, provider, country, currency, and card-brand metrics into structured incident candidates. |
| AI3 | **Model-agnostic AI investigation workflow** | [ ] | Build provider adapters for OpenAI/ChatGPT, Claude, and Gemini; support configurable default/fallback models; summarize evidence, compare baseline vs current telemetry, explain impact, and draft routing-policy changes without applying them. |
| AI3b | **AI evidence redaction and data policy** | [ ] | Add field-level allowlists, redaction, external-model deployment modes, no-external-AI kill switch, prompt-injection handling, and tests that prevent sensitive data from reaching model providers. |
| AI4 | **Policy change model and validator** | [ ] | Add a typed change proposal format plus validation for tenant scope, provider availability, traffic caps, rollback criteria, blast radius, and forbidden changes. |
| AI5 | **Human approval and model settings UI** | [ ] | Add dashboard review UI with diff, explanation, simulated impact, rollback plan, approval/rejection audit trail, versioned routing config updates, and platform-admin controls for default model/provider configuration. |
| AI6 | **Deterministic execution and rollback** | [ ] | Apply approved policy changes through existing routing rules and monitor rollback conditions with deterministic workers, not AI. |
| AI7 | **Agent harness, evals, and auditability** | [ ] | Add prompt/template versioning, golden incident datasets, offline evals, model comparison reports, trace links, tool-call audit logs, and rollout gates for changing default models. |

---

## Dependency graph

```
Phase 0 (done)
    └── Phase 1 (correctness — unblock everything)
            ├── Phase 2 (observability — foundation for 3 and 4)
            │       ├── Phase 3.1/3.2 (smart routing needs success-rate data)
            │       └── Phase 4.1 (analytics needs metrics)
            ├── Phase 3 (orchestration — connector breadth + intelligence)
            ├── Phase 4 (merchant ops — customer vault → Phase 5.6 subscriptions)
            └── Phase H (high-throughput core — sharding, Redis, Kafka, read projections)
                    └── Phase AI (assisted ops control plane — advisory agent + validator + approval)
```
