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

## Phase 2 — Observability ✅ (complete)

Foundation that Phases 3 and 4 depend on — smart routing needs success-rate data; merchant analytics needs aggregated metrics; connector health needs measurement before it can be acted on.

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
| 4.1 | **Merchant analytics API + dashboard** | ✅ | `GET /analytics?mode=&from=&to=&groupBy=status\|connector\|currency\|reason` — JPQL aggregates on `payment_intents` and `refunds`. Response: payment summary (volume, count, conversion rate, failed count), refund summary (refund volume, count, rate, net revenue), breakdown by selected dimension, daily time-series with revenue and refund volume. Dashboard `/analytics`: 8 summary cards (4 payment + 4 refund), dual-axis area chart (revenue left / refunds right), horizontal bar breakdown with legend, 7D/30D/90D preset toggle, groupBy toggle including Reason for refund breakdown. Designed empty state with ghost preview and CTA links for new merchants. |
| 4.2 | **Webhook endpoint management UI** | ✅ | Already delivered: `WebhookEndpointController` (CRUD + rotate-secret + delivery history) and dashboard `/developers/webhooks` page (302 lines). No gap. |
| 4.3 | **Event replay** | ✅ | `POST /webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay` creates a new `WebhookDelivery` row and attempts delivery immediately. Delivery list upgraded to paginated `Page<T>` with `@PageableDefault(size=20)` and optional `?status=` filter. Dashboard: status filter dropdown, Prev/Next pagination, and per-row Replay button with toast feedback. |
| 4.4 | **Customer vault** | ✅ | Delivered in Phase S1/S2: `customers`, `customer_payment_methods`, `PaymentInstrument` vault tokens, dashboard customer management (list/create/edit, attach/detach/set-default). |
| 4.5 | **Disputes / chargebacks** | ✅ | Ingest dispute webhooks from providers (Stripe: `charge.dispute.*`, Square: `dispute.state.changed`, Braintree: `DISPUTE_*`), expose case management in dashboard (paginated list with status filter + detail/evidence page), allow evidence submission back to Stripe (Square/Braintree stubs). New `disputes` + `dispute_evidence_files` tables. Configurable AWS S3 storage for evidence files with local filesystem fallback. |
| 4.6 | **Merchant audit log** | ✅ | `merchant_audit_logs` table (merchant_id, actor_user_id, actor_email denormalized, action enum, resource_type/id/label, metadata JSONB). Write points in ProviderAccountService (connector create/update/delete), RefundService (refund issued, inside TX1), MemberService (invite/role-change/revoke), ApiKeyService (create/revoke). Actor resolved from SecurityContextHolder; null-safe for worker threads. GET `/api/v1/merchants/{merchantId}/audit-logs` (LOG:READ, Page, optional action filter). Dashboard `/settings/audit-log`: paginated table, action-filter dropdown, colour-coded badges, metadata summary. |

---

## Phase 15 — Platform maturity

Lower-priority platform hardening. These items are deferred until the core payment, orchestration, and billing tracks are more mature.

| # | Item | Status | Detail |
|---|---|---|---|
| 15.1 | **Platform admin UI** | [ ] | `admin_users` table exists, zero endpoints or UI. Admin: merchant management, connector oversight, global event log. |
| 15.2 | **Rate limiting** | [ ] | Per-merchant API rate limits. Without this, one runaway integration can starve others. Implemented at filter level (Bucket4j — no Redis needed at this scale). |
| 15.3 | **API versioning strategy** | [ ] | All routes are `/v1/`. Define deprecation policy and version promotion path before breaking changes accumulate. |
| 15.4 | **Mobile SDKs** | [ ] | iOS and Android native SDKs. Browser SDK is the model; same lifecycle pattern. |
| 15.5 | **Reconciliation** | [ ] | Ingest provider settlement files / payout reports. Match against `payment_intents`. Expose discrepancies. |
| 15.6 | **Subscription / recurring billing** | ✅ | Split into standalone Phase S and delivered through S1-S5: customers, payment methods, subscriptions, invoices, off-session execution, recurring retry/dunning, and dashboard operations. Remaining hardening is tracked in Phase S. |

---

## Phase H — High-throughput payment core

This is a new scale profile that intentionally evolves beyond the MVP constraints of a single Postgres-backed gateway. The design keeps Postgres shards as the financial source of truth, uses `payment_id` / `order_id` sharding to avoid hot merchants, adds Redis only for hot-path optimization, and adds Kafka for async event fan-out. H6 should continue with Postgres-backed dashboard read models; OpenSearch remains an optional future search index only if read-model search outgrows Postgres. Elasticsearch is intentionally out of scope for now.

See [high-throughput payment core plan](high-throughput-payment-core-plan.md).

| # | Item | Status | Detail |
|---|---|---|---|
| H0 | **Architecture baseline** | ✅ | Record the new scale profile, consistency boundaries, sharding strategy, and staged implementation plan. |
| H1 | **Logical payment sharding** | ✅ | Added ShardingSphere-JDBC, 64 logical shards for `payment_intents` and `payment_requests`, shard registry tables, local/demo backfill, Docker shard config, and shard routing tests. |
| H2 | **State machine and idempotency hardening** | ✅ | Added sharded create idempotency reservation, optimistic versioning, row-level locks for confirm/capture/cancel/refund decisions, refund available-amount validation, and ShardingSphere-safe outbox/stale-intent locks. Test coverage includes concurrent refund over-refund; confirm/capture/cancel race tests remain follow-up hardening. |
| H3 | **Kafka outbox publisher** | ✅ | Publishes committed outbox events to Kafka while preserving the DB outbox as the recovery source. Tracks Kafka publish state separately from webhook fan-out, cleans fully published outbox rows in bounded batches, creates the lifecycle topic, and exposes Kafka broker/outbox metrics in Prometheus/Grafana. Consumer idempotency and worker status handling move to H4. |
| H4 | **Async workers** | ✅ | Webhook fan-out and payment read-model projection run from independent Kafka consumer groups in Docker and the local Maven profile. Projection has controlled backfill, dashboard list/search cutover, DB idempotency/failure ledger, metrics, and alerts. Reconciliation worker split and notifications are deferred until there is a concrete production requirement. |
| H5 | **Redis hot path** | ✅ | Added Redis local/Docker service, Redisson-backed merchant/API rate limiting, payment-create idempotency route cache, provider health cache, and fail-open outage fallback metrics. Postgres remains authoritative for payment state and idempotency. |
| H5b | **Preview runtime hardening** | ✅ | Added `application-preview.yml`, `.env.preview`, and `docker-compose.preview.yml` for a production-like local stack before H6: Kafka workers and Redis hot path enabled, webhook DB poller off, projection backfill opt-in, health details hidden, and preview consumer groups. |
| H6 | **Dashboard read-model hardening** | [ ] | `payment_read_models` already powers dashboard list/search when Kafka projections are enabled. Added `postgres-exporter` plus Grafana panels/alerts for `payment_read_models` table/index size, sequential-vs-index scan rate, dead-tuple ratio, and merchant payment list p95/p99 latency, with a documented decision order (index → partition → OpenSearch) for when the table approaches a ceiling. Remaining: search/filter ergonomics, projection lag visibility in the dashboard UI, backfill/repair workflow, and operational docs. Keep OpenSearch as an optional future adapter if dashboard/support search outgrows Postgres; keep Elasticsearch out of scope. |
| H7 | **Benchmarks and failure-mode docs** | ✅ | k6 now compares Postgres-only vs optional Kafka/Redis infra, covers create/confirm/refund/get/list/idempotency flows, creates a TEST-only Mason Simulator connector through the normal provider path, supports configurable simulator PSP success rates, and feeds Prometheus/Grafana bench/payment dashboards. Fresh numeric baselines and deeper failure-mode playbooks remain follow-up hardening, not H7 blockers. |

---

## Phase O — Advanced Payment Orchestration

MasonXPay should prioritize Yuno-like deterministic orchestration before the AI control plane: richer routing context, payment-instrument abstraction, provider capability checks, route policy versioning, outcome-based fallback, and controlled retry orchestration.

See [payment orchestration, routing, retry, and instrument plan](payment-orchestration-routing-retry-plan.md).

Core boundary: MasonXPay does not need raw PAN to build the next orchestration layer. The near-term system should route on safe context and opaque instrument references. Future vault, network-token, or PCI-scoped card handling must plug into a separate instrument domain instead of leaking PAN/CVV into payment intents, logs, Kafka events, or read models.

| # | Item | Status | Detail |
|---|---|---|---|
| O1 | **Instrument and context foundation** | ✅ | Added `PaymentInstrument`, safe metadata, portability/source flags, `RoutingContext`, gateway-token instrument linking, provider-scoped confirm safety, and payment-method reference log redaction. Live confirm now permits route fallback only for portable instruments. |
| O2 | **Provider capability matrix** | ✅ | Added account-scoped capabilities, connector-scoped capability management APIs, default card capability seeds for newly created connector accounts, capability-aware route-step filtering, dashboard UI, simulator hosted-checkout support, capability-aware payment-link tokenization, and Docker/manual payment-link verification with capabilities enabled. |
| O3 | **Route policy v2** | ✅ | Added route policy/route/step tables, draft create/replace, publish/archive/list/detail/audit APIs, strict condition-schema validation, dry-run route simulation, dashboard policy list/create/edit/simulation UI, active-policy routing before legacy rules, and simulator-backed local tests. |
| O3b | **Routing UI consolidation** | ✅ | Exposed one Routing navigation entry backed by route policies, moved policies into list and dedicated create/edit pages, and kept legacy rules APIs/runtime fallback as compatibility only. |
| O4 | **Outcome-based fallback** | ✅ | Added route-step `outcome_actions_json`, conservative outcome categorization, retry/next/stop execution, and focused tests. Hard declines stop, simulator declines model hard declines, and live cross-route fallback is gated by instrument portability. |
| O5 | **Scheduled retry orchestration** | ✅ | Added merchant-scoped scheduled retry job storage, list/cancel APIs, due-job worker execution, automatic capture recovery scheduling, and dashboard visibility. Refund auto-retry is disabled by default because background money movement must not risk merchant fund loss without explicit approval. |
| O6 | **Optional portable card support** | [ ] | Add third-party vault or network-token integration only when cross-PSP card portability is a real requirement; keep raw PAN behind an isolated PCI boundary if ever introduced. |

---

## Phase S — Subscription and Recurring Billing

MasonXPay now has a merchant-owned subscription and recurring billing foundation. The module owns customers, payment-method references, subscriptions, invoices, off-session invoice execution, retry/dunning state, and dashboard operations while preserving the normal payment-core boundaries. Remaining work is product hardening such as configurable dunning policy, Mollie mandate completion, and promotions/coupons.

See [subscription and recurring billing plan](subscription-recurring-billing-plan.md).

| # | Item | Status | Detail |
|---|---|---|---|
| S0 | **Architecture and state model** | ✅ | Defined customer, payment-method, subscription, invoice, invoice payment attempt, permission, webhook, and payment-instrument boundaries as the foundation for S1-S5. |
| S1 | **Customer and payment method foundation** | ✅ | Merchant-scoped TEST/LIVE-isolated customers, payment-method references backed by safe `PaymentInstrument` rows, backend APIs, service tests, controller integration tests (tenant isolation, mode filtering, instrument ownership), and dashboard customer management. |
| S2 | **Subscription and invoice foundation** | ✅ | Mode-aware subscriptions, subscription items, trial-aware creation, checkout-link tokens, public checkout-link lookup, TEST-mode simulator activation, reusable payment-method setup for Stripe/Square/Braintree/Simulator, invoice storage with mode isolation, idempotent period invoice generation, period advancement worker, controller integration tests, and state transition guards. Promotions/coupons deferred until after S3. Mollie mandate completion remains pending. |
| S3 | **Off-session invoice payment execution** | ✅ | InvoicePaymentService charges open invoices off-session via saved vault tokens. Two-phase transaction discipline (provider call outside DB transaction). requiresAction treated as failure. Idempotent for already-paid invoices. InvoiceController with flat list/get/pay endpoints. 8 unit + 6 integration tests. |
| S4 | **Recurring retry and dunning** | ✅ | `InvoiceBillingWorker` scans OPEN invoices every 5 minutes, charges off-session, reschedules with 3/5/7-day delays on soft failure, marks UNCOLLECTIBLE on hard decline or exhaustion. Optimistic claim (UPDATE WHERE timestamp matches) prevents concurrent workers from double-charging. Deterministic provider idempotency key (`inv-{id}-attempt-{n}`) prevents double-charge on crash/retry. ShardingSphere single-table registration required for all billing tables. |
| S5 | **Dashboard operations** | ✅ | Full merchant billing operations: customer list/create/edit with human-readable payment method display (brand+last4+expiry), set-default and detach actions. Subscription list (paginated), detail, cancel (Dialog), checkout links (single-active-link rule). Public checkout with real PSP SDKs (Stripe Elements, Square Web Payments, Braintree Drop-in, Simulator). Invoices standalone page + inline detail with pay/write-off/payment-link. Billing sidebar group. Deferred: dunning config UI, Mollie mandate, promotions. |

---

## Phase AI — Assisted Payment Operations Control Plane

MasonXPay should eventually add an AI-assisted control plane, but this phase is lower priority than the deterministic orchestration engine. The AI layer should analyze, explain, and draft policy changes after routing, retry, telemetry, and instrument boundaries are mature.

See [AI-assisted operations control plane plan](ai-control-plane-plan.md).

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

## Phase R — Financial Reconciliation

Settlement file reconciliation and real-time dual-stream monitoring at 1–10M transactions/day. Phase R is entirely additive — it builds on the existing Kafka event stream, `payment_requests` table, and provider response storage without touching the authorization path. It is also the foundation that Phase N settlement processing will extend.

| # | Item | Status | Detail |
|---|---|---|---|
| R1 | **Settlement file ingestion** | [ ] | Per-PSP CSV/JSON settlement file parsers (Stripe, Square, Braintree, Mollie). Store raw settlement records in a separate reconciliation table. Schedule daily ingestion jobs. |
| R2 | **Batch matching engine** | [ ] | Match each settlement line against `payment_requests` by provider payment ID. Detect: unmatched charges, over/under-settled amounts, fee discrepancies, currency conversion gaps. Write discrepancy records. |
| R3 | **Real-time dual-stream comparison** | [ ] | Kafka Streams job comparing Stream A (internal payment lifecycle events) against Stream B (PSP webhooks). Flag gaps beyond configurable tolerance windows (e.g. succeeded internally but no PSP webhook within 5 min). |
| R4 | **Reconciliation dashboard** | [ ] | Merchant-facing: unmatched transactions, settlement status per PSP, fee variance report, discrepancy drill-down. Ops: real-time stream divergence alerts. |
| R5 | **Automated escalation and audit export** | [ ] | Configurable alerting thresholds, merchant-facing reconciliation reports, audit-ready export (CSV/PDF), chargeback evidence packaging. |

---

## Phase N — Direct Card Network Connectivity

Long-term path toward direct acquiring relationships with card networks (Visa, Mastercard, JCB, UnionPay). The `PaymentProviderService` interface is the stable abstraction — Phase N plugs new implementations below it without changing routing, orchestration, or billing above it.

**PCI boundary rule:** Raw PAN and track data must never enter MasonXPay core services at any phase. Network tokens (Visa VTS DPAN / Mastercard MDES) are the only card references that cross the MasonXPay service boundary. A separate, isolated PCI-scoped component handles ISO 8583 communication and translates between network tokens and the card networks. This boundary must be maintained across all phases.

**Prerequisite design hooks (low cost, record intent now):**
- `InstrumentSource.NETWORK_TOKEN` reserved in the enum for Visa VTS / MC MDES tokens.
- Phase R settlement accounting should be designed to extend into Phase N clearing and interchange.

| # | Item | Status | Detail |
|---|---|---|---|
| N1 | **Payment Facilitator model** | [ ] | Sub-merchant onboarding under a BIN sponsor acquirer. MasonXPay becomes the master merchant. Direct acquiring bank API integration (not ISO 8583 yet). Extends existing `PaymentProviderService` with a `PayFacProvider` implementation. Requires acquiring bank partnership and PCI DSS SAQ D or Level 1. |
| N2 | **Direct card network acquiring** | [ ] | ISO 8583 authorization via a separate PCI-scoped service. New `VISA_DIRECT` / `MC_DIRECT` provider implementations. Network token support (`InstrumentSource.NETWORK_TOKEN` via Visa VTS / MC MDES). Requires full acquiring license, dedicated network connectivity, HSM hardware, and PCI DSS Level 1 certification. 5+ year horizon. |

---

## Dependency graph

```
Phase 0 (done)
    └── Phase 1 (correctness — unblock everything)
            ├── Phase 2 (observability — foundation for 3 and 4)
            │       ├── Phase 3.1/3.2 (smart routing needs success-rate data)
            │       └── Phase 4.1 (analytics needs metrics)
            ├── Phase 3 (orchestration — connector breadth + intelligence)
            ├── Phase 4 (merchant ops — customer vault → Phase 15.6 subscriptions)
            └── Phase H (high-throughput core — sharding, Redis, Kafka, read projections)
                    ├── Phase O (advanced orchestration — instruments, route policies, fallback, retries)
                    └── Phase AI (later advisory ops control plane — recommendations, validator, approval)
```
