#!/usr/bin/env bash
# Replays the day again on every Edge (restarts the Edge containers with the latest image).
# The start time is kept in .run-start, so deploy/aws/collect-metrics.sh measures only this run.
#
#   deploy/aws/run-edges.sh            # keeps the visits of the previous runs in the database
#   CLEAN=1 deploy/aws/run-edges.sh    # deletes them first (POST /admin/clean on the TMS)
#
# Evaluation scenarios, in the middle of the replayed day: the script finds in CloudWatch when the
# first Edge started pinging ("phone(s) connected to entry broker") and triggers the event half a day of pings
# (24 slots) after it. AT_SLOT=30 picks another slot; AT_S=200 forces a fixed delay in seconds
# after this script starts the Edges (the old behavior):
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

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

if [ -n "${OUTAGE_S:-}${CRASH_FOG:-}" ]; then
  REGION="$(terraform output -raw region)"
  DELAY="$(terraform output -raw edge_start_delay_s)"
  INTERVAL="$(terraform output -raw ping_interval_ms)"
  if [ -n "${AT_S:-}" ]; then
    echo "Waiting ${AT_S} s (AT_S) before the event (Ctrl+C cancels the event, not the run)..."
    sleep "$AT_S"
  else
    SLOT="${AT_SLOT:-24}"
    echo "Waiting for the Edges to start pinging (about ${DELAY} s)..."
    FIRST=""
    for _ in $(seq 1 $(( (DELAY + 180) / 5 ))); do
      FIRST="$(./first-ping.sh 2>/dev/null || true)"
      case "$FIRST" in ''|*[!0-9]*) FIRST="" ;; *) break ;; esac
      sleep 5
    done
    if [ -z "$FIRST" ]; then
      echo "Could not find the first ping of the Edges in CloudWatch; the event was not triggered." >&2
      exit 1
    fi
    WAIT_MS=$(( FIRST + SLOT * INTERVAL - $(now_ms) ))
    echo "Pings started; the event goes at slot ${SLOT} ($(( SLOT / 2 )):$(printf %02d $(( SLOT % 2 * 30 )))), in $(( WAIT_MS / 1000 )) s (Ctrl+C cancels the event, not the run)..."
    if [ "$WAIT_MS" -gt 0 ]; then
      sleep "$(python3 -c "print($WAIT_MS / 1000)")"
    fi
  fi
  [ -n "${OUTAGE_S:-}" ] && ./tms-outage.sh "$OUTAGE_S"
  [ -n "${CRASH_FOG:-}" ] && ./crash-fog.sh "$CRASH_FOG" && echo "Fog $CRASH_FOG crashed (restore: deploy/aws/restore-fog.sh $CRASH_FOG)"
fi
echo "Run started. When the Edges finish (deploy/aws/logs.sh edge-): deploy/aws/collect-metrics.sh <label>"
