package fog;

/** Fog layer configuration (hardcoded; some values may come from environment variables). */
public final class Config {

    // ------------------------------------------------------------------ topology
    /** YJMob100K grid: 200 x 200 cells of 500 m. */
    public static final int GRID = 200;
    /** Side of each Fog node's area, in cells. 50 -> 16 Fog nodes of 25 x 25 km. */
    public static final int FOG_SIZE = 50;
    /**
     * Extra reach of a Fog beyond its area, in cells. 0 = handover as soon as the
     * user leaves the area. Larger values avoid back-and-forth (ping-pong) handovers.
     */
    public static final int REACH_MARGIN = 0;

    // ------------------------------------------------------------------ network
    /** Host advertised by the nodes (in containers: the node's host name). */
    public static final String HOST = env("HTF_FOG_HOST", "localhost");
    /** The embedded MQTT broker of node N listens on MQTT_BASE_PORT + N (Fog 1 -> 1901). */
    public static final int MQTT_BASE_PORT = 1900;
    /** The HTTP API (Chord + handover) of node N listens on HTTP_BASE_PORT + N (Fog 1 -> 9001). */
    public static final int HTTP_BASE_PORT = 9000;
    /** Seed node: creates the Chord ring; the others join through it. */
    public static final int SEED_FOG = 1;
    public static final String SEED_URL = env("HTF_FOG_SEED", "http://localhost:" + (HTTP_BASE_PORT + SEED_FOG));

    // ------------------------------------------------------------------ MQTT (each Fog's local broker)
    /** Topic where the Edge publishes pings on the broker of the Fog serving it. */
    public static final String PINGS_TOPIC = "htf/pings";
    /** Topic where the Fog sends commands to the Edge (e.g. switch broker). */
    public static final String CONTROL_TOPIC = "htf/edge/%s/control";
    public static final int QOS = 1;

    // ------------------------------------------------------------------ Chord
    /** Bits of the ring identifier (2^16 positions). */
    public static final int CHORD_BITS = 16;
    /** Successor list size: a node survives up to SUCCESSOR_LIST_SIZE - 1 consecutive failures after it. */
    public static final int SUCCESSOR_LIST_SIZE = 3;
    /** A node that does not answer within this time is considered down. */
    public static final int FAILURE_TIMEOUT_MS = 1500;
    /** A "fog:{id}" record not refreshed for this long is treated as a Fog that is down. */
    public static final int ADDRESS_TTL_MS = 10_000;

    // ------------------------------------------------------------------ handover decision
    /**
     * "rsrp" (default): synthetic RSRP per Fog + Kalman filter + hysteresis (HOM) + Time to Trigger (TTT).
     * "area": handover as soon as the user leaves the Fog's area (baseline, shows the ping-pong effect).
     */
    public static final String HANDOVER_POLICY = env("HTF_HANDOVER", "rsrp");
    /** Log-distance path loss: RSRP = P0 - 10 n log10(d / d0) + X_sigma. */
    public static final double RSRP_P0_DBM = -40.0;
    /** Reference distance d0 (km): one grid cell. */
    public static final double RSRP_D0_KM = 0.5;
    /** Path loss exponent n (urban macro cell). */
    public static final double PATH_LOSS_EXPONENT = 3.5;
    /** Shadowing X_sigma: Gaussian noise (dB). */
    public static final double SHADOWING_SIGMA_DB = 4.0;
    /** Kalman filter: process noise Q and measurement noise R (= sigma^2). */
    public static final double KALMAN_Q = 1.0;
    public static final double KALMAN_R = SHADOWING_SIGMA_DB * SHADOWING_SIGMA_DB;
    /** Handover margin (hysteresis): the neighbor must be this much stronger (dB). */
    public static final double HOM_DB = Double.parseDouble(env("HTF_HOM_DB", "3"));
    /** Time to Trigger: the condition must hold for this long (minutes of dataset time). */
    public static final int TTT_MINUTES = Integer.parseInt(env("HTF_TTT_MIN", "60"));
    /**
     * Make-before-break: if the target Fog does not confirm the handover (COMMIT) within this
     * time (real time), the origin aborts it, cancels the PREPARE and keeps serving the user.
     */
    public static final int HANDOVER_TIMEOUT_MS = Integer.parseInt(env("HTF_HANDOVER_TIMEOUT_MS", "10000"));
    /** Dataset time between two timeslots (YJMob100K: 30 min). */
    public static final int MINUTES_PER_SLOT = 30;

    // ------------------------------------------------------------------ GS1 EPCIS
    /** YJMob100K has no calendar dates: day 0 is mapped to this date (local time of the dataset city). */
    public static final String EPCIS_EPOCH = "2026-01-01";
    public static final String EPCIS_TZ_OFFSET = "+09:00";
    /** GS1 Company Prefix used in the SGLNs of the Fog nodes (0614141 = example prefix of the GS1 standards). */
    public static final String GS1_COMPANY_PREFIX = "0614141";

    // ------------------------------------------------------------------ cloud
    /** Folder of each node's persistent store-and-forward queue: {DATA_DIR}/fog-{id}/outbox.jsonl. */
    public static final String DATA_DIR = env("HTF_FOG_DATA", "data");
    /** Trail Management Service. The Fog does POST {TMS_URL}/capture with an EPCIS document. */
    public static final String TMS_URL = env("HTF_TMS_URL", "http://localhost:8080");

    private Config() {
    }

    public static int numberOfFogs() {
        int perAxis = GRID / FOG_SIZE;
        return perAxis * perAxis;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
