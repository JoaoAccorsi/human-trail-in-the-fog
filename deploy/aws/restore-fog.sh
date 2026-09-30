#!/usr/bin/env bash
# Starts a crashed Fog node again; it rejoins the Chord ring: deploy/aws/restore-fog.sh 7
set -euo pipefail
N="${1:?Fog number, e.g. 7}"
"$(dirname "$0")/ssm.sh" tag:Name "htf-fog-$N" "/opt/htf/run.sh"
