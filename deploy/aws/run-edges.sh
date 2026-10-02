#!/usr/bin/env bash
# Replays the day again on every Edge (restarts the Edge containers with the latest image).
# The start time is kept in .run-start, so deploy/aws/collect-metrics.sh measures only this run.
set -euo pipefail
cd "$(dirname "$0")"
python3 -c 'import time; print(int(time.time() * 1000))' > .run-start
./ssm.sh tag:Role edge "/opt/htf/run.sh"
