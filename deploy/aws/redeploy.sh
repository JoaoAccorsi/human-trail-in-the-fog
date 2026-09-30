#!/usr/bin/env bash
# After changing the code: rebuild and push the images, then restart every container with them.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
REGION="$(terraform output -raw region)"
REGISTRY="$(terraform output -raw registry)"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
for pair in fog:fog tms:cloud/tms cmp:cloud/mobility-platform edge:edge; do
  docker buildx build --platform linux/arm64 -t "$REGISTRY/htf/${pair%%:*}:latest" --push "$ROOT/${pair#*:}"
done
./ssm.sh tag:Project htf "/opt/htf/run.sh"
