# Testing Strategy

Use the test pyramid. Do not rely on expensive E2E tests as the main correctness layer.

## Unit Tests

Cover deterministic business logic:

- routing and capability matching
- route condition validation
- retry and fallback decisions
- payment/refund state transitions
- billing state transitions
- idempotency helpers
- security helpers
- serialization and mapping edge cases

Unit tests should be fast, focused, and numerous.

## Integration Tests

Cover behavior crossing module boundaries:

- repositories and migrations
- controllers and auth/tenant scope
- TEST/LIVE mode filtering
- transaction behavior
- outbox writes
- simulator-backed provider flows
- Kafka/Redis/projection behavior when relevant

## E2E And Smoke Tests

Keep E2E tests limited to critical merchant/customer workflows:

- hosted checkout
- payment links
- connector preview
- dashboard capability/routing configuration
- webhook delivery

Prefer Mason Simulator unless provider-specific sandbox behavior is the thing being tested.

## Test Placement

- Routing tests: owning service or `service/routing`.
- Provider tests: `provider`.
- Controller tests: `web`.
- Redis/Kafka/projection tests: their owning module package.
- Billing tests: owning `billing/*` package.

Keep each test class focused on one behavior owner. Use local builders/helper methods before introducing shared fixtures.

## Completion Rule

Do not mark a phase or feature complete until the relevant test layer has passing coverage, or the remaining verification gap is explicitly documented in the phase tracker.

Do not claim tests passed unless the command actually ran.
