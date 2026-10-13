#!/usr/bin/env bash
# Prints the epoch ms of the first "... phone(s) connected to entry broker" line of the Edges since
# the start of the last deploy/aws/run-edges.sh (file .run-start): the 00:00 slot of the replayed day.
# Prints nothing if the Edges have not started pinging yet.
#   deploy/aws/first-ping.sh            (since .run-start)
#   deploy/aws/first-ping.sh <epoch_ms> (since that time)
set -euo pipefail
cd "$(dirname "$0")"
REGION="$(terraform output -raw region)"
SINCE="${1:-$(cat .run-start)}"
T="$(AWS_PAGER="" aws logs filter-log-events --region "$REGION" --log-group-name /htf \
      --log-stream-name-prefix edge- --start-time "$SINCE" \
      --query "min(events[?contains(message, 'connected to entry broker')].timestamp)" --output text)"
# The CLI applies the query to each page of results: keep the smallest number among the pages ("None" = no match).
echo "$T" | tr -s ' \t' '\n\n' | grep -E '^[0-9]+$' | sort -n | head -1 || true
