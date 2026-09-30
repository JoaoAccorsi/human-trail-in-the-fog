#!/usr/bin/env bash
# Abrupt failure of a Fog node (kills its container, no warning to the others): deploy/aws/crash-fog.sh 7
set -euo pipefail
N="${1:?Fog number, e.g. 7}"
"$(dirname "$0")/ssm.sh" tag:Name "htf-fog-$N" "docker kill fog-$N"
