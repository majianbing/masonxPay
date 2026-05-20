# MasonXPay Agent Guide

MasonXPay is evolving from a payment gateway into a payment operations platform. The core product is a multi-provider payment gateway; the scale track adds sharding, Kafka, Redis, projections, and preview operations; the future AI control plane remains advisory and never executes payment decisions directly.

## Repository Map

- `backend/`: Java 21 Spring Boot API, payment core, providers, sharding, Kafka workers, Redis hot path, migrations, tests.
- `dashboard/`: Next.js merchant/admin UI.
- `sdk/server/`, `sdk/browser/`: TypeScript SDKs. Browser checkout UI lives in `sdk/browser/src/index.ts`.
- `monitor/`: Prometheus, Grafana, Kafka JMX assets.
- `bench/`: k6 benchmark scenarios.
- `cloud-deploy/`: deployment assets.
- `docs/`: roadmap, high-throughput plan, long development guide, and historical prompt/reference docs.

## Primary Docs

- Product and phase roadmap: `docs/ROADMAP.md`
- High-throughput payment core plan: `docs/HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md`
- AI-assisted operations control-plane plan: `docs/AI_CONTROL_PLANE_PLAN.md`
- Detailed development guide migrated from the old Claude root file: `docs/DEVELOPMENT_GUIDE.md`
- Full historical prompt/reference: `docs/payment-gateway-full-prompt.md`
- Root README for setup and public project overview: `README.md`

## Current Phases

- MVP/core gateway: complete enough for multi-provider payment flows, hosted checkout, dashboard, webhooks, RBAC, MFA, and observability.
- High-throughput track H1-H5b: logical payment sharding, state/idempotency hardening, Kafka outbox/workers, Redis hot path, and preview profile are done.
- Next high-throughput work: H6 dashboard search/read projections and H7 benchmarks/failure-mode documentation.
- AI operations control plane: planned. AI analyzes, recommends, explains, and drafts config changes; validators, human approval, and deterministic routing remain authoritative.

## Commands

- `docker compose up --build`: run the default local stack.
- `docker compose -p masonxpay-preview --env-file .env.preview -f docker-compose.yml -f docker-compose.preview.yml up --build`: run the preview stack.
- `cd backend && mvn compile`: compile backend.
- `cd backend && mvn test`: run backend tests.
- `cd dashboard && npm run build`: build dashboard.
- `cd sdk/server && npm run build`: build server SDK.
- `cd sdk/browser && npm run build && npm run bundle`: build/browser bundle SDK.
- `docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6`: run benchmark profile.

## Non-Negotiable Architecture Rules

- Every new table must include `merchant_id`; every read/write path must enforce tenant scope.
- Merchant portal users and platform admin users stay separate.
- Provider webhooks are unauthenticated at the HTTP edge, so signature verification is mandatory.
- Never log secrets, tokens, PAN/card data, CVV, private keys, raw provider payloads, or signature headers.
- Webhook/outbox writes must stay atomic with payment state.
- Do not add `@Transactional` to methods that perform remote provider calls; keep DB transactions short and around state changes.
- Keep Postgres/sharded payment tables authoritative for financial state. Redis, Kafka, and OpenSearch are supporting systems, not payment-state authorities.
- Prefer mature infrastructure components for Redis, Kafka, database access, mapping, retries, rate limiting, and similar cross-cutting behavior.
- Keep submodules clean and focused; avoid turning one module into a catch-all.
- AI must not authorize, decline, or route payments directly. AI output must pass deterministic validation and human approval before config changes are applied.
- External AI models must receive only redacted, aggregated, policy-approved evidence. Secrets, raw payment payloads, card data, provider credentials, webhook signatures, private keys, tokens, and unredacted PII must never be sent to model providers.

## Engineering Style

- Java: 4-space indentation, package root `com.masonx.paygateway`, constructor injection, DTOs at API boundaries.
- TypeScript/React: 2-space indentation, PascalCase components, camelCase functions, `@/` imports.
- Keep business logic out of controllers.
- Avoid broad `catch (Exception)` blocks unless there is a clear fallback and logging strategy.
- Add or update tests for business logic, state transitions, auth boundaries, routing, webhooks, and bug fixes.
- Do not claim tests passed unless they actually ran.

## Before Final Response

Summarize:

- Files changed
- Why each change was made
- Tests run and results
- Remaining risks or follow-up work
