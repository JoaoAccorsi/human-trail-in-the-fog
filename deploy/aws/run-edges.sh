#!/usr/bin/env bash
# Replays the day again on every Edge (restarts the Edge containers with the latest image).
# The start time is kept in .run-start, so deploy/aws/collect-metrics.sh measures only this run.
#
#   deploy/aws/run-edges.sh            # keeps the visits of the previous runs in the database
#   CLEAN=1 deploy/aws/run-edges.sh    # deletes them first (POST /admin/clean on the TMS)
set -euo pipefail
cd "$(dirname "$0")"
if [ "${CLEAN:-0}" = "1" ]; then
  ./ssm.sh tag:Role tms "curl -fsS -X POST http://localhost:8080/admin/clean"
  echo "Asked the TMS to delete the visits of the previous runs; waiting 10 s..."
  sleep 10
fi
python3 -c 'import time; print(int(time.time() * 1000))' > .run-start
./ssm.sh tag:Role edge "/opt/htf/run.sh"
