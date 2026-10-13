package fog;

import java.io.IOException;
import java.util.Properties;

import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;

/**
 * MQTT broker embedded in the Fog node (Moquette). Each Fog node has its own: the Edge
 * inside the Fog's area connects to its broker, like a phone to its base station.
 */
final class LocalBroker {

    private final Server server = new Server();
    private final int port;

    private LocalBroker(int port) {
        this.port = port;
    }

    static LocalBroker start(int port) throws IOException {
        LocalBroker b = new LocalBroker(port);
        Properties p = new Properties();
        p.setProperty("host", "0.0.0.0");
        p.setProperty("port", String.valueOf(port));
        p.setProperty("allow_anonymous", "true");
        // No disk persistence: each broker in memory. If Moquette still creates files,
        // each broker uses its own folder (otherwise the 2nd broker finds the file locked).
        // Do not use "persistent_store": in Moquette 0.17+ it forces persistence into a single folder.
        p.setProperty("persistence_enabled", "false");
        p.setProperty("data_path", "target/moquette/broker-" + port);
        p.setProperty("telemetry_enabled", "false");
        p.setProperty("buffer_flush_millis", "0");   // deliver messages immediately
        // Commands of the sessions are queued per event loop; with thousands of phones on the entry
        // node the default queue (1024) filled up ("Session command queue is full") and the broker stalled.
        p.setProperty("session_queue_size", Config.BROKER_QUEUE_SIZE);
        b.server.startServer(new MemoryConfig(p));
        return b;
    }

    int port() {
        return port;
    }

    void stop() {
        server.stopServer();
    }
}
