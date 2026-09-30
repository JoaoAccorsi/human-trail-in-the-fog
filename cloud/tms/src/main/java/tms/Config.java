package tms;

/** Trail Management Service configuration. */
public final class Config {

    public static final int PORT = 8080;
    /** Visits derived from the EPCIS events (one JSON per line), used by the trail queries. Relative to cloud/tms/. */
    public static final String FILE = "data/visits.jsonl";
    /** Raw GS1 EPCIS 2.0 documents received from the Fogs (event repository), one per line. */
    public static final String EVENTS_FILE = "data/epcis.jsonl";

    private Config() {
    }
}
