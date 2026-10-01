package fog;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * OIDA Resolver: one peer of the Chord DHT ring formed by the Fog nodes.
 *
 * - Each node has a position on the ring (SHA-1 of its name, CHORD_BITS bits).
 * - Each key (string) lives on the successor node of SHA-1(key).
 * - The lookup (find_successor) is ITERATIVE: the caller asks node by node,
 *   following the finger tables, in O(log N) hops. HTTP handlers never call
 *   other nodes (avoids deadlocks between nodes).
 *
 * Failure handling (Chord protocol, Stoica et al. 2001):
 * - Each node keeps a SUCCESSOR LIST (the next SUCCESSOR_LIST_SIZE nodes), copied
 *   from its successor on every stabilize round.
 * - A node is considered down when its port refuses the connection, or when it does not
 *   answer within FAILURE_TIMEOUT_MS twice in a row (one slow answer is not a failure).
 *   It is removed from the successor list and the finger table, and the next live
 *   successor takes its place (the ring repairs itself).
 * - check_predecessor: the predecessor is pinged periodically; if it is down it is
 *   cleared, and the next node that notifies becomes the new predecessor.
 * - Lookups that hit a dead node drop it and start again from this node.
 * Keys stored ONLY on a node that went down are lost (no replication in this version);
 * "fog:{id}" records come back because every Fog republishes its address.
 *
 * Records stored on the ring:
 *   "fog:{id}" -> Fog node address (NodeRef)       node discovery
 *   "h:{pseudonym}" -> {"fog": id, "seq": n}       which Fog holds the user's context
 *                                                  (written with claim: the record with the
 *                                                  most recent ping wins; fog 0 = released)
 *
 * HTTP API (on the node's server):
 *   GET  /chord/info                 {self, successor, successors, predecessor}
 *   POST /chord/closest {key}        {closest, successor}   (one lookup step)
 *   POST /chord/notify  {NodeRef}    "am I your predecessor?"
 *   POST /dht/put       {key, value}
 *   POST /dht/claim     {key, value}  -> {previous, stored}   keeps the value with the highest "seq"
 *   GET  /dht/get?key=...            value or 404
 *   POST /dht/bulk      {entries}    keys migrated when a new node joins
 */
final class ChordNode {

    static final int M = Config.CHORD_BITS;
    static final int RING = 1 << M;
    private static final Duration TIMEOUT = Duration.ofMillis(Config.FAILURE_TIMEOUT_MS);

    /** Lookup result: responsible node and number of hops (remote calls). */
    record Lookup(NodeRef node, int hops) {
    }

    /** Result of a get: value (or null), number of lookup hops and the node that stores it. */
    record Value(JsonElement value, int hops, NodeRef storedAt) {
    }

    /** A node on the lookup path did not answer. */
    private static final class Unreachable extends IOException {
        final NodeRef node;

        Unreachable(NodeRef node, IOException cause) {
            super(node + " did not answer: " + cause.getMessage(), cause);
            this.node = node;
        }
    }

    final NodeRef self;
    /** successors.get(0) is the successor; the rest are backups. Never empty. */
    private final List<NodeRef> successors = new CopyOnWriteArrayList<>();
    private volatile NodeRef predecessor;
    private final NodeRef[] fingers = new NodeRef[M];
    private int nextFinger = 0;
    private final Map<String, JsonElement> data = new ConcurrentHashMap<>();
    private final Consumer<String> log;
    /** Consecutive timeouts per node id (a refused connection counts as down at once). */
    private final Map<Integer, Integer> strikes = new ConcurrentHashMap<>();
    /** Set when the predecessor was found down, so its replacement is logged. */
    private volatile boolean predecessorLost;

    ChordNode(NodeRef self, Consumer<String> log) {
        this.self = self;
        this.log = log;
        this.successors.add(self);
    }

    // ------------------------------------------------------------ utilities

    static int hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xFF) << 8 | (d[1] & 0xFF)) & (RING - 1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** x in the open ring interval (a, b). a == b means the whole ring except a. */
    static boolean between(int x, int a, int b) {
        if (a < b) {
            return x > a && x < b;
        }
        return x > a || x < b;
    }

    /** x in the ring interval (a, b]. */
    static boolean betweenInclusive(int x, int a, int b) {
        return x == b || between(x, a, b);
    }

    NodeRef successor() {
        return successors.isEmpty() ? self : successors.get(0);
    }

    NodeRef predecessor() {
        return predecessor;
    }

    List<NodeRef> successors() {
        return List.copyOf(successors);
    }

    // ------------------------------------------------------------ lifecycle

    void createRing() {
        setSuccessors(List.of(self));
        predecessor = null;
        for (int i = 0; i < M; i++) {
            fingers[i] = self;
        }
    }

    void join(String seedUrl) throws IOException {
        NodeRef seed = FogApp.JSON.fromJson(FogCommunication.get(seedUrl + "/chord/info").getAsJsonObject().get("self"),
                NodeRef.class);
        NodeRef succ = findSuccessor(self.id, seed).node();
        setSuccessors(List.of(succ));
        for (int i = 0; i < M; i++) {
            fingers[i] = succ;
        }
    }

    /**
     * Periodic Chord protocol: checks the successor, adopts a closer one if it appeared,
     * copies its successor list and introduces itself to it (notify).
     * If the successor is down, the next one in the list takes its place.
     */
    void stabilize() {
        NodeRef succ = successor();
        if (succ.equals(self)) {                 // alone on the ring (or bootstrapping)
            NodeRef p = predecessor;
            if (p == null || p.equals(self)) {
                return;
            }
            succ = p;
        }
        JsonObject info;
        try {
            info = FogCommunication.get(succ.http + "/chord/info", TIMEOUT).getAsJsonObject();
            alive(succ);
        } catch (IOException e) {
            failed(succ, e);
            return;
        }
        NodeRef x = ref(info.get("predecessor"));
        if (x != null && !x.equals(self) && between(x.id, self.id, succ.id)) {
            try {                                // a node joined between us and the successor
                info = FogCommunication.get(x.http + "/chord/info", TIMEOUT).getAsJsonObject();
                succ = x;
            } catch (IOException e) {
                // x is down: keep the current successor
            }
        }
        List<NodeRef> list = new ArrayList<>();
        list.add(succ);
        for (NodeRef n : refs(info.get("successors"))) {
            if (list.size() >= Config.SUCCESSOR_LIST_SIZE) {
                break;
            }
            if (!n.equals(self) && !list.contains(n)) {
                list.add(n);
            }
        }
        setSuccessors(list);
        try {
            FogCommunication.post(succ.http + "/chord/notify", self, TIMEOUT);
        } catch (IOException e) {
            failed(succ, e);
        }
    }

    /** check_predecessor: if the predecessor stopped answering, forget it. */
    void checkPredecessor() {
        NodeRef p = predecessor;
        if (p == null || p.equals(self)) {
            return;
        }
        try {
            FogCommunication.get(p.http + "/chord/info", TIMEOUT);
            alive(p);
        } catch (IOException e) {
            failed(p, e);
        }
    }

    /** Updates one finger table entry at a time: finger[i] = successor(id + 2^i). */
    void fixFingers() {
        try {
            int i = nextFinger;
            nextFinger = (nextFinger + 1) % M;
            fingers[i] = findSuccessor((self.id + (1 << i)) % RING).node();
        } catch (IOException e) {
            // try again next round
        }
    }

    private void alive(NodeRef n) {
        strikes.remove(n.id);
    }

    /** A call to n failed. Refused connection = down now; a timeout needs a second strike. */
    private void failed(NodeRef n, IOException e) {
        if (FogCommunication.answered(e)) {
            return;                              // it answered with an HTTP error: it is up
        }
        int s = FogCommunication.refused(e) ? 2 : strikes.merge(n.id, 1, Integer::sum);
        if (s >= 2) {
            strikes.remove(n.id);
            nodeDown(n);
        }
    }

    /** A node is down: remove it from every routing table; the next live successor takes over. */
    synchronized void nodeDown(NodeRef dead) {
        if (dead == null || dead.equals(self)) {
            return;
        }
        boolean wasSuccessor = successor().equals(dead);
        boolean wasPredecessor = dead.equals(predecessor);
        successors.removeIf(dead::equals);
        for (int i = 0; i < M; i++) {
            if (dead.equals(fingers[i])) {
                fingers[i] = null;
            }
        }
        if (wasPredecessor) {
            predecessor = null;
            predecessorLost = true;
        }
        if (successors.isEmpty()) {              // whole list down: fall back to any live finger
            NodeRef next = self;
            for (NodeRef f : fingers) {
                if (f != null && !f.equals(self)) {
                    next = f;
                    break;
                }
            }
            successors.add(next);
        }
        if (wasSuccessor) {
            log.accept(String.format("Chord: successor %s is down -> new successor %s", dead, successor()));
        }
        if (wasPredecessor) {
            log.accept(String.format("Chord: predecessor %s is down -> waiting for a new one to notify", dead));
        }
    }

    private void setSuccessors(List<NodeRef> list) {
        successors.clear();
        successors.addAll(list);
    }

    // ------------------------------------------------------------ iterative lookup

    Lookup findSuccessor(int key) throws IOException {
        return findSuccessor(key, self);
    }

    /** Lookup that survives dead nodes: drops the node that did not answer and starts again from here. */
    Lookup findSuccessor(int key, NodeRef start) throws IOException {
        Unreachable last = null;
        for (int attempt = 0; attempt < Config.SUCCESSOR_LIST_SIZE + 1; attempt++) {
            try {
                return lookup(key, attempt == 0 ? start : self);
            } catch (Unreachable u) {
                failed(u.node, (IOException) u.getCause());
                last = u;
            }
        }
        throw last;
    }

    private Lookup lookup(int key, NodeRef start) throws IOException {
        NodeRef n = start;
        int hops = 0;
        for (int i = 0; i < 4 * M; i++) {
            NodeRef nSucc;
            NodeRef next;
            if (n.equals(self)) {
                nSucc = successor();
                next = closestPreceding(key);
            } else {
                JsonObject body = new JsonObject();
                body.addProperty("key", key);
                JsonObject r;
                try {
                    r = FogCommunication.post(n.http + "/chord/closest", body, TIMEOUT).getAsJsonObject();
                } catch (IOException e) {
                    throw new Unreachable(n, e);
                }
                hops++;
                nSucc = FogApp.JSON.fromJson(r.get("successor"), NodeRef.class);
                next = FogApp.JSON.fromJson(r.get("closest"), NodeRef.class);
            }
            if (n.id == nSucc.id || betweenInclusive(key, n.id, nSucc.id)) {
                return new Lookup(nSucc, hops);
            }
            if (next.equals(n)) {
                return new Lookup(nSucc, hops);
            }
            n = next;
        }
        return new Lookup(successor(), hops);
    }

    /** Closest known node before the key, from the finger table and the successor list. */
    private NodeRef closestPreceding(int key) {
        NodeRef best = self;
        for (int i = M - 1; i >= 0; i--) {
            NodeRef f = fingers[i];
            if (f != null && between(f.id, self.id, key)) {
                best = f;
                break;
            }
        }
        for (NodeRef s : successors) {
            if (between(s.id, self.id, key) && (best.equals(self) || between(s.id, best.id, key))) {
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ DHT (put/get)

    Lookup put(String key, Object value) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            JsonElement v = FogApp.JSON.toJsonTree(value);
            if (l.node().equals(self)) {
                data.put(key, v);
                return l;
            }
            JsonObject body = new JsonObject();
            body.addProperty("key", key);
            body.add("value", v);
            try {
                FogCommunication.post(l.node().http + "/dht/put", body, TIMEOUT);
                return l;
            } catch (IOException e) {
                if (FogCommunication.answered(e) || attempt > 0) {
                    throw e;
                }
                failed(l.node(), e);             // responsible node did not answer: look up again
            }
        }
    }

    /** Result of a claim: the record that was stored before and the one stored now (the winner). */
    record Claim(JsonElement previous, JsonElement stored) {
    }

    /**
     * Conditional put for ownership records: the storing node keeps the value with the highest
     * "seq" (ties: the new value wins) and answers with the previous and the current record.
     * The comparison runs on the node that stores the key, so two Fogs claiming the same user
     * at the same time always agree on a single winner.
     */
    Claim claim(String key, JsonObject value) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            if (l.node().equals(self)) {
                return keepNewest(key, value);
            }
            JsonObject body = new JsonObject();
            body.addProperty("key", key);
            body.add("value", value);
            try {
                JsonObject r = FogCommunication.post(l.node().http + "/dht/claim", body, TIMEOUT).getAsJsonObject();
                return new Claim(r.get("previous"), r.get("stored"));
            } catch (IOException e) {
                if (FogCommunication.answered(e) || attempt > 0) {
                    throw e;
                }
                failed(l.node(), e);
            }
        }
    }

    private Claim keepNewest(String key, JsonObject value) {
        JsonElement[] previous = new JsonElement[1];
        JsonElement stored = data.compute(key, (k, old) -> {
            previous[0] = old;
            return old == null || released(old) || seqOf(old) <= seqOf(value) ? value : old;
        });
        return new Claim(previous[0], stored);
    }

    /** A released ownership record (fog 0, end of the day) never blocks a new owner. */
    private static boolean released(JsonElement e) {
        return e.isJsonObject() && e.getAsJsonObject().has("fog") && e.getAsJsonObject().get("fog").getAsInt() == 0;
    }

    private static long seqOf(JsonElement e) {
        if (e != null && e.isJsonObject() && e.getAsJsonObject().has("seq")) {
            return e.getAsJsonObject().get("seq").getAsLong();
        }
        return Long.MIN_VALUE;
    }

    Value get(String key) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            if (l.node().equals(self)) {
                return new Value(data.get(key), l.hops(), self);
            }
            try {
                String url = l.node().http + "/dht/get?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                return new Value(FogCommunication.get(url, TIMEOUT), l.hops() + 1, l.node());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 404")) {
                    return new Value(null, l.hops() + 1, l.node());
                }
                if (FogCommunication.answered(e) || attempt > 0) {
                    throw e;
                }
                failed(l.node(), e);
            }
        }
    }

    // ------------------------------------------------------------ HTTP API (no remote calls)

    void registerRoutes(HttpServer http) {
        http.createContext("/chord/info", ex -> {
            JsonObject r = new JsonObject();
            r.add("self", FogApp.JSON.toJsonTree(self));
            r.add("successor", FogApp.JSON.toJsonTree(successor()));
            r.add("successors", FogApp.JSON.toJsonTree(successors()));
            r.add("predecessor", FogApp.JSON.toJsonTree(predecessor));
            FogCommunication.respond(ex, 200, r);
        });
        http.createContext("/chord/closest", ex -> {
            int key = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject().get("key").getAsInt();
            JsonObject r = new JsonObject();
            r.add("closest", FogApp.JSON.toJsonTree(closestPreceding(key)));
            r.add("successor", FogApp.JSON.toJsonTree(successor()));
            FogCommunication.respond(ex, 200, r);
        });
        http.createContext("/chord/notify", ex -> {
            NodeRef p = FogApp.JSON.fromJson(FogCommunication.body(ex), NodeRef.class);
            onNotify(p);
            FogCommunication.respond(ex, 200, null);
        });
        http.createContext("/dht/put", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            data.put(b.get("key").getAsString(), b.get("value"));
            FogCommunication.respond(ex, 200, null);
        });
        http.createContext("/dht/claim", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            Claim c = keepNewest(b.get("key").getAsString(), b.getAsJsonObject("value"));
            JsonObject r = new JsonObject();
            r.add("previous", c.previous());
            r.add("stored", c.stored());
            FogCommunication.respond(ex, 200, r);
        });
        http.createContext("/dht/get", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String key = q == null ? "" : URLDecoder.decode(q.replaceFirst("^key=", ""), StandardCharsets.UTF_8);
            JsonElement v = data.get(key);
            FogCommunication.respond(ex, v == null ? 404 : 200, v);
        });
        http.createContext("/dht/bulk", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            b.getAsJsonObject("entries").entrySet().forEach(e -> data.put(e.getKey(), e.getValue()));
            FogCommunication.respond(ex, 200, null);
        });
    }

    /** A node says it may be our predecessor. If so, we hand it the keys that now belong to it. */
    private synchronized void onNotify(NodeRef p) {
        if (p.equals(self)) {
            return;
        }
        if (predecessor == null || between(p.id, predecessor.id, self.id)) {
            predecessor = p;
            if (predecessorLost) {
                predecessorLost = false;
                log.accept(String.format("Chord: new predecessor %s (ring repaired)", p));
            }
            JsonObject moved = new JsonObject();
            List<String> remove = new ArrayList<>();
            data.forEach((k, v) -> {
                if (!betweenInclusive(hash(k), p.id, self.id)) {
                    moved.add(k, v);
                    remove.add(k);
                }
            });
            if (!remove.isEmpty()) {
                JsonObject body = new JsonObject();
                body.add("entries", moved);
                FogCommunication.postAsync(p.http + "/dht/bulk", body);
                remove.forEach(data::remove);
            }
        }
    }

    /** Copy of the finger table (used to check that the ring has converged). */
    List<NodeRef> fingers() {
        return Arrays.asList(fingers.clone());
    }

    JsonObject state() {
        JsonObject r = new JsonObject();
        r.add("self", FogApp.JSON.toJsonTree(self));
        r.add("successors", FogApp.JSON.toJsonTree(successors()));
        r.add("predecessor", FogApp.JSON.toJsonTree(predecessor));
        r.add("fingers", FogApp.JSON.toJsonTree(fingers));
        r.addProperty("keys", data.size());
        return r;
    }

    // ------------------------------------------------------------ JSON helpers

    private static NodeRef ref(JsonElement e) {
        return e == null || e.isJsonNull() ? null : FogApp.JSON.fromJson(e, NodeRef.class);
    }

    private static List<NodeRef> refs(JsonElement e) {
        List<NodeRef> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) {
                out.add(FogApp.JSON.fromJson(x, NodeRef.class));
            }
        }
        return out;
    }
}
