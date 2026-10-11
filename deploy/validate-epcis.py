#!/usr/bin/env python3
"""Conformance of the stored EPCIS documents with GS1 EPCIS 2.0 (scenario C2).

Validates every document the TMS stored for a day (one per visit, as received from the Fog) against
the official EPCIS 2.0 JSON Schema published by GS1, and checks what the schema cannot: the CBV
business steps, the pseudonym in the epcList (never a real id) and the SGLN of the read point.
Appends the results to results/metrics.csv:

    python3 deploy/validate-epcis.py --label c2-normal                     # TMS at localhost:8080
    python3 deploy/validate-epcis.py --label aws-c2 --documents results/aws/aws-c2/documents.json

Options:
    --tms URL          TMS to read GET /documents?day=N from (default $HTF_TMS_URL or http://localhost:8080)
    --documents PATH   or a file saved from that endpoint (JSON array)
    --day N            day of the visits (default 20)
    --schema PATH      EPCIS 2.0 JSON Schema; downloaded once from GS1 to deploy/schema/ if missing
    --show N           print the first N errors (default 5)

Needs the jsonschema package:  pip install jsonschema   (e.g. in ~/.venvs/htf)
"""
import argparse
import collections
import csv
import json
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_URL = "https://ref.gs1.org/standards/epcis/2.0.1/epcis-json-schema.json"
DEFAULT_SCHEMA = os.path.join(ROOT, "deploy", "schema", "epcis-json-schema.json")
PSEUDONYM = re.compile(r"^urn:htf:pseudonym:[0-9a-f]{64}$")
SGLN = re.compile(r"^urn:epc:id:sgln:\d+\.\d{5}\.[\w-]+$")


def load_schema(path):
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        print(f"downloading the EPCIS 2.0 JSON Schema from {SCHEMA_URL} ...")
        try:
            with urllib.request.urlopen(SCHEMA_URL, timeout=60) as r:
                data = r.read()
        except OSError as e:
            # Python from python.org on macOS does not use the system certificates; curl does
            print(f"  Python could not download it ({getattr(e, 'reason', e)}); trying curl ...")
            try:
                data = subprocess.run(["curl", "-fsSL", "--max-time", "60", SCHEMA_URL],
                                      check=True, capture_output=True).stdout
            except (OSError, subprocess.CalledProcessError) as e2:
                sys.exit(f"could not download the schema ({e2}). Download it by hand:\n"
                         f"  curl -fsSL -o '{path}' {SCHEMA_URL}")
        with open(path, "wb") as f:
            f.write(data)
    with open(path) as f:
        return json.load(f)


def load_documents(args):
    if args.documents:
        with open(args.documents) as f:
            return json.load(f)
    with urllib.request.urlopen(f"{args.tms.rstrip('/')}/documents?day={args.day}", timeout=300) as r:
        return json.load(r)


def htf_checks(doc):
    """What the JSON Schema does not check: CBV steps, pseudonym only, SGLN read point, pair of events."""
    problems = []
    events = doc.get("epcisBody", {}).get("eventList", [])
    steps = sorted(e.get("bizStep", "") for e in events)
    if steps != ["arriving", "departing"]:
        problems.append(f"events {steps}, expected one arriving and one departing")
    for e in events:
        for epc in e.get("epcList", []):
            if not PSEUDONYM.match(epc):
                problems.append(f"epcList holds {epc[:40]}..., not a pseudonym")
        rp = e.get("readPoint", {}).get("id", "")
        if not SGLN.match(rp):
            problems.append(f"readPoint {rp} is not an SGLN")
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tms", default=os.environ.get("HTF_TMS_URL", "http://localhost:8080"))
    ap.add_argument("--documents")
    ap.add_argument("--day", type=int, default=20)
    ap.add_argument("--schema", default=DEFAULT_SCHEMA)
    ap.add_argument("--label", default=time.strftime("run-%Y%m%d-%H%M%S"))
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "metrics.csv"))
    ap.add_argument("--show", type=int, default=5)
    args = ap.parse_args()

    try:
        import jsonschema
    except ImportError:
        sys.exit("validate-epcis.py needs the jsonschema package: pip install jsonschema")

    schema = load_schema(args.schema)
    validator = jsonschema.Draft7Validator(schema)
    docs = load_documents(args)
    if not docs:
        sys.exit(f"no EPCIS documents stored for day {args.day}")

    invalid = htf_invalid = 0
    errors = collections.Counter()
    shown = 0
    sizes = []
    events = 0
    for doc in docs:
        sizes.append(len(json.dumps(doc, separators=(",", ":")).encode()))
        events += len(doc.get("epcisBody", {}).get("eventList", []))
        errs = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
        if errs:
            invalid += 1
            for e in errs:
                errors[f"{'/'.join(map(str, e.absolute_path)) or '(document)'}: {e.message[:120]}"] += 1
            if shown < args.show:
                print(f"invalid document (Fog visit of {doc['epcisBody']['eventList'][0].get('eventTime', '?')}): "
                      f"{errs[0].message[:200]}")
                shown += 1
        problems = htf_checks(doc)
        if problems:
            htf_invalid += 1
            if shown < args.show:
                print("HTF check failed:", "; ".join(problems))
                shown += 1

    n = len(docs)
    rows = [
        ("epcis_documents", n, "count"),
        ("epcis_events", events, "count"),
        ("epcis_schema_valid", (n - invalid) / n * 100, "% of documents"),
        ("epcis_htf_checks_valid", (n - htf_invalid) / n * 100, "% of documents"),
        ("epcis_document_bytes_mean", statistics.fmean(sizes), "bytes"),
        ("epcis_document_bytes_max", max(sizes), "bytes"),
    ]
    print(f"\n{n} EPCIS documents ({events} events) of day {args.day}, schema {os.path.relpath(args.schema, ROOT)}")
    for _, metric, value, unit in [("city",) + r for r in rows]:
        print(f"  {metric:28} {value:12.1f}  {unit}" if isinstance(value, float) else f"  {metric:28} {value:12}  {unit}")
    if errors:
        print("\nMost frequent schema errors:")
        for msg, k in errors.most_common(10):
            print(f"  {k:6} x {msg}")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    new = not os.path.exists(args.out) or os.path.getsize(args.out) == 0
    with open(args.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["run", "scope", "metric", "value", "unit"])
        for metric, value, unit in rows:
            w.writerow([args.label, "city", metric, round(value, 3), unit])
    print(f"\n{len(rows)} rows appended to {os.path.relpath(args.out)}")


if __name__ == "__main__":
    main()
