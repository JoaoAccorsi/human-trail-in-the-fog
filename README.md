# HTF — Human Trail in the Fog

Rebuilds a person's digital trail across the Fog nodes of a smart city, with stateful handover
between Fog nodes, GS1 EPCIS 2.0 events and a decentralized directory (Chord ring) of pseudonyms.

![HTF architecture](docs/img/architecture.png)
<sub>A single tier of peer Fog nodes on a Chord ring. Orange: control plane (handover). Blue: data plane
(EPCIS and queries). Grey: MQTT Edge → Fog. C1–C3: contributions.</sub>

**Contents:** [Overview](#overview) · [Architecture](#architecture) · [How it works](#how-it-works) ·
[APIs](#apis) · [How to run](#how-to-run) · [Containers and AWS](#containers-and-aws)

---

## Overview

A person's phone (**Edge**) crosses a city split among **Fog nodes**. Each node records the person's
**visit** in its area and, when the person moves, hands the person's state to the next node
(**handover**). Closed visits go to the **cloud** as EPCIS events, where the person's **trail** (the
ordered visits of a day) is rebuilt.

![Use case](docs/img/use-case.png)
<sub>A citizen's day across Fog 9, 6, 7 and 4. Vital signs are motivation only; the prototype handles positions.</sub>

![Before and after](docs/img/before-after.png)
<sub>Centralized monitoring (before) and HTF (after).</sub>

| | Contribution | Code |
|---|---|---|
| **C1** | Make-before-break handover decided by a Kalman-filtered RSRP with HOM and TTT | `fog/.../HandoverDecision.java`, `Radio.java`, `Kalman.java`, `FogNode.java` |
| **C2** | Visits as GS1 EPCIS 2.0 documents with store-and-forward; trails rebuilt in the cloud | `fog/.../Epcis.java`, `TmsClient.java`, `cloud/tms/` |
| **C3** | Decentralized directory (OIDA) on a fault-tolerant Chord ring, indexed by pseudonyms | `fog/.../ChordNode.java`, `FogCommunication.java` |

Mobility comes from **YJMob100K** dataset 2: 25,000 users on a 200 × 200 grid of 500 m cells, in
30-minute slots. Each record becomes one ping.

---

## Architecture

| Layer | Component | Folder | Role |
|---|---|---|---|
| Edge | Edge device (Java) | `edge/` | Replays users of the dataset and publishes one ping per slot over MQTT |
| Fog | 16 Fog nodes (Java) | `fog/` | MQTT broker, HTTP API, Chord peer, handover logic, EPCIS adapter |
| Cloud | TMS (Java) | `cloud/tms/` | Receives EPCIS documents (`POST /capture`), stores them, answers trail queries |
| Cloud | Cloud Mobility Platform (Streamlit) | `cloud/mobility-platform/` | Web interface for trails and Fog nodes |
| — | Deployment | `deploy/`, `docker-compose.yml` | Containers and AWS (Terraform) |

Edge → Fog: MQTT to the serving node's broker. Fog ↔ Fog: direct HTTP, nodes found through the ring.
Fog → Cloud: `POST /capture` with EPCIS 2.0.

### The city grid

![City grid](docs/img/city-grid.png)
<sub>16 Fog areas of 50 × 50 cells; Fog N listens on MQTT 1900+N and HTTP 9000+N. Right: user 4005
stays with Fog 2 inside the hysteresis band and makes a single handover at 13:30.</sub>

### The Chord ring (OIDA)

![Chord ring](docs/img/chord-ring.png)
<sub>Every Fog node is a peer. Records: `fog:{id}` → node address; `h:{pseudonym}` → node holding the context.</sub>

---

## How it works

### Handover (make-before-break)

![Sequence: from the Edge to the TMS](docs/img/seq-capture.png)

1. The Edge computes `h = HMAC-SHA256(salt, uid)` and is redirected to the node of its cell.
2. On each ping, node **A** runs the handover decision; otherwise the ping goes into A's open visit.
3. A sends the context to **B** (`/handover/prepare`) and tells the Edge to connect to B.
4. The Edge connects to B (make); B claims `h:{h}` on the ring and confirms (`/handover/commit`).
5. A closes the visit and queues it for the TMS (break).

Without a commit in `HTF_HANDOVER_TIMEOUT_MS`, A cancels and keeps serving the user.

### Handover decision (RSRP + Kalman + TTT)

The dataset has no radio data, so each candidate's RSRP is synthetic,
`RSRP = P0 − 10·n·log10(d/d0) + X_σ`, smoothed by a Kalman filter that migrates with the user.
As in 5G event A3, the handover happens when `RSRP_B > RSRP_A + HOM` holds for the TTT.

| Parameter | Value | Where |
|---|---|---|
| P0, d0, n, σ | −40 dBm, 0.5 km, 3.5, 4 dB | `fog/.../Config.java` |
| Kalman Q / R | 1 dB² / σ² | `fog/.../Config.java` |
| HOM / TTT | 3 dB / 60 min (2 pings) | `HTF_HOM_DB` / `HTF_TTT_MIN` |
| Policy | `rsrp` or `area` (baseline) | `HTF_HANDOVER` |

### GS1 EPCIS 2.0 events

Each closed visit becomes one EPCIS 2.0 document (JSON-LD) with two `ObjectEvent`s:

| Field | arriving | departing |
|---|---|---|
| `eventTime` | arrival | departure |
| `epcList` (what) | `urn:htf:pseudonym:{h}` | same |
| `readPoint` (Fog node) | `urn:epc:id:sgln:0614141.{fog:05}.0` | same |
| `bizLocation` (cell) | first cell | last cell |
| `bizStep` (why) | `arriving` | `departing` |

The TMS validates each document and ignores resends (key: pseudonym, day, node, arrival).

<details><summary>Example: departing event of user 4005 at Fog 2</summary>

```json
{
  "type": "ObjectEvent",
  "eventTime": "2026-01-21T13:00:00+09:00",
  "eventTimeZoneOffset": "+09:00",
  "epcList": ["urn:htf:pseudonym:8f177d9fdd3ccc3df3a94581576a8a99f0b2b04ef9964cbe32734374bbbc8688"],
  "action": "OBSERVE",
  "bizStep": "departing",
  "readPoint": {"id": "urn:epc:id:sgln:0614141.00002.0"},
  "bizLocation": {"id": "urn:epc:id:sgln:0614141.00002.c118-29"},
  "htf:day": 20, "htf:slot": 26, "htf:pings": 15, "htf:reason": "handover", "htf:nextFog": 3
}
```
</details>

### Rebuilding trails in the cloud

![Sequence: queries of the Cloud Mobility Platform](docs/img/seq-queries.png)

- **User trail:** the platform computes `h` from uid and salt and asks the TMS for the trail.
- **People at a Fog node:** pseudonyms that passed through a node and their trails.
- **Whole city:** all visits of a day, with people and handovers per node.

---

## APIs

### MQTT topics

| Topic | From → To | Content |
|---|---|---|
| `htf/pings` | Edge → Fog | `{h, day, t, time, x, y, seq}`; `{type: "end"}` at the end of the day |
| `htf/edge/{h}/control` | Fog → Edge | `{type: "handover", fog, broker}` |

### Fog node HTTP API (port 9000 + N)

| Endpoint | Purpose |
|---|---|
| `/chord/info`, `/chord/closest`, `/chord/notify` | Ring information, lookup step, stabilization |
| `/dht/put`, `/dht/get`, `/dht/bulk`, `/dht/claim`, `/dht/claims` | Ring records; conditional ownership writes |
| `/handover/{prepare,commit,cancel,ping,transfer,release}` | Handover and context transfer |
| `/ring/changed` | Republish owner records lost with a failed node |
| `/status`, `/admin/crash` | Debugging; simulated crash |

### TMS HTTP API (port 8080)

| Endpoint | Purpose |
|---|---|
| `POST /capture` | Receives EPCIS documents (201 new, 200 repeated, 400 invalid) |
| `GET /trail/{h}?day=20`, `/events/{h}?day=20` | Trail and EPCIS events of a pseudonym |
| `GET /visits?day=20[&fog=6]`, `/fogs?day=20` | Visits of a node or the whole city; nodes with visits |
| `GET /documents`, `/stats`, `/health` | Stored documents, capture counters, health |
| `POST /admin/clean`, `/admin/outage?seconds=150` | Clean the data; simulated outage (S2) |

### TMS database (PostgreSQL + PostGIS)

| Table | Content |
|---|---|
| `visits` | One row per visit, with the visited cells as a PostGIS `MultiPoint` |
| `epcis_events` | The EPCIS document of each visit (`jsonb`) |

Key `(h, day, fog, arrival_t)` deduplicates resends; tables are `PARTITION BY HASH (fog)`.
Local: `docker compose up -d postgres`. AWS: Amazon RDS.

---

## How to run

### Prerequisites

JDK 21, Maven, Python 3.11+ and Docker Desktop. Create the platform environment:

```bash
python3 -m venv ~/.venvs/htf && source ~/.venvs/htf/bin/activate
pip install -r cloud/mobility-platform/requirements.txt
```

The dataset must be at `YJMob100K/yjmob100k-dataset2.csv`.

### Step by step

One terminal per service, in this order:

```bash
docker compose up -d postgres                # 0. database
cd cloud/tms && mvn -q compile exec:exec     # 1. TMS ("[TMS] listening")
cd fog && mvn -q compile exec:exec           # 2. 16 Fog nodes ("you can run the Edge now")
cd edge && mvn -q compile exec:exec          # 3. Edge (user 4005, day 20 by default)
deploy/run-users.sh 4000-5000 20            #    or many users at once
cd cloud/mobility-platform && streamlit run mobility_platform.py   # 4. http://localhost:8501
```

Expected: user 4005 on day 20 makes **1 handover** (Fog 2 → Fog 3 at 13:30). Another user:
`HTF_USER=4000 mvn -q compile exec:exec`. Fog nodes in separate processes: `HTF_FOG_IDS=1` and
`HTF_FOG_IDS=2-16`.

### Evaluation

Each Fog node writes `fog/metrics/fog-{id}.csv`; `deploy/run-users.sh` then calls
`deploy/metrics.py`, which appends the run's metrics to `results/metrics.csv`.
`deploy/validate-epcis.py` checks the documents against the EPCIS 2.0 JSON Schema and
`deploy/bench-queries.py` times the TMS queries.

| Scenario | Varied parameter | Command |
|---|---|---|
| S1 (C1) | Area vs. HTF | `CLEAN=1 LABEL=s1 deploy/run-users.sh` (`HTF_HANDOVER=area` on the Fog for the baseline) |
| S2 (C2) | TMS down for 150 s | `CLEAN=1 OUTAGE_S=150 LABEL=s2 deploy/run-users.sh` |
| S3 (C3) | Crash of Fog 7 | `CLEAN=1 CRASH_FOG=7 LABEL=s3 deploy/run-users.sh` (`HTF_COVERAGE=off` for the baseline) |
| S4 | 1k, 5k, 25k users | `deploy/run-users.sh` with the user range, or on AWS |

### Useful commands

```bash
curl -X POST localhost:8080/admin/clean      # delete all visits (docker compose down -v: delete the volume)
curl localhost:9008/status                   # Fog 8 on the ring
curl 'localhost:8080/visits?fog=6&day=20'    # who passed through Fog 6
curl "localhost:8080/trail/$H?day=20"        # trail of pseudonym $H
```

---

## Containers and AWS

Each component has a `Dockerfile` and reads `HTF_*` environment variables.

- **Local:** `deploy/extract-users.sh 4005 && docker compose up --build` (http://localhost:8501).
- **AWS:** one EC2 instance per node, created with Terraform — see [deploy/aws/README.md](deploy/aws/README.md).

![HTF on AWS](docs/img/aws-deployment.png)
<sub>One EC2 instance per node, Amazon RDS for the TMS database, one Edge instance per group of users.</sub>

| Variable | Component | Meaning (default) |
|---|---|---|
| `HTF_FOG_IDS`, `HTF_FOG_HOST`, `HTF_FOG_SEED` | Fog | Nodes to start (all), host (`localhost`), seed (`http://localhost:9001`) |
| `HTF_NUMBER_OF_FOGS` / `HTF_FOG_AREA_SIDE` | Fog | Number of nodes (16) or area side in cells (50) |
| `HTF_HANDOVER`, `HTF_HOM_DB`, `HTF_TTT_MIN` | Fog | Policy (`rsrp`), HOM (3 dB), TTT (60 min) |
| `HTF_HANDOVER_TIMEOUT_MS` | Fog | Abort a handover without commit (10000 ms) |
| `HTF_COVERAGE` | Fog | Neighbors cover a failed node's area (`on`) |
| `HTF_FOG_LANES` | Fog | Processing lanes per node (8) |
| `HTF_TMS_BATCH_MAX`, `HTF_TMS_BATCH_WINDOW_MS` | Fog | Visits per document (500), flush period (1000 ms) |
| `HTF_FOG_DATA`, `HTF_METRICS_DIR` | Fog | Store-and-forward queue and metrics folders |
| `HTF_TMS_URL` | Fog, platform | TMS address (`http://localhost:8080`) |
| `HTF_TMS_DB_URL`, `HTF_TMS_DB_USER`, `HTF_TMS_DB_PASSWORD` | TMS | Database (`localhost:5432/htf`, `htf`, `htf`) |
| `HTF_USER`, `HTF_USERS`, `HTF_DAY`, `HTF_DATASET` | Edge | User (4005), several users, day (20), dataset |
| `HTF_PING_INTERVAL_MS` | Edge | Real time per slot (1000 ms) |
| `HTF_SALT` | Edge, platform | Pseudonym secret (`htf-dev-salt`) |
| `HTF_CMP_USER`, `HTF_CMP_PASSWORD` | Platform | Login (`htf` / `htf`) |
