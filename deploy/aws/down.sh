#!/usr/bin/env bash
# Destroys everything created by up.sh (instances, network, ECR images, logs). Nothing keeps running.
set -euo pipefail
cd "$(dirname "$0")"
terraform destroy
