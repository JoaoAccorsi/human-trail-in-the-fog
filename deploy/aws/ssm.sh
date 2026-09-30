#!/usr/bin/env bash
# Runs a shell command on the HTF instances selected by tag, through SSM (no SSH needed).
#   deploy/aws/ssm.sh <tag:Role|tag:Name> <value> "<command>"
set -euo pipefail
cd "$(dirname "$0")"
KEY="${1:?tag key, e.g. tag:Role}"; VALUE="${2:?tag value, e.g. edge}"; CMD="${3:?command}"
REGION="$(terraform output -raw region)"
aws ssm send-command --region "$REGION" --document-name AWS-RunShellScript \
  --targets "Key=$KEY,Values=$VALUE" \
  --parameters "commands=[\"$CMD\"]" \
  --comment "HTF: $CMD" --query 'Command.CommandId' --output text
