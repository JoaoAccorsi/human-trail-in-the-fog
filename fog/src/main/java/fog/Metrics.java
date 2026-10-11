package fog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Evaluation measurements of one Fog node, appended to {METRICS_DIR}/fog-{id}.csv:
 *
 *   time_ms,fog,event,value_ms,h,detail
 *
 *   ping          time from the ping's arrival (MQTT) to the end of its processing (lane queue + logic)
 *   handover      on the origin: from the handover decision to the COMMIT (detail: to, result, prepare_ms)
 *   tms_delivery  from the moment a visit could be sent to the TMS until the TMS confirmed it
 *                 (detail: batch size; the end-of-day jitter is not counted)
 *   tms_request   one POST /capture: round trip (detail: visits, bytes of the EPCIS document,
 *                 HTTP status; 0 = TMS unreachable, the batch stays in the store-and-forward queue)
 *   lookup        one operation on the OIDA directory (Chord DHT): total time, h for user records
 *                 (detail: op = get | put | claim, key = h | fog, hops = routing hops on the ring)
 *   node_down     this node detected that its successor failed (detail: lost key range)
 *   owner_refresh owner records republished after a ring change, value = time since the change was
 *                 reported (detail: sent, restored)
 *   crash         simulated crash of this node (/admin/crash)
 *   cover         a ping served in the area of a Fog that is down (detail: for = the failed Fog)
 *
 * deploy/metrics.py turns these files (and the visits stored by the TMS) into the final CSV.
 * Lines are written by a single background thread, so measuring never delays a user's lane.
 */
final class Metrics {

    static final String HEADER = "time_ms,fog,event,value_ms,h,detail";

    private final int fogId;
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "metrics");
        t.setDaemon(true);
        return t;
    });
    private BufferedWriter out;

    Metrics(int fogId) {
        this.fogId = fogId;
        this.file = Path.of(Config.METRICS_DIR, "fog-" + fogId + ".csv");
    }

    void record(String event, double valueMs, String h, String detail) {
        long now = System.currentTimeMillis();
        String line = String.format(Locale.ROOT, "%d,%d,%s,%.3f,%s,%s", now, fogId, event, valueMs,
                h == null ? "" : h, detail == null ? "" : detail.replace(',', ';'));
        writer.execute(() -> write(line));
    }

    /** Milliseconds elapsed since a System.nanoTime() reading. */
    static double since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    void stop() {
        writer.execute(() -> {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // shutting down
            }
        });
        writer.shutdown();
    }

    private void write(String line) {
        try {
            if (out == null) {
                Files.createDirectories(file.getParent());
                boolean fresh = !Files.exists(file) || Files.size(file) == 0;
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                if (fresh) {
                    out.write(HEADER);
                    out.newLine();
                }
            }
            out.write(line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            System.out.printf("[Fog %2d] could not write %s (%s)%n", fogId, file, e.getMessage());
            out = null;
        }
    }
}
