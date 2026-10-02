package tms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads a GS1 EPCIS 2.0 document sent by a Fog and derives the visits used by the trail queries.
 * Each visit is a pair of ObjectEvents, "arriving" and "departing"; a document may carry the
 * pairs of many visits (the Fogs send in batches). The pair of a visit is matched by
 * pseudonym + htf:day + Fog + arrival slot (htf:slot of "arriving", htf:arrivalSlot of "departing").
 *
 *   what  epcList[0]       urn:htf:pseudonym:{h}
 *   where readPoint.id     urn:epc:id:sgln:{prefix}.{fog, 5 digits}.0
 *   when  htf:day + htf:slot of each event (eventTime carries the same instant as ISO 8601)
 *   why   bizStep          arriving | departing
 */
final class EpcisReader {

    static final String PSEUDONYM_PREFIX = "urn:htf:pseudonym:";

    private EpcisReader() {
    }

    /** A visit derived from a document, and the document reduced to that visit's two events. */
    record Capture(JsonObject visit, JsonObject document) {
    }

    /** Result of reading a document: the valid visits and how many event pairs were rejected. */
    record Batch(List<Capture> captures, int rejected, String firstError) {
    }

    /**
     * Splits a document into visits. Throws IllegalArgumentException if the document itself is
     * invalid; an invalid visit inside a valid document is counted as rejected, so one bad visit
     * never blocks the others of the batch (the Fog would otherwise resend the batch forever).
     */
    static Batch toVisits(JsonObject doc) {
        if (!"EPCISDocument".equals(str(doc, "type"))) {
            throw new IllegalArgumentException("type must be EPCISDocument");
        }
        JsonArray events = obj(doc, "epcisBody").getAsJsonArray("eventList");
        if (events == null) {
            throw new IllegalArgumentException("missing epcisBody.eventList");
        }
        Map<String, JsonObject[]> pairs = new LinkedHashMap<>();
        int rejected = 0;
        String firstError = null;
        for (JsonElement el : events) {
            JsonObject e = el.getAsJsonObject();
            String step = str(e, "bizStep");
            if (!"ObjectEvent".equals(str(e, "type")) || !("arriving".equals(step) || "departing".equals(step))) {
                continue;
            }
            try {
                String slotField = "arriving".equals(step) ? "htf:slot" : "htf:arrivalSlot";
                String key = e.getAsJsonArray("epcList").get(0).getAsString() + "|" + num(e, "htf:day") + "|"
                        + obj(e, "readPoint").get("id").getAsString() + "|" + num(e, slotField);
                pairs.computeIfAbsent(key, k -> new JsonObject[2])["arriving".equals(step) ? 0 : 1] = e;
            } catch (RuntimeException ex) {
                rejected++;
                firstError = firstError == null ? ex.getMessage() : firstError;
            }
        }
        List<Capture> out = new ArrayList<>();
        for (JsonObject[] p : pairs.values()) {
            try {
                JsonObject single = new JsonObject();
                doc.entrySet().forEach(x -> single.add(x.getKey(), x.getValue()));
                JsonArray two = new JsonArray();
                if (p[0] != null) {
                    two.add(p[0]);
                }
                if (p[1] != null) {
                    two.add(p[1]);
                }
                JsonObject body = new JsonObject();
                body.add("eventList", two);
                single.add("epcisBody", body);
                out.add(new Capture(toVisit(single), single));
            } catch (RuntimeException ex) {
                rejected++;
                firstError = firstError == null ? ex.getMessage() : firstError;
            }
        }
        return new Batch(out, rejected, firstError);
    }

    /** Returns the visit of a one-visit document, or throws IllegalArgumentException explaining what is wrong. */
    static JsonObject toVisit(JsonObject doc) {
        if (!"EPCISDocument".equals(str(doc, "type"))) {
            throw new IllegalArgumentException("type must be EPCISDocument");
        }
        JsonObject body = obj(doc, "epcisBody");
        JsonArray events = body.getAsJsonArray("eventList");
        if (events == null) {
            throw new IllegalArgumentException("missing epcisBody.eventList");
        }
        JsonObject arriving = null;
        JsonObject departing = null;
        for (JsonElement el : events) {
            JsonObject e = el.getAsJsonObject();
            if (!"ObjectEvent".equals(str(e, "type"))) {
                continue;
            }
            if ("arriving".equals(str(e, "bizStep"))) {
                arriving = e;
            } else if ("departing".equals(str(e, "bizStep"))) {
                departing = e;
            }
        }
        if (arriving == null || departing == null) {
            throw new IllegalArgumentException("the document needs one 'arriving' and one 'departing' ObjectEvent");
        }
        String epc = departing.getAsJsonArray("epcList").get(0).getAsString();
        if (!epc.startsWith(PSEUDONYM_PREFIX)) {
            throw new IllegalArgumentException("epcList must hold the pseudonym (" + PSEUDONYM_PREFIX + "...)");
        }
        String readPoint = obj(departing, "readPoint").get("id").getAsString();
        String[] parts = readPoint.substring(readPoint.lastIndexOf(':') + 1).split("\\.");

        JsonObject v = new JsonObject();
        v.addProperty("h", epc.substring(PSEUDONYM_PREFIX.length()));
        v.addProperty("day", num(departing, "htf:day"));
        v.addProperty("fog", Integer.parseInt(parts[1]));
        v.addProperty("fogSize", num(departing, "htf:fogSize"));
        int arrivalT = num(arriving, "htf:slot");
        int departureT = num(departing, "htf:slot");
        v.addProperty("arrivalT", arrivalT);
        v.addProperty("departureT", departureT);
        v.addProperty("arrival", hhmm(arrivalT));
        v.addProperty("departure", hhmm(departureT));
        v.addProperty("pings", num(departing, "htf:pings"));
        v.add("cells", departing.get("htf:cells"));
        v.addProperty("reason", str(departing, "htf:reason"));
        if (departing.has("htf:nextFog")) {
            v.addProperty("nextFog", num(departing, "htf:nextFog"));
        }
        v.addProperty("eventTime", str(arriving, "eventTime"));
        return v;
    }

    private static String hhmm(int slot) {
        int m = slot * 30;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static int num(JsonObject o, String k) {
        if (!o.has(k)) {
            throw new IllegalArgumentException("missing field " + k);
        }
        return o.get(k).getAsInt();
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonObject()) {
            throw new IllegalArgumentException("missing object " + k);
        }
        return o.getAsJsonObject(k);
    }
}
