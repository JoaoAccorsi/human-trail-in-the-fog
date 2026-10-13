#!/usr/bin/env bash
# Waits until every Edge published its end of day in the run started by deploy/aws/run-edges.sh
# (file .run-start), so deploy/aws/collect-metrics.sh only runs on a finished day.
#   deploy/aws/wait-edges.sh            (gives up after 60 min; MAX_MIN=90 to wait longer)
set -euo pipefail
cd "$(dirname "$0")"
REGION="$(terraform output -raw region)"
EDGES="$(terraform output -json edge_groups | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
SINCE="$(cat .run-start)"
DEADLINE=$(( $(date +%s) + ${MAX_MIN:-60} * 60 ))
while true; do
  DONE="$(AWS_PAGER="" aws logs filter-log-events --region "$REGION" --log-group-name /htf \
      --log-stream-name-prefix edge- --start-time "$SINCE" \
      --query "events[?contains(message, 'end of day published')].message" --output text \
      | tr '\t' '\n' | grep -c "end of day published" || true)"
  echo "$(date +%H:%M:%S)  ${DONE}/${EDGES} Edge(s) finished the day"
  if [ "$DONE" -ge "$EDGES" ]; then
    AWS_PAGER="" aws logs filter-log-events --region "$REGION" --log-group-name /htf \
      --log-stream-name-prefix edge- --start-time "$SINCE" \
      --query "events[?contains(message, 'end of day published')].[logStreamName,message]" --output text
    echo "All Edges finished; waiting 30 s more for the last visits to reach the TMS..."
    sleep 30
    exit 0
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "Not every Edge finished after ${MAX_MIN:-60} min." >&2
    exit 1
  fi
  sleep 20
done
