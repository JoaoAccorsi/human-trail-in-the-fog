#!/usr/bin/env bash
# Creates the whole HTF environment on AWS (terraform apply) and publishes the Docker images to ECR.
# The instances keep trying to pull their image, so they start as soon as the push finishes.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"

for cmd in terraform aws docker; do
  command -v "$cmd" >/dev/null || { echo "missing command: $cmd" >&2; exit 1; }
done
[ -f terraform.tfvars ] || { echo "create deploy/aws/terraform.tfvars first (see terraform.tfvars.example)" >&2; exit 1; }

terraform init -input=false
terraform apply

REGION="$(terraform output -raw region)"
REGISTRY="$(terraform output -raw registry)"
# every group joined with commas, e.g. ["4000-5000","6000,6005"] -> 4000-5000,6000,6005
USERS="$(terraform output -json edge_groups | tr -d '[]" \n')"

"$ROOT/deploy/extract-users.sh" "$USERS"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
build() {
  echo "== building $1 (linux/arm64)"
  docker buildx build --platform linux/arm64 -t "$REGISTRY/htf/$1:latest" --push "$ROOT/$2"
}
build fog  fog
build tms  cloud/tms
build cmp  cloud/mobility-platform
build edge edge

cat <<MSG

Images pushed. The instances pull them and start within a few minutes;
the Edges wait $(terraform output -raw edge_start_delay_s) s more so the Chord ring
of $(terraform output -raw number_of_fogs) Fog nodes can form first.

  Cloud Mobility Platform: $(terraform output -raw cloud_mobility_platform_url)
  Logs (all nodes):        deploy/aws/logs.sh
  Run the Edges again:     deploy/aws/run-edges.sh
  Destroy everything:      deploy/aws/down.sh
MSG
