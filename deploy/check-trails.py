#!/usr/bin/env python3
"""Consistency check of the trails stored by the TMS (cloud/tms/data/visits.jsonl).

For every pseudonym, its visits must not overlap in time: two Fog nodes must never have served
the same person in the same timeslot (a single owner per user). Also prints how the visits ended.

    python3 deploy/check-trails.py [path/to/visits.jsonl] [day]
"""
import collections
import json
import os
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "cloud", "tms", "data", "visits.jsonl")
day = int(sys.argv[2]) if len(sys.argv) > 2 else None

visits = [json.loads(line) for line in open(path) if line.strip()]
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
