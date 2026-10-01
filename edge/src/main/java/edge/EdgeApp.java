package edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.google.gson.Gson;

/**
 * EDGE - simulates the user's phone.
 *
 * 1. Reads the records of Config.USER on Config.DAY from YJMob100K.
 * 2. Replaces the uid with the pseudonym h(s, e) = HMAC-SHA256(salt, uid).
 * 3. Connects to the entry broker (Fog 1) and subscribes to htf/edge/{h}/control.
 * 4. Publishes one ping per record on htf/pings, waiting PING_INTERVAL_MS between them.
 * 5. When the Fog sends a handover command ("connect to the broker of Fog B"), it connects to
 *    the new broker BEFORE disconnecting from the old one (make-before-break) and keeps publishing there.
 * 6. At the end, publishes an "end" message so the Fog closes the day.
 * 7. If the broker of the target Fog cannot be reached, it stays on the current one; if the
 *    current broker stops answering, it reconnects to the entry broker (Fog 1), which
 *    redirects it to the Fog that owns its context on the Chord ring.
 *
 * The Edge does not know how the city is split into Fogs: the Fog decides where it goes.
 *
 *   cd edge && mvn -q compile exec:exec
 */
public class EdgeApp {

    private static final Gson JSON = new Gson();

    /** Handover command received from the Fog: {"type":"handover","fog":8,"broker":"tcp://localhost:1908"}. */
    static class Command {
        String type;
        int fog;
        String broker;
    }

    private static volatile Command pending;
    private static MqttClient current;
    private static String currentFog = "entry (Fog " + Config.ENTRY_FOG + ")";

    public static void main(String[] args) throws Exception {
        String h = pseudonym(Config.SALT, Config.USER);
        List<Ping> pings = readPings(Path.of(Config.DATASET), Config.USER, Config.DAY, h);
        if (pings.isEmpty()) {
            System.out.printf("No records for user %d on day %d.%n", Config.USER, Config.DAY);
            return;
        }
        System.out.printf("[Edge] user %d, day %d -> pseudonym %s... (%d pings)%n",
                Config.USER, Config.DAY, h.substring(0, 16), pings.size());

        if (Config.START_DELAY_S > 0) {
            System.out.printf("[Edge] waiting %d s before starting%n", Config.START_DELAY_S);
            Thread.sleep(Config.START_DELAY_S * 1000L);
        }
        long deadline = System.currentTimeMillis() + Config.CONNECT_RETRY_S * 1000L;
        while (current == null) {
            try {
                current = connect(Config.ENTRY_BROKER, h);
            } catch (MqttException e) {
                if (System.currentTimeMillis() < deadline) {
                    System.out.printf("[Edge] broker %s not available yet, retrying...%n", Config.ENTRY_BROKER);
                    Thread.sleep(5000);
                    continue;
                }
                System.out.printf("[Edge] could not connect to broker %s (%s).%n"
                        + "       Is the Fog running? Use: cd fog && mvn -q compile exec:exec%n",
                        Config.ENTRY_BROKER, e.getReasonCode());
                System.exit(1);
                return;
            }
        }
        System.out.printf("[Edge] connected to entry broker %s%n", Config.ENTRY_BROKER);

        for (Ping p : pings) {
            switchBrokerIfNeeded(h);
            publishOrFallBack(h, p);
            System.out.printf("[Edge] %s  cell (%d,%d)  -> %s%n", p.time, p.x, p.y, currentFog);
            Thread.sleep(Config.PING_INTERVAL_MS);
        }
        switchBrokerIfNeeded(h);
        publishOrFallBack(h, Ping.end(h, Config.DAY));
        System.out.printf("[Edge] end of day published -> %s%n", currentFog);
        Thread.sleep(500);
        current.disconnect();
        current.close();
    }

    /** Make-before-break: connects to the new broker, subscribes to control there, and only then drops the old one. */
    private static void switchBrokerIfNeeded(String h) throws MqttException {
        Command c = pending;
        if (c == null) {
            return;
        }
        pending = null;
        if (c.broker.equals(current.getServerURI())) {
            return;
        }
        MqttClient next;
        try {
            next = connect(c.broker, h);
        } catch (MqttException e) {
            System.out.printf("[Edge] could not connect to Fog %d (%s, reason %d); staying on %s%n",
                    c.fog, c.broker, e.getReasonCode(), currentFog);
            return;
        }
        MqttClient old = current;
        current = next;
        closeQuietly(old);
        currentFog = "Fog " + c.fog;
        System.out.printf("[Edge] HANDOVER: now connected to Fog %d (%s)%n", c.fog, c.broker);
    }

    /**
     * Publishes on the current broker. If it does not answer (e.g. the Fog went down), the Edge
     * goes back to the entry broker and publishes there: the entry node redirects it to the Fog
     * that owns its context, or to the Fog of its cell.
     */
    private static void publishOrFallBack(String h, Ping p) throws MqttException, InterruptedException {
        try {
            publish(current, p);
            return;
        } catch (MqttException e) {
            System.out.printf("[Edge] broker of %s did not answer (reason %d); back to the entry broker %s%n",
                    currentFog, e.getReasonCode(), Config.ENTRY_BROKER);
        }
        closeQuietly(current);
        pending = null;
        for (int attempt = 1; ; attempt++) {
            try {
                current = connect(Config.ENTRY_BROKER, h);
                break;
            } catch (MqttException e) {
                if (attempt >= 30) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
        currentFog = "entry (Fog " + Config.ENTRY_FOG + ")";
        publish(current, p);
    }

    private static void closeQuietly(MqttClient c) {
        try {
            if (c.isConnected()) {
                c.disconnect();
            }
        } catch (MqttException ignored) {
            // the broker is already gone
        }
        try {
            c.close();
        } catch (MqttException ignored) {
            // nothing else to release
        }
    }

    /** h(s, e) = HMAC-SHA256(salt, uid) in hexadecimal. */
    public static String pseudonym(String salt, int uid) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(String.valueOf(uid).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Reads the CSV (uid,d,t,x,y). The file is sorted by uid, so reading stops
     * as soon as it passes the user (no need to scan all 570 MB).
     */
    static List<Ping> readPings(Path csv, int uid, int day, String h) throws IOException {
        List<Ping> pings = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            in.readLine(); // header
            String line;
            while ((line = in.readLine()) != null) {
                String[] c = line.split(",");
                int u = Integer.parseInt(c[0]);
                if (u < uid) {
                    continue;
                }
                if (u > uid) {
                    break;
                }
                int d = Integer.parseInt(c[1]);
                int x = Integer.parseInt(c[3]);
                int y = Integer.parseInt(c[4]);
                if (d != day || x == 999 || y == 999) { // 999 = masked position
                    continue;
                }
                Ping p = new Ping();
                p.h = h;
                p.day = d;
                p.t = Integer.parseInt(c[2]);
                p.time = Config.time(p.t);
                p.x = x;
                p.y = y;
                p.seq = d * 48 + p.t;
                pings.add(p);
            }
        }
        pings.sort((a, b) -> Integer.compare(a.t, b.t));
        return pings;
    }

    /** Connects to a broker and subscribes to this user's control topic. */
    static MqttClient connect(String broker, String h) throws MqttException {
        MqttClient mqtt = new MqttClient(broker, "htf-edge-" + Config.USER, new MemoryPersistence());
        mqtt.setCallback(new MqttCallback() {
            @Override
            public void messageArrived(String topic, MqttMessage msg) {
                Command c = JSON.fromJson(new String(msg.getPayload(), StandardCharsets.UTF_8), Command.class);
                if ("handover".equals(c.type)) {
                    System.out.printf("[Edge] command from Fog: connect to Fog %d (%s)%n", c.fog, c.broker);
                    pending = c;
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        mqtt.connect(options);
        mqtt.subscribe(new String[] {String.format(Config.CONTROL_TOPIC, h)}, new int[] {Config.QOS});
        return mqtt;
    }

    static void publish(MqttClient mqtt, Ping p) throws MqttException {
        byte[] json = JSON.toJson(p).getBytes(StandardCharsets.UTF_8);
        mqtt.publish(Config.PINGS_TOPIC, json, Config.QOS, false);
    }
}
