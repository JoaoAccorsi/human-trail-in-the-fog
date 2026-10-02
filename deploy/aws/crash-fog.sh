#!/usr/bin/env bash
# Abrupt failure of a Fog node (kills its container, no warning to the others): deploy/aws/crash-fog.sh 7
# The time of the crash goes to results/crash-times.csv, used by deploy/metrics.py to measure
# how long the ring takes to detect the failure.
set -euo pipefail
cd "$(dirname "$0")"
N="${1:?Fog number, e.g. 7}"
CRASHES="../../results/crash-times.csv"
mkdir -p "$(dirname "$CRASHES")"
[ -s "$CRASHES" ] || echo "time_ms,fog" > "$CRASHES"
./ssm.sh tag:Name "htf-fog-$N" "docker kill fog-$N"
echo "$(python3 -c 'import time; print(int(time.time() * 1000))'),$N" >> "$CRASHES"
