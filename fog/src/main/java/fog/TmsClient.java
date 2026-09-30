package fog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sends visits to the Trail Management Service (Cloud) as GS1 EPCIS 2.0 documents
 * (HTTP POST /capture, the EPCIS 2.0 capture endpoint).
 *
 * Simple store-and-forward: if the TMS is down, the visit waits in an in-memory
 * queue and is resent periodically (FogNode calls resendPending).
 */
class TmsClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Deque<Visit> pending = new ArrayDeque<>();
    private final URI url = URI.create(Config.TMS_URL + "/capture");

    /** Tries to send now; on failure, keeps it to resend later. */
    void send(Visit v) {
        if (!pending.isEmpty() || !post(v)) {
            pending.addLast(v);
            System.out.printf("[TMS ] unavailable: %d visit(s) waiting to be resent%n", pending.size());
        }
    }

    /** Resends in the original order; stops at the first error. */
    void resendPending() {
        while (!pending.isEmpty() && post(pending.peekFirst())) {
            pending.removeFirst();
        }
    }

    private boolean post(Visit v) {
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(FogApp.JSON.toJson(Epcis.document(v))))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            System.out.printf("[TMS ] POST /capture (EPCIS) Fog %d %s-%s -> HTTP %d%n",
                    v.fog, v.arrival, v.departure, resp.statusCode());
            return ok;
        } catch (Exception e) {
            System.out.printf("[TMS ] POST failed (%s)%n", e.getClass().getSimpleName());
            return false;
        }
    }
}
