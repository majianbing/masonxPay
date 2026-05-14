# YUNO Challenge Prep: Payment Retry Orchestrator

This branch is prepared for the YUNO backend challenge, "The Sao Paulo Flash Sale Disaster: Build a Payment Retry Orchestrator".

## Submission Focus

MasonXPay implements a backend payment retry orchestrator for high-traffic payment confirmation flows. The implementation is intentionally production-minded: it retries transient failures, avoids retry storms, preserves provider idempotency, records each attempt, and keeps cross-provider retry gated behind an explicit compatibility context.

## Core Files

- `backend/src/main/java/com/masonx/paygateway/service/PaymentRetryOrchestratorService.java`
- `backend/src/main/java/com/masonx/paygateway/service/PaymentRetryContext.java`
- `backend/src/main/java/com/masonx/paygateway/service/RoutePlan.java`
- `backend/src/main/java/com/masonx/paygateway/service/RouteCandidate.java`
- `backend/src/main/java/com/masonx/paygateway/service/FailoverPolicy.java`
- `backend/src/main/java/com/masonx/paygateway/service/ConnectorCircuitBreaker.java`
- `backend/src/main/java/com/masonx/paygateway/domain/payment/PaymentAttemptType.java`
- `backend/src/main/resources/db/migration/V37__add_retry_metadata_to_payment_requests.sql`
- `backend/src/test/java/com/masonx/paygateway/service/PaymentRetryOrchestratorServiceTest.java`
- `backend/src/test/java/com/masonx/paygateway/service/PaymentIntentServiceTest.java`

## Behavior

- Same provider/account is retried before any fallback provider.
- Same-account retries reuse the same provider idempotency key.
- Total attempts are capped by config and hard-capped at 5.
- Same-account attempts are separately capped by config.
- Hard card declines are not retried.
- Transient/provider/gateway failures can retry.
- Cross-provider fallback only runs when `PaymentRetryContext.allowFallback()` is explicitly passed.
- Normal payment confirmation currently uses `PaymentRetryContext.sameAccountOnly()` because provider tokens are not portable.
- Every attempt is persisted with `attempt_number`, `attempt_type`, connector account, status, failure code, and failure message.

## Test Commands

Use Java 21:

```bash
cd backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
mvn -Dtest=PaymentRetryOrchestratorServiceTest,PaymentIntentServiceTest,PaymentIntentResponseTest test
```

Broader retry/routing suite:

```bash
cd backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
mvn -Dtest=RoutingEngineTest,ConnectorCircuitBreakerTest,PaymentRetryOrchestratorServiceTest,PaymentIntentServiceTest,PaymentIntentResponseTest test
```

## Demo Script

Explain the system with the unit tests first. They are deterministic and avoid real-provider flakiness.

Recommended tests to highlight:

- `execute_allowFallback_retryableFailure_retriesSameAccountBeforeFallbackAndReusesProviderIdempotencyKey`
- `execute_sameAccountOnly_neverFallsBack`
- `execute_allowFallback_nonRetryableFailure_stopsAfterFirstAttempt`
- `execute_allowFallback_retryableFailures_respectsConfiguredAttemptLimit`
- `confirm_routedFallbackCandidate_usesSameAccountOnlyRetryContext`

Optional Stripe sandbox check for backend-confirm flows:

1. Configure a Stripe TEST connector in the dashboard.
2. Create a PaymentIntent through the secret-key API.
3. Confirm with Stripe test PaymentMethod `pm_card_chargeDeclinedProcessingError`.
4. Inspect the payment detail attempts. Expect `PRIMARY` then `SAME_ACCOUNT_RETRY`.

Do not use payment links to demo retry orchestration. The hosted Stripe Payment Element flow confirms directly with Stripe.js, then records the result; it does not call the backend retry orchestrator.

## Architecture Talking Points

- Provider calls are outside long database transactions.
- Each attempt is created and updated in short transaction blocks.
- Provider idempotency key is stable per payment intent and connector account.
- The circuit breaker records retryable failures but ignores hard card declines.
- Route fallback is separate from retry fallback. Routing may promote a healthy fallback before execution, while retry fallback is gated by credential compatibility.
- Dashboard exposes attempt timelines without exposing provider idempotency keys.

## Known Tradeoffs

- Cross-provider retries are disabled by default because Stripe/Square/Braintree tokens are provider-specific.
- Hosted Stripe Payment Element is optimized for PCI scope reduction and browser confirmation; it is not the retry-orchestrated backend confirm path.
- A future production version should add a persisted merchant retry policy and a credential compatibility model before enabling cross-provider retry broadly.

## If the Prompt Requires a New API

Prefer adding a small controller/service boundary around the existing orchestrator instead of rewriting it. Keep the core behavior in `PaymentRetryOrchestratorService` and add tests around the new boundary.
