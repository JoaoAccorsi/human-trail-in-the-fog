package tms;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stores the GS1 EPCIS documents received from the Fogs (data/epcis.jsonl) and the visits
 * derived from them (data/visits.jsonl), both as JSON Lines.
 *
 * The file is the source of truth: every query re-reads it, so data generated in
 * previous runs (or a file deleted/edited by hand) shows up right away, without
 * restarting the TMS.
 *
 * Key = pseudonym + day + fog + arrival: a resend from the Fog does not duplicate the visit.
 * Only the pseudonym h is stored, never the user's real id ("blind" cloud).
 * Future: replace with a database with geographic shards.
 */
class Repository {

    private static final Comparator<JsonObject> BY_ARRIVAL =
            Comparator.<JsonObject>comparingInt(v -> v.get("day").getAsInt())
                    .thenComparingInt(v -> v.get("arrivalT").getAsInt());

    private final Path file;
    private final Path eventsFile;

    Repository(Path file, Path eventsFile) throws IOException {
        this.file = file;
        this.eventsFile = eventsFile;
        Files.createDirectories(file.toAbsolutePath().getParent());
    }

    /** Reads all visits from the file (without duplicates). */
    private Map<String, JsonObject> read() throws IOException {
        Map<String, JsonObject> visits = new LinkedHashMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    try {
                        JsonObject v = JsonParser.parseString(line).getAsJsonObject();
                        visits.putIfAbsent(key(v), v);
                    } catch (RuntimeException e) {
                        // corrupted or old-format line: skip it
                    }
                }
            }
        }
        return visits;
    }

    /**
     * Saves an EPCIS capture: the raw document goes to the event repository and the derived
     * visit to the query store. Returns false if the visit already existed (resend).
     */
    synchronized boolean saveCapture(JsonObject v, JsonObject document) throws IOException {
        if (read().containsKey(key(v))) {
            return false;
        }
        JsonObject line = new JsonObject();
        line.addProperty("h", v.get("h").getAsString());
        line.addProperty("day", v.get("day").getAsInt());
        line.addProperty("fog", v.get("fog").getAsInt());
        line.addProperty("arrivalT", v.get("arrivalT").getAsInt());
        line.add("document", document);
        append(eventsFile, line);
        append(file, v);
        return true;
    }

    private static void append(Path f, JsonObject o) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write(o.toString());
            out.newLine();
        }
    }

    /** EPCIS events of a pseudonym (optionally of one day), in time order. */
    synchronized List<JsonObject> events(String h, Integer day) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        if (!Files.exists(eventsFile)) {
            return out;
        }
        for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject l = JsonParser.parseString(line).getAsJsonObject();
            if (l.get("h").getAsString().equals(h) && (day == null || l.get("day").getAsInt() == day)) {
                l.getAsJsonObject("document").getAsJsonObject("epcisBody").getAsJsonArray("eventList")
                        .forEach(e -> out.add(e.getAsJsonObject()));
            }
        }
        out.sort(Comparator.comparing(e -> e.get("eventTime").getAsString()));
        return out;
    }

    /** Visits of the pseudonym (optionally of one day only), in arrival order. */
    synchronized List<JsonObject> trail(String h, Integer day) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject v : read().values()) {
            if (v.get("h").getAsString().equals(h) && (day == null || v.get("day").getAsInt() == day)) {
                out.add(v);
            }
        }
        out.sort(BY_ARRIVAL);
        return out;
    }

    /** Visits recorded by a Fog node on a day, in arrival order. */
    synchronized List<JsonObject> visitsAtFog(int fog, int day) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject v : read().values()) {
            if (v.get("fog").getAsInt() == fog && v.get("day").getAsInt() == day) {
                out.add(v);
            }
        }
        out.sort(BY_ARRIVAL);
        return out;
    }

    /** Summary per Fog node on a day: how many people (pseudonyms) and visits. */
    synchronized List<JsonObject> fogsOfDay(int day) throws IOException {
        Map<Integer, JsonObject> byFog = new TreeMap<>();
        Map<Integer, Set<String>> people = new TreeMap<>();
        for (JsonObject v : read().values()) {
            if (v.get("day").getAsInt() != day) {
                continue;
            }
            int fog = v.get("fog").getAsInt();
            JsonObject r = byFog.computeIfAbsent(fog, f -> {
                JsonObject o = new JsonObject();
                o.addProperty("fog", f);
                o.addProperty("visits", 0);
                return o;
            });
            r.addProperty("visits", r.get("visits").getAsInt() + 1);
            people.computeIfAbsent(fog, f -> new HashSet<>()).add(v.get("h").getAsString());
        }
        byFog.forEach((fog, r) -> r.addProperty("people", people.get(fog).size()));
        return new ArrayList<>(byFog.values());
    }

    synchronized int total() throws IOException {
        return read().size();
    }

    private static String key(JsonObject v) {
        return v.get("h").getAsString() + "|" + v.get("day").getAsInt() + "|"
                + v.get("fog").getAsInt() + "|" + v.get("arrivalT").getAsInt();
    }
}
