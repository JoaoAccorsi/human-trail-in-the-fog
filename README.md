# HTF — Human Trail in the Fog

Rebuilding a person's digital trail through the Fog nodes of a smart city, with stateful
handover between Fog nodes, GS1 EPCIS 2.0 traceability events and a decentralized directory
(Chord ring) that stores pseudonyms only.

Undergraduate thesis (TCC) — Computer Science, UNISINOS (2026) · João Vítor Accorsi · Advisor: Dr. Rodrigo da Rosa Righi

![HTF architecture](docs/img/architecture.png)
<sub>Architecture of the prototype. Orange: control plane (handover and context migration between Fog
nodes). Blue: data plane (EPCIS documents sent to the cloud and queries). Dashed green: Chord ring
(OIDA) among the Fog nodes. Dashed grey: MQTT between each Edge and the Fog node serving it.
C1–C3 mark the three contributions.</sub>

**Contents:** [Overview](#overview) · [Architecture](#architecture) · [How it works](#how-it-works) ·
[APIs](#apis) · [How to run](#how-to-run) · [Containers and AWS](#containers-and-aws) ·
[Limitations](#limitations-of-the-prototype)

---

## Overview

A person carries a phone (the **Edge** device) across a city whose area is split among **Fog nodes**.
Each Fog node serves the devices in its area and records the person's **visit** there. When the
person moves to another area, the Fog node hands the person's state over to the next one
(**handover**), and each closed visit goes to the **cloud** as a standardized event. In the
cloud, an authorized user rebuilds the person's **trail** (the ordered visits of a day).

![Use case](docs/img/use-case.png)
<sub>Motivating use case: a monitored person crosses the areas of several Fog nodes during the day;
each Fog node records a piece of the trail and the cloud joins them. The vital signs are part of the
motivation only; the prototype handles positions.</sub>

![Before and after](docs/img/before-after.png)
<sub>Centralized monitoring (before) and HTF (after): decisions near the person, context migration
between Fog nodes, a decentralized directory and a cloud that only sees pseudonyms.</sub>

The three contributions and where they live in the code:

| | Contribution | Implementation |
|---|---|---|
| **C1** | Stateful make-before-break handover between Fog nodes, decided by a synthetic RSRP smoothed by a Kalman filter, with hysteresis (HOM) and Time to Trigger (TTT) | `fog/.../HandoverDecision.java`, `Radio.java`, `Kalman.java`, `FogNode.java` |
| **C2** | Visits described as GS1 EPCIS 2.0 documents, with store-and-forward, and trails rebuilt in the cloud | `fog/.../Epcis.java`, `TmsClient.java`, `cloud/tms/` |
| **C3** | Decentralized directory (OIDA) on a fault-tolerant Chord ring, indexed by salted pseudonyms | `fog/.../ChordNode.java`, `FogCommunication.java` |

Human mobility comes from the **YJMob100K** dataset 2: anonymized trajectories of 25,000 users on a
grid of 200 × 200 cells of 500 m, in 30-minute timeslots, over 75 days. Each record of a user
becomes one ping of the Edge.

---

## Architecture

| Layer | Component | Language | Folder | Role |
|---|---|---|---|---|
| Edge | Edge device | Java | `edge/` | Replays one user/day of the dataset: computes the pseudonym, publishes one ping per timeslot over MQTT and switches broker when the Fog node says so |
| Fog | 16 Fog nodes | Java | `fog/` | Each node: embedded MQTT broker, HTTP API, Chord ring peer, handover logic and EPCIS adapter |
| Cloud | TMS — Trail Management Service | Java | `cloud/tms/` | Receives the EPCIS documents (`POST /capture`), stores them and answers trail queries |
| Cloud | CMP — Cloud Mobility Platform | Python (Streamlit) | `cloud/mobility-platform/` | Web interface: rebuilds a user's trail and shows who passed through a Fog node |
| — | Deployment | Docker, Terraform | `deploy/`, `docker-compose.yml` | Containers and the AWS environment (one EC2 instance per node) |

- **Edge → Fog: MQTT, with a broker in each Fog node.** The Edge connects to the broker of the Fog
  node serving it, like a phone to its base station, and switches broker on handover.
- **Fog ↔ Fog: direct (P2P) HTTP.** The nodes find each other and locate a user's context through
  the Chord ring; the context goes straight from one node to the other.
- **Fog → Cloud: HTTP `POST /capture`** with GS1 EPCIS 2.0 documents and store-and-forward.
- A **visit** is a user's stay in one Fog node's area (arrival, departure, cells, pings and how it
  ended: handover or end of day). A **trail** is the ordered list of visits of a day.

### The city on the cartesian plane

![City grid](docs/img/city-grid.png)
<sub>Left: the 200 × 200 cells split into 16 Fog areas of 50 × 50 cells (25 × 25 km); Fog N is at row
(N−1)/4 and column (N−1)%4 counted from the bottom-left, with MQTT broker on port 1900+N and HTTP
API on 9000+N. Fog 1 is the entry node of every Edge and the seed of the ring. Right: user 4005 on
day 20 near the Fog 2 | Fog 3 border; the hysteresis band keeps the user in the serving node, so
there is a single handover (13:30) instead of a back-and-forth.</sub>

### Inside a Fog node

![Fog node](docs/img/fog-node.png)
<sub>Every Fog node is the same object with its own ports. All decisions run on a single logic
thread, so the state needs no locks; HTTP handlers only enqueue work or answer from local data.</sub>

### The Chord ring (OIDA)

![Chord ring](docs/img/chord-ring.png)
<sub>The ring is not an extra node: each Fog node is a peer. It stores two kinds of records:
`fog:{id}` → address of a Fog node, and `h:{pseudonym}` → Fog node holding the user's context.</sub>

---

## How it works

### Data path and make-before-break handover

![Sequence: from the Edge to the TMS](docs/img/seq-capture.png)

1. The Edge computes `h = HMAC-SHA256(salt, uid)` and enters through Fog 1, which redirects it to
   the Fog node that owns its cell.
2. On every ping, the serving node **A** runs the handover decision (below). While there is no
   handover, the ping is recorded in A's open visit.
3. When the decision picks node **B**, A finds B's address on the ring, sends it the user's
   context (`/handover/prepare`) and only then tells the Edge to connect to B's broker.
4. The Edge connects to B **before** leaving A (make). B activates the context, registers
   `h:{h} → B` on the ring and confirms to A (`/handover/commit`).
5. A closes its visit and sends it to the TMS as an EPCIS document (break). Pings that still reach
   A during the transition are relayed to B.

If a user reappears in another node without a handover (e.g. after hours offline), that node looks
up `h:{h}` on the ring and pulls the context from the node holding it (`/handover/transfer`).

**A single owner per user.** The `h:{h}` record also stores the `seq` of the owner's most recent
ping and is written with a conditional put (`/dht/claim`): the node that stores the key keeps the
record with the most recent ping, so two nodes claiming the same user always agree on one winner.

- A node that does not serve the user (e.g. the entry node) redirects it to the **owner** of the
  record, and only falls back to the node of the cell when the user has no owner.
- A node that loses a claim closes its visit and drops the context; a node that wins over an
  unexpected owner tells it to do the same (`/handover/release`).
- A ping older than the owner's record (a late relay) is forwarded to the owner instead of
  starting a new stay, and an open visit is never extended after the node lost the user: it is
  closed (`context_lost`) before a new stay starts.
- At the end of the day the record is released (`fog = 0`).

**Aborted handover.** If B does not confirm (`/handover/commit`) within `HTF_HANDOVER_TIMEOUT_MS`,
or a relay to B fails, A aborts: it cancels the PREPARE on B (`/handover/cancel`) and keeps serving
the user. An Edge that cannot reach B stays on A; an Edge whose broker stops answering goes back to
the entry broker, which redirects it to the owner of its record.

### Handover decision (RSRP + Kalman + TTT)

YJMob100K has no radio measurements, so the serving node computes a **synthetic RSRP** for each
candidate node (itself, its 8 neighbors and the owner of the current cell) with the log-distance
path loss model `RSRP = P0 − 10·n·log10(d/d0) + X_σ`, where `d` is the distance from the user's cell
to the center of the candidate's area. Each candidate's RSRP goes through a one-dimensional
**Kalman filter** whose state is part of the user context (it migrates with the user). Following
the A3 event of 5G, the handover to the best neighbor B happens only when
`RSRP_B > RSRP_A + HOM` holds for the **TTT**; this removes the ping-pong effect near borders.

| Parameter | Value | Where |
|---|---|---|
| P0 (at d0 = 0.5 km, one cell) | −40 dBm | `fog/.../Config.java` |
| Path loss exponent n | 3.5 | `fog/.../Config.java` |
| Shadowing σ | 4 dB, seeded by pseudonym, ping and node (runs are reproducible) | `fog/.../Config.java` |
| Kalman Q / R | 1 dB² / σ² | `fog/.../Config.java` |
| HOM | 3 dB | env `HTF_HOM_DB` |
| TTT | 60 min of dataset time (2 consecutive pings) | env `HTF_TTT_MIN` |
| Policy | `rsrp` (default) or `area` (baseline: handover as soon as the user leaves the area) | env `HTF_HANDOVER` |

With these values, between two neighbors the switch happens about 1.2 km past the border
(`d_A / d_B > 10^(HOM/10n)`), which creates the hysteresis band shown in the city figure.

### GS1 EPCIS 2.0 events

When a visit closes, the node sends the TMS one EPCIS 2.0 document (JSON-LD, `application/ld+json`)
with two `ObjectEvent`s:

| EPCIS field | arriving | departing |
|---|---|---|
| `eventTime` | arrival (day 0 = 2026-01-01, offset +09:00) | departure |
| `epcList` (what) | `urn:htf:pseudonym:{h}` (the uid never leaves the Edge) | same |
| `readPoint` (where: Fog node) | `urn:epc:id:sgln:0614141.{fog:05}.0` | same |
| `bizLocation` (where: cell) | `urn:epc:id:sgln:0614141.{fog:05}.c{x}-{y}` (first cell) | last cell |
| `bizStep` (why) | `arriving` | `departing` |
| `action` | `OBSERVE` | `OBSERVE` |
| `htf:*` extensions | day, slot, fogAreaSide | + arrivalSlot, cells, pings, reason, nextFog |

`0614141` is the example GS1 Company Prefix of the GS1 standards. The TMS validates each document
(400 if invalid), keeps it as received in the table `epcis_events` and derives the visit in the table
`visits` of a PostgreSQL + PostGIS database; repeated documents are ignored (key: pseudonym, day,
node, arrival — `INSERT ... ON CONFLICT DO NOTHING`).

<details><summary>Example: the departing event of user 4005 at Fog 2 (day 20)</summary>

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
  "htf:day": 20, "htf:slot": 26, "htf:fogAreaSide": 50, "htf:arrivalSlot": 0,
  "htf:cells": [[98,24],[99,24],[98,24],[99,24],[98,24],[99,24],[96,24],[98,24],[106,26],[118,29]],
  "htf:pings": 15, "htf:reason": "handover", "htf:nextFog": 3
}
```
</details>

### Rebuilding trails in the cloud

![Sequence: queries of the Cloud Mobility Platform](docs/img/seq-queries.png)

- **User trail:** the authorized user enters uid, day and salt; the platform computes `h` locally and
  asks the TMS for the trail of `h`. It shows a map, a timeline, the visits and the EPCIS events.
- **People at a Fog node:** the platform lists the nodes with visits on a day, then the pseudonyms
  that passed through the chosen node and their trails (where each person came from and went to).
- **Whole city:** every visit of the day in one query; the map shades each Fog area by how many
  people it served and draws trails on top, a table lists people, visits, pings and handovers per
  node, and a timeline shows which node served each sampled person over the day.

In the last two queries the charts show a **random sample** of the people ("People on the charts",
10 by default, a new sample on each query; the metrics always count everyone). The map legend sits
outside the axes (and is left out above 40 people, when the presence chart beside it names everyone).

### Privacy

Only the pseudonym `h` travels: Fog nodes, the ring and the TMS never see the uid, and the TMS has
neither the salt nor any identity ("blind" cloud). The platform recomputes `h` on the client for an
authorized user. The "people at a Fog node" view uses no uid or salt, but a full-day trail may still
re-identify someone (home and work), so such queries must be restricted to authorized profiles.

---

## APIs

### MQTT topics (on each Fog node's broker)

| Topic | From → To | Content |
|---|---|---|
| `htf/pings` | Edge → Fog | One ping per timeslot `{h, day, t, time, x, y, seq}`; at the end of the day `{type: "end"}` |
| `htf/edge/{h}/control` | Fog → Edge | Handover command `{type: "handover", fog: 3, broker: "tcp://host:1903"}` |

### HTTP API of each Fog node (port 9000 + N)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/chord/info` | Node, successor and predecessor on the ring |
| `POST` | `/chord/closest` | One lookup step: closest node to the key in the finger table |
| `POST` | `/chord/notify` | "May I be your predecessor?" (ring stabilization) |
| `POST` / `GET` | `/dht/put` · `/dht/get?key=` · `/dht/bulk` | Store, read and migrate ring records |
| `POST` | `/dht/claim` | Conditional put of an ownership record: the most recent ping wins |
| `POST` | `/handover/prepare` | Node A hands the user's context to node B before the user arrives |
| `POST` | `/handover/ping` | Relay of a ping that still reached the old node (or the entry node) |
| `POST` | `/handover/commit` | Node B reports that the user arrived; node A closes its visit |
| `POST` | `/handover/transfer` | A node asks for the context of a user who reappeared there |
| `POST` | `/handover/release` | Another node now owns the user: close the visit and drop the context |
| `POST` | `/handover/cancel` | The origin aborted the handover (no COMMIT in time): drop the PREPARE |
| `POST` | `/ring/changed` | Broadcast: keys `(from, to]` were lost with a failed node; republish the owner records in that range and forward to the fingers in `(me, limit)` |
| `POST` | `/dht/claims` | Conditional put of many ownership records stored on this node (one request per node) |
| `GET` | `/status` | Successor list, predecessor and finger table (debugging) |
| `POST` | `/admin/crash` | Simulates a crash (stops broker, API and timers without warning anyone) |

### TMS HTTP API (port 8080)

| Method | Endpoint | Called by | Purpose |
|---|---|---|---|
| `POST` | `/capture` | Fog | Receives a GS1 EPCIS 2.0 document with one visit or a batch (201 some new, 200 all repeated, 400 invalid) |
| `GET` | `/events/{h}?day=20[&from=07:00&to=19:00][&toDay=21]` | CMP | EPCIS events of pseudonym `h` in the period, in time order |
| `GET` | `/trail/{h}?day=20[&from=07:00&to=19:00][&toDay=21]` | CMP | Trail of `h` in the period: visits that overlap it, in arrival order |
| `GET` | `/visits?day=20[&fog=6]` | CMP | Visits recorded on day 20 by Fog 6, or by every Fog node (whole city) without `fog` |
| `GET` | `/fogs?day=20` | CMP | Fog nodes with visits on the day: `[{fog, people, visits}]` |
| `GET` | `/health` | anyone | Is the TMS up? |
| `GET` | `/documents?day=20` | `validate-epcis.py` | EPCIS documents stored for the day (one per visit, as received) |
| `GET` | `/stats` | `bench-queries.py` | Captures since the start or the last clean: requests, visits new / repeated (resends discarded) / rejected, refused during an outage |
| `POST` | `/admin/clean` | scripts | Deletes every visit and EPCIS document (`CLEAN=1` of the run scripts) |
| `POST` | `/admin/outage?seconds=30` | `tms-outage.sh` | Simulated outage: `/capture` answers 503 for 30 s (scenario C2) |

### TMS database (PostgreSQL + PostGIS)

The TMS creates the schema at startup (`cloud/tms/src/main/resources/schema.sql`):

| Table | Content |
|---|---|
| `visits` | One row per visit: `h`, `day`, `fog`, arrival and departure slots, pings, reason, next Fog, and the visited cells as a PostGIS `MultiPoint` (Cartesian plane of the 200×200 grid, SRID 0) |
| `epcis_events` | The EPCIS document of each visit (`jsonb`), as received |

- **Key** `(h, day, fog, arrival_t)` in both tables: deduplicates the resends of the store-and-forward
  queue, and its prefix `(h, day)` serves the trail queries; an index on `(fog, day)` serves the
  queries by Fog node and a GiST index the spatial queries on the cells.
- **Partitioned by region:** `PARTITION BY HASH (fog)` in 4 partitions; a query for one Fog node reads
  one partition, and the same column would be the distribution key of a sharded deployment (Citus).
- The queries return the same JSON the Fog sent, so the platform does not depend on the storage. The
  index on the cells allows, for example, "who crossed this region of the city on day 20":

```sql
SELECT DISTINCT h FROM visits
WHERE day = 20 AND ST_Intersects(cells, ST_MakeEnvelope(80, 80, 100, 100, 0));
```

When the database fails, `/capture` answers 503 and the Fog nodes keep the visits in their
store-and-forward queue until it is back. Local: `docker compose up -d postgres` (user, password and
database `htf`; `psql -h localhost -U htf htf`). AWS: Amazon RDS for PostgreSQL.

---

## How to run

### Prerequisites (once)

| Tool | Check | Install (macOS, Apple Silicon) |
|---|---|---|
| JDK 21 | `java -version` | `brew install openjdk@21` |
| Maven | `mvn -v` | `brew install maven` |
| Python 3.11+ | `python3 --version` | `brew install python` |
| Docker Desktop (database) | `docker --version` | `brew install --cask docker` |

No Mosquitto is needed: each Fog node ships its own embedded broker (Moquette). Create the platform's
Python environment **outside iCloud** (inside iCloud Drive Python becomes very slow):

```bash
python3 -m venv ~/.venvs/htf
source ~/.venvs/htf/bin/activate
pip install -r cloud/mobility-platform/requirements.txt
```

The dataset must be at `YJMob100K/yjmob100k-dataset2.csv`. If the project is in iCloud, mark the
project folder as **Keep Downloaded** in Finder.

### Step by step

From the project root, one terminal per service, **in this order**:

```bash
docker compose up -d postgres                # 0. database (PostgreSQL + PostGIS), once: it keeps running
cd cloud/tms && mvn -q compile exec:exec     # 1. TMS: wait for "[TMS] listening on http://localhost:8080"
cd fog && mvn -q compile exec:exec           # 2. the 16 Fog nodes: wait for "you can run the Edge now"
cd edge && mvn -q compile exec:exec          # 3. Edge: replays the user/day and exits
deploy/run-users.sh 4000-5000 20            #    or: 1,001 users at once (one Edge process, summary at the end)
source ~/.venvs/htf/bin/activate
cd cloud/mobility-platform && streamlit run mobility_platform.py   # 4. platform: http://localhost:8501
```

Always use `exec:exec` (not `exec:java`). The Fog terminal shows the entry through Fog 1, the ring
lookups, the handover decisions and the documents sent to the TMS; the Edge terminal shows the
broker switches. By default the Edge replays **user 4005 on day 20**; another user or day:
`HTF_USER=4000 HTF_DAY=20 mvn -q compile exec:exec` (TMS and Fog keep running).

### Expected result

- **User 4005, day 20** (city figure): 27 pings, Fog 2 → Fog 3, **1 handover** at 13:30.
- **User 4000, day 20:** 13 pings, Fog 8 → Fog 4, **1 handover** with the RSRP policy; with
  `HTF_HANDOVER=area` the same user makes **4 handovers** back and forth along the border (y = 50).

```
[Fog  1] user 0fe28881... outside my area -> redirected to Fog 8
[Fog  8] ping 09:00 | cell (181,51) | RSRP Fog 8 -96.2 dBm, best neighbor Fog 4 -91.1 dBm | > HOM for 30/60 min
[Fog  8] ping 09:30 | cell (174,49) | RSRP Fog 8 -94.0 dBm, best neighbor Fog 4 -91.3 dBm
[Fog  8] ping 10:00 | cell (165,45) | RSRP Fog 8 -94.3 dBm, best neighbor Fog 4 -88.6 dBm | > HOM for 30/60 min
[Fog  8] ping 10:30 | cell (165,45) | RSRP Fog 8 -93.6 dBm, best neighbor Fog 4 -88.9 dBm | > HOM for 60/60 min -> HANDOVER to Fog 4
[Fog  4] PREPARE received from Fog 8 for user 0fe28881...
[Fog  4] user 0fe28881... arrived; taking over (handover #1, from Fog 8)
[Fog  8] COMMIT from Fog 4: handover complete; visit 08:30-10:00 -> TMS
[TMS ] POST /capture (EPCIS) Fog 8 08:30-10:00 -> HTTP 201
```

### One Fog node per process

The 16 nodes start in one process by default, but they share nothing. To start them separately
(Fog 1 is the seed of the ring; the others keep retrying until it is up):

```bash
cd fog && HTF_FOG_IDS=1 mvn -q compile exec:exec        # terminal A
cd fog && HTF_FOG_IDS=2-16 mvn -q compile exec:exec     # terminal B (or one per node: HTF_FOG_IDS=8)
```

### Fog node failure

The ring follows the Chord protocol (Stoica et al., 2001) and repairs itself:

- **Successor list:** each node keeps its next 3 nodes, copied from its successor every 500 ms; the
  ring survives up to 2 consecutive nodes failing at once.
- **Failure detection:** a node is down when its port refuses the connection, or when it does not
  answer within 1.5 s twice in a row; it is removed from the successor list and the finger table.
- **Predecessor check:** every 500 ms; a dead predecessor is cleared and the next node that notifies
  takes its place.
- **Stale addresses:** each node republishes `fog:{id}` every 3 s; a record older than 10 s is ignored.

```bash
curl -X POST localhost:9008/admin/crash      # Fog 8 sits between Fog 6 and Fog 15 on the ring
```

```
[Fog  8] SIMULATED CRASH: broker, HTTP API and timers stopped without telling anyone
[Fog  6] Chord: successor Fog 8 is down -> new successor Fog 15
[Fog 15] Chord: predecessor Fog 8 is down -> waiting for a new one to notify
[Fog 15] Chord: new predecessor Fog 6 (ring repaired)
```

```mermaid
sequenceDiagram
    autonumber
    participant F6 as Fog 6 (predecessor)
    participant F8 as Fog 8 (down)
    participant F15 as Fog 15 (successor)
    Note over F8: crashes (no warning)
    F6-xF8: stabilize: GET /chord/info (refused / timeout ×2)
    F6->>F6: successor list [8, 15, 12] → [15, 12, 4]
    F15-xF8: check_predecessor (refused / timeout ×2)
    F15->>F15: predecessor = none
    F6->>F15: POST /chord/notify
    F15->>F15: predecessor = Fog 6 — ring repaired
```

**Batches to the TMS.** Visits are not posted one by one: each Fog node queues them (on disk) and,
every `HTF_TMS_BATCH_WINDOW_MS` or as soon as `HTF_TMS_BATCH_MAX` are ready, sends one EPCIS
document with the events of all of them (an EPCIS document may carry any number of events). The
TMS splits the document into visits, pairing each `arriving` with its `departing` event. Visits
closed at the end of the day wait a random 0..`HTF_END_OF_DAY_JITTER_MS` before leaving, so the
nodes do not all hit the TMS at the same instant.

**Logic lanes.** Each Fog node processes its users on `HTF_FOG_LANES` single-thread lanes chosen by
the pseudonym: one person's pings are always handled in order by the same lane, while different
people run in parallel. A lane that waits on the network (ring lookup, PREPARE, context transfer)
only delays the users of that lane. Visits are sent to the TMS by a separate sender thread, and the
owner-record refresh runs on a background thread, so no user waits for the cloud or for the ring
maintenance.

**64-bit identifiers.** Nodes and keys are placed on the ring by the first 64 bits of their SHA-1
(unsigned longs). With 16 bits (65,536 positions), a ring of 1,000 nodes would already have about 8
pairs of nodes sharing an identifier (birthday paradox, ~N^2 / 2^17); with 64 bits collisions are
negligible even for millions of nodes. The finger table has 64 entries; consecutive fingers that
point to the same node are filled with a single lookup.

**Owner records after a failure.** The `h:{pseudonym}` records stored on the failed node are lost
with it, but they come back. The predecessor of the failed node knows exactly which keys were
there, the range (predecessor, failed node], and **broadcasts** that range over the finger tables
(`/ring/changed`, El-Ansary et al., IPTPS 2003): each node forwards it to its fingers, each finger
being responsible for the interval up to the next one, so every live node receives it exactly once,
with N - 1 messages, depth O(log N) and no lookup of addresses (the `fog:{id}` records of some
nodes may have been lost with the failed node). If a finger does not answer, the next live node
takes its interval. Every node then republishes **only** the records of its users whose key falls in
that range, 1 to 3 s later (random jitter, so the nodes do not write at the same instant), on a
background thread (pings and handovers never wait), with one request per responsible node
(`/dht/claims`). The conditional write recreates the lost records on the node that inherited the
keys and never overwrites a more recent owner. A full republication also runs every
`HTF_OWNER_REFRESH_MS` (5 min) as a safety net.

Cost per failure, for U users and N nodes: about U/N records rewritten (the lost ones), instead of
all U. With 1,000,000 users and 1,000 nodes: ~1,000 writes, about one per node.

**Coverage of the failed node's area** (cell outage compensation, as in cellular networks). Every node
learns that a Fog is down from the same announcement that triggers the owner records refresh (the
predecessor detects the failure in under a second and broadcasts it), or, as a safety net, when its
`fog:{id}` address is no longer refreshed on the ring (10 s; checked in the background every 3 s,
never while a ping is processed). A Fog reported down stays down until it publishes its address
again. From then on, each cell of that area is served by the live neighbor (of the 8 around it) whose area center
is closest to the cell; every node applies the same rule, so they agree without talking. The Edges
of the failed node go back to the entry broker, which redirects them to that neighbor; the neighbor
accepts users outside its own area only while it covers a failed node, and a Fog that is down is
never a handover target. When the node comes back, its users return to it by ordinary handovers
(the RSRP of its area is again the strongest). `HTF_COVERAGE=off` turns this off (the area stays
without service), to compare.

Not recovered in this version: the contexts and open visits in the failed node's memory (the users
start a new context on the neighbor) and the pings of the few seconds until the failure is noticed.
Visits already sent to the TMS are safe.

### Evaluation metrics

Each Fog node writes its measurements to `fog/metrics/fog-{id}.csv`
(`time_ms,fog,event,value_ms,h,detail`):

| Event | What is measured |
|---|---|
| `ping` | Arrival of the ping (MQTT) to the end of its processing: lane queue + handover logic |
| `handover` | On the origin, from the handover decision to the COMMIT (or the abort); `detail` has the target, the result and the PREPARE round trip |
| `tms_delivery` | From the moment a visit may be sent (end-of-day jitter excluded) until the TMS confirms it |
| `tms_request` | One `POST /capture`: round trip, visits in the EPCIS document, its size in bytes and the HTTP status (0 = TMS unreachable) |
| `lookup` | One operation on the OIDA directory (`get`, `put`, `claim` of a user record `h:` or an address `fog:`): time and routing hops on the Chord ring |
| `node_down` | The node detected that its successor on the ring failed |
| `owner_refresh` | Owner records republished after a ring change: time since the change was reported, records restored |
| `crash` | Simulated crash of the node (`/admin/crash`) |
| `cover` | A ping served in the area of a Fog node that is down (coverage by a neighbor) |

`deploy/metrics.py` joins them with the visits stored by the TMS and appends one row per metric to
**`results/metrics.csv`** (`run,scope,metric,value,unit`), for the whole city and for each Fog node:
people, visits, pings stored and expected (ping delivery ratio), Fog handovers, ping-pongs (return
to the previous Fog within 60 min), visit duration, how the visits ended, overlapping visits, load per
Fog (and max/mean imbalance), the count/mean/p50/p95/max of the ping processing, handover, PREPARE
and TMS delivery times, aborted handovers, and, with a failure, the detection time and the owner
records restored. Every run is appended under its own label, so the scenarios can be compared in
one file.

`metrics.py` also computes, with the dataset: the trail rebuilt in the cloud against the original
trajectory (people whose sequence of cells is identical, and pings whose timeslot falls inside a stored
visit); the EPCIS requests (count, failures, visits per request, bytes per visit) and, with a TMS outage,
the delivery time of the visits that waited in the store-and-forward queue; and the hops and time of the
directory lookups, next to the reference ½·log₂N of Chord.

Two more scripts append to the same CSV: `deploy/validate-epcis.py` validates every stored EPCIS document
against the official GS1 EPCIS 2.0 JSON Schema (downloaded once to `deploy/schema/`; needs
`pip install jsonschema`) plus the HTF rules (pseudonym only, SGLN read point, one arriving and one
departing event), and `deploy/bench-queries.py` times the TMS queries of the platform (Fog nodes, whole
city, per Fog, trail of each person, trail in a period, EPCIS events) and reads the capture counters of
the TMS (resends discarded).

**Evaluation scenarios** (the event happens `AT_S` seconds after the first ping reaches the Fog nodes;
by default half the replayed day, 12:00 of the dataset day):

| Scenario | Command | What it shows |
|---|---|---|
| C1 | `CLEAN=1 LABEL=c1-htf deploy/run-users.sh` (and `HTF_HANDOVER=area` on the Fog for the baseline) | Handover decision |
| C2 | `CLEAN=1 LABEL=c2-normal deploy/run-users.sh` | EPCIS conformance, trail fidelity, cost of the events and queries |
| C2 | `CLEAN=1 OUTAGE_S=30 LABEL=c2-tms-down deploy/run-users.sh` | Store-and-forward: the TMS refuses captures for 30 s (`deploy/tms-outage.sh`); no visit lost or duplicated |
| C3 | `CLEAN=1 CRASH_FOG=7 LABEL=c3-crash-fog7 deploy/run-users.sh` | Directory lookups (hops, time) and abrupt crash of Fog 7: detection, owner records restored, coverage of its area by the neighbors, consistency of the trails. Restart the Fog nodes afterwards |
| C3 (baseline) | Fog nodes started with `HTF_COVERAGE=off`, then `CLEAN=1 CRASH_FOG=7 LABEL=c3-crash-fog7-nocover deploy/run-users.sh` | The same crash without coverage: the area of Fog 7 stays without service |

`deploy/run-users.sh` calls them at the end of the run (label: `LABEL=c1-rsrp deploy/run-users.sh`,
default `users-<range>-day<day>`), counting only the measurements made since the run started. Use
`CLEAN=1`, so the TMS data holds only this run (it calls `POST /admin/clean`). By hand (the visits
come from the TMS, `--visits http://localhost:8080` by default):

```bash
python3 deploy/metrics.py --label c1-area --users 4000-5000 --dataset YJMob100K/yjmob100k-dataset2.csv
```

### Stopping and useful commands

`Ctrl+C` in each terminal. The data stays in the database (Docker volume `pgdata`, kept by
`docker compose stop` and `down`). To start from scratch: `curl -X POST localhost:8080/admin/clean`
(or `docker compose down -v`, which deletes the volume).

Data of the old JSONL version of the TMS (`cloud/tms/data/epcis.jsonl`) can be imported once:
`cd cloud/tms && HTF_TMS_IMPORT=data/epcis.jsonl mvn -q compile exec:exec` (visits already stored
are skipped).

```bash
curl localhost:9008/status                   # Fog 8 on the ring: successor list, predecessor, fingers
curl localhost:8080/health                   # is the TMS up?
curl 'localhost:8080/fogs?day=20'            # Fog nodes with visits on day 20
curl 'localhost:8080/visits?fog=6&day=20'    # who passed through Fog 6 on day 20
curl 'localhost:8080/visits?day=20'          # every visit of day 20 (whole city)
curl "localhost:8080/events/$H?day=20"       # EPCIS events of pseudonym $H on day 20
curl "localhost:8080/trail/$H?day=20&from=07:00&to=19:00"   # trail between 07:00 and 19:00
```

---

## Containers and AWS

Each component has a `Dockerfile` (`fog/`, `cloud/tms/`, `cloud/mobility-platform/`, `edge/`) and
reads its settings from `HTF_*` environment variables (the defaults are the local run).

- **Local, with Docker:** `deploy/extract-users.sh 4005 && docker compose up --build`, then open
  http://localhost:8501 (the 16 Fog nodes run in one container).
- **AWS, one EC2 instance per node** (`number_of_fogs` Fog nodes, 16 by default, TMS, platform
  and one Edge instance per group of users, each replaying many users), created with
  Terraform — see [deploy/aws/README.md](deploy/aws/README.md). `deploy/aws/up.sh` creates everything,
  `deploy/aws/crash-fog.sh N` simulates a node failure and `deploy/aws/down.sh` destroys everything.

![HTF on AWS](docs/img/aws-deployment.png)
<sub>The AWS environment created by `deploy/aws/up.sh`: one EC2 instance (one container) per node, fixed private IPs, a single security group, images in ECR, logs in CloudWatch and commands through SSM.</sub>

| Variable | Component | Meaning (default) |
|---|---|---|
| `HTF_FOG_IDS`, `HTF_FOG_HOST`, `HTF_FOG_SEED` | Fog | Nodes to start (all), advertised host (`localhost`), seed URL (`http://localhost:9001`) |
| `HTF_NUMBER_OF_FOGS` | Fog | Number of Fog nodes; the side of each area is computed from it (4, 16, 25, 64, 100, 400...; must be a perfect square whose root divides 200). Default 16 |
| `HTF_FOG_AREA_SIDE` | Fog | Or the side of each node's area in cells; must divide 200 (50 = 16 nodes; 20 = 100 nodes; 10 = 400). Set only one of the two; clean the TMS data when you change it |
| `HTF_TMS_URL` | Fog, platform | TMS address (`http://localhost:8080`) |
| `HTF_TMS_DB_URL`, `HTF_TMS_DB_USER`, `HTF_TMS_DB_PASSWORD` | TMS | Database (`jdbc:postgresql://localhost:5432/htf`, `htf`, `htf`) |
| `HTF_TMS_DB_SSLMODE`, `HTF_TMS_DB_WAIT_S`, `HTF_TMS_DB_POOL` | TMS | TLS to the database (`prefer`; `require` on AWS), how long to wait for it at startup (300 s), connections kept open (8) |
| `HTF_TMS_IMPORT` | TMS | `epcis.jsonl` of the old JSONL version, imported at startup (none) |
| `HTF_HANDOVER`, `HTF_HOM_DB`, `HTF_TTT_MIN` | Fog | Handover policy (`rsrp`), HOM (3 dB), TTT (60 min) |
| `HTF_TMS_BATCH_MAX`, `HTF_TMS_BATCH_WINDOW_MS` | Fog | Visits per EPCIS document sent to the TMS (500) and flush period (1000 ms) |
| `HTF_END_OF_DAY_JITTER_MS` | Fog | Random delay (0..value) before sending the visits closed at the end of the day (5000 ms) |
| `HTF_FOG_LANES` | Fog | Logic lanes per node: users are spread over this many threads by pseudonym; one user's pings stay in order (8) |
| `HTF_OWNER_REFRESH_MS`, `HTF_OWNER_REFRESH_JITTER_MS` | Fog | Full republication of the owner records (300000 ms); random extra wait after a failure (2000 ms) |
| `HTF_COVERAGE`, `HTF_LIVENESS_CACHE_MS` | Fog | Coverage of the area of a failed Fog by its closest live neighbor (`on`); how long a node trusts what it learned about another being up or down (3000 ms) |
| `HTF_HANDOVER_TIMEOUT_MS` | Fog | Abort a handover if the target does not COMMIT in this real time (10000 ms) |
| `HTF_FOG_DATA` | Fog | Folder of the persistent store-and-forward queue, `{dir}/fog-{id}/outbox.jsonl` (`store-and-forward-queue`, inside `fog/`) |
| `HTF_METRICS_DIR` | Fog | Folder of the evaluation measurements, `{dir}/fog-{id}.csv` (`metrics`, inside `fog/`) |
| `HTF_USER`, `HTF_DAY`, `HTF_DATASET` | Edge | Simulated user (4005), day (20), dataset CSV |
| `HTF_USERS`, `HTF_EDGE_THREADS` | Edge | Several users in one process, e.g. `4000-5000` or `4000,4005` (one simulated phone each, shared clock); publishing threads (32) |
| `HTF_ENTRY_BROKER`, `HTF_PING_INTERVAL_MS` | Edge | Entry broker (`tcp://localhost:1901`), real time between pings (1000 ms) |
| `HTF_START_DELAY_S`, `HTF_CONNECT_RETRY_S` | Edge | Wait before starting (0 s), keep retrying the broker for (0 s) |
| `HTF_SALT` | Edge, platform | Pseudonym secret (`htf-dev-salt`) |
| `HTF_CMP_USER`, `HTF_CMP_PASSWORD` | Platform | Login of the Cloud Mobility Platform (`htf` / `htf`) |

---

## Limitations of the prototype

- The TMS uses a single PostgreSQL instance; the visits are partitioned by region (Fog node), but
  spreading the partitions over several servers (e.g. Citus) was not evaluated.
- The store-and-forward queue of each Fog node is persisted on the node's disk; visits that are
  still open (not closed yet) are lost if the node crashes.
- No vital signs are collected: the evaluation uses the positions of YJMob100K.
- All devices share one salt and the pseudonym is fixed; a real deployment would give each device
  only its own `h` (or a per-person secret), rotate pseudonyms daily and authenticate devices on the
  brokers (MQTT over TLS).
- Ring records are not replicated: a record lost with a failed node is unavailable until its owner
  republishes it (about 1 s after the failure is detected). The Fog nodes are organized in a single level (regional Fog
  nodes are future work).
- The embedded broker is Moquette 0.17, the latest release on Maven Central; the fix for
  CVE-2026-85724 (0.18.x) is only published on GitHub.
