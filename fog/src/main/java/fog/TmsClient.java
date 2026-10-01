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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Sends visits to the Trail Management Service (Cloud) as GS1 EPCIS 2.0 documents
 * (HTTP POST /capture, the EPCIS 2.0 capture endpoint).
 *
 * Persistent store-and-forward: a document that could not be delivered is appended to a
 * local queue on disk ({DATA_DIR}/fog-{id}/outbox.jsonl) and resent periodically, in the
 * original order (FogNode calls resendPending). The queue survives a restart of the node:
 * on startup it is read back and its documents are sent again. A document leaves the queue
 * only after the TMS confirms it (HTTP 2xx); resends are harmless because the TMS discards
 * duplicates by the visit key.
 *
 * Used only by the node's logic thread (no concurrency).
 */
class TmsClient {

    /** A queued document and a short label for the log. */
    private record Entry(String label, String document) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Deque<Entry> pending = new ArrayDeque<>();
    private final URI url = URI.create(Config.TMS_URL + "/capture");
    private final Path outbox;
    private final int fogId;

    TmsClient(int fogId) {
        this.fogId = fogId;
        this.outbox = Path.of(Config.DATA_DIR, "fog-" + fogId, "outbox.jsonl");
        load();
    }

    /** Tries to send now; on failure (or if older documents are waiting), queues it on disk. */
    void send(Visit v) {
        Entry e = new Entry(String.format("Fog %d %s-%s", v.fog, v.arrival, v.departure),
                FogApp.JSON.toJson(Epcis.document(v)));
        if (!pending.isEmpty() || !post(e)) {
            pending.addLast(e);
            save();
            System.out.printf("[TMS ] unavailable: %d visit(s) of Fog %d queued in %s%n", pending.size(), fogId, outbox);
        }
    }

    /** Resends in the original order; stops at the first error. */
    void resendPending() {
        boolean changed = false;
        while (!pending.isEmpty() && post(pending.peekFirst())) {
            pending.removeFirst();
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private boolean post(Entry e) {
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(e.document()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            System.out.printf("[TMS ] POST /capture (EPCIS) %s -> HTTP %d%n", e.label(), resp.statusCode());
            return ok;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            System.out.printf("[TMS ] POST failed (%s)%n", ex.getClass().getSimpleName());
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
                pending.addLast(new Entry(o.get("label").getAsString(), o.get("document").toString()));
            }
            if (!pending.isEmpty()) {
                System.out.printf("[Fog %2d] %d visit(s) recovered from %s; they will be resent to the TMS%n",
                        fogId, pending.size(), outbox);
            }
        } catch (IOException | RuntimeException e) {
            System.out.printf("[Fog %2d] could not read %s (%s)%n", fogId, outbox, e.getMessage());
        }
    }

    /** Rewrites the queue file atomically (temporary file + move); removes it when the queue is empty. */
    private void save() {
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(outbox);
                return;
            }
            Files.createDirectories(outbox.getParent());
            List<String> lines = new ArrayList<>();
            for (Entry e : pending) {
                JsonObject o = new JsonObject();
                o.addProperty("label", e.label());
                o.add("document", JsonParser.parseString(e.document()));
                lines.add(o.toString());
            }
            Path tmp = outbox.resolveSibling("outbox.jsonl.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, outbox, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.printf("[Fog %2d] could not write %s (%s)%n", fogId, outbox, e.getMessage());
        }
    }
}
