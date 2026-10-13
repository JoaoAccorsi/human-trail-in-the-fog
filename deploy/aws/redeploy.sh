#!/usr/bin/env bash
# After changing the code: rebuild and push the images, then restart the containers with them.
#   deploy/aws/redeploy.sh            # every component (fog, tms, cmp, edge)
#   deploy/aws/redeploy.sh cmp        # only the listed ones, e.g. "cmp" or "fog tms"
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
REGION="$(terraform output -raw region)"
REGISTRY="$(terraform output -raw registry)"
src() {  # source folder of each component (bash 3.2 on macOS has no associative arrays)
  case "$1" in fog) echo fog ;; tms) echo cloud/tms ;; cmp) echo cloud/mobility-platform ;; edge) echo edge ;;
    *) echo "unknown component: $1 (fog, tms, cmp, edge)" >&2; return 1 ;; esac
}
ROLES="${*:-fog tms cmp edge}"
for role in $ROLES; do src "$role" >/dev/null; done
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
for role in $ROLES; do
  docker buildx build --platform linux/arm64 -t "$REGISTRY/htf/$role:latest" --push "$ROOT/$(src "$role")"
done
if [ $# -eq 0 ]; then
  ./ssm.sh tag:Project htf "/opt/htf/run.sh"
else
  for role in $ROLES; do ./ssm.sh tag:Role "$role" "/opt/htf/run.sh"; done
fi
