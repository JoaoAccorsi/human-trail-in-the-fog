-- Database of the Trail Management Service (PostgreSQL 16 + PostGIS 3).
-- Run by the TMS at startup (every statement is idempotent), so neither the local run nor AWS
-- needs a manual step.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Visits derived from the EPCIS documents: what the trail queries read.
-- Key = pseudonym + day + Fog node + arrival slot: a resend of the Fog's store-and-forward
-- queue hits ON CONFLICT DO NOTHING and never duplicates a visit.
-- Only the pseudonym h is stored, never the user's real id ("blind" cloud).
-- Partitioned by region: each Fog node covers one area of the city, and HASH (fog) spreads the
-- nodes over the partitions. A query for one Fog node reads one partition; the same column
-- would be the distribution key of a sharded deployment (e.g. Citus).
CREATE TABLE IF NOT EXISTS visits (
    h             text      NOT NULL,              -- pseudonym (SHA-256, hex)
    day           int       NOT NULL,              -- YJMob100K day
    fog           int       NOT NULL,              -- Fog node (region) that served the visit
    arrival_t     smallint  NOT NULL,              -- arrival timeslot (0..47, 30 min each)
    departure_t   smallint  NOT NULL,              -- departure timeslot
    fog_area_side int       NOT NULL,              -- side of the Fog node's area, in cells
    pings         int       NOT NULL,              -- pings received during the visit
    cells         geometry(MultiPoint, 0),         -- visited cells (x, y) of the 200x200 grid, in order
    reason        text      NOT NULL,              -- handover | end_of_day | context_lost
    next_fog      int,                             -- target of the handover, if any
    event_time    text      NOT NULL,              -- eventTime of the arriving event (ISO 8601, as sent)
    PRIMARY KEY (h, day, fog, arrival_t)           -- also serves the trail queries (h, day)
) PARTITION BY HASH (fog);

CREATE TABLE IF NOT EXISTS visits_p0 PARTITION OF visits FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE IF NOT EXISTS visits_p1 PARTITION OF visits FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE IF NOT EXISTS visits_p2 PARTITION OF visits FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE IF NOT EXISTS visits_p3 PARTITION OF visits FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- "Who passed through Fog F on day D" and the per-node summary of a day.
CREATE INDEX IF NOT EXISTS visits_fog_day ON visits (fog, day);
-- Spatial queries on the visited cells, e.g. who crossed a region of the city:
--   SELECT DISTINCT h FROM visits
--   WHERE day = 20 AND ST_Intersects(cells, ST_MakeEnvelope(80, 80, 100, 100, 0));
CREATE INDEX IF NOT EXISTS visits_cells ON visits USING gist (cells);

-- Event repository: the GS1 EPCIS 2.0 document of each visit (its arriving and departing
-- ObjectEvents), kept as received. Same key and partitioning as the visits.
CREATE TABLE IF NOT EXISTS epcis_events (
    h         text     NOT NULL,
    day       int      NOT NULL,
    fog       int      NOT NULL,
    arrival_t smallint NOT NULL,
    document  jsonb    NOT NULL,
    PRIMARY KEY (h, day, fog, arrival_t)
) PARTITION BY HASH (fog);

CREATE TABLE IF NOT EXISTS epcis_events_p0 PARTITION OF epcis_events FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE IF NOT EXISTS epcis_events_p1 PARTITION OF epcis_events FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE IF NOT EXISTS epcis_events_p2 PARTITION OF epcis_events FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE IF NOT EXISTS epcis_events_p3 PARTITION OF epcis_events FOR VALUES WITH (MODULUS 4, REMAINDER 3);
