#!/usr/bin/env python3
"""Evaluation metrics of one run -> final CSV (one row per metric).

Reads the visits stored by the TMS (GET /visits?day=N, or a file saved from it) and the measurements
written by the Fog nodes ({HTF_METRICS_DIR}/fog-{id}.csv, see fog/src/main/java/fog/Metrics.java), and appends
the metrics of the run to results/metrics.csv:

    run,scope,metric,value,unit

scope is "city" (the whole run) or "fog N" (one Fog node). Every run is appended with its own
label, so several runs (e.g. rsrp x area, 1,000 x 5,000 users, with and without a failure) end up
in the same file, ready to compare.

    python3 deploy/metrics.py --label c1-rsrp                         # local run, defaults below
    python3 deploy/metrics.py --label c2-5000 --users 4000-8999 --dataset YJMob100K/yjmob100k-dataset2.csv
    python3 deploy/metrics.py --visits results/aws/c3/visits.json --fog-metrics results/aws/c3/metrics --label aws-c3

Options:
    --visits SRC       where the visits come from: the TMS URL (default $HTF_TMS_URL or
                       http://localhost:8080), or a file with the answer of /visits (JSON array) or
                       the visits.jsonl of the old JSONL version of the TMS
    --day N            day of the visits (default 20)
    --since MS         only Fog measurements at or after this epoch time in ms (default: all);
                       deploy/run-users.sh passes the start of the run
    --users SPEC       users of the run ("4000-5000", "4000,4005"): with --dataset, counts the pings
                       the Edge should have sent, to compute the ping delivery ratio
    --dataset PATH     YJMob100K CSV, .csv.gz or edge/data/users.csv, used with --users
    --crash-times PATH CSV "time_ms,fog" of crashes made outside the Fog (deploy/aws/crash-fog.sh);
                       crashes made with /admin/crash are already in the Fog measurements
    --ping-pong-min M  a return to the previous Fog within M minutes counts as ping-pong (default 60)
    --out PATH         final CSV (default results/metrics.csv)
"""
import argparse
import collections
import csv
import glob
import gzip
import json
import math
import os
import statistics
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINUTES_PER_SLOT = 30


def parse_users(spec):
    users = set()
    for part in spec.split(","):
        a, _, b = part.strip().partition("-")
        users.update(range(int(a), int(b or a) + 1))
    return users


def percentile(values, p):
    """p-th percentile (0..100) with linear interpolation; None for an empty list."""
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def read_visits(source, day):
    """Visits of the day, from the TMS (URL) or from a file (JSON array or JSON Lines)."""
    if source.startswith(("http://", "https://")):
        with urllib.request.urlopen(f"{source.rstrip('/')}/visits?day={day}", timeout=120) as r:
            visits = json.load(r)
    else:
        with open(source) as f:
            text = f.read()
        visits = json.loads(text) if text.lstrip().startswith("[") else \
            [json.loads(line) for line in text.splitlines() if line.strip()]
    return [v for v in visits if v["day"] == day]


def read_measurements(folder, since):
    rows = []
    for path in sorted(glob.glob(os.path.join(folder, "**", "fog-*.csv"), recursive=True)):
        with open(path, newline="") as f:
            for r in csv.DictReader(f):
                if int(r["time_ms"]) >= since:
                    r["time_ms"] = int(r["time_ms"])
                    r["fog"] = int(r["fog"])
                    r["value_ms"] = float(r["value_ms"])
                    r["detail"] = dict(kv.split("=", 1) for kv in r["detail"].split(";") if "=" in kv)
                    rows.append(r)
    return rows


def expected_pings(dataset, users, day):
    """Rows of the dataset for these users on the day = pings the Edge should have published."""
    n = 0
    with (gzip.open(dataset, "rt") if dataset.endswith(".gz") else open(dataset)) as f:
        next(f)
        for line in f:
            uid, d, _ = line.split(",", 2)
            if int(d) == day and int(uid) in users:
                n += 1
    return n


def duration_min(v):
    return (v["departureT"] - v["arrivalT"] + 1) * MINUTES_PER_SLOT


def trails_of(visits):
    trails = collections.defaultdict(list)
    for v in visits:
        trails[v["h"]].append(v)
    for t in trails.values():
        t.sort(key=lambda v: v["arrivalT"])
    return trails


def ping_pongs(trails, window_min):
    """A -> B -> A where the stay in B lasted at most window_min: the second handover undid the first."""
    n = 0
    for t in trails.values():
        for a, b, c in zip(t, t[1:], t[2:]):
            if (a["fog"] == c["fog"] != b["fog"] and a["reason"] == "handover" and b["reason"] == "handover"
                    and duration_min(b) <= window_min):
                n += 1
    return n


def overlaps(trails):
    return sum(1 for t in trails.values() for a, b in zip(t, t[1:]) if b["arrivalT"] <= a["departureT"])


def stats(rows, prefix, values, unit="ms"):
    """count, mean, p50, p95, max of a list of measurements."""
    rows.append((f"{prefix}_count", len(values), "count"))
    if values:
        rows.append((f"{prefix}_mean", statistics.fmean(values), unit))
        rows.append((f"{prefix}_p50", percentile(values, 50), unit))
        rows.append((f"{prefix}_p95", percentile(values, 95), unit))
        rows.append((f"{prefix}_max", max(values), unit))


def city_metrics(visits, measures, args, crash_times):
    rows = []
    trails = trails_of(visits)
    handovers = sum(1 for v in visits if v["reason"] == "handover")
    people_per_fog = collections.Counter()
    for fog, hs in _people_by_fog(visits).items():
        people_per_fog[fog] = len(hs)

    # ---- trails (TMS)
    rows.append(("people", len(trails), "count"))
    rows.append(("total_visits", len(visits), "count"))
    pings = sum(v["pings"] for v in visits)
    rows.append(("pings_stored", pings, "count"))
    if args.users and args.dataset:
        expected = expected_pings(args.dataset, parse_users(args.users), args.day)
        rows.append(("pings_expected", expected, "count"))
        rows.append(("ping_delivery_ratio", pings / expected * 100 if expected else None, "%"))
    rows.append(("fog_handovers", handovers, "count"))
    rows.append(("fog_handovers_per_person", handovers / len(trails) if trails else None, "count"))
    pp = ping_pongs(trails, args.ping_pong_min)
    rows.append(("ping_pongs", pp, "count"))
    rows.append(("ping_pong_rate", pp / handovers * 100 if handovers else 0, "% of handovers"))
    durations = [duration_min(v) for v in visits]
    stats(rows, "visit_duration", durations, "min")
    for reason in ("handover", "end_of_day", "context_lost"):
        rows.append((f"visits_ended_{reason}", sum(1 for v in visits if v["reason"] == reason), "count"))
    rows.append(("overlapping_visits", overlaps(trails), "count"))
    rows.append(("fogs_with_visits", len(people_per_fog), "count"))
    if people_per_fog:
        mean = statistics.fmean(people_per_fog.values())
        rows.append(("people_per_fog_mean", mean, "count"))
        rows.append(("people_per_fog_max", max(people_per_fog.values()), "count"))
        rows.append(("load_imbalance", max(people_per_fog.values()) / mean, "max/mean"))

    # ---- Fog measurements
    by_event = collections.defaultdict(list)
    for m in measures:
        by_event[m["event"]].append(m)
    stats(rows, "ping_processing", [m["value_ms"] for m in by_event["ping"]])
    committed = [m for m in by_event["handover"] if m["detail"].get("result") == "committed"]
    stats(rows, "handover_duration", [m["value_ms"] for m in committed])
    stats(rows, "handover_prepare", [float(m["detail"].get("prepare_ms", 0)) for m in committed])
    rows.append(("handovers_aborted", sum(1 for m in by_event["handover"] if m["detail"].get("result") == "aborted"),
                 "count"))
    stats(rows, "tms_delivery", [m["value_ms"] for m in by_event["tms_delivery"]])

    # ---- failures (scenario C3)
    crashes = sorted([(m["time_ms"], m["fog"]) for m in by_event["crash"]] + crash_times)
    downs = sorted(m["time_ms"] for m in by_event["node_down"])
    rows.append(("fog_crashes", len(crashes), "count"))
    rows.append(("node_down_detections", len(downs), "count"))
    detection = []
    for t, _ in crashes:
        after = [d for d in downs if d >= t]
        if after:
            detection.append(after[0] - t)
    if detection:
        stats(rows, "failure_detection", detection)
    refresh = by_event["owner_refresh"]
    rows.append(("owner_records_restored", sum(int(m["detail"].get("restored", 0)) for m in refresh), "count"))
    if refresh:
        rows.append(("owner_restore_time_max", max(m["value_ms"] for m in refresh), "ms"))
    return rows


def _people_by_fog(visits):
    out = collections.defaultdict(set)
    for v in visits:
        out[v["fog"]].add(v["h"])
    return out


def fog_metrics(visits, measures):
    people = _people_by_fog(visits)
    rows = []
    for fog in sorted(set(people) | {m["fog"] for m in measures}):
        vs = [v for v in visits if v["fog"] == fog]
        r = [
            ("people", len(people.get(fog, ())), "count"),
            ("total_visits", len(vs), "count"),
            ("pings_stored", sum(v["pings"] for v in vs), "count"),
            ("fog_handovers_out", sum(1 for v in vs if v["reason"] == "handover"), "count"),
            ("fog_handovers_in", sum(1 for v in visits if v.get("nextFog") == fog and v["reason"] == "handover"),
             "count"),
        ]
        mine = [m for m in measures if m["fog"] == fog]
        for event, name in (("ping", "ping_processing"), ("handover", "handover_duration"),
                            ("tms_delivery", "tms_delivery")):
            vals = [m["value_ms"] for m in mine if m["event"] == event
                    and (event != "handover" or m["detail"].get("result") == "committed")]
            if vals:
                r.append((f"{name}_p95", percentile(vals, 95), "ms"))
        rows += [(f"fog {fog}",) + x for x in r]
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--visits", default=os.environ.get("HTF_TMS_URL", "http://localhost:8080"))
    ap.add_argument("--fog-metrics", default=os.path.join(ROOT, "fog", "metrics"))
    ap.add_argument("--day", type=int, default=20)
    ap.add_argument("--since", type=int, default=0)
    ap.add_argument("--label", default=time.strftime("run-%Y%m%d-%H%M%S"))
    ap.add_argument("--users")
    ap.add_argument("--dataset")
    ap.add_argument("--crash-times")
    ap.add_argument("--ping-pong-min", type=int, default=60)
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "metrics.csv"))
    args = ap.parse_args()

    visits = read_visits(args.visits, args.day)
    if not visits:
        sys.exit(f"no visits on day {args.day} in {args.visits}")
    measures = read_measurements(args.fog_metrics, args.since) if os.path.isdir(args.fog_metrics) else []
    if not measures:
        print(f"warning: no Fog measurements in {args.fog_metrics} (only the TMS metrics will be computed)")
    crash_times = []
    if args.crash_times and os.path.exists(args.crash_times):
        with open(args.crash_times, newline="") as f:
            crash_times = [(int(r["time_ms"]), int(r["fog"])) for r in csv.DictReader(f)
                           if int(r["time_ms"]) >= args.since]

    rows = [("city",) + r for r in city_metrics(visits, measures, args, crash_times)] + fog_metrics(visits, measures)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    new = not os.path.exists(args.out) or os.path.getsize(args.out) == 0
    with open(args.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["run", "scope", "metric", "value", "unit"])
        for scope, metric, value, unit in rows:
            w.writerow([args.label, scope, metric, "" if value is None else round(value, 3), unit])

    print(f"{'metric':32} {'value':>12}  unit   (run {args.label}, day {args.day})")
    for scope, metric, value, unit in rows:
        if scope == "city":
            shown = "-" if value is None else (f"{value:.1f}" if isinstance(value, float) else str(value))
            print(f"{metric:32} {shown:>12}  {unit}")
    print(f"\n{len(rows)} rows appended to {os.path.relpath(args.out)} (the city and one block per Fog node)")


if __name__ == "__main__":
    main()
