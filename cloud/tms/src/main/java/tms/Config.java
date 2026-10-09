package tms;

/** Trail Management Service configuration (environment variables; the defaults are the local run). */
public final class Config {

    public static final int PORT = 8080;

    /** PostgreSQL + PostGIS database (docker compose up -d postgres runs one with these defaults). */
    public static final String DB_URL = env("HTF_TMS_DB_URL", "jdbc:postgresql://localhost:5432/htf");
    public static final String DB_USER = env("HTF_TMS_DB_USER", "htf");
    public static final String DB_PASSWORD = env("HTF_TMS_DB_PASSWORD", "htf");
    /** disable | prefer | require | verify-full ... (prefer: TLS when the server offers it, as RDS does). */
    public static final String DB_SSLMODE = env("HTF_TMS_DB_SSLMODE", "prefer");
    /** Database connections kept open (the HTTP server has 4 threads). */
    public static final int DB_POOL = Integer.parseInt(env("HTF_TMS_DB_POOL", "8"));
    /** How long to keep trying to reach the database at startup (it may still be booting). */
    public static final int DB_WAIT_S = Integer.parseInt(env("HTF_TMS_DB_WAIT_S", "300"));

    /**
     * Optional: an epcis.jsonl file of the old JSONL version of the TMS, imported at startup
     * (each line's EPCIS document goes through /capture's logic; visits already stored are skipped).
     */
    public static final String IMPORT = env("HTF_TMS_IMPORT", null);

    private Config() {
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
