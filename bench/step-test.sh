#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Step capacity test — find the knee.
#
# Runs fixed-rate open-model soaks at increasing arrival rates and reports the
# steady-state confirm p99 + system-error rate at each, so the KNEE is the highest
# rate that still holds the gate:
#     confirm p99 ≤ 500ms  AND  system errors ≤ 0.1%  AND  zero dropped iterations
#
# Run from the REPO ROOT on the M2 (where k6 is installed natively):
#     BASE_URL=http://192.168.50.31:8088 ./bench/step-test.sh
#
# Env (all optional):
#   BASE_URL        nginx ALB on the M1            (default http://192.168.50.31:8088)
#   RUN_MODE        label: postgres_only | infra   (default postgres_only)
#   RATES           charges/sec to test, in order  (default "50 100 150 200 300 400")
#   DURATION        steady-state per step          (default 3m)
#   MERCHANT_COUNT  synthetic merchants            (default 20)
#   SKIP_WARMUP     set to 1 to skip JVM warm-up    (default warm up once at 30/s, 1m)
#   STOP_ON_FAIL    set to 0 to test every rate     (default 1 = stop after first FAIL)
#   SCRIPT          k6 script                       (default bench/k6/capacity.js)
# ─────────────────────────────────────────────────────────────────────────────
set -u

BASE_URL="${BASE_URL:-http://192.168.50.31:8088}"
RUN_MODE="${RUN_MODE:-postgres_only}"
RATES="${RATES:-50 100 150 200 300 400}"
DURATION="${DURATION:-3m}"
MERCHANT_COUNT="${MERCHANT_COUNT:-20}"
SKIP_WARMUP="${SKIP_WARMUP:-0}"
STOP_ON_FAIL="${STOP_ON_FAIL:-1}"
SCRIPT="${SCRIPT:-bench/k6/capacity.js}"

command -v k6 >/dev/null 2>&1 || { echo "ERROR: k6 not on PATH (brew install k6)"; exit 1; }
[ -f "$SCRIPT" ] || { echo "ERROR: $SCRIPT not found — run from the repo root"; exit 1; }

# quick reachability check so we fail fast instead of 20 merchants of timeouts
if ! curl -fsS -m 5 "$BASE_URL/alb-health" >/dev/null 2>&1; then
  echo "ERROR: cannot reach $BASE_URL/alb-health — check M1 stack, IP, and the wired/LAN path"
  exit 1
fi

ts=$(date +%Y%m%d-%H%M%S)
outdir="bench/results/step-$ts"
mkdir -p "$outdir"
summary="$outdir/summary.tsv"

echo "Step test → $BASE_URL  mode=$RUN_MODE  rates=[$RATES]  ${DURATION} each"
echo "logs: $outdir"
echo

# ── optional warm-up (discarded) ─────────────────────────────────────────────
if [ "$SKIP_WARMUP" != "1" ]; then
  echo "warm-up 30/s for 1m (discarded) ..."
  BASE_URL="$BASE_URL" RUN_MODE="$RUN_MODE" SCENARIO=warmup MERCHANT_COUNT="$MERCHANT_COUNT" \
    k6 run "$SCRIPT" >"$outdir/warmup.log" 2>&1
  echo
fi

printf '%-7s %-12s %-10s %-9s %-12s %s\n' rate confirm_p99 sys_err dropped achieved/s gate | tee "$summary"
printf '%-7s %-12s %-10s %-9s %-12s %s\n' ------ ----------- ------- ------- ----------- ---- | tee -a "$summary"

knee=""
for R in $RATES; do
  log="$outdir/rate-$R.log"
  BASE_URL="$BASE_URL" RUN_MODE="$RUN_MODE" SCENARIO=soak \
    TARGET_RATE="$R" DURATION="$DURATION" MERCHANT_COUNT="$MERCHANT_COUNT" \
    k6 run "$SCRIPT" >"$log" 2>&1
  code=$?

  # confirm p99 from the CUSTOM trend line (summaryTrendStats includes p(99))
  p99=$(grep 'cap_confirm_ms' "$log" | grep -oE 'p\(99\)=[0-9.]+(ms|s|µs)' | head -1 | cut -d= -f2)
  # system-error rate from the CUSTOM rate line (the one with "out of")
  err=$(grep 'cap_system_errors' "$log" | grep 'out of' | grep -oE '[0-9.]+%' | head -1)
  dropped=$(grep 'dropped_iterations' "$log" | grep -oE '[0-9]+' | head -1)
  ach=$(grep -E 'iterations\.+:' "$log" | grep -oE '[0-9.]+/s' | head -1)

  dropped="${dropped:-0}"
  if [ "$code" -eq 0 ] && [ "$dropped" = "0" ]; then gate="PASS"; else gate="FAIL"; fi

  printf '%-7s %-12s %-10s %-9s %-12s %s\n' \
    "$R" "${p99:-?}" "${err:-?}" "$dropped" "${ach:-?}" "$gate" | tee -a "$summary"

  if [ "$gate" = "PASS" ]; then knee="$R"; fi
  if [ "$gate" = "FAIL" ] && [ "$STOP_ON_FAIL" = "1" ]; then
    echo
    echo "stopped at first FAIL (set STOP_ON_FAIL=0 to test every rate)"
    break
  fi
done

echo
if [ -n "$knee" ]; then
  echo "KNEE ≈ ${knee}/s  (highest rate that held the gate: p99≤500ms, errors≤0.1%, 0 dropped)"
else
  echo "No rate passed the gate — step down (try RATES=\"20 30 40\")"
fi
echo "full k6 logs + summary.tsv in: $outdir"
