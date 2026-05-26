# MasonXPay Docs

This directory keeps the larger planning, architecture, and historical reference documents out of the repository root. Root `AGENTS.md` and `CLAUDE.md` are intentionally short skeletons that point here for detail.

## Files

- `ROADMAP.md`: product roadmap, high-throughput phases, and AI-assisted operations control-plane milestones.
- `HIGH_THROUGHPUT_PAYMENT_CORE_PLAN.md`: detailed H-track design for sharding, Kafka, Redis, projections, and preview runtime.
- `PAYMENT_ORCHESTRATION_ROUTING_RETRY_PLAN.md`: Yuno-like routing, retry, fallback, payment-instrument, future tokenization architecture, and the Phase O implementation tracker.
- `SUBSCRIPTION_RECURRING_BILLING_PLAN.md`: standalone subscription, invoice, off-session charging, and recurring retry/dunning phase plan.
- `AI_CONTROL_PLANE_PLAN.md`: AI-assisted payment operations control-plane architecture, model-provider strategy, safety boundaries, workflow, and eval plan.
- `DEVELOPMENT_GUIDE.md`: detailed engineering guide migrated from the old root `CLAUDE.md`, including connector, SDK, MFA, and implementation rules.
- `payment-gateway-full-prompt.md`: historical full prompt/reference material.

## Root Docs

- `../README.md`: public setup and project overview.
- `../AGENTS.md`: concise coding-agent guide and hard rules.
- `../CLAUDE.md`: concise project skeleton for Claude-style assistants.
- `../SECURITY.md`: security policy and vulnerability reporting.
