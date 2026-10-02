package fog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Sends visits to the Trail Management Service (Cloud) as GS1 EPCIS 2.0 documents
 * (HTTP POST /capture, the EPCIS 2.0 capture endpoint).
 *
 * Batching: every visit goes into a local queue; every TMS_BATCH_WINDOW_MS (or as soon as
 * TMS_BATCH_MAX visits are ready) the queue is flushed as ONE EPCISDocument holding the events
 * of up to TMS_BATCH_MAX visits. An EPCIS document may carry any number of events, so a burst
 * of 1,000,000 visits becomes ~2,000 requests instead of 1,000,000.
 *
 * Delayed sending (jitter): a visit may carry a "not before" time; it stays queued until then
 * (used for the visits closed at the end of the day, so the nodes do not all send at once).
 *
 * Persistent store-and-forward: the queue lives on disk ({DATA_DIR}/fog-{id}/outbox.jsonl), a
 * visit is appended there as soon as it is handed over and leaves it only after the TMS confirms
 * the batch (HTTP 2xx). On startup the queue is read back and sent again; resends are harmless
 * because the TMS discards duplicates by the visit key.
 *
 * All the work runs on the client's own thread: send() only hands the visit over, so the user's
 * lane never waits for the cloud, and the queue is touched by a single thread.
 */
class TmsClient {

    /** A queued visit: its own EPCIS document, a label for the log and the earliest sending time. */
    private record Entry(String label, String document, long notBefore) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final LinkedList<Entry> pending = new LinkedList<>();
    private final URI url = URI.create(Config.TMS_URL + "/capture");
    private final Path outbox;
    private final int fogId;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    private boolean tmsDown;

    TmsClient(int fogId) {
        this.fogId = fogId;
        this.outbox = Path.of(Config.DATA_DIR, "fog-" + fogId, "outbox.jsonl");
        sender.execute(this::load);
        sender.scheduleWithFixedDelay(this::flush, Config.TMS_BATCH_WINDOW_MS, Config.TMS_BATCH_WINDOW_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Hands the visit over (returns at once); it goes out in the next batch. */
    void send(Visit v) {
        send(v, 0);
    }

    /** Hands the visit over; it is written to the queue now and sent no earlier than delayMs from now. */
    void send(Visit v, long delayMs) {
        Entry e = new Entry(String.format("Fog %d %s-%s", v.fog, v.arrival, v.departure),
                FogApp.JSON.toJson(Epcis.document(v)), System.currentTimeMillis() + delayMs);
        sender.execute(() -> {
            pending.addLast(e);
            append(e);
            if (ready().size() >= Config.TMS_BATCH_MAX) {
                flush();
            }
        });
    }

    void stop() {
        sender.shutdownNow();
    }

    /** Sender thread: sends the ready visits in batches of up to TMS_BATCH_MAX; stops at the first error. */
    private void flush() {
        boolean changed = false;
        while (true) {
            List<Entry> batch = ready();
            if (batch.isEmpty()) {
                break;
            }
            if (batch.size() > Config.TMS_BATCH_MAX) {
                batch = batch.subList(0, Config.TMS_BATCH_MAX);
            }
            if (!post(batch)) {
                break;
            }
            pending.removeAll(batch);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** Queued visits whose sending time has come, in queue order. */
    private List<Entry> ready() {
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        for (Entry e : pending) {
            if (e.notBefore() <= now) {
                out.add(e);
            }
        }
        return out;
    }

    /** One EPCISDocument with the events of every visit in the batch. */
    private boolean post(List<Entry> batch) {
        JsonObject doc = null;
        JsonArray events = new JsonArray();
        for (Entry e : batch) {
            JsonObject d = JsonParser.parseString(e.document()).getAsJsonObject();
            if (doc == null) {
                doc = d;
            }
            for (JsonElement ev : d.getAsJsonObject("epcisBody").getAsJsonArray("eventList")) {
                events.add(ev);
            }
        }
        doc.addProperty("creationDate", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        doc.getAsJsonObject("epcisBody").add("eventList", events);
        String label = batch.size() == 1 ? batch.get(0).label()
                : String.format("batch of %d visits of Fog %d", batch.size(), fogId);
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(doc.toString()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            System.out.printf("[TMS ] POST /capture (EPCIS) %s -> HTTP %d%n", label, resp.statusCode());
            if (ok && tmsDown) {
                tmsDown = false;
                System.out.printf("[TMS ] reachable again from Fog %d; sending the %d queued visit(s)%n", fogId, pending.size());
            }
            return ok;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            if (!tmsDown) {
                tmsDown = true;
                System.out.printf("[TMS ] unavailable from Fog %d (%s): %d visit(s) kept in %s%n",
                        fogId, ex.getClass().getSimpleName(), pending.size(), outbox);
            }
            return false;
        }
    }

    // ------------------------------------------------------------ disk queue

    /** Reads the queue left by a previous run of this node (if any). */
    private void load() {
        if (!Files.exists(outbox)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(outbox, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                long notBefore = o.has("notBefore") ? o.get("notBefore").getAsLong() : 0;
                pending.addLast(new Entry(o.get("label").getAsString(), o.get("document").toString(), notBefore));
            }
            if (!pending.isEmpty()) {
                System.out.printf("[Fog %2d] %d visit(s) recovered from %s; they will be resent to the TMS%n",
                        fogId, pending.size(), outbox);
            }
        } catch (IOException | RuntimeException e) {
            System.out.printf("[Fog %2d] could not read %s (%s)%n", fogId, outbox, e.getMessage());
        }
    }

    private static String line(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("label", e.label());
        o.addProperty("notBefore", e.notBefore());
        o.add("document", JsonParser.parseString(e.document()));
        return o.toString();
    }

    /** A new visit is appended to the queue file (cheap: no rewrite). */
    private void append(Entry e) {
        try {
            Files.createDirectories(outbox.getParent());
            Files.writeString(outbox, line(e) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.out.printf("[Fog %2d] could not write %s (%s)%n", fogId, outbox, ex.getMessage());
        }
    }

    /** After a batch is confirmed: rewrites the queue atomically (temporary file + move), or removes it. */
    private void save() {
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(outbox);
                return;
            }
            List<String> lines = new ArrayList<>();
            for (Iterator<Entry> it = pending.iterator(); it.hasNext(); ) {
                lines.add(line(it.next()));
            }
            Path tmp = outbox.resolveSibling("outbox.jsonl.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, outbox, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.printf("[Fog %2d] could not write %s (%s)%n", fogId, outbox, e.getMessage());
        }
    }
}
