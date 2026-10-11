#!/usr/bin/env python3
"""Response time of the TMS queries used by the Cloud Mobility Platform (scenario C2), and the
capture counters of the TMS (resends discarded, captures refused during an outage).

    python3 deploy/bench-queries.py --label c2-normal            # TMS at localhost:8080

Queries, each timed on the client (p50 / p95 / max, in ms):
    fogs        GET /fogs?day=N                       Fog nodes with visits on the day
    city        GET /visits?day=N                     every visit of the day (whole city)
    fog         GET /visits?day=N&fog=F               once per Fog node
    trail       GET /trail/{h}?day=N                  once per person (up to --people)
    trail_period GET /trail/{h}?day=N&from=07:00&to=19:00
    events      GET /events/{h}?day=N                 EPCIS events of the person

On AWS the TMS is only reachable inside the VPC, so deploy/aws/collect-metrics.sh runs this script
on the TMS instance with --rows-out (Python 3.9, standard library only) and appends the rows here
with --rows-in.
"""
import argparse
import csv
import json
import math
import os
import random
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def percentile(values, p):
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def get(base, path):
    """(elapsed ms, parsed JSON) of one GET."""
    started = time.perf_counter()
    with urllib.request.urlopen(base + path, timeout=120) as r:
        body = r.read()
    return (time.perf_counter() - started) * 1000, json.loads(body)


def measure(args):
    base = args.tms.rstrip("/")
    day = args.day
    times = {}
    rows = []

    def timed(name, path):
        ms, data = get(base, path)
        times.setdefault(name, []).append(ms)
        return data

    fogs = timed("fogs", f"/fogs?day={day}")
    city = timed("city", f"/visits?day={day}")
    for _ in range(2):                                     # the whole-city query is the heaviest: 3 samples
        timed("city", f"/visits?day={day}")
    for f in fogs:
        timed("fog", f"/visits?day={day}&fog={f['fog']}")
    people = sorted({v["h"] for v in city})
    random.Random(42).shuffle(people)
    for h in people[:args.people]:
        timed("trail", f"/trail/{h}?day={day}")
    for h in people[:min(args.people, 200)]:
        timed("trail_period", f"/trail/{h}?day={day}&from=07:00&to=19:00")
        timed("events", f"/events/{h}?day={day}")

    rows.append(("query_people", len(people), "count"))
    rows.append(("query_city_visits", len(city), "count"))
    for name, values in times.items():
        rows.append((f"query_{name}_count", len(values), "count"))
        rows.append((f"query_{name}_p50", percentile(values, 50), "ms"))
        rows.append((f"query_{name}_p95", percentile(values, 95), "ms"))
        rows.append((f"query_{name}_max", max(values), "ms"))

    _, stats = get(base, "/stats")
    rows.append(("tms_capture_requests", stats["captureRequests"], "count"))
    rows.append(("tms_visits_new", stats["visitsNew"], "count"))
    rows.append(("tms_visits_repeated", stats["visitsRepeated"], "count (resends discarded)"))
    rows.append(("tms_visits_rejected", stats["visitsRejected"], "count"))
    rows.append(("tms_captures_refused_outage", stats["refusedInOutage"], "count"))
    return rows


def append(rows, label, out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    new = not os.path.exists(out) or os.path.getsize(out) == 0
    with open(out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["run", "scope", "metric", "value", "unit"])
        for metric, value, unit in rows:
            w.writerow([label, "city", metric, round(value, 3), unit])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tms", default=os.environ.get("HTF_TMS_URL", "http://localhost:8080"))
    ap.add_argument("--day", type=int, default=20)
    ap.add_argument("--people", type=int, default=1000, help="trails queried (random sample, default 1000)")
    ap.add_argument("--label", default=time.strftime("run-%Y%m%d-%H%M%S"))
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "metrics.csv"))
    ap.add_argument("--rows-out", help="write the rows to this JSON file instead of the CSV")
    ap.add_argument("--rows-in", help="append the rows of a JSON file written with --rows-out")
    args = ap.parse_args()

    if args.rows_in:
        with open(args.rows_in) as f:
            rows = [tuple(r) for r in json.load(f)]
    else:
        try:
            rows = measure(args)
        except OSError as e:
            sys.exit(f"could not query the TMS at {args.tms}: {e}")
    if args.rows_out:
        with open(args.rows_out, "w") as f:
            json.dump(rows, f)
        print(f"{len(rows)} rows written to {args.rows_out}")
        return

    for metric, value, unit in rows:
        print(f"  {metric:32} {value:12.1f}  {unit}" if isinstance(value, float) else f"  {metric:32} {value:12}  {unit}")
    append(rows, args.label, args.out)
    print(f"{len(rows)} rows appended to {os.path.relpath(args.out)}")


if __name__ == "__main__":
    main()
