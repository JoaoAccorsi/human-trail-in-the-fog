#!/usr/bin/env bash
# Replays the day again on every Edge (restarts the Edge containers with the latest image).
set -euo pipefail
"$(dirname "$0")/ssm.sh" tag:Role edge "/opt/htf/run.sh"
