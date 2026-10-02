package fog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   If B does not COMMIT within HANDOVER_TIMEOUT_MS, or a relay to B fails, A aborts the
 *   handover: it cancels the PREPARE on B and keeps serving the user (the Edge, if it cannot
 *   reach B, falls back to the entry broker, which redirects it to A, the owner on the ring).
 *
 * Single owner per user (OIDA record "h:{h}" on the Chord ring):
 *   - a Fog that does not serve the user redirects it to the Fog that owns the record,
 *     and only falls back to the Fog of the cell when the user has no owner;
 *   - ownership is taken with a conditional write (ChordNode.claim): the Fog with the
 *     most recent ping wins. A Fog that loses closes its visit and drops the context; a
 *     Fog that wins over an unexpected owner tells it to do so (POST /handover/release);
 *   - a stale open visit is never extended: it is closed before a new stay starts;
 *   - a record lost with a failed node comes back on the node that inherited its keys: the
 *     predecessor of the failed node broadcasts the lost range of keys over the finger tables
 *     (POST /ring/changed, each node forwards it inside its interval), and every node
 *     republishes only the records of its users that fall
 *     in that range, after a random delay, off the logic thread and grouped by responsible
 *     node. All records are also republished every OWNER_REFRESH_MS (safety net).
 *
 * Handover API:
 *   POST /handover/prepare  {Context}         -> 200
 *   POST /handover/ping     {Ping}            -> 202 (relayed ping)
 *   POST /handover/commit   {h, nextFog}      -> 202
 *   POST /handover/transfer {h, nextFog}      -> 200 {Context} | 404  (user reappeared in another Fog)
 *   POST /handover/release  {h, nextFog}      -> 202  (another Fog now owns the user: close and drop)
 *   POST /handover/cancel   {h}               -> 202  (the origin aborted the handover: drop the PREPARE)
 *   POST /ring/changed      {fog, from, to, limit} -> 202  (keys (from, to] were lost: republish mine
 *                                                    there and forward to my fingers in (me, limit))
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
    private final TmsClient tms;

    /** All node logic runs on this single thread: no concurrency on the state. */
    private final ExecutorService logic = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);

    private final Map<String, Context> contexts = new HashMap<>();            // users being served
    private final Map<String, Visit> openVisits = new HashMap<>();            // open visits
    private final Map<String, Context> prepared = new ConcurrentHashMap<>();  // received via PREPARE
    private final Map<String, NodeRef> inHandover = new HashMap<>();          // h -> target Fog
    private final Map<Integer, NodeRef> addresses = new ConcurrentHashMap<>(); // cache of "fog:{id}"
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);
    /** Lost key ranges waiting for the next targeted refresh (guarded by itself). */
    private final List<ChordNode.Range> lostRanges = new ArrayList<>();
    /** Lost ranges already received (broadcast deduplication), with the time they arrived. */
    private final Map<ChordNode.Range, Long> seenRanges = new HashMap<>();
    /** Owner-record writes and failure announcements: never on the logic thread. */
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    FogNode(int id) {
        this.id = id;
        this.ref = NodeRef.ofFog(id);
        this.chord = new ChordNode(ref, this::log);
        this.tms = new TmsClient(id);
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
        timers.scheduleWithFixedDelay(() -> logic.execute(() -> safely(() -> refreshOwnership(null, null))),
                Config.OWNER_REFRESH_MS, Config.OWNER_REFRESH_MS, TimeUnit.MILLISECONDS);
        chord.onRingChange(range -> {
            scheduleOwnerRefresh(range, "my successor went down");
            background.execute(() -> safely(() -> announceRingChange(range)));
        });
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
        log("up | area x=%s y=%s | broker %s | API %s | Chord ring id=%s",
                range(0), range(1), ref.mqtt, ref.http, ChordNode.show(ref.id));
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
        background.shutdownNow();
    }

    // =========================================================== logic ("logic" thread)

    private void onPing(Ping p) {
        String h = p.h;

        NodeRef pendingTarget = inHandover.get(h);
        if (pendingTarget != null) {                  // make-before-break: still arriving here
            if (relay(pendingTarget, p)) {
                return;
            }
            abortHandover(h, pendingTarget, "relay to Fog " + pendingTarget.fogId + " failed");
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

    /**
     * New user in this Fog, inside the area. If another Fog owns the user's record on the ring:
     *  - a ping older than that record arrived late (e.g. relayed by the entry node): it is
     *    forwarded to the owner, which drops it if it already has a more recent one;
     *  - otherwise the context is pulled from the owner, which closes its visit.
     */
    private void attach(Ping p) {
        Context ctx = null;
        Integer pulledFrom = null;
        try {
            ChordNode.Value v = chord.get("h:" + p.h);
            int owner = fogOf(v.value());
            if (owner > 0 && owner != id) {
                log("OIDA: context of %s... is at Fog %d (ring lookup: %d hops)", brief(p.h), owner, v.hops());
                if (p.seq < seqOf(v.value())) {
                    NodeRef o = resolveFog(owner);
                    if (o != null) {
                        log("ping %s of %s... is older than Fog %d's record -> forwarded to it", p.time, brief(p.h), owner);
                        relay(o, p);
                        return;
                    }
                }
                ctx = pullContext(owner, p.h);
                if (ctx != null) {
                    pulledFrom = owner;
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
        closeStaleVisit(p.h);
        contexts.put(p.h, ctx);
        record(p, ctx);
        claimOwnership(p.h, ctx, pulledFrom);
    }

    /** User this Fog does not serve and outside its area: points the Edge to the right Fog. */
    private void redirect(Ping p) {
        Integer owner = ownerOf(p.h);
        if (owner != null && owner != id) {             // the user already has a serving Fog: send it there
            NodeRef o = resolveFog(owner);
            if (o != null) {
                log("user %s... is served by Fog %d -> redirected to it", brief(p.h), owner);
                commandEdge(p.h, o);
                if (relay(o, p)) {
                    return;
                }
                log("Fog %d did not answer; redirecting by area instead", owner);
            }
        }
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
        String h = p.h;
        timers.schedule(() -> logic.execute(() -> safely(() -> {
            if (b.equals(inHandover.get(h))) {
                abortHandover(h, b, String.format("no COMMIT from Fog %d within %d s",
                        b.fogId, Config.HANDOVER_TIMEOUT_MS / 1000));
            }
        })), Config.HANDOVER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * On the origin: the target did not confirm the handover. The user stays here: the ping
     * that triggered the handover is recorded, the PREPARE on the target is cancelled and the
     * TTT counter starts again. If the target confirms later anyway (late COMMIT), the normal
     * completion still applies and this node closes its visit.
     */
    private void abortHandover(String h, NodeRef b, String why) {
        inHandover.remove(h);
        addresses.remove(b.fogId);
        Context ctx = contexts.get(h);
        log("HANDOVER of %s... to Fog %d aborted (%s); the user stays in Fog %d", brief(h), b.fogId, why, id);
        if (ctx != null) {
            ctx.handovers--;
            Ping trigger = ctx.triggerPing;
            ctx.triggerPing = null;
            if (trigger != null) {
                record(trigger, ctx);
            }
        }
        JsonObject cancel = new JsonObject();
        cancel.addProperty("h", h);
        FogCommunication.postAsync(b.http + "/handover/cancel", cancel);
        commandEdge(h, ref);                                               // if the Edge is still here: stay
    }

    /** On the target: first ping (or relay) after the PREPARE. */
    private void activateHandover(String h) {
        Context ctx = prepared.remove(h);
        NodeRef origin = ctx.origin;
        Ping trigger = ctx.triggerPing;
        ctx.triggerPing = null;
        ctx.origin = ref;
        closeStaleVisit(h);
        contexts.put(h, ctx);
        log("user %s... arrived; taking over (handover #%d, from Fog %d)", brief(h), ctx.handovers, origin.fogId);
        if (trigger != null) {
            record(trigger, ctx);
        }
        claimOwnership(h, ctx, origin.fogId);
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
        Context ctx = contexts.remove(h);
        if (ctx != null) {
            releaseOwnership(h, ctx.lastSeq);
        }
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

    /** Fog that owns the user's record on the ring, or null (no record, released or lookup failed). */
    private Integer ownerOf(String h) {
        try {
            ChordNode.Value v = chord.get("h:" + h);
            int fog = fogOf(v.value());
            return fog > 0 ? fog : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Takes ownership of the user on the ring. The record with the most recent ping wins:
     *  - if another Fog holds a more recent record, this Fog closes its visit and drops the context;
     *  - if this Fog won over an owner other than the expected one (the origin of a handover),
     *    that owner is told to close its visit and drop the context (two owners never coexist).
     */
    private ChordNode.Claim claimOwnership(String h, Context ctx, Integer expectedPrevious) {
        JsonObject record = new JsonObject();
        record.addProperty("fog", id);
        record.addProperty("seq", ctx.lastSeq);
        ChordNode.Claim c;
        try {
            c = chord.claim("h:" + h, record);
        } catch (IOException e) {
            log("OIDA: failed to register the owner of %s... (%s)", brief(h), e.getMessage());
            return null;
        }
        int winner = fogOf(c.stored());
        int previous = fogOf(c.previous());
        if (winner != id) {
            log("OIDA: Fog %d holds a more recent context of %s...; releasing mine", winner, brief(h));
            handOverContext(h, winner);
            return c;
        }
        if (previous > 0 && previous != id && (expectedPrevious == null || previous != expectedPrevious)) {
            NodeRef old = resolveFog(previous);
            if (old != null) {
                log("OIDA: %s... was still registered at Fog %d; telling it to release the user", brief(h), previous);
                JsonObject body = new JsonObject();
                body.addProperty("h", h);
                body.addProperty("nextFog", id);
                FogCommunication.postAsync(old.http + "/handover/release", body);
            }
        }
        return c;
    }

    // =========================================================== owner records after failures

    /**
     * Schedules a targeted refresh of the lost range, after OWNER_REFRESH_DELAY_MS plus a random
     * jitter. Ranges reported while a refresh is pending are merged into it.
     */
    private void scheduleOwnerRefresh(ChordNode.Range range, String reason) {
        synchronized (lostRanges) {
            lostRanges.add(range);
        }
        if (refreshScheduled.compareAndSet(false, true)) {
            long delay = Config.OWNER_REFRESH_DELAY_MS
                    + ThreadLocalRandom.current().nextLong(Config.OWNER_REFRESH_JITTER_MS + 1L);
            timers.schedule(() -> logic.execute(() -> safely(() -> {
                refreshScheduled.set(false);
                List<ChordNode.Range> ranges;
                synchronized (lostRanges) {
                    ranges = new ArrayList<>(lostRanges);
                    lostRanges.clear();
                }
                refreshOwnership(ranges, reason);
            })), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Republishes "h:{h} -> this Fog" for the users served here (not those in a handover):
     * only the keys inside the lost ranges, or all of them when ranges is null (periodic).
     * Runs on the logic thread only to take a snapshot; the writes go to the background thread,
     * grouped by responsible node, and their results come back to the logic thread.
     * The conditional write never overwrites a more recent owner and recreates a lost record.
     */
    private void refreshOwnership(List<ChordNode.Range> ranges, String reason) {
        Map<String, JsonObject> records = new HashMap<>();
        for (Map.Entry<String, Context> e : contexts.entrySet()) {
            String h = e.getKey();
            if (inHandover.containsKey(h) || !inRanges(ChordNode.hash("h:" + h), ranges)) {
                continue;
            }
            JsonObject record = new JsonObject();
            record.addProperty("fog", id);
            record.addProperty("seq", e.getValue().lastSeq);
            records.put("h:" + h, record);
        }
        if (records.isEmpty()) {
            if (reason != null) {
                log("OIDA: ring change (%s): none of my %d user(s) had the record on the failed node",
                        reason, contexts.size());
            }
            return;
        }
        background.execute(() -> safely(() -> {
            Map<String, ChordNode.Claim> results = chord.claimAll(records);
            logic.execute(() -> safely(() -> applyRefresh(records.size(), results, reason)));
        }));
    }

    private static boolean inRanges(long key, List<ChordNode.Range> ranges) {
        if (ranges == null) {
            return true;
        }
        for (ChordNode.Range r : ranges) {
            if (r.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Logic thread: acts on the results of a refresh (lost ownership, stale owners, restored records). */
    private void applyRefresh(int sent, Map<String, ChordNode.Claim> results, String reason) {
        int restored = 0;
        for (Map.Entry<String, ChordNode.Claim> e : results.entrySet()) {
            String h = e.getKey().substring("h:".length());
            int winner = fogOf(e.getValue().stored());
            int previous = fogOf(e.getValue().previous());
            if (winner != id) {
                if (contexts.containsKey(h) && !inHandover.containsKey(h)) {
                    log("OIDA: Fog %d holds a more recent context of %s...; releasing mine", winner, brief(h));
                    handOverContext(h, winner);
                }
                continue;
            }
            if (previous != id) {
                restored++;
            }
            if (previous > 0 && previous != id) {
                NodeRef old = resolveFog(previous);
                if (old != null) {
                    JsonObject body = new JsonObject();
                    body.addProperty("h", h);
                    body.addProperty("nextFog", id);
                    FogCommunication.postAsync(old.http + "/handover/release", body);
                }
            }
        }
        if (reason != null || restored > 0) {
            log("OIDA: owner records republished (%s): %d sent, %d written, %d restored",
                    reason == null ? "periodic refresh" : reason, sent, results.size(), restored);
        }
    }

    /**
     * Only the neighbors of a failed node notice the failure: its predecessor broadcasts the lost
     * range of keys over the finger tables (background thread). No address lookup is needed, so
     * nodes whose "fog:{id}" record was lost with the failed node are reached as well.
     */
    private void announceRingChange(ChordNode.Range range) {
        JsonObject payload = new JsonObject();
        payload.addProperty("fog", id);
        payload.addProperty("from", range.from());
        payload.addProperty("to", range.to());
        seenAnnouncement(range);
        int sent = chord.broadcast("/ring/changed", payload);
        log("OIDA: lost key range (%s, %s] broadcast over the finger tables (%d direct send(s))",
                ChordNode.show(range.from()), ChordNode.show(range.to()), sent);
    }

    /** true the first time a lost range is seen (the broadcast reaches each node once; this guards
     *  against repeats while finger tables are still inconsistent). */
    private boolean seenAnnouncement(ChordNode.Range range) {
        long now = System.currentTimeMillis();
        synchronized (seenRanges) {
            seenRanges.values().removeIf(t -> now - t > 60_000);
            return seenRanges.putIfAbsent(range, now) == null;
        }
    }

    /** End of the day: the record stays on the ring, but marked as released (fog 0). */
    private void releaseOwnership(String h, int lastSeq) {
        JsonObject record = new JsonObject();
        record.addProperty("fog", 0);
        record.addProperty("seq", lastSeq);
        try {
            ChordNode.Claim c = chord.claim("h:" + h, record);
            if (fogOf(c.stored()) != 0) {
                log("OIDA: record of %s... already belongs to Fog %d; left unchanged", brief(h), fogOf(c.stored()));
            }
        } catch (IOException e) {
            log("OIDA: failed to release %s... (%s)", brief(h), e.getMessage());
        }
    }

    /** A visit still open for a user this Fog no longer serves is closed, never extended. */
    private void closeStaleVisit(String h) {
        Visit v = openVisits.remove(h);
        if (v != null) {
            v.reason = "context_lost";
            log("stale visit %s-%s of %s... closed before the new stay -> TMS", v.arrival, v.departure, brief(h));
            tms.send(v);
        }
    }

    private static long seqOf(JsonElement record) {
        if (record != null && record.isJsonObject() && record.getAsJsonObject().has("seq")) {
            return record.getAsJsonObject().get("seq").getAsLong();
        }
        return Long.MIN_VALUE;
    }

    private static int fogOf(JsonElement record) {
        if (record != null && record.isJsonObject() && record.getAsJsonObject().has("fog")) {
            return record.getAsJsonObject().get("fog").getAsInt();
        }
        return 0;
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

    /** Relays a ping to another Fog; false if that Fog did not answer. */
    private boolean relay(NodeRef b, Ping p) {
        try {
            FogCommunication.post(b.http + "/handover/ping", p);
            log("ping %s of %s... relayed to Fog %d", p.time == null ? p.type : p.time, brief(p.h), b.fogId);
            return true;
        } catch (IOException e) {
            log("failed to relay ping to Fog %d (%s)", b.fogId, e.getMessage());
            addresses.remove(b.fogId);
            return false;
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
        http.createContext("/ring/changed", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            ChordNode.Range range = new ChordNode.Range(b.get("from").getAsLong(), b.get("to").getAsLong());
            long limit = b.get("limit").getAsLong();
            FogCommunication.respond(ex, 202, null);
            if (!seenAnnouncement(range)) {
                return;                                   // already received (and forwarded)
            }
            scheduleOwnerRefresh(range, "Fog " + b.get("fog").getAsInt() + " reported a node down");
            JsonObject payload = new JsonObject();
            payload.add("fog", b.get("fog"));
            payload.add("from", b.get("from"));
            payload.add("to", b.get("to"));
            background.execute(() -> safely(() -> chord.forward("/ring/changed", payload, limit)));
        });
        http.createContext("/handover/cancel", ex -> {
            String h = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject().get("h").getAsString();
            if (prepared.remove(h) != null) {
                log("PREPARE for user %s... cancelled by the origin", brief(h));
            }
            FogCommunication.respond(ex, 202, null);
        });
        http.createContext("/handover/release", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            String h = b.get("h").getAsString();
            int nextFog = b.get("nextFog").getAsInt();
            logic.execute(() -> safely(() -> {
                if (contexts.containsKey(h) || openVisits.containsKey(h)) {
                    log("Fog %d now owns %s...; closing my visit and dropping the context", nextFog, brief(h));
                    handOverContext(h, nextFog);
                }
            }));
            FogCommunication.respond(ex, 202, null);
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
