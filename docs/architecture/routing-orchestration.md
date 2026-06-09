# Routing And Orchestration

MasonXPay routing is deterministic. Route selection, retry, fallback, and provider capability checks are driven by explicit merchant configuration and backend validators, not AI output.

## Instrument Boundary

Routing operates on `PaymentInstrument` and `RoutingContext`, not raw card details or provider token strings. Instruments store opaque references and safe metadata only.

Provider-scoped payment tokens can be reused only on the original provider account. Cross-route or cross-PSP fallback requires a portable instrument, a future vault/network token, or explicit customer re-authorization.

## Routing Context

The routing engine evaluates normalized context such as merchant, mode, amount, currency, country, payment method type, capture method, customer, order, instrument source, instrument portability, safe card metadata, wallet type, provider health, and provider capabilities.

Fields can be absent. Conditions that require unavailable metadata should not match unless the condition explicitly handles missing values.

## Provider Capabilities

Capabilities are account-scoped, not only provider-brand scoped. Route steps target concrete provider accounts and must pass capability checks before execution. Capability data should describe method support, countries, currencies, capture/refund support, 3DS/SCA, redirect flow, token type support, and amount bounds.

## Route Policies

Route policies are tenant-scoped, mode-scoped, versioned configuration. Draft policies can be simulated, published policies are audited, and one active policy should apply per merchant/mode.

Recommended route behavior:

- ordered condition sets with first match wins
- AND semantics inside a condition set
- ordered route steps
- route steps target provider accounts
- publication validates condition schema, provider availability, capabilities, and portability constraints

## Retry And Fallback

Keep retry types separate:

- technical retry: short retry for transient provider-call failures inside one provider boundary
- route fallback: same payment intent tries another route step when a normalized outcome allows fallback
- scheduled recovery retry: delayed recovery for capture, refund, recurring invoice, or asynchronous operations

Do not mix these into one generic retry loop. Hard declines, fraud signals, invalid card, invalid CVV, and clear customer/payment-method failures should not automatically cross-PSP retry.

## References

- [Security boundaries](security-boundaries.md)
- [Payment core](payment-core.md)
- [Payment orchestration, routing, retry, and instrument plan](../planning/payment-orchestration-routing-retry-plan.md)
