package tms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * TRAIL MANAGEMENT SERVICE (Cloud) - receives the visits from the Fogs and returns trails.
 *
 *   POST /capture              GS1 EPCIS 2.0 document (one visit or a batch) -> 201 (some new) | 200 (all repeated) | 400
 *   GET  /events/{h}?day=N[&from=HH:MM&to=HH:MM][&toDay=M]   EPCIS events of h in the period -> 200 [ ... ]
 *   GET  /visits?fog=F&day=N   visits recorded by Fog F on day N          -> 200 [ ... ]
 *   GET  /trail/{h}?day=N[&from=HH:MM&to=HH:MM][&toDay=M]    visits of h that overlap the period -> 200 [ ... ]
 *   GET  /fogs?day=N           Fog nodes with visits on day N             -> 200 [{fog, people, visits}]
 *   GET  /health
 *
 * Each capture is kept as received (data/epcis.jsonl) and turned into a visit (data/visits.jsonl);
 * every query reads these files (the data already generated).
 *
 *   cd cloud/tms && mvn -q compile exec:exec
 */
public class TmsApp {

    private final Repository repo;

    TmsApp(Repository repo) {
        this.repo = repo;
    }

    public static void main(String[] args) throws IOException {
        Repository repo = new Repository(Path.of(Config.FILE), Path.of(Config.EVENTS_FILE));
        HttpServer server = new TmsApp(repo).createServer(Config.PORT);
        server.start();
        System.out.printf("[TMS] listening on http://localhost:%d | %d visits in %s%n",
                Config.PORT, repo.total(), Config.FILE);
    }

    HttpServer createServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/capture", this::capture);
        server.createContext("/events/", this::events);
        server.createContext("/visits", this::visits);
        server.createContext("/trail/", this::trail);
        server.createContext("/fogs", this::fogs);
        server.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.setExecutor(Executors.newFixedThreadPool(4));
        return server;
    }

    // ------------------------------------------------ POST /visits | GET /visits?fog=&day=

    private void visits(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            respond(ex, 405, "{\"error\":\"use GET (Fogs send visits as EPCIS documents to POST /capture)\"}");
            return;
        }
        visitsAtFog(ex);
    }

    // ------------------------------------------------ POST /capture (GS1 EPCIS 2.0)

    private void capture(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        EpcisReader.Batch batch;
        try (InputStream in = ex.getRequestBody()) {
            JsonObject doc = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            batch = EpcisReader.toVisits(doc);
            if (batch.captures().isEmpty()) {
                throw new IllegalArgumentException(batch.firstError() == null
                        ? "the document needs one 'arriving' and one 'departing' ObjectEvent per visit"
                        : batch.firstError());
            }
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "invalid EPCIS document: " + e.getMessage());
            respond(ex, 400, err.toString());
            return;
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"error\":\"invalid JSON\"}");
            return;
        }
        int added = repo.saveCaptures(batch.captures());
        int visits = batch.captures().size();
        if (visits == 1) {
            JsonObject v = batch.captures().get(0).visit();
            System.out.printf("[TMS] EPCIS capture Fog %5d %s-%s (%s)%s%n", v.get("fog").getAsInt(),
                    v.get("arrival").getAsString(), v.get("departure").getAsString(), v.get("reason").getAsString(),
                    added == 1 ? "" : "  [repeated, ignored]");
        } else {
            System.out.printf("[TMS] EPCIS capture: batch of %d visits, %d new, %d repeated%s%n", visits, added,
                    visits - added, batch.rejected() > 0 ? ", " + batch.rejected() + " rejected (" + batch.firstError() + ")" : "");
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("visits", visits);
        r.addProperty("new", added);
        r.addProperty("rejected", batch.rejected());
        respond(ex, added > 0 ? 201 : 200, r.toString());
    }

    // -------------------------------------------------------- GET /events/{h}?day=

    private void events(HttpExchange ex) throws IOException {
        String h = ex.getRequestURI().getPath().substring("/events/".length());
        Period period;
        try {
            period = period(ex);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, error(e));
            return;
        }
        JsonArray arr = new JsonArray();
        repo.events(h, period).forEach(arr::add);
        respond(ex, 200, arr.toString());
    }

    // -------------------------------------------------------- GET /trail/{h}?day=

    private void trail(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            respond(ex, 405, "{\"error\":\"use GET\"}");
            return;
        }
        String h = ex.getRequestURI().getPath().substring("/trail/".length());
        Period period;
        try {
            period = period(ex);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, error(e));
            return;
        }
        List<JsonObject> trail = repo.trail(h, period);
        JsonArray arr = new JsonArray();
        trail.forEach(arr::add);
        System.out.printf("[TMS] trail query %s... %s -> %d visits%n",
                h.substring(0, Math.min(16, h.length())), period == null ? "all days" : period, trail.size());
        respond(ex, 200, arr.toString());
    }

    /** Period of a trail/events query: day (required for a filter), toDay, from and to (HH:MM). */
    private static Period period(HttpExchange ex) {
        try {
            return Period.of(intParam(ex, "day"), intParam(ex, "toDay"), param(ex, "from"), param(ex, "to"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("day, toDay must be numbers and from, to must be HH:MM");
        }
    }

    private static String error(IllegalArgumentException e) {
        JsonObject err = new JsonObject();
        err.addProperty("error", e.getMessage());
        return err.toString();
    }

    // ------------------------------------------------ GET /visits?fog=F&day=N

    private void visitsAtFog(HttpExchange ex) throws IOException {
        Integer fog;
        Integer day;
        try {
            fog = intParam(ex, "fog");
            day = intParam(ex, "day");
        } catch (NumberFormatException e) {
            respond(ex, 400, "{\"error\":\"fog and day must be numbers\"}");
            return;
        }
        if (fog == null || day == null) {
            respond(ex, 400, "{\"error\":\"provide fog and day: /visits?fog=6&day=20\"}");
            return;
        }
        List<JsonObject> list = repo.visitsAtFog(fog, day);
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        System.out.printf("[TMS] Fog %d query day %d -> %d visits%n", fog, day, list.size());
        respond(ex, 200, arr.toString());
    }

    // -------------------------------------------------------------- GET /fogs?day=N

    private void fogs(HttpExchange ex) throws IOException {
        Integer day;
        try {
            day = intParam(ex, "day");
        } catch (NumberFormatException e) {
            day = null;
        }
        if (day == null) {
            respond(ex, 400, "{\"error\":\"provide the day: /fogs?day=20\"}");
            return;
        }
        JsonArray arr = new JsonArray();
        repo.fogsOfDay(day).forEach(arr::add);
        respond(ex, 200, arr.toString());
    }

    /** Reads an integer parameter from the query string (null if absent). */
    private static Integer intParam(HttpExchange ex, String name) {
        String v = param(ex, name);
        return v == null ? null : Integer.parseInt(v);
    }

    /** Reads a text parameter from the query string (null if absent or empty). */
    private static String param(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv[0].equals(name) && kv.length == 2) {
                    String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
