package com.masonx.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    @Test
    void rejects_invalid_node_id() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_boundary_node_ids() {
        new SnowflakeIdGenerator(0);
        new SnowflakeIdGenerator(1023);
    }

    @Test
    void ids_are_positive() {
        var gen = new SnowflakeIdGenerator(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(gen.next()).isPositive();
        }
    }

    @Test
    void ids_are_monotonically_increasing() {
        var gen = new SnowflakeIdGenerator(1);
        long prev = gen.next();
        for (int i = 0; i < 10_000; i++) {
            long next = gen.next();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void ids_are_unique_single_threaded() {
        var gen = new SnowflakeIdGenerator(1);
        int count = 100_000;
        Set<Long> seen = new ConcurrentSkipListSet<>();
        for (int i = 0; i < count; i++) {
            seen.add(gen.next());
        }
        assertThat(seen).hasSize(count);
    }

    @Test
    void ids_are_unique_across_threads() throws Exception {
        var gen = new SnowflakeIdGenerator(1);
        int threads = 8;
        int perThread = 10_000;
        Set<Long> seen = new ConcurrentSkipListSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    seen.add(gen.next());
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(seen).hasSize(threads * perThread);
    }

    @Test
    void tolerates_small_clock_drift_without_throwing() {
        // Simulate a 1 ms backward tick (common NTP adjustment on Docker/VMs).
        // next() must spin-wait and return a valid ID rather than throwing.
        var gen = new SnowflakeIdGenerator(1) {
            private final long[] ticks = { 1_000L, 999L, 1_001L };
            private int call = 0;
            @Override long currentMs() { return ticks[Math.min(call++, ticks.length - 1)]; }
        };
        long id1 = gen.next();  // primes lastTimestamp = 1000
        long id2 = gen.next();  // clock returns 999 → drift=1 → spin → returns 1001
        assertThat(id2).isGreaterThan(id1);
    }

    @Test
    void throws_on_large_clock_drift() {
        var gen = new SnowflakeIdGenerator(1) {
            private final long[] ticks = { 1_000L, 994L };
            private int call = 0;
            @Override long currentMs() { return ticks[Math.min(call++, ticks.length - 1)]; }
        };
        gen.next();  // primes lastTimestamp = 1000
        assertThatThrownBy(gen::next)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Clock moved backwards by 6 ms");
    }

    @Test
    void generate_returns_prefixed_string() {
        var gen = new SnowflakeIdGenerator(0);
        String id = gen.generate("ac_");
        assertThat(id).startsWith("ac_");
        assertThat(id.substring(3)).matches("\\d+");
    }

    @Test
    void different_prefixes_produce_different_id_types() {
        var gen = new SnowflakeIdGenerator(0);
        assertThat(gen.generate("ac_")).startsWith("ac_");
        assertThat(gen.generate("le_")).startsWith("le_");
        assertThat(gen.generate("tx_")).startsWith("tx_");
    }
}
