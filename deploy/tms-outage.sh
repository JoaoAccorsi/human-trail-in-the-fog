#!/usr/bin/env bash
# Outage of the TMS (scenario C2): for N seconds POST /capture answers 503, as if the cloud were
# down. The Fog nodes keep the visits in their store-and-forward queue and send them again when the
# TMS answers; the window goes to results/tms-outages.csv, used by deploy/metrics.py.
#
#   deploy/tms-outage.sh            # 30 s, now
#   deploy/tms-outage.sh 60         # 60 s
#   (deploy/run-users.sh does it by itself with OUTAGE_S=30 [OUTAGE_AT_S=45])
#
# A real outage also works: stop the TMS (Ctrl+C) and start it again; the visits are not lost either,
# but then the window is not recorded.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMS="${HTF_TMS_URL:-http://localhost:8080}"
SECONDS_DOWN="${1:-30}"
OUT="$ROOT/results/tms-outages.csv"
mkdir -p "$(dirname "$OUT")"
[ -s "$OUT" ] || echo "from_ms,until_ms,kind" > "$OUT"
R="$(curl -fsS -X POST "$TMS/admin/outage?seconds=$SECONDS_DOWN")"
python3 -c 'import json,sys; r=json.loads(sys.argv[1]); print("%d,%d,simulated" % (r["from_ms"], r["until_ms"]))' "$R" >> "$OUT"
echo "TMS refusing captures for $SECONDS_DOWN s (window recorded in ${OUT#$ROOT/})"
