package edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
 * EDGE - simulates the users' phones.
 *
 * 1. Reads the records of the users (HTF_USERS, or HTF_USER) on Config.DAY from YJMob100K.
 * 2. Replaces each uid with the pseudonym h(s, e) = HMAC-SHA256(salt, uid).
 * 3. Each user is a separate simulated phone: it connects to the entry broker (Fog 1) and
 *    subscribes to htf/edge/{h}/control. The phones of this process share one MQTT connection
 *    per Fog broker (see Brokers); each phone publishes on the broker of the Fog serving it.
 * 4. The day is replayed on a shared clock: for every timeslot that has records, the pings of
 *    all users in that slot are published (in parallel), then the Edge waits PING_INTERVAL_MS.
 * 5. When the Fog sends a handover command ("connect to the broker of Fog B"), the phone switches
 *    at once: it subscribes on the new broker, sends an "attach" message there (so Fog B takes over
 *    without waiting for the next ping, which may come hours later in the dataset) and only then
 *    leaves the old broker (make-before-break).
 * 6. At the end, each phone publishes an "end" message so the Fog closes its day.
 * 7. If the broker of the target Fog cannot be reached, the phone stays on the current one; if the
 *    current broker stops answering, it reconnects to the entry broker, which redirects it to the
 *    Fog that owns its context on the Chord ring.
 *
 * With a single user the log shows every ping; with many users, one summary line per timeslot.
 * The Edge does not know how the city is split into Fogs: the Fog decides where it goes.
 *
 *   cd edge && mvn -q compile exec:exec                                   (HTF_USER, default 4005)
 *   cd edge && HTF_USERS=4000-5000 HTF_DAY=20 mvn -q compile exec:exec    (1,001 users)
 */
public class EdgeApp {

    private static final Gson JSON = new Gson();
    private static final String ENTRY = "entry (Fog " + Config.ENTRY_FOG + ")";

    /** Handover command received from the Fog: {"type":"handover","fog":8,"broker":"tcp://localhost:1908"}. */
    static class Command {
        String type;
        int fog;
        String broker;
    }

    private static boolean verbose;

    /**
     * MQTT connections to the Fog brokers, shared by all the simulated phones of this process:
     * one connection per broker (at most one per Fog node), instead of one per phone. Each phone
     * subscribes to its own control topic (htf/edge/{h}/control) on the broker of the Fog that
     * serves it and publishes its pings there, so for the Fog nodes nothing changes. Sharing
     * avoids ~4 threads per phone in the MQTT client (1,000 phones would need ~4,000 threads,
     * above the per-process limit of macOS). Commands are routed to the phone by the topic.
     */
    static final class Brokers {
        private final Map<String, MqttClient> clients = new HashMap<>();
        private final Map<String, Device> byPseudonym;
        private final String clientPrefix = "htf-edge-" + ProcessHandle.current().pid() + "-";

        Brokers(Map<String, Device> byPseudonym) {
            this.byPseudonym = byPseudonym;
        }

        /** Connected client of a broker (creates or recreates it if needed). */
        synchronized MqttClient client(String broker) throws MqttException {
            MqttClient c = clients.get(broker);
            if (c != null && c.isConnected()) {
                return c;
            }
            if (c != null) {
                closeQuietly(c);
            }
            c = new MqttClient(broker, clientPrefix + Math.abs(broker.hashCode()), new MemoryPersistence());
            c.setCallback(new MqttCallback() {
                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    // topic: htf/edge/{h}/control
                    String[] parts = topic.split("/");
                    Device d = parts.length >= 3 ? byPseudonym.get(parts[2]) : null;
                    Command cmd = JSON.fromJson(new String(msg.getPayload(), StandardCharsets.UTF_8), Command.class);
                    if (d != null && "handover".equals(cmd.type)) {
                        if (verbose) {
                            System.out.printf("[Edge] command from Fog: connect to Fog %d (%s)%n", cmd.fog, cmd.broker);
                        }
                        d.pending = cmd;
                        // switch right away (not at the next ping), off the MQTT callback thread
                        switcher.execute(d::switchBrokerIfNeeded);
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
            options.setConnectionTimeout(10);
            options.setMaxInflight(1000);                 // many phones publish at once on one connection
            c.connect(options);
            clients.put(broker, c);
            return c;
        }

        void closeAll() {
            synchronized (this) {
                clients.values().forEach(EdgeApp::closeQuietly);
                clients.clear();
            }
        }
    }

    private static Brokers brokers;
    /** Applies handover commands as soon as they arrive (a phone does not wait for its next ping). */
    private static final ExecutorService switcher = Executors.newFixedThreadPool(4);

    /** One simulated phone: its own pseudonym, broker and handover state. */
    static final class Device {
        final int uid;
        final String h;
        final List<Ping> pings = new ArrayList<>();
        volatile Command pending;
        String broker;                                   // broker of the Fog serving this phone
        String currentFog = ENTRY;
        int handovers;
        boolean failed;

        Device(int uid, String h) {
            this.uid = uid;
            this.h = h;
        }

        String controlTopic() {
            return String.format(Config.CONTROL_TOPIC, h);
        }

        /** "Connects" this phone to a broker: subscribes to its control topic there. */
        void attach(String b) throws MqttException {
            brokers.client(b).subscribe(new String[] {controlTopic()}, new int[] {Config.QOS});
            broker = b;
        }

        void detach(String b) {
            try {
                brokers.client(b).unsubscribe(controlTopic());
            } catch (MqttException ignored) {
                // the old broker is gone: nothing to undo there
            }
        }

        /**
         * Make-before-break: subscribes on the new broker, tells the new Fog it arrived ("attach",
         * so the Fog takes over at once even if the next ping is hours later in the dataset), and
         * only then leaves the old one. Synchronized: runs both on command arrival and before a ping.
         */
        synchronized void switchBrokerIfNeeded() {
            Command c = pending;
            if (c == null) {
                return;
            }
            pending = null;
            if (c.broker.equals(broker)) {
                return;
            }
            String old = broker;
            try {
                attach(c.broker);
            } catch (MqttException e) {
                System.out.printf("[Edge] user %d: could not connect to Fog %d (%s, reason %d); staying on %s%n",
                        uid, c.fog, c.broker, e.getReasonCode(), currentFog);
                return;
            }
            try {
                publish(brokers.client(c.broker), Ping.attach(h, Config.DAY));
            } catch (MqttException e) {
                // the first ping on the new broker activates the handover as well
            }
            detach(old);
            if (!ENTRY.equals(currentFog)) {
                handovers++;
            }
            currentFog = "Fog " + c.fog;
            if (verbose) {
                System.out.printf("[Edge] HANDOVER: now connected to Fog %d (%s)%n", c.fog, c.broker);
            }
        }

        /**
         * Publishes on the current broker. If it does not answer (e.g. the Fog went down), the phone
         * goes back to the entry broker and publishes there: the entry node redirects it to the Fog
         * that owns its context, or to the Fog of its cell.
         */
        synchronized void publishOrFallBack(Ping p) throws MqttException, InterruptedException {
            try {
                publish(brokers.client(broker), p);
                return;
            } catch (MqttException e) {
                System.out.printf("[Edge] user %d: broker of %s did not answer (reason %d); back to the entry broker %s%n",
                        uid, currentFog, e.getReasonCode(), Config.ENTRY_BROKER);
            }
            pending = null;
            for (int attempt = 1; ; attempt++) {
                try {
                    attach(Config.ENTRY_BROKER);
                    break;
                } catch (MqttException e) {
                    if (attempt >= 30) {
                        throw e;
                    }
                    Thread.sleep(1000);
                }
            }
            currentFog = ENTRY;
            publish(brokers.client(broker), p);
        }

        /** One timeslot of this phone: follow a pending handover command, then publish. */
        void step(Ping p) {
            if (failed) {
                return;
            }
            try {
                switchBrokerIfNeeded();
                publishOrFallBack(p);
                if (verbose) {
                    System.out.printf("[Edge] %s  cell (%d,%d)  -> %s%n", p.time, p.x, p.y, currentFog);
                }
            } catch (MqttException | InterruptedException e) {
                failed = true;
                System.out.printf("[Edge] user %d: gave up (%s)%n", uid, e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<Integer> uids = Config.users();
        verbose = uids.size() == 1;
        Map<Integer, Device> devices = new LinkedHashMap<>();
        for (int uid : uids) {
            devices.put(uid, new Device(uid, pseudonym(Config.SALT, uid)));
        }
        readPings(Path.of(Config.DATASET), devices, Config.DAY);
        devices.values().removeIf(d -> d.pings.isEmpty());
        if (devices.isEmpty()) {
            System.out.printf("No records for user(s) %s on day %d.%n", describe(uids), Config.DAY);
            return;
        }
        Map<String, Device> byPseudonym = new HashMap<>();
        devices.values().forEach(d -> byPseudonym.put(d.h, d));
        brokers = new Brokers(byPseudonym);
        int totalPings = devices.values().stream().mapToInt(d -> d.pings.size()).sum();
        if (verbose) {
            Device d = devices.values().iterator().next();
            System.out.printf("[Edge] user %d, day %d -> pseudonym %s... (%d pings)%n",
                    d.uid, Config.DAY, d.h.substring(0, 16), d.pings.size());
        } else {
            System.out.printf("[Edge] users %s, day %d: %d with records (%d without), %d pings in total%n",
                    describe(uids), Config.DAY, devices.size(), uids.size() - devices.size(), totalPings);
        }

        if (Config.START_DELAY_S > 0) {
            System.out.printf("[Edge] waiting %d s before starting%n", Config.START_DELAY_S);
            Thread.sleep(Config.START_DELAY_S * 1000L);
        }
        ExecutorService pool = Executors.newFixedThreadPool(verbose ? 1 : Config.EDGE_THREADS);
        try {
            connectAll(pool, devices.values());

            // shared clock: timeslot -> pings of every user in that slot
            TreeMap<Integer, List<Map.Entry<Device, Ping>>> slots = new TreeMap<>();
            for (Device d : devices.values()) {
                for (Ping p : d.pings) {
                    slots.computeIfAbsent(p.t, t -> new ArrayList<>()).add(Map.entry(d, p));
                }
            }
            long started = System.currentTimeMillis();
            for (Map.Entry<Integer, List<Map.Entry<Device, Ping>>> slot : slots.entrySet()) {
                long t0 = System.currentTimeMillis();
                List<Callable<Void>> tasks = new ArrayList<>();
                for (Map.Entry<Device, Ping> dp : slot.getValue()) {
                    tasks.add(() -> {
                        dp.getKey().step(dp.getValue());
                        return null;
                    });
                }
                pool.invokeAll(tasks);
                long took = System.currentTimeMillis() - t0;
                if (!verbose) {
                    int onEntry = 0;
                    int handovers = 0;
                    for (Device d : devices.values()) {
                        onEntry += ENTRY.equals(d.currentFog) ? 1 : 0;
                        handovers += d.handovers;
                    }
                    System.out.printf("[Edge] %s | %4d ping(s) in %4d ms | %4d phone(s) still on the entry broker | %d handover(s) so far%n",
                            Config.time(slot.getKey()), slot.getValue().size(), took, onEntry, handovers);
                }
                Thread.sleep(Math.max(0, Config.PING_INTERVAL_MS - took));
            }

            List<Callable<Void>> ends = new ArrayList<>();
            for (Device d : devices.values()) {
                ends.add(() -> {
                    if (!d.failed) {
                        try {
                            d.switchBrokerIfNeeded();
                            d.publishOrFallBack(Ping.end(d.h, Config.DAY));
                        } catch (MqttException | InterruptedException e) {
                            System.out.printf("[Edge] user %d: could not publish the end of day (%s)%n", d.uid, e.getMessage());
                        }
                    }
                    return null;
                });
            }
            pool.invokeAll(ends);
            if (verbose) {
                System.out.printf("[Edge] end of day published -> %s%n", devices.values().iterator().next().currentFog);
            } else {
                int handovers = devices.values().stream().mapToInt(d -> d.handovers).sum();
                long failed = devices.values().stream().filter(d -> d.failed).count();
                System.out.printf("[Edge] end of day published by %d phone(s) | %d handover(s) | %d failed | %.0f s%n",
                        devices.size() - failed, handovers, failed, (System.currentTimeMillis() - started) / 1000.0);
            }
            Thread.sleep(500);
        } finally {
            switcher.shutdownNow();
            brokers.closeAll();
            pool.shutdownNow();
        }
    }

    /** Connects every phone to the entry broker, retrying up to CONNECT_RETRY_S. */
    private static void connectAll(ExecutorService pool, Iterable<Device> devices) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Config.CONNECT_RETRY_S * 1000L;
        while (true) {
            try {
                brokers.client(Config.ENTRY_BROKER);
                break;
            } catch (MqttException e) {
                if (System.currentTimeMillis() < deadline) {
                    System.out.printf("[Edge] broker %s not available yet, retrying...%n", Config.ENTRY_BROKER);
                    Thread.sleep(5000);
                    continue;
                }
                System.out.printf("[Edge] could not connect to broker %s (%d).%n"
                        + "       Is the Fog running? Use: cd fog && mvn -q compile exec:exec%n",
                        Config.ENTRY_BROKER, e.getReasonCode());
                System.exit(1);
            }
        }
        AtomicInteger connected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Device d : devices) {
            tasks.add(() -> {
                try {
                    d.attach(Config.ENTRY_BROKER);
                    connected.incrementAndGet();
                } catch (MqttException e) {
                    d.failed = true;
                    System.out.printf("[Edge] user %d: could not subscribe on %s (%d)%n", d.uid, Config.ENTRY_BROKER, e.getReasonCode());
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        System.out.printf("[Edge] %d phone(s) connected to entry broker %s%n", connected.get(), Config.ENTRY_BROKER);
    }

    private static String describe(List<Integer> uids) {
        return uids.size() == 1 ? String.valueOf(uids.get(0))
                : uids.get(0) + ".." + uids.get(uids.size() - 1) + " (" + uids.size() + ")";
    }

    /** h(s, e) = HMAC-SHA256(salt, uid) in hexadecimal. */
    public static String pseudonym(String salt, int uid) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(String.valueOf(uid).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Reads the CSV (uid,d,t,x,y) once for all users. The file is sorted by uid, so reading stops
     * as soon as it passes the last user (no need to scan all 570 MB).
     */
    static void readPings(Path csv, Map<Integer, Device> devices, int day) throws IOException {
        int last = devices.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        Map<Integer, Device> byUid = new HashMap<>(devices);
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            in.readLine(); // header
            String line;
            while ((line = in.readLine()) != null) {
                int comma = line.indexOf(',');
                int u = Integer.parseInt(line.substring(0, comma));
                if (u > last) {
                    break;
                }
                Device dev = byUid.get(u);
                if (dev == null) {
                    continue;
                }
                String[] c = line.split(",");
                int d = Integer.parseInt(c[1]);
                int x = Integer.parseInt(c[3]);
                int y = Integer.parseInt(c[4]);
                if (d != day || x == 999 || y == 999) { // 999 = masked position
                    continue;
                }
                Ping p = new Ping();
                p.h = dev.h;
                p.day = d;
                p.t = Integer.parseInt(c[2]);
                p.time = Config.time(p.t);
                p.x = x;
                p.y = y;
                p.seq = d * 48 + p.t;
                dev.pings.add(p);
            }
        }
        devices.values().forEach(dev -> dev.pings.sort((a, b) -> Integer.compare(a.t, b.t)));
    }

    static void publish(MqttClient mqtt, Ping p) throws MqttException {
        byte[] json = JSON.toJson(p).getBytes(StandardCharsets.UTF_8);
        mqtt.publish(Config.PINGS_TOPIC, json, Config.QOS, false);
    }

    static void closeQuietly(MqttClient c) {
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
}
