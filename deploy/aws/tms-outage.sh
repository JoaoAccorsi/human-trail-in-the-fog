#!/usr/bin/env bash
# Outage of the TMS on AWS (scenario C2), through SSM:
#
#   deploy/aws/tms-outage.sh 30           # /capture answers 503 for 30 s (simulated, exact window)
#   KIND=stop deploy/aws/tms-outage.sh 30 # real outage: docker stop tms, 30 s, docker start tms
#
# The window goes to results/tms-outages.csv, used by deploy/metrics.py (collect-metrics.sh).
# deploy/aws/run-edges.sh does it by itself in the middle of the day with OUTAGE_S=30.
set -euo pipefail
cd "$(dirname "$0")"
SECONDS_DOWN="${1:-30}"
KIND="${KIND:-simulated}"
OUT="../../results/tms-outages.csv"
mkdir -p "$(dirname "$OUT")"
[ -s "$OUT" ] || echo "from_ms,until_ms,kind" > "$OUT"
NOW="$(python3 -c 'import time; print(int(time.time() * 1000))')"
if [ "$KIND" = "stop" ]; then
  ./ssm.sh tag:Role tms "docker stop tms && sleep $SECONDS_DOWN && docker start tms" >/dev/null
  # stop + restart of the container (and reconnection to RDS) add a few seconds to the window
  echo "$NOW,$((NOW + SECONDS_DOWN * 1000)),stop" >> "$OUT"
else
  ./ssm.sh tag:Role tms "curl -fsS -X POST 'http://localhost:8080/admin/outage?seconds=$SECONDS_DOWN'" >/dev/null
  echo "$NOW,$((NOW + SECONDS_DOWN * 1000)),simulated" >> "$OUT"
fi
echo "TMS outage ($KIND) of $SECONDS_DOWN s requested (SSM takes ~1-2 s to deliver it)"
