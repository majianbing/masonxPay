# rail-simulator

Two-sided network simulator for MasonXPay's Phase MR rail infrastructure. Simulates both a card network (ISO 8583 TCP) and a bank payment rail (ISO 20022 HTTP), including edge cases that production rails produce but are impossible to test against real networks.

Plan doc: [`docs/planning/multi-rail-iso8583-iso20022-plan.md`](../../docs/planning/multi-rail-iso8583-iso20022-plan.md)

Ports: **9090** (HTTP / bank-rail-sim), **9091** (TCP / card-network-sim)

---

## card-network-sim (ISO 8583, port 9091)

Netty TCP server that accepts ISO 8583 messages from `rail-service` and returns responses according to the PAN suffix behavior table.

**BIN routing**

| BIN prefix | Issuer |
|---|---|
| 4xxx | Visa logic (rule-based) |
| 5xxx | Mastercard logic (rule-based) |
| 999999 | VA issuer — calls `virtual-account-service /internal/issuer/authorize` for real balance check |

**PAN suffix behavior**

| PAN suffix | Behavior |
|---|---|
| 0000 | Approve (DE39=00) |
| 0001 | Decline — insufficient funds (DE39=51) |
| 0002 | Decline — do not honor (DE39=05) |
| 0003 | No response (simulates TCP timeout → triggers UNKNOWN state in rail-service) |
| 0004 | Late approve — sends 0110 after rail-service timeout window has closed |
| 0005 | Duplicate 0110 response |
| 0006 | Decline — invalid card (DE39=14) |
| 0007 | Issuer unavailable (DE39=91) |

MTIs handled: 0800/0810, 0100/0110, 0200/0210, 0400/0410, 0420/0430.

---

## bank-rail-sim (ISO 20022, port 9090)

HTTP server that accepts pain.001 credit transfer initiations and drives the full async ISO 20022 message family in response.

**Creditor account suffix behavior**

| Account suffix | Behavior |
|---|---|
| 0000 | Accepted and settled (pain.002 ACCP → pacs.002 ACSC → camt.054) |
| 0001 | Rejected immediately (pain.002 RJCT — invalid account) |
| 0002 | Accepted then returned (pacs.002 ACSP → pacs.004 return) |
| 0003 | Pending indefinitely — no settlement notification |
| 0004 | Duplicate status report |
| 0005 | Settlement notification delayed |
| 0006 | Amount mismatch in camt.054 (reconciliation exception scenario) |

**Message flow (happy path)**

```
rail-service → pain.001
  bank-rail-sim → pain.002 ACCP
  bank-rail-sim simulates clearing (pacs.008)
  bank-rail-sim → pacs.002 ACSC
  bank-rail-sim → camt.054
rail-service status: SETTLED
```

---

## VA issuer integration

For BIN 999999, card-network-sim calls `virtual-account-service` at `POST /internal/issuer/authorize` instead of applying rule-based logic. This makes the VCC authorization decision driven by real ledger balance state — the card-network-sim itself has no knowledge of account balances.

---

## Engineering notes

- This simulator is a lab tool — it does not connect to Visa, Mastercard, SWIFT, SEPA, or FedNow production networks.
- PAN suffix 0003 (no response) is the primary mechanism for testing UNKNOWN state handling. The rail-service `IdleStateHandler` timeout must fire before rail-service transitions to UNKNOWN.
- PAN suffix 0004 (late response) tests `LateResponseHandler` — the 0110 must arrive after rail-service has already sent the 0400 reversal.
- The amount mismatch scenario (account suffix 0006) is designed to surface reconciliation exceptions in `GET /internal/rail/reconciliation/exceptions`.
