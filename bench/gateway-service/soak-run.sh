#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Soak driver — RUN ON THE M2 (load generator).
# Drives a multi-hour constant-rate soak against the M1 capacity stack's nginx ALB.
# Pair it with `bench/soak-watch.sh` running ON THE M1 (server-side drift snapshots).
#
#   git pull                                   # get the latest capacity.js on the M2
#   BASE_URL=http://192.168.50.31:8088 ./bench/soak-run.sh
#
# Env (all optional):
#   BASE_URL        M1 nginx ALB              (default http://192.168.50.31:8088)
#   RUN_MODE        infra | postgres_only      (default infra)
#   RATE            charges/sec sustained      (default 100 — the avg target)
#   DURATION        soak length                (default 3h)
#   MERCHANT_COUNT  synthetic merchants        (default 20)
#   PROM_RW         Prometheus remote-write URL to stream live metrics to Grafana,
#                   e.g. http://192.168.50.31:9090/api/v1/write   (default: off)
#
# NOTE: over WiFi the k6 *latency* numbers carry network noise; the meaningful
# soak signals (table bloat, outbox backlog, heap creep, pool leaks) are captured
# server-side by bench/soak-watch.sh on the M1, which WiFi does not touch.
# ─────────────────────────────────────────────────────────────────────────────
set -u

BASE_URL="${BASE_URL:-http://192.168.50.31:8088}"
RUN_MODE="${RUN_MODE:-infra}"
RATE="${RATE:-100}"
DURATION="${DURATION:-3h}"
MERCHANT_COUNT="${MERCHANT_COUNT:-20}"
PROM_RW="${PROM_RW:-}"
SCRIPT="${SCRIPT:-bench/k6/capacity.js}"

command -v k6 >/dev/null 2>&1 || { echo "ERROR: k6 not on PATH (brew install k6)"; exit 1; }
[ -f "$SCRIPT" ] || { echo "ERROR: $SCRIPT not found — run from the repo root"; exit 1; }

# Fail fast if the M1 ALB is unreachable (WiFi / firewall / wrong IP / Local Network perm)
if ! curl -fsS -m 5 "$BASE_URL/alb-health" >/dev/null 2>&1; then
  echo "ERROR: cannot reach $BASE_URL/alb-health"
  echo "  check: M1 stack up, IP correct, wired/LAN path, macOS Local Network permission granted to the terminal"
  exit 1
fi

export BASE_URL RUN_MODE MERCHANT_COUNT
export SCENARIO=soak
export TARGET_RATE="$RATE"
export DURATION

echo "Soak → $BASE_URL   mode=$RUN_MODE   rate=$RATE/s   duration=$DURATION   merchants=$MERCHANT_COUNT"
echo "Reminder: run  bash bench/soak-watch.sh loop 1200 | tee bench/results/soak-drift.log  ON THE M1."
echo

if [ -n "$PROM_RW" ]; then
  export K6_PROMETHEUS_RW_SERVER_URL="$PROM_RW"
  export K6_PROMETHEUS_RW_TREND_STATS="p(50),p(95),p(99),p(99.9),avg,max"
  echo "streaming metrics → $PROM_RW"
  exec k6 run -o experimental-prometheus-rw "$SCRIPT"
else
  exec k6 run "$SCRIPT"
fi
