#!/usr/bin/env bash
# Gathers the results of the last run on AWS and appends its metrics to results/metrics.csv.
#
#   deploy/aws/collect-metrics.sh c2-5000-users        # label of the run in the CSV
#
# 1. the Fog nodes copy their measurements (/opt/htf/metrics) and the TMS the visits of the day
#    (GET /visits?day=N, read from the RDS database) to the results bucket (through SSM, no SSH);
# 2. they are downloaded to results/aws/<label>/;
# 3. deploy/metrics.py computes the metrics, counting only what happened since the last
#    deploy/aws/run-edges.sh (file .run-start; without it, everything since the instances started).
# Run it after the Edges finished the day (deploy/aws/logs.sh edge- shows when).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
LABEL="${1:?label of the run, e.g. c1-rsrp}"
REGION="$(terraform output -raw region)"
BUCKET="$(terraform output -raw results_bucket)"
DAY="$(terraform output -raw day)"
USERS="$(terraform output -json edge_groups | tr -d '[]" \n')"
DEST="s3://$BUCKET/$LABEL"
OUT="$ROOT/results/aws/$LABEL"

wait_for() {   # waits until every instance of an SSM command finished
  local id="$1" status
  for _ in $(seq 1 100); do
    status="$(aws ssm list-command-invocations --region "$REGION" --command-id "$id" \
      --query 'CommandInvocations[].Status' --output text)"
    case "$status" in
      ""|*Pending*|*InProgress*|*Delayed*) sleep 3 ;;
      *) echo "  $status"; return ;;
    esac
  done
  echo "  timed out waiting for SSM command $id" >&2
}

echo "Copying the measurements of the Fog nodes and the visits of the TMS to $DEST ..."
FOGS="$(./ssm.sh tag:Role fog "aws s3 cp /opt/htf/metrics/ $DEST/metrics/\$(hostname)/ --recursive --region $REGION")"
TMS="$(./ssm.sh tag:Role tms "curl -fsS -o /tmp/visits.json 'http://localhost:8080/visits?day=$DAY' && aws s3 cp /tmp/visits.json $DEST/visits.json --region $REGION")"
wait_for "$FOGS"
wait_for "$TMS"

echo "Downloading to ${OUT#$ROOT/} ..."
mkdir -p "$OUT"
aws s3 sync "$DEST" "$OUT" --region "$REGION" --only-show-errors

SINCE=0
[ -f .run-start ] && SINCE="$(cat .run-start)"
python3 "$ROOT/deploy/metrics.py" --label "$LABEL" --day "$DAY" --since "$SINCE" \
  --visits "$OUT/visits.json" --fog-metrics "$OUT/metrics" \
  --users "$USERS" --dataset "$ROOT/edge/data/users.csv" \
  --crash-times "$ROOT/results/crash-times.csv"
