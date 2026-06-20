package com.masonx.common.id;

/**
 * Thread-safe Snowflake ID generator.
 *
 * Layout (63 usable bits of a signed long):
 *   [41 bits] milliseconds since EPOCH (2020-01-01 UTC) — valid until ~2089
 *   [10 bits] node ID (0–1023)
 *   [12 bits] per-millisecond sequence (0–4095)
 *
 * Instantiate once per node and inject as a singleton. Node ID must be unique
 * across all running instances to guarantee global uniqueness.
 *
 * Usage:
 *   generator.next()               → raw long
 *   generator.generate("ac_")      → "ac_8234910293847"
 */
public class SnowflakeIdGenerator {

    /** 2020-01-01 00:00:00 UTC */
    private static final long EPOCH = 1_577_836_800_000L;

    private static final int  NODE_BITS      = 10;
    private static final int  SEQUENCE_BITS  = 12;

    private static final long MAX_NODE       = (1L << NODE_BITS) - 1;      // 1023
    private static final long MAX_SEQUENCE   = (1L << SEQUENCE_BITS) - 1;  // 4095

    private static final int  NODE_SHIFT      = SEQUENCE_BITS;              // 12
    private static final int  TIMESTAMP_SHIFT = NODE_BITS + SEQUENCE_BITS;  // 22

    /**
     * Under load, NTP corrections on Docker/VMs can move the clock backwards by tens of ms.
     * For any backward tick within this threshold we pin now=lastTimestamp and let the
     * per-ms sequence absorb the drift — no spin, no exception, IDs stay monotonic.
     * Beyond this threshold the clock has been reset intentionally; throw to surface it.
     */
    private static final long MAX_BACKWARD_MS = 2_000L;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence      = 0L;

    public SnowflakeIdGenerator(int nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE) {
            throw new IllegalArgumentException(
                    "nodeId must be 0–" + MAX_NODE + ", got: " + nodeId);
        }
        this.nodeId = nodeId;
    }

    /** Returns the next unique ID as a raw long. */
    public synchronized long next() {
        long now = currentMs();

        if (now < lastTimestamp) {
            long drift = lastTimestamp - now;
            if (drift > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "Clock moved backwards by " + drift + " ms (max tolerated: " + MAX_BACKWARD_MS + " ms)");
            }
            // NTP correction — pin to lastTimestamp; sequence handles ordering within the ms.
            now = lastTimestamp;
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted for this millisecond — spin until the next one.
                while (now <= lastTimestamp) {
                    now = currentMs();
                }
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = now;
        return (now << TIMESTAMP_SHIFT) | (nodeId << NODE_SHIFT) | sequence;
    }

    /** Returns a prefixed string ID, e.g. {@code generate("ac_")} → {@code "ac_8234910293847"}. */
    public String generate(String prefix) {
        return prefix + next();
    }

    long currentMs() {
        return System.currentTimeMillis() - EPOCH;
    }
}
