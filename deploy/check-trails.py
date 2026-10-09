#!/usr/bin/env python3
"""Consistency check of the trails stored by the TMS.

For every pseudonym, its visits must not overlap in time: two Fog nodes must never have served
the same person in the same timeslot (a single owner per user). Also prints how the visits ended.

    python3 deploy/check-trails.py [source] [day]

source: the TMS URL (default $HTF_TMS_URL or http://localhost:8080; then the day is required,
default 20), or a file with the answer of /visits?day=N (JSON array) or a visits.jsonl of the old
JSONL version of the TMS.
"""
import collections
import json
import os
import sys
import urllib.request

source = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("HTF_TMS_URL", "http://localhost:8080")
day = int(sys.argv[2]) if len(sys.argv) > 2 else None

if source.startswith(("http://", "https://")):
    day = 20 if day is None else day
    with urllib.request.urlopen(f"{source.rstrip('/')}/visits?day={day}", timeout=120) as r:
        visits = json.load(r)
else:
    text = open(source).read()
    visits = json.loads(text) if text.lstrip().startswith("[") else \
        [json.loads(line) for line in text.splitlines() if line.strip()]
if day is not None:
    visits = [v for v in visits if v["day"] == day]
by_person = collections.defaultdict(list)
for v in visits:
    by_person[(v["h"], v["day"])].append(v)

overlaps = 0
for (h, d), vs in by_person.items():
    vs.sort(key=lambda v: v["arrivalT"])
    for a, b in zip(vs, vs[1:]):
        if b["arrivalT"] <= a["departureT"]:
            overlaps += 1
            print(f"overlap {h[:8]} day {d}: Fog {a['fog']} {a['arrival']}-{a['departure']} | "
                  f"Fog {b['fog']} {b['arrival']}-{b['departure']}")

print(f"people: {len(by_person)} | visits: {len(visits)} | overlapping pairs: {overlaps}")
print("visits ended by:", dict(collections.Counter(v["reason"] for v in visits)))
sys.exit(1 if overlaps else 0)
