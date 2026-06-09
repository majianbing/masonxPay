# MasonXPay Development Guide

This section explains how to build, test, and extend MasonXPay. Durable system rules live in `../architecture/`; implementation workflow and module-specific guidance lives here.

## Start Here

- [Local development](local-development.md): Docker/manual startup and common commands.
- [Backend guide](backend-guide.md): Java/Spring conventions, service boundaries, RBAC, and transaction rules.
- [Database and migrations](database-migrations.md): Flyway, tenant/mode scope, ShardingSphere single-table registration, and schema rules.
- [Connector development](connector-development.md): backend, dashboard, SDK, webhook, and README steps for adding a payment provider.
- [SDK guide](sdk-guide.md): browser checkout architecture and hosted checkout rules.
- [Auth and security implementation](auth-security.md): MFA, webhook verification, and log redaction implementation details.
- [Testing strategy](testing-strategy.md): expected coverage layers and test placement.
- [Engineering anti-patterns](anti-patterns.md): common suggestions and shortcuts to avoid.

## Repository Layout

```text
backend/                          Spring Boot API, payment core, providers, workers, migrations
dashboard/                        Next.js merchant/admin UI and hosted checkout pages
sdk/browser/                      Browser checkout SDK and provider SDK integration
sdk/server/                       TypeScript server SDK
monitor/                          Prometheus, Grafana, Kafka JMX, alerting
bench/                            k6 benchmark scenarios and results
cloud-deploy/                     Deployment references
docker-compose.yml                Local quickstart stack
```

## Core References

- [Architecture overview](../architecture/overview.md)
- [Security boundaries](../architecture/security-boundaries.md)
- [Payment core](../architecture/payment-core.md)
- [Sharding, Kafka, and Redis](../architecture/sharding-kafka-redis.md)
- [Routing and orchestration](../architecture/routing-orchestration.md)
