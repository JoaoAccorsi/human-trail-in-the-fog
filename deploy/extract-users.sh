#!/usr/bin/env bash
# Writes edge/data/users.csv with only the YJMob100K rows of the given users,
# so the Edge Docker image does not carry the full dataset (~570 MB).
# Users are a list and/or ranges, in the same form as HTF_USERS:
#
#   deploy/extract-users.sh 4000,4005
#   deploy/extract-users.sh 4000-5000,6000
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
USERS="${1:?usage: deploy/extract-users.sh 4000,4005 | 4000-5000}"
SRC="$ROOT/YJMob100K/yjmob100k-dataset2.csv"
OUT="$ROOT/edge/data/users.csv"
mkdir -p "$(dirname "$OUT")"
if [ -f "$SRC" ]; then
  READ=(cat "$SRC")
elif [ -f "$SRC.gz" ]; then
  READ=(gzip -dc "$SRC.gz")
else
  echo "dataset not found: $SRC" >&2
  exit 1
fi
"${READ[@]}" | awk -F, -v users="$USERS" '
  BEGIN {
    n = split(users, parts, ",")
    for (i = 1; i <= n; i++) {
      m = split(parts[i], ab, "-")
      a = ab[1] + 0; b = (m > 1 ? ab[2] : ab[1]) + 0
      for (u = a; u <= b; u++) keep[u] = 1
    }
  }
  NR == 1 || (($1 + 0) in keep)' > "$OUT"
echo "$(( $(wc -l < "$OUT") - 1 )) rows of users $USERS -> ${OUT#$ROOT/}"
