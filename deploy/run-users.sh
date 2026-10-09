#!/usr/bin/env bash
# Replays the trajectories of a range of YJMob100K users on one day, all in ONE Edge process:
# one simulated phone per user (its own pseudonym, control topic and handover state), on a shared
# clock. The phones share one MQTT connection per Fog broker (1,000 separate MQTT clients would
# need ~4,000 threads, above the per-process thread limit of macOS).
#
#   deploy/run-users.sh                    # users 4000-5000, day 20
#   deploy/run-users.sh 4000-4100 20       # other range / day
#   CLEAN=1 deploy/run-users.sh            # delete the TMS data first (fresh results): POST /admin/clean
#   HTF_PING_INTERVAL_MS=3000 deploy/run-users.sh   # slower clock (real time per 30-min slot)
#   LABEL=c1-rsrp deploy/run-users.sh      # label of the run in results/metrics.csv
#
# At the end, the metrics of the run (trails in the TMS + measurements of the Fog nodes since the
# start of the run) are appended to results/metrics.csv by deploy/metrics.py.
#
# Start the database, the TMS and the Fog nodes first:
#   docker compose up -d postgres
#   cd cloud/tms && mvn -q compile exec:exec
#   cd fog && mvn -q compile exec:exec        (wait for "you can run the Edge now")
set -euo pipefail

USERS="${1:-4000-5000}"
DAY="${2:-20}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMS="${HTF_TMS_URL:-http://localhost:8080}"

# raise the open-files limit (harmless if already high)
ulimit -n 10240 2>/dev/null || ulimit -n 4096 2>/dev/null || true

curl -fsS "$TMS/health" >/dev/null 2>&1 || { echo "The TMS is not answering at $TMS. Start it: cd cloud/tms && mvn -q compile exec:exec"; exit 1; }
curl -fsS "http://localhost:9001/status" >/dev/null 2>&1 || { echo "Fog 1 is not answering. Start the Fog nodes: cd fog && mvn -q compile exec:exec"; exit 1; }

if [ "${CLEAN:-0}" = "1" ]; then
  curl -fsS -X POST "$TMS/admin/clean" >/dev/null
  echo "Previous TMS data deleted (tables visits and epcis_events)"
fi

export HTF_USERS="$USERS" HTF_DAY="$DAY"
export HTF_PING_INTERVAL_MS="${HTF_PING_INTERVAL_MS:-2000}"
echo "Replaying users $USERS on day $DAY (one 30-min slot every ${HTF_PING_INTERVAL_MS} ms)..."
START=$(date +%s)
START_MS=$(python3 -c 'import time; print(int(time.time() * 1000))')
(cd "$ROOT/edge" && mvn -q compile exec:exec)
echo "Edge finished in $(( $(date +%s) - START )) s."

# the last visits leave the Fogs after the end-of-day jitter and the batch window
WAIT=$(( ${HTF_END_OF_DAY_JITTER_MS:-5000} / 1000 + 3 ))
echo "Waiting ${WAIT} s for the last batches to reach the TMS..."
sleep "$WAIT"

echo
echo "Fog nodes with visits on day $DAY (from the TMS):"
curl -fsS "$TMS/fogs?day=$DAY" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
print("%4s %7s %7s" % ("Fog", "people", "visits"))
for r in rows:
    print("%4d %7d %7d" % (r["fog"], r["people"], r["visits"]))
print("%4s %7d %7d   (a person is counted once per Fog)" % ("all", sum(r["people"] for r in rows), sum(r["visits"] for r in rows)))
'

echo
echo "Consistency of the trails (no person served by two Fog nodes at the same time):"
python3 "$ROOT/deploy/check-trails.py" "$TMS" "$DAY" || true

echo
echo "Metrics of the run:"
DATASET="$ROOT/YJMob100K/yjmob100k-dataset2.csv"
[ -f "$DATASET" ] || DATASET="$DATASET.gz"
EXTRA=()                                    # an array keeps paths with spaces (e.g. iCloud Drive) whole
[ -f "$DATASET" ] && EXTRA=(--dataset "$DATASET")
python3 "$ROOT/deploy/metrics.py" --label "${LABEL:-users-$USERS-day$DAY}" --day "$DAY" --since "$START_MS" \
  --visits "$TMS" --fog-metrics "$ROOT/fog/metrics" \
  --users "$USERS" ${EXTRA[@]+"${EXTRA[@]}"} || true
