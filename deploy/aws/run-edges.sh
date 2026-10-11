#!/usr/bin/env bash
# Replays the day again on every Edge (restarts the Edge containers with the latest image).
# The start time is kept in .run-start, so deploy/aws/collect-metrics.sh measures only this run.
#
#   deploy/aws/run-edges.sh            # keeps the visits of the previous runs in the database
#   CLEAN=1 deploy/aws/run-edges.sh    # deletes them first (POST /admin/clean on the TMS)
#
# Evaluation scenarios, in the middle of the replayed day (AT_S seconds after this script starts
# the Edges; default: their start delay + 30 s to pull the image and load the users + half a day
# of pings. Check with deploy/aws/logs.sh edge- when the pings start, and set AT_S if needed):
#   CLEAN=1 OUTAGE_S=30 deploy/aws/run-edges.sh   # C2: TMS outage of 30 s (deploy/aws/tms-outage.sh;
#                                                 #     KIND=stop for a real docker stop)
#   CLEAN=1 CRASH_FOG=7 deploy/aws/run-edges.sh   # C3: abrupt crash of Fog 7 (deploy/aws/crash-fog.sh)
set -euo pipefail
cd "$(dirname "$0")"
if [ "${CLEAN:-0}" = "1" ]; then
  ./ssm.sh tag:Role tms "curl -fsS -X POST http://localhost:8080/admin/clean"
  echo "Asked the TMS to delete the visits of the previous runs; waiting 10 s..."
  sleep 10
fi
python3 -c 'import time; print(int(time.time() * 1000))' > .run-start
./ssm.sh tag:Role edge "/opt/htf/run.sh"

if [ -n "${OUTAGE_S:-}${CRASH_FOG:-}" ]; then
  DELAY="$(terraform output -raw edge_start_delay_s)"
  INTERVAL="$(terraform output -raw ping_interval_ms)"
  AT_S="${AT_S:-$(( DELAY + 30 + 24 * INTERVAL / 1000 ))}"
  echo "Waiting ${AT_S} s for the middle of the replayed day (Ctrl+C cancels the event, not the run)..."
  sleep "$AT_S"
  [ -n "${OUTAGE_S:-}" ] && ./tms-outage.sh "$OUTAGE_S"
  [ -n "${CRASH_FOG:-}" ] && ./crash-fog.sh "$CRASH_FOG" && echo "Fog $CRASH_FOG crashed (restore: deploy/aws/restore-fog.sh $CRASH_FOG)"
fi
echo "Run started. When the Edges finish (deploy/aws/logs.sh edge-): deploy/aws/collect-metrics.sh <label>"
