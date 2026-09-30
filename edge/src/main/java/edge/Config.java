package edge;

/** Edge configuration (hardcoded). Change it here and run again. */
public final class Config {

    public static final int USER = 4005;
    public static final int DAY = 20;

    public static final String DATASET = "../YJMob100K/yjmob100k-dataset2.csv";
    /** Real time between two published pings (each ping = one 30-min timeslot). */
    public static final long PING_INTERVAL_MS = 1000;
    public static final String SALT = "htf-dev-salt";

    /** Entry Fog: the Edge connects to its broker first, and it redirects the Edge to the right Fog. */
    public static final int ENTRY_FOG = 1;
    /** Embedded MQTT broker of the entry Fog (Fog N listens on 1900 + N). */
    public static final String ENTRY_BROKER = "tcp://localhost:" + (1900 + ENTRY_FOG);
    /** Topic where the Edge publishes its pings (on the broker of the Fog serving it). */
    public static final String PINGS_TOPIC = "htf/pings";
    /** Topic where the Edge receives commands from the Fog (e.g. switch broker on handover). */
    public static final String CONTROL_TOPIC = "htf/edge/%s/control";
    public static final int QOS = 1;

    public static final int MINUTES_PER_SLOT = 30;

    private Config() {
    }

    /** Timeslot (0..47) -> "HH:MM". */
    public static String time(int t) {
        int minutes = t * MINUTES_PER_SLOT;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
