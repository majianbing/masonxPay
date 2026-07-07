# rail-service

ISO 8583 card rail and ISO 20022 bank rail client for MasonXPay. Implements the acquirer/processor side of both card and bank payment rails, connected to a two-sided `rail-simulator` and the existing VA double-entry ledger via Kafka.

Own database: `msx_rail` (Postgres, separate from the gateway and VA databases).  
Plan doc: [`docs/planning/multi-rail-iso8583-iso20022-plan.md`](../../docs/planning/multi-rail-iso8583-iso20022-plan.md)

Port: **8081**

---

## Capabilities

### ISO 8583 card rail

- Netty bootstrap with `NioEventLoopGroup`, `Iso8583FrameDecoder/Encoder`, and `IdleStateHandler`
- jPOS `ISOMsg` / `GenericPackager` for codec (bitmap, LLVAR, LLLVAR field types)
- `VisaSimIso8583Adapter` and `MastercardSimIso8583Adapter` — BIN-based routing (4xxx → Visa, 5xxx → MC, 999999 → VA issuer)
- MTIs: 0800/0810 (sign-on), 0100/0110 (auth), 0200/0210 (sale), 0400/0410 (reversal), 0420/0430 (reversal advice)
- **UNKNOWN state and reversal discipline**: timeout drives `rail_payment.status = UNKNOWN`, not FAILED; `ReversalTaskService` sends 0400 immediately; `LateResponseHandler` detects 0110 arriving after reversal
- Composite correlation key: `{network}:{acquirer_id}:{stan}:{rrn}:{transmission_date}` — STAN and RRN alone are not globally unique
- DE2 (PAN) masked before any log write

### ISO 20022 bank rail

- JAXB POJOs for pain.001, pain.002, pacs.008, pacs.002, pacs.004, camt.054
- `SepaSimAdapter` and `FedNowSimAdapter` — HTTP + XML transport to `rail-simulator`
- Async status flow: pain.001 → pain.002 ACCP → pacs.002 ACSC → camt.054 → SETTLED
- Correlation ID chain: MessageId, EndToEndId, InstructionId, TransactionId stored in `rail_network_correlation`
- Bank return (pacs.004) is a new credit transfer, not a reversal — recorded as a separate `rail_payment` entry

### Settlement and reconciliation

- Publishes `RailSettlementEvent` to Kafka on card sale settlement and bank transfer settlement
- `GET /internal/rail/reconciliation/exceptions?merchantId=` — surfaces late-response, exhausted-reversal, and stuck-UNKNOWN exceptions (requires `X-Internal-Token`)

---

## API

| Method | Path | Description |
|---|---|---|
| POST | `/v1/rail/authorize` | Card authorization — ISO 8583 path |
| POST | `/v1/rail/bank-transfers` | Bank credit transfer — ISO 20022 path |
| GET | `/internal/rail/reconciliation/exceptions` | Reconciliation exceptions for a merchant |

---

## Database tables

| Table | Purpose |
|---|---|
| `rail_payment` | Canonical payment record; `mode` column enforces TEST/LIVE isolation |
| `rail_routing_decision` | Persisted before adapter executes — always auditable |
| `rail_iso8583_log` | Masked ISO 8583 message log (DE2 never raw) |
| `rail_iso20022_log` | ISO 20022 message metadata (raw XML never stored) |
| `rail_network_correlation` | Composite correlation key linking internal ID to network IDs |
| `rail_reversal_task` | Pending reversals for UNKNOWN state card transactions |
| `rail_bank_return_task` | pacs.004 return tracking |

---

## Simulator PAN behavior (ISO 8583)

| PAN suffix | Behavior |
|---|---|
| 0000 | Approve |
| 0001 | Decline — insufficient funds (DE39=51) |
| 0002 | Decline — do not honor (DE39=05) |
| 0003 | Timeout — no response → UNKNOWN state + reversal |
| 0004 | Late approve — arrives after timeout window |
| 0005 | Duplicate response |
| 0006 | Decline — invalid card (DE39=14) |

---

## Key rules

- Timeout is UNKNOWN, not FAILED. Never transition directly to FAILED on a TCP read timeout.
- Card reversal is a new financial message (0400). It is not a database rollback.
- Bank return (pacs.004) is not card reversal. It is a new credit transfer in the opposite direction.
- STAN and RRN are not globally unique. Always use the composite correlation key.
- Never log raw DE2 (PAN), full IBAN, or account numbers.
- Ledger journals must be idempotent. Use `railPaymentId + eventType` as the idempotency key.

## TODO
- Configurable authorization engine, per-card.