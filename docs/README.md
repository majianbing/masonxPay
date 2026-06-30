# MasonXPay Docs

This directory separates durable architecture, engineering guidance, active planning, and historical reference material. Root `AGENTS.md` and `CLAUDE.md` stay concise and point here for detail.

## Start Here

- [Architecture overview](architecture/overview.md): system map, ownership boundaries, and core invariants.
- [Security boundaries](architecture/security-boundaries.md): tenant/mode isolation, PCI boundary, AI data policy, and secret handling.
- [Payment core](architecture/payment-core.md): financial state ownership, idempotency, provider calls, and outbox rules.
- [Development guide](engineering/development-guide.md): engineering docs index — connector, SDK, auth, testing, database, and anti-pattern guides.
- [Connector development](engineering/connector-development.md): provider onboarding across backend, dashboard, SDK, and docs.
- [Testing strategy](engineering/testing-strategy.md): expected coverage layers and test placement.
- [Roadmap](planning/roadmap.md): product phases, current status, and future tracks.

## Folders

- `architecture/`: durable system design and invariants. These docs should change only when the architecture changes.
- `engineering/`: how to build, test, and extend the system.
- `planning/`: roadmap and phase trackers. These docs can be noisy and status-oriented.
- `archive/`: historical source material that should not be treated as current guidance.

## Planning Trackers

- [High-throughput payment core plan](planning/high-throughput-payment-core-plan.md)
- [Payment orchestration, routing, retry, and instrument plan](planning/payment-orchestration-routing-retry-plan.md)
- [Subscription and recurring billing plan](planning/subscription-recurring-billing-plan.md)
- [Multi-rail ISO 8583 / ISO 20022 plan](planning/multi-rail-iso8583-iso20022-plan.md)
- [AI-assisted operations control plane plan](planning/ai-control-plane-plan.md)

## Root Docs

- `../README.md`: public setup and project overview.
- `../AGENTS.md`: concise coding-agent guide and hard rules.
- `../CLAUDE.md`: concise project skeleton for Claude-style assistants.
- `../SECURITY.md`: security policy and vulnerability reporting.
