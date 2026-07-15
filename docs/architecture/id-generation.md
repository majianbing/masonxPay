# ID Generation

MasonXPay uses prefixed public/business IDs for merchant-facing resources and service-minted domain records:

```text
{prefix}_{snowflakeId}
```

Examples include `pi_...` for payment intents, `rf_...` for refunds, `evt_...` for events, `ac_...` for virtual accounts, and `rp_...` for rail payments. Canonical prefixes live in `MasonXIdPrefix`; new service code should use that registry or a typed wrapper such as `GatewayIdService`, not raw string literals.

## Current Decision

Keep the in-process `SnowflakeIdGenerator` for the current architecture capacity target, but do not adopt Meituan Leaf Snowflake as a direct replacement.

The current generator is a standard Snowflake-style layout:

- 41 bits: milliseconds since `2020-01-01T00:00:00Z`
- 10 bits: node ID
- 12 bits: per-millisecond sequence

At the current design target, around 1000 payment operations per second in soak/capacity testing, the raw sequence capacity is not the limiting factor. A single node has 4096 IDs per millisecond available in the current layout. The observed soak-test risk is therefore a wall-clock behavior problem, not an ID throughput problem.

## Clock Rollback Finding

The current implementation tolerates backward wall-clock movement by pinning to the last emitted timestamp for rollback within the configured tolerance. If generation continues under a pinned timestamp and the per-millisecond sequence is exhausted, the generator waits until the system clock advances beyond the pinned timestamp.

This preserves monotonicity and uniqueness inside one generator instance, but it means ID generation can block or fail during host clock corrections. This failure class is inherent to Snowflake-style generators that depend on wall-clock time.

## Leaf Snowflake Assessment

Meituan [Leaf](https://github.com/Meituan-Dianping/Leaf) provides both segment mode and Snowflake mode. Leaf Snowflake improves worker/node ID assignment through ZooKeeper-backed coordination, but it is still Twitter Snowflake-derived and remains dependent on wall-clock time.

Leaf Snowflake would help with:

- allocating worker IDs across service instances,
- reducing accidental duplicate node IDs,
- centralizing ID generation as an operational service.

Leaf Snowflake would not remove:

- wall-clock rollback sensitivity,
- wait/error behavior during clock skew,
- the per-node sequence ceiling,
- the operational cost of running a ZooKeeper-backed ID service.

For MasonXPay, adopting Leaf Snowflake would add infrastructure while preserving the clock failure mode seen in soak testing. It is not the preferred fix for the current problem.

## Industry Pattern Comparison

These systems are useful reference points, but they solve different parts of the problem. The important distinction for MasonXPay is whether the design removes wall-clock dependency from correctness, or only improves worker assignment/throughput around a Snowflake-like core.

| Pattern | Representative system | Basic approach | Clock rollback risk | Operational tradeoff | Fit for MasonXPay |
|---|---|---|---|---|---|
| In-process Snowflake | Current `SnowflakeIdGenerator` | Timestamp + node ID + sequence in each service instance | Yes | Simple and fast, but requires unique node IDs and healthy clocks | Keep for current capacity target with stronger operational guardrails |
| Coordinated Snowflake | Meituan Leaf Snowflake | Snowflake generation with ZooKeeper-backed worker assignment | Yes | Solves node assignment better, but adds ZooKeeper/Leaf service operations | Not a direct fix for the soak-test clock issue |
| Segment allocator | Meituan Leaf segment mode | DB allocates ID ranges by business tag; service consumes ranges from memory | No for ID generation correctness | Requires allocator table/service and range management; IDs expose allocation order | Strong candidate if clock independence becomes required |
| Cached Snowflake variant | Baidu [UidGenerator](https://github.com/baidu/uid-generator) | Snowflake-derived ID with configurable bits, DB worker assignment, and RingBuffer caching that can consume future time | Reduced throughput pressure, but still time-based | High single-instance throughput; more moving parts and lifecycle/bit-allocation planning | Useful reference, but not enough if the requirement is removing clock dependency |
| Alternative Snowflake variant | [Sonyflake](https://github.com/sony/sonyflake) | Snowflake-inspired layout with longer lifetime and more machine bits, lower per-instance burst rate by default | Yes | Good machine-ID flexibility; still needs machine-ID uniqueness and clock discipline | Not a direct fix for clock rollback |
| Synchronized-clock database | Google [Spanner/TrueTime](https://research.google/pubs/spanner-googles-globally-distributed-database-2/) | Database-level external consistency using clock uncertainty bounds | Managed by database/time infrastructure, not an app ID utility | Requires specialized distributed database/time infrastructure | Architectural reference only, not appropriate as an ID-generator replacement |
| Random/time-sortable IDs | UUIDv7 / ULID | Mostly decentralized string IDs with timestamp component and random bits | Does not rely on monotonic local clock for uniqueness in the same way | Larger IDs; different public-ID shape; ordering is approximate | Viable future option if compact numeric IDs stop mattering |

Baidu UidGenerator and Sonyflake are valuable references because they show different bit allocation and throughput strategies. They remain Snowflake-family designs. They may reduce bottlenecks or improve deployment fit, but they do not make ID correctness independent of wall-clock behavior.

Google Spanner is a different category. It is a distributed database design whose TrueTime API exposes clock uncertainty to support externally consistent transactions. It is not a practical drop-in replacement for service-local public ID generation in this architecture.

## Operational Requirements

Snowflake uniqueness depends on node ID discipline. Every running service instance that can mint IDs in the same namespace must have a unique node ID.

Current local defaults are:

- virtual-account service: `VA_NODE_ID=0`
- rail service: `RAIL_NODE_ID=1`
- gateway service: `GATEWAY_NODE_ID=2`

These defaults are acceptable for local single-instance development only. Multi-replica deployments must assign unique node IDs per replica. Running two gateway replicas with the same `GATEWAY_NODE_ID` can produce duplicate IDs if they generate the same sequence in the same millisecond.

Production deployments must also expose operational signals for:

- backward clock drift events,
- drift amount,
- sequence exhaustion,
- spin/wait duration,
- ID generation exceptions.

## Medium-Term Direction

If ID generation must become robust against clock rollback as a correctness requirement, prefer a segment or hi-lo allocator over Leaf Snowflake.

A segment/hi-lo allocator reserves ranges from an authoritative store, then serves IDs from memory. In this architecture, Postgres is already the source of truth for financial state, so a Postgres-backed allocator fits the system better than adding ZooKeeper only for Snowflake worker assignment.

Tradeoffs:

- Snowflake: compact, roughly time-sortable, decentralized after node assignment, but clock-dependent.
- Leaf Snowflake: adds worker coordination, but remains clock-dependent and adds ZooKeeper/Leaf service operations.
- Segment/hi-lo: removes clock rollback from ID correctness and easily supports the current target throughput, but requires range allocation tables and exposes allocation order more directly.
- UUIDv7/ULID: decentralized and time-sortable with low collision probability, but larger string IDs and different public-ID shape.

The next meaningful migration, if required, should be to a segment/hi-lo allocator for business/public IDs rather than to another Snowflake variant.
