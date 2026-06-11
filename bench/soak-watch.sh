#!/usr/bin/env bash
# One drift snapshot of the capacity soak (cheap — uses pg_stat catalogs, no table scans).
# Watches the slow-degradation indicators a short sweep can't see:
#   table growth, dead tuples (autovacuum keeping up?), outbox backlog, projection size,
#   connections / idle-in-transaction, JVM heap + GC count, Hikari pool.
# Usage:  ./bench/soak-watch.sh            # one snapshot line
#         ./bench/soak-watch.sh loop 1200  # snapshot every 1200s until killed
set -u
P=masonxpay-cap

pg() { docker exec ${P}-postgres-capacity-1 psql -U pay_app_user -d paygateway_capacity -tAc "$1" 2>/dev/null | tr -d ' \n'; }
prom() { docker run --rm --network ${P}_default curlimages/curl:latest -s http://backend:8080/actuator/prometheus 2>/dev/null; }

snapshot() {
  local ts pi pr dead outbox rm conns idletx dbsize m heap gc hk_act hk_pend
  ts=$(date +%H:%M:%S)
  pi=$(pg "select coalesce(sum(n_live_tup),0) from pg_stat_user_tables where relname like 'payment_intents_%'")
  pr=$(pg "select coalesce(sum(n_live_tup),0) from pg_stat_user_tables where relname like 'payment_requests_%'")
  dead=$(pg "select coalesce(sum(n_dead_tup),0) from pg_stat_user_tables where relname ~ '^(payment_intents|payment_requests)_[0-9]'")
  outbox=$(pg "select count(*) from outbox_events where published=false")
  rm=$(pg "select coalesce(n_live_tup,0) from pg_stat_user_tables where relname='payment_read_models'")
  conns=$(pg "select count(*) from pg_stat_activity where datname='paygateway_capacity'")
  idletx=$(pg "select count(*) from pg_stat_activity where datname='paygateway_capacity' and state='idle in transaction'")
  dbsize=$(pg "select pg_size_pretty(pg_database_size('paygateway_capacity'))")
  m=$(prom)
  heap=$(echo "$m" | awk '/^jvm_memory_used_bytes\{/ && /area="heap"/ {s+=$NF} END{printf "%.0f", s/1048576}')
  gc=$(echo "$m"   | awk '/^jvm_gc_pause_seconds_count\{/ {s+=$NF} END{printf "%.0f", s}')
  hk_act=$(echo "$m"  | awk '/^hikaricp_connections_active\{/ {print $NF; exit}')
  hk_pend=$(echo "$m" | awk '/^hikaricp_connections_pending\{/ {print $NF; exit}')
  printf '%s | pi=%s pr=%s readmodels=%s dead=%s outbox_unpub=%s | conns=%s idle_in_tx=%s db=%s | node1: heapMB=%s gc_count=%s hikari_active=%s pending=%s\n' \
    "$ts" "$pi" "$pr" "$rm" "$dead" "$outbox" "$conns" "$idletx" "$dbsize" "$heap" "$gc" "$hk_act" "$hk_pend"
}

if [ "${1:-}" = "loop" ]; then
  interval="${2:-1200}"
  while true; do snapshot; sleep "$interval"; done
else
  snapshot
fi
