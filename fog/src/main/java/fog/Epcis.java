package fog;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Converts a closed visit into a GS1 EPCIS 2.0 document (JSON-LD) with two ObjectEvents:
 *
 *   arriving   when = arrival time,   where = Fog (readPoint) + first cell (bizLocation)
 *   departing  when = departure time, where = Fog (readPoint) + last cell  (bizLocation)
 *
 * what  = the pseudonym h (urn:htf:pseudonym:{h}); the real id never leaves the Edge
 * where = SGLN of the Fog node: urn:epc:id:sgln:{company prefix}.{fog, 5 digits}.0
 *         cell as the SGLN extension:  ...{fog}.c{x}-{y}
 * why   = CBV business step "arriving" / "departing"
 *
 * HTF data that has no EPCIS field (day, timeslots, cells, pings, reason, next Fog) goes in
 * user extensions under the "htf:" namespace, as EPCIS 2.0 allows.
 */
final class Epcis {

    static final String CONTEXT = "https://ref.gs1.org/standards/epcis/epcis-context.jsonld";
    static final String HTF_NS = "urn:htf:epcis:";

    private Epcis() {
    }

    static JsonObject document(Visit v) {
        JsonObject arriving = event(v, "arriving", v.arrivalT, v.cells.get(0));
        JsonObject departing = event(v, "departing", v.departureT, v.cells.get(v.cells.size() - 1));
        departing.addProperty("htf:arrivalSlot", v.arrivalT);
        departing.add("htf:cells", FogApp.JSON.toJsonTree(v.cells));
        departing.addProperty("htf:pings", v.pings);
        departing.addProperty("htf:reason", v.reason);
        if (v.nextFog != null) {
            departing.addProperty("htf:nextFog", v.nextFog);
        }
        JsonArray events = new JsonArray();
        events.add(arriving);
        events.add(departing);

        JsonObject ns = new JsonObject();
        ns.addProperty("htf", HTF_NS);
        JsonArray context = new JsonArray();
        context.add(CONTEXT);
        context.add(ns);
        JsonObject body = new JsonObject();
        body.add("eventList", events);
        JsonObject doc = new JsonObject();
        doc.add("@context", context);
        doc.addProperty("type", "EPCISDocument");
        doc.addProperty("schemaVersion", "2.0");
        doc.addProperty("creationDate", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        doc.add("epcisBody", body);
        return doc;
    }

    private static JsonObject event(Visit v, String bizStep, int slot, int[] cell) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "ObjectEvent");
        e.addProperty("eventID", "urn:uuid:" + UUID.nameUUIDFromBytes(
                (v.h + "|" + v.day + "|" + v.fog + "|" + v.arrivalT + "|" + bizStep).getBytes(StandardCharsets.UTF_8)));
        e.addProperty("eventTime", time(v.day, slot));
        e.addProperty("eventTimeZoneOffset", Config.EPCIS_TZ_OFFSET);
        JsonArray epcs = new JsonArray();
        epcs.add("urn:htf:pseudonym:" + v.h);
        e.add("epcList", epcs);
        e.addProperty("action", "OBSERVE");
        e.addProperty("bizStep", bizStep);
        e.add("readPoint", id(sgln(v.fog, "0")));
        e.add("bizLocation", id(sgln(v.fog, "c" + cell[0] + "-" + cell[1])));
        e.addProperty("htf:day", v.day);
        e.addProperty("htf:slot", slot);
        e.addProperty("htf:fogAreaSide", v.fogAreaSide);
        return e;
    }

    /** Dataset day + timeslot -> ISO 8601 with the dataset city's offset. */
    static String time(int day, int slot) {
        int minutes = slot * Config.MINUTES_PER_SLOT;
        return LocalDate.parse(Config.EPCIS_EPOCH).plusDays(day)
                .atTime(minutes / 60, minutes % 60)
                .atOffset(ZoneOffset.of(Config.EPCIS_TZ_OFFSET))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String sgln(int fog, String extension) {
        return String.format("urn:epc:id:sgln:%s.%05d.%s", Config.GS1_COMPANY_PREFIX, fog, extension);
    }

    private static JsonObject id(String uri) {
        JsonObject o = new JsonObject();
        o.addProperty("id", uri);
        return o;
    }
}
