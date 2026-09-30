package edge;

/**
 * Edge configuration. Change the defaults here and run again; in containers (AWS deployment)
 * the values come from HTF_* environment variables.
 */
public final class Config {

    public static final int USER = Integer.parseInt(env("HTF_USER", "4005"));
    public static final int DAY = Integer.parseInt(env("HTF_DAY", "20"));

    public static final String DATASET = env("HTF_DATASET", "../YJMob100K/yjmob100k-dataset2.csv");
    /** Real time between two published pings (each ping = one 30-min timeslot). */
    public static final long PING_INTERVAL_MS = Long.parseLong(env("HTF_PING_INTERVAL_MS", "1000"));
    public static final String SALT = env("HTF_SALT", "htf-dev-salt");

    /** Entry Fog: the Edge connects to its broker first, and it redirects the Edge to the right Fog. */
    public static final int ENTRY_FOG = 1;
    /** Embedded MQTT broker of the entry Fog (Fog N listens on 1900 + N). */
    public static final String ENTRY_BROKER = env("HTF_ENTRY_BROKER", "tcp://localhost:" + (1900 + ENTRY_FOG));
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

    /** Timeslot (0..47) -> "HH:MM". */
    public static String time(int t) {
        int minutes = t * MINUTES_PER_SLOT;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
