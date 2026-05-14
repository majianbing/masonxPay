# Repository Guidelines

## Project Structure

MasonXPay is a multi-module payment gateway workspace.

- `backend/`: Java 21 Spring Boot API. Code: `src/main/java/com/masonx/paygateway`; tests: `src/test/java`; migrations: `src/main/resources/db/migration`.
- `dashboard/`: Next.js 15 portal. Routes: `app/`; UI: `components/`; helpers: `lib/`, `store/`; assets: `public/`.
- `sdk/server/`, `sdk/browser/`: TypeScript SDKs. Browser checkout UI belongs in `sdk/browser/src/index.ts`.
- `monitor/`, `bench/`, `cloud-deploy/`: Prometheus/Grafana, k6, and deployment assets.

## Commands

- `docker compose up --build`: run Postgres, backend, dashboard, Prometheus, Grafana.
- `cd backend && mvn spring-boot:run`: run backend locally.
- `cd backend && mvn test`: run JUnit/Spring tests.
- `cd backend && mvn compile`: compile backend.
- `cd dashboard && npm run dev`: start dashboard on port 3000.
- `cd dashboard && npm run build`: build the dashboard.
- `cd sdk/server && npm run build`: compile server SDK.
- `cd sdk/browser && npm run build && npm run bundle`: type-check and bundle browser SDK.
- `docker compose -f docker-compose.yml -f docker-compose.bench.yml --profile bench run --rm k6`: run k6 to `bench/results/`.

## Coding Style & Naming Conventions

Use existing style. Java uses 4-space indentation, package root `com.masonx.paygateway`, PascalCase classes, camelCase members, and DTO records. TypeScript/React uses 2-space indentation, PascalCase components, camelCase functions, and `@/` imports. Providers: Stripe, Square, Braintree, Mollie.

Do not add Redis, Kafka/RocketMQ, Caffeine, or `ddl-auto: create/update` without a measured requirement.

## Testing Guidelines

Backend tests use JUnit 5, Mockito, AssertJ, and Spring test utilities. Name tests after behavior, e.g. `create_publishableKey_throwsAccessDenied`. Cover service logic, auth boundaries, routing, webhooks, circuit breaking, stale intent cleanup, and financial state transitions. Frontend relies on build/type checks.

## Commit & Pull Request Guidelines

Prefer concise imperative commits. Conventional Commit style is encouraged, e.g. `feat(routing): add cost-aware routing`. PRs need a summary, tests run, linked issue if applicable, and screenshots for UI changes.

## Security & Architecture Rules

Every new table must include `merchant_id`; every read/write path must enforce tenant scope. Merchant portal and platform admin users stay separate. Do not embed merchant roles in JWT claims; resolve memberships from the database.

Provider webhooks are unauthenticated, so signature verification is mandatory. Reject missing secrets or signatures, use constant-time comparison where applicable, and never log raw bodies or signature headers. Update `ApiRequestLoggingFilter` redaction for new credentials/tokens.

Webhook delivery uses Postgres plus transactional outbox; keep event writes atomic with payment state. Preserve `X-Request-Id` tracing and `/actuator/prometheus` metrics.

Do not add `@Transactional` to methods with remote provider calls. Use short DB transactions around state changes.
