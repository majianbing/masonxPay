# MasonXPay AI-Assisted Control Plane Plan

MasonXPay should evolve beyond a payment gateway demo into a payment operations platform with an AI-assisted control plane.

## Safety Boundary

AI does not authorize, decline, or route payments directly.

AI may:

- Analyze telemetry.
- Explain incidents.
- Recommend routing-policy changes.
- Draft safe configuration updates.

AI may not:

- Execute a payment authorization decision.
- Decline a payment.
- Directly mutate routing rules.
- Bypass tenant scope, RBAC, approval, or validator checks.
- Receive or exfiltrate secrets, raw payment payloads, card data, webhook signatures, provider credentials, private keys, or unredacted customer PII.

A deterministic validator and a human approval step remain between AI output and production routing changes. The runtime routing engine executes explicit, versioned configuration only.

## Data Security and Privacy Policy

The AI control plane must be designed as if every external model provider is outside the MasonXPay trust boundary unless a deployment explicitly configures otherwise.

Default policy:

- Do not send PAN, CVV, raw card data, provider credentials, API keys, webhook secrets, signature headers, private keys, refresh tokens, MFA secrets, raw provider payloads, or full customer payment payloads to any model.
- Do not send merchant secrets or connector credentials to any model.
- Do not send unredacted customer PII to external models. Use aggregates, hashes, coarse labels, or masked fields where possible.
- Do not send raw database rows by default. Send curated evidence objects with only the fields needed for investigation.
- Do not let the model call arbitrary SQL, arbitrary HTTP, or broad filesystem-like tools.
- Do not use model output as an authorization decision, financial decision, or routing decision.
- Preserve tenant isolation. Evidence for one merchant must not include another merchant's data.

Allowed evidence should be narrow and purpose-built:

- Provider name or provider account alias, not secret credentials.
- Country, currency, card brand, payment method type, and coarse region.
- Aggregated success-rate, latency, retry, failover, and error-code metrics.
- Time windows, baseline/current comparison, and incident IDs.
- Current routing policy shape, expressed as safe config metadata.
- Redacted sample event IDs or trace IDs only when needed for audit/debug links.

Deployment modes:

| Mode | Model location | Data allowed | Use case |
|---|---|---|---|
| `external-redacted` | OpenAI/Claude/Gemini or other hosted APIs | Redacted and aggregated evidence only | Default SaaS-friendly mode |
| `external-restricted` | Hosted APIs with stricter enterprise/data controls | Redacted evidence plus explicitly approved limited context | Regulated production with vendor agreements |
| `internal-only` | Self-hosted/local model or internal model gateway | Broader but still policy-filtered context | Sensitive deployments |
| `disabled` | No model call | Deterministic incident detection, validator, dashboard workflow still work | No-AI or outage fallback mode |

The AI control plane should still work when external models are disabled. In `disabled` mode, MasonXPay can still detect incidents, show deterministic metric summaries, run policy validation, display manual routing-change forms, and execute approved deterministic rollback rules. The missing capability is natural-language investigation and recommendation drafting.

Security controls:

- Add an `AiEvidenceRedactor` before every model call.
- Add an `AiDataPolicy` that declares allowed fields per workflow stage and deployment mode.
- Store model requests and responses in an audit table only after redaction.
- Encrypt AI provider credentials at rest with the existing credential-encryption pattern.
- Apply RBAC to AI settings and investigation views; only platform admins should configure model providers.
- Add a “no external AI” kill switch at environment and dashboard levels.
- Add tests that assert forbidden fields cannot appear in model-bound evidence.
- Add prompt-injection defenses by treating provider messages, webhook payloads, dashboard text, and logs as untrusted data.

## Model Strategy

The AI control plane should be provider-agnostic. Support multiple model providers behind a stable internal `AiModelProvider` interface:

- OpenAI / ChatGPT
- Anthropic Claude
- Google Gemini
- Future providers

The dashboard should let platform admins configure:

- Enabled providers.
- Credentials.
- Default model.
- Fallback model.
- Per-workflow model selection.
- Cost ceilings.
- Timeout budgets.
- Enable/disable state.

The selected model affects investigation, explanation, and draft proposal quality only. It must never change payment execution semantics.

## Engineering Principles

- Start with simple workflow orchestration before autonomous agents.
- Use autonomous behavior only for bounded investigation steps with iteration/time limits.
- Use narrow, explicit tools for telemetry reads, incident summaries, policy drafting, and validation.
- Keep all write-capable tools behind deterministic validators and human approval.
- Run evaluations for incident classification, recommendation quality, policy validation, and explanation clarity before changing defaults.
- Log model, prompt/template version, input evidence references, output proposal, validator result, approver, and final applied config version.
- Enforce tenant and role permissions through existing MasonXPay access control; AI may only see data the requesting user or service role is allowed to inspect.
- Enforce data minimization before every model call; redaction is a code-level policy, not a prompt instruction.

## Target Flow

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

## Recommended Architecture

```text
IncidentDetector
  -> EvidenceCollector
  -> AiInvestigationWorkflow
       -> AiModelProvider adapter
       -> read-only tools only
  -> RoutingPolicyProposal
  -> PolicyValidator
  -> HumanApproval
  -> RoutingConfigVersion
  -> DeterministicRoutingEngine
  -> RollbackMonitor
```

## Example Incident

```text
Incident:
  Stripe SG VISA success rate dropped from 97.8% to 82.3%

Agent recommendation:
  reduce Stripe SG VISA traffic from 80% to 20%
  increase alternate SG VISA provider traffic from 20% to 80%
  keep Mastercard unchanged
  rollback automatically if alternate provider error rate > 5%
```

Current implementation can use existing providers for local simulation. Adyen-style examples are valid as future connector scenarios, not an assumption that Adyen is already implemented.

## Components

### AI0: Safety and Authority Model

Define hard boundaries:

- AI is advisory.
- Validator and human approval are mandatory.
- Deterministic routing engine is the only runtime decision maker.
- Sensitive data must be blocked before any model call.

### AI1: Traffic Simulator and Incident Seeds

Generate synthetic payment traffic and incident scenarios:

- Provider degradation.
- Latency spikes.
- Retry storms.
- Regional/card-brand incidents.
- Provider failover behavior.

### AI2: Telemetry-to-Incident Detector

Convert metrics into structured incident candidates:

- Success rate.
- Latency.
- Retry rate.
- Failover rate.
- Provider.
- Country.
- Currency.
- Card brand.

### AI3: Model-Agnostic Investigation Workflow

Build provider adapters and workflow orchestration:

- OpenAI/ChatGPT adapter.
- Claude adapter.
- Gemini adapter.
- Configurable default and fallback models.
- External redacted, external restricted, internal-only, and disabled deployment modes.
- Evidence summaries.
- Baseline vs current telemetry comparison.
- Routing-policy draft generation without applying changes.

### AI3b: Evidence Redaction and Data Policy

Add the security layer before model calls:

- Typed evidence DTOs.
- Field-level allowlists.
- Redaction tests.
- Prompt-injection handling for untrusted log/provider text.
- Audit-safe request/response persistence.
- External-model kill switch.

### AI4: Policy Change Model and Validator

Define a typed proposal format and validation rules:

- Tenant scope.
- Provider availability.
- Traffic caps.
- Rollback criteria.
- Blast radius.
- Forbidden changes.
- Card-brand/country targeting.

### AI5: Human Approval and Model Settings UI

Add dashboard workflows:

- Proposal diff.
- Explanation.
- Simulated impact.
- Rollback plan.
- Approval/rejection audit trail.
- Versioned routing config updates.
- Platform-admin controls for default model/provider configuration.

### AI6: Deterministic Execution and Rollback

Apply approved policy changes through existing routing rules and monitor rollback conditions with deterministic workers, not AI.

### AI7: Agent Harness, Evals, and Auditability

Add production-grade agent engineering support:

- Prompt/template versioning.
- Golden incident datasets.
- Offline evals.
- Model comparison reports.
- Trace links.
- Tool-call audit logs.
- Rollout gates for changing default models.

## Tooling Constraints

- Keep tools intentionally narrow: read metrics, read incidents, read current routing policy, draft proposal, validate proposal.
- Do not expose direct payment, refund, or routing mutation tools to the model.
- Do not expose tools that can retrieve raw provider payloads, credentials, card data, secrets, or unrestricted customer records.
- Version prompts/templates, tool schemas, policy proposal schemas, validator rules, and model selections.
- Store an audit trail for model provider, model name, prompt version, evidence IDs, generated proposal, validation result, approver, applied routing config version, and rollback outcome.
- Build eval datasets from synthetic and real incidents before changing the default model.
- Compare accuracy, hallucination rate, unsafe proposal rate, cost, latency, and explanation quality across providers.
- Use existing RBAC and tenant isolation. The AI layer is an interface over allowed data, not a privileged bypass.
