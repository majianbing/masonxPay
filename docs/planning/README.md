# Planning

Planning docs are allowed to include status, checklists, deferred work, and implementation notes. Durable rules should be promoted into `../architecture/` or `../engineering/` once they become long-term guidance.

## Active Now

Updated 2026-10-23. This is the only place that summarizes phase status; each plan doc is authoritative for its own detail.

| Track | Status | Open items |
|---|---|---|
| Phases 0-4, S, H, O, MR, LC | Complete | O6 portable card deferred until cross-PSP portability is a real requirement. S hardening list in the subscription plan. |
| PF (Paystack, Flutterwave) | Delivered | Paystack manual sandbox verification blocked on account activation; live-mode gates pending. |
| PPC (prepaid card platform) | PPC1-PPC6 complete; PPC7, PPC8 partial | PPC0 naming cleanup; PPC7 EXTERNAL system-of-record reconciliation and real-issuer reconciliation processor; PPC8 merchant-safe exception actions. |
| FE (reusable fee engine, PPC9) | FE0-FE5, FE7 complete; FE6 partial | Gateway capture/refund/dispute/settlement fee contexts, fee management tooling, and the decision on when gateway fees move money. |
| RAG (docs assistant) | RAG0-RAG3, RAG6 complete; RAG4, RAG5, RAG7 in progress | RAG4 feedback persistence; RAG5 needs real embeddings and a distractor-heavy golden set; RAG7 vector DB auth/TLS, alerting, runbooks; RAG8 semantic retrieval quality planned. |
| AI (operations agent) | AI0 complete; AI1-AI7 planned | Next slice: AI2 incident detector over connector health plus AI3b typed evidence and redaction tests, runnable in `disabled` mode. |
| Phase 15 (platform maturity) | Deferred | Rate limiting, platform admin UI, API versioning, mobile SDKs, provider settlement reconciliation. |

Good single-evening slices: PPC0 naming cleanup, RAG4 feedback controls, a gateway `CAPTURE`/`REFUND` fee context, or the AI2+AI3b detector in disabled mode.

- [Roadmap](roadmap.md)
- [High-throughput payment core plan](high-throughput-payment-core-plan.md)
- [Payment orchestration, routing, retry, and instrument plan](payment-orchestration-routing-retry-plan.md)
- [Subscription and recurring billing plan](subscription-recurring-billing-plan.md)
- [Multi-rail ISO 8583 / ISO 20022 plan](multi-rail-iso8583-iso20022-plan.md)
- [Prepaid card program platform plan](prepaid-card-program-platform-plan.md)
- [Paystack and Flutterwave provider plan](paystack-flutterwave-provider-plan.md)
- [RAG support assistant plan](rag-assistant-plan.md)
- [Payment operations agent plan](payment-operations-agent-plan.md)
