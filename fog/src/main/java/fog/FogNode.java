package fog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * An independent Fog node. It has:
 *  - its own MQTT broker (port 1900+id) where the Edges in its area publish;
 *  - its own HTTP API (port 9000+id) for the Chord ring and the handover;
 *  - its own state (users being served, open visits).
 * Fog nodes only talk over the network, directly (P2P), with no broker in between.
 *
 * Make-before-break handover (the Fog decides and commands the Edge):
 *   A: RSRP of B > RSRP of A + HOM for TTT (HandoverDecision) -> looks up B's address on the Chord ring
 *   A -> B  POST /handover/prepare {context}       B keeps the context as "prepared"
 *   A -> Edge (MQTT htf/edge/{h}/control)          "connect to B's broker"
 *   Edge -> B  ping on B's broker                  B activates the context and continues the trail
 *   B -> A  POST /handover/commit                  A closes the visit and sends it to the TMS
 *   Pings that still reach A in the meantime are relayed to B (POST /handover/ping).
 *
 * Handover API:
 *   POST /handover/prepare  {Context}         -> 200
 *   POST /handover/ping     {Ping}            -> 202 (relayed ping)
 *   POST /handover/commit   {h, nextFog}      -> 202
 *   POST /handover/transfer {h, nextFog}      -> 200 {Context} | 404  (user reappeared in another Fog)
 *   GET  /status                              -> node and ring state
 *   POST /admin/crash                         -> simulates a crash of this node (for failure tests)
 */
final class FogNode {

    private static final int MAX_HISTORY = 16;

    final int id;
    final NodeRef ref;
    final ChordNode chord;

    private LocalBroker broker;
    private HttpServer http;
    private MqttClient mqtt;
    private final TmsClient tms = new TmsClient();

    /** All node logic runs on this single thread: no concurrency on the state. */
    private final ExecutorService logic = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);

    private final Map<String, Context> contexts = new HashMap<>();            // users being served
    private final Map<String, Visit> openVisits = new HashMap<>();            // open visits
    private final Map<String, Context> prepared = new ConcurrentHashMap<>();  // received via PREPARE
    private final Map<String, NodeRef> inHandover = new HashMap<>();          // h -> target Fog
    private final Map<Integer, NodeRef> addresses = new ConcurrentHashMap<>(); // cache of "fog:{id}"

    FogNode(int id) {
        this.id = id;
        this.ref = NodeRef.ofFog(id);
        this.chord = new ChordNode(ref, this::log);
    }

    // =========================================================== startup

    void start(boolean seed) throws IOException, MqttException, InterruptedException {
        broker = LocalBroker.start(Config.MQTT_BASE_PORT + id);

        http = HttpServer.create(new InetSocketAddress(Config.HTTP_BASE_PORT + id), 0);
        chord.registerRoutes(http);
        registerHandoverRoutes();
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();

        if (seed) {
            chord.createRing();
        } else {
            joinRing();
        }
        timers.scheduleWithFixedDelay(chord::stabilize, 300, 500, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(chord::checkPredecessor, 600, 500, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(chord::fixFingers, 400, 150, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(this::publishAddress, 500, 3000, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(() -> logic.execute(tms::resendPending), 5, 5, TimeUnit.SECONDS);

        mqtt = new MqttClient(ref.mqtt, "htf-fog-" + id, new MemoryPersistence());
        mqtt.setCallback(new MqttCallback() {
            @Override
            public void messageArrived(String topic, MqttMessage msg) {
                Ping p = FogApp.JSON.fromJson(new String(msg.getPayload(), StandardCharsets.UTF_8), Ping.class);
                logic.execute(() -> safely(() -> onPing(p)));
            }

            @Override
            public void connectionLost(Throwable cause) {
                log("lost connection to own broker: %s", cause.getMessage());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        mqtt.connect(options);
        mqtt.subscribe(new String[] {Config.PINGS_TOPIC}, new int[] {Config.QOS});
        log("up | area x=%s y=%s | broker %s | API %s | Chord ring id=%d",
                range(0), range(1), ref.mqtt, ref.http, ref.id);
    }

    private void joinRing() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                chord.join(Config.SEED_URL);
                return;
            } catch (IOException e) {
                if (attempt % 10 == 1) {
                    log("waiting for seed node %s ...", Config.SEED_URL);
                }
                Thread.sleep(500);
            }
        }
    }

    /** "fog:{id}" record on the ring (soft state: republished periodically, expires if not refreshed). */
    private void publishAddress() {
        try {
            ref.seenAt = System.currentTimeMillis();
            chord.put("fog:" + id, ref);
        } catch (IOException e) {
            // ring still stabilizing
        }
    }

    void stop() {
        timers.shutdownNow();
        try {
            if (mqtt != null && mqtt.isConnected()) {
                mqtt.disconnect();
            }
        } catch (MqttException ignored) {
            // shutting down
        }
        if (http != null) {
            http.stop(0);
        }
        if (broker != null) {
            broker.stop();
        }
        logic.shutdownNow();
    }

    // =========================================================== logic ("logic" thread)

    private void onPing(Ping p) {
        String h = p.h;

        NodeRef pendingTarget = inHandover.get(h);
        if (pendingTarget != null) {                  // make-before-break: still arriving here
            relay(pendingTarget, p);
            return;
        }
        if (prepared.containsKey(h)) {                // first ping after PREPARE: take over the user
            activateHandover(h);
        }
        if ("end".equals(p.type)) {
            endDay(h);
            return;
        }

        Context ctx = contexts.get(h);
        if (ctx != null) {
            if (p.seq <= ctx.lastSeq) {
                return;                                   // repeated or out of order
            }
            HandoverDecision.Result d = HandoverDecision.evaluate(id, ctx, p);   // RSRP + Kalman + HOM + TTT
            if (d.target() == null) {
                record(p, ctx, d.summary());
            } else {
                startHandover(p, ctx, d.target(), d.summary());
            }
        } else if (Grid.reaches(id, p.x, p.y)) {
            attach(p);
        } else {
            redirect(p);
        }
    }

    /** New user in this Fog, inside the area. If it had a context in another Fog, look it up on the ring. */
    private void attach(Ping p) {
        Context ctx = null;
        try {
            ChordNode.Value v = chord.get("h:" + p.h);
            if (v.value() != null) {
                int owner = v.value().getAsJsonObject().get("fog").getAsInt();
                log("OIDA: context of %s... is at Fog %d (ring lookup: %d hops)", brief(p.h), owner, v.hops());
                if (owner != id) {
                    ctx = pullContext(owner, p.h);
                }
            }
        } catch (IOException e) {
            log("OIDA: context lookup failed (%s)", e.getMessage());
        }
        if (ctx == null) {
            ctx = new Context();
            ctx.h = p.h;
            ctx.day = p.day;
            log("user %s... attached (new context)", brief(p.h));
        } else {
            log("user %s... attached with the recovered context", brief(p.h));
        }
        ctx.origin = ref;
        contexts.put(p.h, ctx);
        registerOwner(p.h);
        record(p, ctx);
    }

    /** User this Fog does not serve and outside its area: points the Edge to the right Fog. */
    private void redirect(Ping p) {
        int target = Grid.fogOfCell(p.x, p.y);
        NodeRef b = resolveFog(target);
        if (b == null) {
            log("could not find Fog %d on the ring; ping %s dropped", target, p.time);
            return;
        }
        log("user %s... outside my area -> redirected to Fog %d", brief(p.h), target);
        commandEdge(p.h, b);
        relay(b, p);
    }

    /** Make-before-break: prepares the target, tells the Edge to switch broker and waits for the COMMIT. */
    private void startHandover(Ping p, Context ctx, int target, String why) {
        log("ping %s | cell (%d,%d) | %s -> HANDOVER to Fog %d", p.time, p.x, p.y, why, target);
        NodeRef b = resolveFog(target);
        if (b == null) {
            log("Fog %d not found on the ring; keeping the user here", target);
            record(p, ctx);
            return;
        }
        ctx.origin = ref;
        ctx.handovers++;
        ctx.triggerPing = p;
        try {
            FogCommunication.post(b.http + "/handover/prepare", ctx);                   // 1. PREPARE directly on Fog B
        } catch (IOException e) {
            log("PREPARE to Fog %d failed (%s); keeping the user here", target, e.getMessage());
            addresses.remove(target);
            ctx.handovers--;
            ctx.triggerPing = null;
            record(p, ctx);
            return;
        }
        inHandover.put(p.h, b);
        commandEdge(p.h, b);                                               // 2. Edge switches broker
        log("PREPARE accepted by Fog %d; Edge told to connect to %s", target, b.mqtt);
    }

    /** On the target: first ping (or relay) after the PREPARE. */
    private void activateHandover(String h) {
        Context ctx = prepared.remove(h);
        NodeRef origin = ctx.origin;
        Ping trigger = ctx.triggerPing;
        ctx.triggerPing = null;
        ctx.origin = ref;
        contexts.put(h, ctx);
        log("user %s... arrived; taking over (handover #%d, from Fog %d)", brief(h), ctx.handovers, origin.fogId);
        if (trigger != null) {
            record(trigger, ctx);
        }
        registerOwner(h);
        JsonObject commit = new JsonObject();
        commit.addProperty("h", h);
        commit.addProperty("nextFog", id);
        FogCommunication.postAsync(origin.http + "/handover/commit", commit);          // 3. COMMIT to Fog A
    }

    /** On the origin: the target confirmed; closes the visit and sends it to the TMS. */
    private void completeHandover(String h, int nextFog) {
        inHandover.remove(h);
        contexts.remove(h);
        Visit v = openVisits.remove(h);
        if (v != null) {
            v.reason = "handover";
            v.nextFog = nextFog;
            log("COMMIT from Fog %d: handover complete; visit %s-%s -> TMS", nextFog, v.arrival, v.departure);
            tms.send(v);
        }
    }

    /** Another Fog asked for the context of a user who reappeared there. */
    private Context handOverContext(String h, int nextFog) {
        Context ctx = contexts.remove(h);
        inHandover.remove(h);
        Visit v = openVisits.remove(h);
        if (v != null) {
            v.reason = "handover";
            v.nextFog = nextFog;
            tms.send(v);
        }
        if (ctx != null) {
            log("context of %s... handed over to Fog %d", brief(h), nextFog);
        }
        return ctx;
    }

    private void endDay(String h) {
        Visit v = openVisits.remove(h);
        contexts.remove(h);
        if (v != null) {
            v.reason = "end_of_day";
            log("end of day | visit %s-%s (%d pings) -> TMS", v.arrival, v.departure, v.pings);
            tms.send(v);
        }
    }

    private void record(Ping p, Context ctx) {
        record(p, ctx, null);
    }

    private void record(Ping p, Context ctx, String radio) {
        if (p.seq <= ctx.lastSeq) {
            return;                                     // repeated or out of order
        }
        ctx.lastSeq = p.seq;
        Visit v = openVisits.get(p.h);
        if (v == null) {
            v = new Visit();
            v.h = p.h;
            v.day = p.day;
            v.fog = id;
            v.arrivalT = p.t;
            v.arrival = p.time;
            openVisits.put(p.h, v);
        }
        v.departureT = p.t;
        v.departure = p.time;
        v.pings++;
        int[] last = v.cells.isEmpty() ? null : v.cells.get(v.cells.size() - 1);
        if (last == null || last[0] != p.x || last[1] != p.y) {
            v.cells.add(new int[] {p.x, p.y});
        }
        ctx.history.add(new int[] {p.t, p.x, p.y});
        if (ctx.history.size() > MAX_HISTORY) {
            ctx.history.remove(0);
        }
        log("ping %s | cell (%d,%d)%s", p.time, p.x, p.y, radio == null ? "" : " | " + radio);
    }

    // =========================================================== communication

    /** Address of another Fog, discovered on the Chord ring ("fog:{id}" record). */
    private NodeRef resolveFog(int fogId) {
        NodeRef cached = addresses.get(fogId);
        if (cached != null) {
            return cached;
        }
        try {
            ChordNode.Value v = chord.get("fog:" + fogId);
            if (v.value() == null) {
                return null;
            }
            NodeRef r = FogApp.JSON.fromJson(v.value(), NodeRef.class);
            if (System.currentTimeMillis() - r.seenAt > Config.ADDRESS_TTL_MS) {
                log("OIDA: address of Fog %d is stale (not refreshed for %d s): Fog %d looks down",
                        fogId, (System.currentTimeMillis() - r.seenAt) / 1000, fogId);
                return null;
            }
            addresses.put(fogId, r);
            log("OIDA: address of Fog %d resolved on the Chord ring (%d hops) -> %s", fogId, v.hops(), r.http);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    private void registerOwner(String h) {
        try {
            JsonObject v = new JsonObject();
            v.addProperty("fog", id);
            chord.put("h:" + h, v);
        } catch (IOException e) {
            log("OIDA: failed to register the owner of %s... (%s)", brief(h), e.getMessage());
        }
    }

    private Context pullContext(int owner, String h) {
        NodeRef a = resolveFog(owner);
        if (a == null) {
            return null;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("h", h);
            body.addProperty("nextFog", id);
            JsonElement r = FogCommunication.post(a.http + "/handover/transfer", body);
            return r == null ? null : FogApp.JSON.fromJson(r, Context.class);
        } catch (IOException e) {
            return null;                                // owner no longer has the context
        }
    }

    private void relay(NodeRef b, Ping p) {
        try {
            FogCommunication.post(b.http + "/handover/ping", p);
            log("ping %s of %s... relayed to Fog %d", p.time == null ? p.type : p.time, brief(p.h), b.fogId);
        } catch (IOException e) {
            log("failed to relay ping to Fog %d (%s)", b.fogId, e.getMessage());
            addresses.remove(b.fogId);
        }
    }

    /** Command to the Edge through this Fog's broker: "connect to Fog B's broker". */
    private void commandEdge(String h, NodeRef b) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "handover");
        cmd.addProperty("fog", b.fogId);
        cmd.addProperty("broker", b.mqtt);
        try {
            mqtt.publish(String.format(Config.CONTROL_TOPIC, h),
                    cmd.toString().getBytes(StandardCharsets.UTF_8), Config.QOS, false);
        } catch (MqttException e) {
            log("failed to command the Edge: %s", e.getMessage());
        }
    }

    // =========================================================== handover HTTP API

    private void registerHandoverRoutes() {
        http.createContext("/handover/prepare", ex -> {
            Context ctx = FogApp.JSON.fromJson(FogCommunication.body(ex), Context.class);
            prepared.put(ctx.h, ctx);
            log("PREPARE received from Fog %d for user %s...", ctx.origin.fogId, brief(ctx.h));
            FogCommunication.respond(ex, 200, "{\"ok\":true}");
        });
        http.createContext("/handover/ping", ex -> {
            Ping p = FogApp.JSON.fromJson(FogCommunication.body(ex), Ping.class);
            logic.execute(() -> safely(() -> onPing(p)));
            FogCommunication.respond(ex, 202, null);
        });
        http.createContext("/handover/commit", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            String h = b.get("h").getAsString();
            int nextFog = b.get("nextFog").getAsInt();
            logic.execute(() -> safely(() -> completeHandover(h, nextFog)));
            FogCommunication.respond(ex, 202, null);
        });
        http.createContext("/handover/transfer", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            String h = b.get("h").getAsString();
            int nextFog = b.get("nextFog").getAsInt();
            Future<Context> f = logic.submit(() -> handOverContext(h, nextFog));
            try {
                Context ctx = f.get(5, TimeUnit.SECONDS);
                FogCommunication.respond(ex, ctx == null ? 404 : 200, ctx);
            } catch (Exception e) {
                FogCommunication.respond(ex, 500, null);
            }
        });
        http.createContext("/status", ex -> {
            JsonObject r = new JsonObject();
            r.addProperty("fog", id);
            r.add("chord", chord.state());
            r.addProperty("users", contexts.size());
            FogCommunication.respond(ex, 200, r);
        });
        http.createContext("/admin/crash", ex -> {
            FogCommunication.respond(ex, 202, "{\"ok\":true}");
            Thread t = new Thread(() -> {
                log("SIMULATED CRASH: broker, HTTP API and timers stopped without telling anyone");
                stop();
            });
            t.setDaemon(true);
            t.start();
        });
    }

    // =========================================================== utilities

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log("error: %s", e);
        }
    }

    private String range(int axis) {
        int[] a = Grid.area(id);
        return axis == 0 ? a[0] + ".." + a[2] : a[1] + ".." + a[3];
    }

    private static String brief(String h) {
        return h == null ? "?" : h.substring(0, Math.min(8, h.length()));
    }

    void log(String format, Object... args) {
        System.out.printf("[Fog %2d] %s%n", id, String.format(format, args));
    }
}
