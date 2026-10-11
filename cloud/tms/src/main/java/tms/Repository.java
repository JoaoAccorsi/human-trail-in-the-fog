package tms;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stores the GS1 EPCIS documents received from the Fogs (table epcis_events) and the visits
 * derived from them (table visits, with the visited cells as a PostGIS geometry) in PostgreSQL.
 * See src/main/resources/schema.sql.
 *
 * Key = pseudonym + day + fog + arrival: a resend from the Fog does not duplicate the visit
 * (INSERT ... ON CONFLICT DO NOTHING). Only the pseudonym h is stored, never the user's real id
 * ("blind" cloud). The visits are returned with the same JSON fields the Fog sent, so the
 * Cloud Mobility Platform does not depend on how they are stored.
 */
class Repository {

    /** Visit columns, in the order read by toVisit(). */
    private static final String COLUMNS = "h, day, fog, fog_area_side, arrival_t, departure_t, pings, "
            + "ST_AsGeoJSON(cells) AS cells, reason, next_fog, event_time";

    /** Visits of a period: day range first (uses the keys), then the overlap in absolute timeslots. */
    private static final String IN_PERIOD = " AND day BETWEEN ? AND ?"
            + " AND day * " + Period.SLOTS_PER_DAY + " + arrival_t <= ?"
            + " AND day * " + Period.SLOTS_PER_DAY + " + departure_t >= ?";

    /** One statement per visit: the visit, and its EPCIS document only if the visit is new. */
    private static final String INSERT = """
            WITH new_visit AS (
                INSERT INTO visits (h, day, fog, arrival_t, departure_t, fog_area_side, pings, cells,
                                    reason, next_fog, event_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 0), ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING h, day, fog, arrival_t)
            INSERT INTO epcis_events (h, day, fog, arrival_t, document)
            SELECT h, day, fog, arrival_t, ?::jsonb FROM new_visit""";

    private final Db db;

    Repository(Db db) {
        this.db = db;
    }

    /**
     * Saves the visits of a captured EPCIS document (one or a batch) in one transaction: each new
     * visit goes to the visits table and its document to the event repository. Visits that already
     * exist (resends) are skipped. Returns how many were new.
     */
    int saveCaptures(List<EpcisReader.Capture> captures) throws IOException {
        try {
            return db.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                    for (EpcisReader.Capture cap : captures) {
                        JsonObject v = cap.visit();
                        int i = 1;
                        ps.setString(i++, v.get("h").getAsString());
                        ps.setInt(i++, v.get("day").getAsInt());
                        ps.setInt(i++, v.get("fog").getAsInt());
                        ps.setInt(i++, v.get("arrivalT").getAsInt());
                        ps.setInt(i++, v.get("departureT").getAsInt());
                        ps.setInt(i++, v.get("fogAreaSide").getAsInt());
                        ps.setInt(i++, v.get("pings").getAsInt());
                        ps.setString(i++, multiPoint(v.get("cells")));
                        ps.setString(i++, v.get("reason").getAsString());
                        if (v.has("nextFog") && !v.get("nextFog").isJsonNull()) {
                            ps.setInt(i++, v.get("nextFog").getAsInt());
                        } else {
                            ps.setNull(i++, Types.INTEGER);
                        }
                        ps.setString(i++, v.get("eventTime").getAsString());
                        ps.setString(i, cap.document().toString());
                        ps.addBatch();
                    }
                    int added = 0;
                    for (int n : ps.executeBatch()) {
                        added += n == Statement.SUCCESS_NO_INFO ? 1 : Math.max(n, 0);
                    }
                    return added;
                }
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** The visit's cells [[x, y], ...] as a GeoJSON MultiPoint (malformed entries are skipped). */
    private static String multiPoint(JsonElement cells) {
        JsonArray coordinates = new JsonArray();
        if (cells != null && cells.isJsonArray()) {
            for (JsonElement cell : cells.getAsJsonArray()) {
                if (cell.isJsonArray() && cell.getAsJsonArray().size() >= 2) {
                    JsonArray xy = cell.getAsJsonArray();
                    if (xy.get(0).isJsonPrimitive() && xy.get(0).getAsJsonPrimitive().isNumber()
                            && xy.get(1).isJsonPrimitive() && xy.get(1).getAsJsonPrimitive().isNumber()) {
                        JsonArray p = new JsonArray();
                        p.add(xy.get(0));
                        p.add(xy.get(1));
                        coordinates.add(p);
                    }
                }
            }
        }
        JsonObject geo = new JsonObject();
        geo.addProperty("type", "MultiPoint");
        geo.add("coordinates", coordinates);
        return geo.toString();
    }

    /** EPCIS events of the pseudonym's visits that overlap the period (null = all), in time order. */
    List<JsonObject> events(String h, Period period) throws IOException {
        // USING merges h, day, fog and arrival_t, so IN_PERIOD's unqualified columns still apply
        String sql = "SELECT e.document FROM epcis_events e JOIN visits v USING (h, day, fog, arrival_t)"
                + " WHERE h = ?" + (period == null ? "" : IN_PERIOD);
        try {
            List<JsonObject> out = db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, h);
                    setPeriod(ps, 2, period);
                    List<JsonObject> events = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonParser.parseString(rs.getString(1)).getAsJsonObject().getAsJsonObject("epcisBody")
                                    .getAsJsonArray("eventList").forEach(e -> events.add(e.getAsJsonObject()));
                        }
                    }
                    return events;
                }
            });
            out.sort(Comparator.comparing(e -> e.get("eventTime").getAsString()));
            return out;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Visits of the pseudonym that overlap the period (null = all), in arrival order. */
    List<JsonObject> trail(String h, Period period) throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM visits WHERE h = ?" + (period == null ? "" : IN_PERIOD)
                + " ORDER BY day, arrival_t, fog";
        return visits(sql, ps -> {
            ps.setString(1, h);
            setPeriod(ps, 2, period);
        });
    }

    /** Visits recorded on a day by one Fog node (fog = null: by every Fog node), in arrival order. */
    List<JsonObject> visitsOfDay(Integer fog, int day) throws IOException {
        String sql = "SELECT " + COLUMNS + " FROM visits WHERE day = ?" + (fog == null ? "" : " AND fog = ?")
                + " ORDER BY day, arrival_t, fog, h";
        return visits(sql, ps -> {
            ps.setInt(1, day);
            if (fog != null) {
                ps.setInt(2, fog);
            }
        });
    }

    /** Summary per Fog node on a day: how many people (pseudonyms) and visits. */
    List<JsonObject> fogsOfDay(int day) throws IOException {
        String sql = "SELECT fog, count(*) AS visits, count(DISTINCT h) AS people FROM visits"
                + " WHERE day = ? GROUP BY fog ORDER BY fog";
        try {
            return db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, day);
                    List<JsonObject> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject o = new JsonObject();
                            o.addProperty("fog", rs.getInt("fog"));
                            o.addProperty("visits", rs.getInt("visits"));
                            o.addProperty("people", rs.getInt("people"));
                            out.add(o);
                        }
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** EPCIS documents stored for a day (one per visit, as received), for the conformance check. */
    List<JsonObject> documents(int day) throws IOException {
        String sql = "SELECT document FROM epcis_events WHERE day = ? ORDER BY fog, arrival_t, h";
        try {
            return db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, day);
                    List<JsonObject> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(JsonParser.parseString(rs.getString(1)).getAsJsonObject());
                        }
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    int total() throws IOException {
        try {
            return db.with(c -> {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM visits")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Deletes every visit and EPCIS document (a fresh start for a new experiment run). */
    void clear() throws IOException {
        try {
            db.with(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("TRUNCATE visits, epcis_events");
                }
                return null;
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<JsonObject> visits(String sql, Binder binder) throws IOException {
        try {
            return db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    binder.bind(ps);
                    List<JsonObject> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(toVisit(rs));
                        }
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Binds the 4 parameters of IN_PERIOD starting at index i (nothing when there is no period). */
    private static void setPeriod(PreparedStatement ps, int i, Period period) throws SQLException {
        if (period != null) {
            ps.setInt(i, period.start() / Period.SLOTS_PER_DAY);
            ps.setInt(i + 1, period.end() / Period.SLOTS_PER_DAY);
            ps.setInt(i + 2, period.end());
            ps.setInt(i + 3, period.start());
        }
    }

    /** A row of the visits table as the visit JSON derived by EpcisReader.toVisit(). */
    private static JsonObject toVisit(ResultSet rs) throws SQLException {
        JsonObject v = new JsonObject();
        v.addProperty("h", rs.getString("h"));
        v.addProperty("day", rs.getInt("day"));
        v.addProperty("fog", rs.getInt("fog"));
        v.addProperty("fogAreaSide", rs.getInt("fog_area_side"));
        int arrivalT = rs.getInt("arrival_t");
        int departureT = rs.getInt("departure_t");
        v.addProperty("arrivalT", arrivalT);
        v.addProperty("departureT", departureT);
        v.addProperty("arrival", EpcisReader.hhmm(arrivalT));
        v.addProperty("departure", EpcisReader.hhmm(departureT));
        v.addProperty("pings", rs.getInt("pings"));
        v.add("cells", cells(rs.getString("cells")));
        v.addProperty("reason", rs.getString("reason"));
        int nextFog = rs.getInt("next_fog");
        if (!rs.wasNull()) {
            v.addProperty("nextFog", nextFog);
        }
        v.addProperty("eventTime", rs.getString("event_time"));
        return v;
    }

    /** GeoJSON MultiPoint -> [[x, y], ...] with integer cells, as the Fog sent them. */
    private static JsonArray cells(String geoJson) {
        JsonArray out = new JsonArray();
        if (geoJson == null) {
            return out;
        }
        for (JsonElement p : JsonParser.parseString(geoJson).getAsJsonObject().getAsJsonArray("coordinates")) {
            JsonArray xy = new JsonArray();
            xy.add(Math.round(p.getAsJsonArray().get(0).getAsDouble()));
            xy.add(Math.round(p.getAsJsonArray().get(1).getAsDouble()));
            out.add(xy);
        }
        return out;
    }

    private static IOException failure(SQLException e) {
        return new IOException("database error: " + e.getMessage(), e);
    }
}
