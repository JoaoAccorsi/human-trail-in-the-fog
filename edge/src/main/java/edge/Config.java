package edge;

/**
 * Edge configuration. Change the defaults here and run again; in containers (AWS deployment)
 * the values come from HTF_* environment variables.
 */
public final class Config {

    public static final int USER = Integer.parseInt(env("HTF_USER", "4005"));
    /**
     * Several users in one Edge process: "4000-5000", "4000,4005,4011" or a mix ("4000-4010,4500").
     * Each user is a separate simulated phone (its own MQTT connection and handover state), and
     * all of them replay the day on a shared clock. Empty = only HTF_USER.
     */
    public static final String USERS = env("HTF_USERS", "");
    /** Threads that publish the pings of one timeslot in parallel (multi-user mode). */
    public static final int EDGE_THREADS = Integer.parseInt(env("HTF_EDGE_THREADS", "32"));
    public static final int DAY = Integer.parseInt(env("HTF_DAY", "20"));

    public static final String DATASET = env("HTF_DATASET", "../YJMob100K/yjmob100k-dataset2.csv");
    /** Real time between two published pings (each ping = one 30-min timeslot). */
    public static final long PING_INTERVAL_MS = Long.parseLong(env("HTF_PING_INTERVAL_MS", "1000"));
    public static final String SALT = env("HTF_SALT", "htf-dev-salt");

    /** Entry Fog: the Edge connects to its broker first, and it redirects the Edge to the right Fog. */
    public static final int ENTRY_FOG = 1;
    /** Embedded MQTT broker of the entry Fog (Fog N listens on 1900 + N). */
    public static final String ENTRY_BROKER = env("HTF_ENTRY_BROKER", "tcp://localhost:" + (1900 + ENTRY_FOG));
    /**
     * Distributed entry (optional): comma-separated brokers that act as entry points, e.g. the broker
     * of every Fog. Each phone uses entry number uid mod n, so no single Fog redirects every phone of
     * the city at start-up. Every Fog already redirects a phone outside its area to the right Fog.
     * Empty = only ENTRY_BROKER (Fog 1), as in scenarios C1-C3.
     */
    public static final java.util.List<String> ENTRY_BROKERS = entryBrokers();
    /** Seconds to wait before starting (lets the Fog nodes form the Chord ring when everything boots together). */
    public static final int START_DELAY_S = Integer.parseInt(env("HTF_START_DELAY_S", "0"));
    /** Seconds to keep retrying the entry broker before giving up (0 = fail at once). */
    public static final int CONNECT_RETRY_S = Integer.parseInt(env("HTF_CONNECT_RETRY_S", "0"));
    /** Topic where the Edge publishes its pings (on the broker of the Fog serving it). */
    public static final String PINGS_TOPIC = "htf/pings";
    /** Topic where the Edge receives commands from the Fog (e.g. switch broker on handover). */
    public static final String CONTROL_TOPIC = "htf/edge/%s/control";
    public static final int QOS = 1;

    public static final int MINUTES_PER_SLOT = 30;

    private Config() {
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static java.util.List<String> entryBrokers() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String b : env("HTF_ENTRY_BROKERS", "").split(",")) {
            if (!b.isBlank()) {
                out.add(b.trim());
            }
        }
        return out.isEmpty() ? java.util.List.of(ENTRY_BROKER) : java.util.List.copyOf(out);
    }

    /** Users to simulate: HTF_USERS (list and ranges) or just HTF_USER. */
    public static java.util.List<Integer> users() {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        if (USERS.isBlank()) {
            out.add(USER);
        } else {
            for (String part : USERS.split(",")) {
                String[] ab = part.trim().split("-");
                int a = Integer.parseInt(ab[0].trim());
                int b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                for (int u = a; u <= b; u++) {
                    out.add(u);
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** Timeslot (0..47) -> "HH:MM". */
    public static String time(int t) {
        int minutes = t * MINUTES_PER_SLOT;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
