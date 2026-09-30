#!/usr/bin/env bash
# Follows the logs of the HTF nodes in CloudWatch. Optional prefix: fog-, fog-7, tms, cmp, edge-
#   deploy/aws/logs.sh            (all)      deploy/aws/logs.sh fog-7
set -euo pipefail
cd "$(dirname "$0")"
REGION="$(terraform output -raw region)"
if [ -n "${1:-}" ]; then
  aws logs tail /htf --region "$REGION" --follow --log-stream-name-prefix "$1"
else
  aws logs tail /htf --region "$REGION" --follow
fi
