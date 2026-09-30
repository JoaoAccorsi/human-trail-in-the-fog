package fog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

/** HTTP/JSON calls between Fog nodes (direct communication, no intermediary). */
final class Rpc {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Rpc() {
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Synchronous POST. Returns the response JSON (or null if empty). Throws IOException if not 2xx. */
    static JsonElement post(String url, Object body) throws IOException {
        return post(url, body, DEFAULT_TIMEOUT);
    }

    /** POST with its own timeout (used by Chord to detect nodes that are down). */
    static JsonElement post(String url, Object body, Duration timeout) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(FogApp.JSON.toJson(body)))
                .build();
        return send(req);
    }

    /** Asynchronous POST (does not wait for the response). Used where waiting could deadlock two nodes. */
    static void postAsync(String url, Object body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(FogApp.JSON.toJson(body)))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    static JsonElement get(String url) throws IOException {
        return get(url, DEFAULT_TIMEOUT);
    }

    static JsonElement get(String url, Duration timeout) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build());
    }

    /** true if the node's port refused the connection (nothing is listening: the node is down). */
    static boolean refused(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException && !(t instanceof java.net.http.HttpConnectTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    /** true if the node answered with an HTTP error (it is up); false if it did not answer at all. */
    static boolean answered(IOException e) {
        return e.getMessage() != null && e.getMessage().startsWith("HTTP ");
    }

    private static JsonElement send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + r.statusCode() + " at " + req.uri());
            }
            return r.body().isBlank() ? null : JsonParser.parseString(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    // ------------------------------------------------------------ server side

    static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void respond(HttpExchange ex, int status, Object json) throws IOException {
        byte[] b = (json == null ? "" : (json instanceof String s ? s : FogApp.JSON.toJson(json)))
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        } else {
            ex.close();
        }
    }
}
