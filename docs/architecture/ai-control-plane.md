# AI Control Plane

The AI control plane is advisory. It may investigate, explain, recommend, and draft configuration changes, but it must never execute payment decisions or directly mutate payment configuration.

## Authority Boundary

AI may:

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

## Deployment Modes

The architecture should support:

- `external-redacted`: hosted models with redacted and aggregated evidence
- `external-restricted`: hosted models with stricter enterprise controls and explicitly approved limited context
- `internal-only`: self-hosted or internal model gateway with policy-filtered context
- `disabled`: no model calls; deterministic detection, validation, dashboard review, and rollback still work

## Audit And Evaluation

Log model provider, model name, prompt/template version, redacted evidence references, model output, validator result, approver, and final applied config version. Run evaluations for incident classification, recommendation quality, policy validation, and explanation clarity before changing default models or prompts.

## References

- [Security boundaries](security-boundaries.md)
- [Routing and orchestration](routing-orchestration.md)
- [AI-assisted operations control plane plan](../planning/ai-control-plane-plan.md)
