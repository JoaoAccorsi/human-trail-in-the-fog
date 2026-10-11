package fog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * - Each node has a position on the ring (SHA-1 of its name, CHORD_BITS = 64 bits, as an
 *   unsigned long; ring arithmetic wraps modulo 2^64).
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
 *   POST /dht/claims    {entries}     -> {results}            the same, for many keys of this node
 *   GET  /dht/get?key=...            value or 404
 *   POST /dht/bulk      {entries}    keys migrated when a new node joins
 */
final class ChordNode {

    static final int M = Config.CHORD_BITS;
    /** Identifiers are the low M bits of an unsigned long; arithmetic is modulo 2^M. */
    static final long MASK = M >= 64 ? -1L : (1L << M) - 1;
    private static final Duration TIMEOUT = Duration.ofMillis(Config.FAILURE_TIMEOUT_MS);

    /** Lookup result: responsible node and number of hops (remote calls). */
    record Lookup(NodeRef node, int hops) {
    }

    /** Result of a get: value (or null), number of lookup hops and the node that stores it. */
    record Value(JsonElement value, int hops, NodeRef storedAt) {
    }

    /** Observer of the DHT operations (evaluation measurements): operation, key, routing hops, total time. */
    @FunctionalInterface
    interface LookupObserver {
        void done(String op, String key, int hops, double ms);
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
    /** Called with the keys (from, to] that were stored on a node found down (only by its predecessor). */
    private volatile Consumer<Range> ringChanged = r -> { };
    /** Consecutive timeouts per node id (a refused connection counts as down at once). */
    private final Map<Long, Integer> strikes = new ConcurrentHashMap<>();
    /** Set when the predecessor was found down, so its replacement is logged. */
    private volatile boolean predecessorLost;
    /** Told about every put, claim and get that succeeded (not about the ring maintenance). */
    private volatile LookupObserver lookups = (op, key, hops, ms) -> { };

    ChordNode(NodeRef self, Consumer<String> log) {
        this.self = self;
        this.log = log;
        this.successors.add(self);
    }

    // ------------------------------------------------------------ utilities

    static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(d).getLong() & MASK;     // first 64 bits of the SHA-1
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** x in the open ring interval (a, b), comparing unsigned. a == b means the whole ring except a. */
    static boolean between(long x, long a, long b) {
        if (Long.compareUnsigned(a, b) < 0) {
            return Long.compareUnsigned(x, a) > 0 && Long.compareUnsigned(x, b) < 0;
        }
        return Long.compareUnsigned(x, a) > 0 || Long.compareUnsigned(x, b) < 0;
    }

    /** x in the ring interval (a, b]. */
    static boolean betweenInclusive(long x, long a, long b) {
        return x == b || between(x, a, b);
    }

    /** Start of finger i of node n: (n + 2^i) mod 2^M. */
    static long fingerStart(long n, int i) {
        return (n + (1L << i)) & MASK;
    }

    /** Identifier for logs (unsigned decimal). */
    static String show(long id) {
        return Long.toUnsignedString(id);
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

    /**
     * Updates the finger table: finger[i] = successor(id + 2^i). One lookup per round; the
     * following fingers whose start still falls before the node found point to the same node
     * and are filled without a lookup (with 64-bit ids most of the 64 fingers are the successor).
     */
    void fixFingers() {
        int i = nextFinger;
        try {
            NodeRef f = findSuccessor(fingerStart(self.id, i)).node();
            fingers[i] = f;
            int j = i + 1;
            while (!f.equals(self) && j < M && betweenInclusive(fingerStart(self.id, j), self.id, f.id)) {
                fingers[j] = f;
                j++;
            }
            nextFinger = j % M;
        } catch (IOException e) {
            nextFinger = (i + 1) % M;            // try again next round
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
        if (wasSuccessor) {
            // this node preceded the failed one: the keys it stored were exactly (self, dead]
            ringChanged.accept(new Range(self.id, dead.id));
        }
    }

    /** Ring interval of keys (from, to]. */
    record Range(long from, long to) {
        boolean contains(long key) {
            return betweenInclusive(key, from, to);
        }
    }

    /**
     * Registers what to do when this node finds its successor down (must not block: it runs
     * inside the Chord tasks). Only the predecessor of the failed node reports it, with the exact
     * range of keys that were stored there.
     */
    void onRingChange(Consumer<Range> r) {
        ringChanged = r;
    }

    /** Registers who is told about each put, claim and get (must not block). */
    void onLookup(LookupObserver o) {
        lookups = o;
    }

    private void observed(String op, String key, Lookup l, long startedNanos) {
        lookups.done(op, key, l.hops(), (System.nanoTime() - startedNanos) / 1e6);
    }

    private void setSuccessors(List<NodeRef> list) {
        successors.clear();
        successors.addAll(list);
    }

    // ------------------------------------------------------------ iterative lookup

    Lookup findSuccessor(long key) throws IOException {
        return findSuccessor(key, self);
    }

    /** Lookup that survives dead nodes: drops the node that did not answer and starts again from here. */
    Lookup findSuccessor(long key, NodeRef start) throws IOException {
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

    private Lookup lookup(long key, NodeRef start) throws IOException {
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
    private NodeRef closestPreceding(long key) {
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

    // ------------------------------------------------------------ broadcast over the fingers

    /**
     * Sends payload to every other live node of the ring exactly once, over the finger tables
     * (El-Ansary et al., "Efficient Broadcast in Structured P2P Networks", IPTPS 2003): the ring
     * is split into disjoint intervals, one per finger, and each finger is responsible for
     * forwarding inside its interval. N - 1 messages, depth O(log N), at most O(log N) sends per
     * node, and no lookup of addresses ("fog:{id}" records may have been lost with a failed node).
     * Blocking: call it off the HTTP handlers and the users' lanes.
     */
    int broadcast(String path, JsonObject payload) {
        return forward(path, payload, self.id);
    }

    /**
     * Forwards payload to the known nodes in (self, limit); each one gets the interval up to the
     * next one. limit == self.id means the whole ring. Returns the number of direct sends.
     */
    int forward(String path, JsonObject payload, long limit) {
        List<NodeRef> targets = new ArrayList<>();
        List<NodeRef> known = new ArrayList<>(Arrays.asList(fingers));
        known.addAll(successors);
        for (NodeRef n : known) {
            if (n != null && !n.equals(self) && between(n.id, self.id, limit) && !targets.contains(n)) {
                targets.add(n);
            }
        }
        targets.sort((a, b) -> Long.compareUnsigned((a.id - self.id) & MASK, (b.id - self.id) & MASK));   // ring distance
        int sent = 0;
        for (int k = 0; k < targets.size(); k++) {
            long subLimit = k + 1 < targets.size() ? targets.get(k + 1).id : limit;
            sent += send(path, payload, targets.get(k), subLimit, 0);
        }
        return sent;
    }

    /** Sends to t (responsible for (t, subLimit)); if t is down, the next live node takes its interval. */
    private int send(String path, JsonObject payload, NodeRef t, long subLimit, int attempt) {
        JsonObject body = new JsonObject();
        payload.entrySet().forEach(e -> body.add(e.getKey(), e.getValue()));
        body.addProperty("limit", subLimit);
        try {
            FogCommunication.post(t.http + path, body, TIMEOUT);
            return 1;
        } catch (IOException e) {
            failed(t, e);
            if (attempt >= Config.SUCCESSOR_LIST_SIZE) {
                return 0;
            }
            try {
                NodeRef next = findSuccessor((t.id + 1) & MASK).node();
                if (!next.equals(t) && !next.equals(self) && between(next.id, t.id, subLimit)) {
                    log.accept(String.format("broadcast: %s did not answer; its interval goes to %s", t, next));
                    return send(path, payload, next, subLimit, attempt + 1);
                }
            } catch (IOException ignored) {
                // nobody else in that interval can be reached now
            }
            return 0;
        }
    }

    // ------------------------------------------------------------ DHT (put/get)

    Lookup put(String key, Object value) throws IOException {
        long started = System.nanoTime();
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            JsonElement v = FogApp.JSON.toJsonTree(value);
            if (l.node().equals(self)) {
                data.put(key, v);
                observed("put", key, l, started);
                return l;
            }
            JsonObject body = new JsonObject();
            body.addProperty("key", key);
            body.add("value", v);
            try {
                FogCommunication.post(l.node().http + "/dht/put", body, TIMEOUT);
                observed("put", key, l, started);
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
        long started = System.nanoTime();
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            if (l.node().equals(self)) {
                Claim c = keepNewest(key, value);
                observed("claim", key, l, started);
                return c;
            }
            JsonObject body = new JsonObject();
            body.addProperty("key", key);
            body.add("value", value);
            try {
                JsonObject r = FogCommunication.post(l.node().http + "/dht/claim", body, TIMEOUT).getAsJsonObject();
                observed("claim", key, l, started);
                return new Claim(r.get("previous"), r.get("stored"));
            } catch (IOException e) {
                if (FogCommunication.answered(e) || attempt > 0) {
                    throw e;
                }
                failed(l.node(), e);
            }
        }
    }

    /**
     * Conditional put of many records, grouped by responsible node: the keys are sorted by hash,
     * and after one lookup every following key up to the responsible node's id goes to the same
     * node, in a single request (POST /dht/claims). Keys that could not be written are left out
     * of the result (the next refresh retries them).
     */
    Map<String, Claim> claimAll(Map<String, JsonObject> records) {
        List<String> keys = new ArrayList<>(records.keySet());
        keys.sort((a, b) -> Long.compareUnsigned(hash(a), hash(b)));
        Map<String, Claim> out = new HashMap<>();
        int i = 0;
        while (i < keys.size()) {
            long first = hash(keys.get(i));
            Lookup l;
            try {
                l = findSuccessor(first);
            } catch (IOException e) {
                i++;
                continue;
            }
            NodeRef node = l.node();
            int j = i + 1;
            while (j < keys.size() && (hash(keys.get(j)) == first
                    || (first != node.id && betweenInclusive(hash(keys.get(j)), first, node.id)))) {
                j++;
            }
            List<String> group = keys.subList(i, j);
            if (node.equals(self)) {
                group.forEach(k -> out.put(k, keepNewest(k, records.get(k))));
            } else {
                JsonArray entries = new JsonArray();
                for (String k : group) {
                    JsonObject e = new JsonObject();
                    e.addProperty("key", k);
                    e.add("value", records.get(k));
                    entries.add(e);
                }
                JsonObject body = new JsonObject();
                body.add("entries", entries);
                try {
                    JsonObject r = FogCommunication.post(node.http + "/dht/claims", body, TIMEOUT).getAsJsonObject();
                    for (JsonElement x : r.getAsJsonArray("results")) {
                        JsonObject o = x.getAsJsonObject();
                        out.put(o.get("key").getAsString(), new Claim(o.get("previous"), o.get("stored")));
                    }
                } catch (IOException e) {
                    failed(node, e);                 // the next refresh looks the keys up again
                }
            }
            i = j;
        }
        return out;
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
        long started = System.nanoTime();
        for (int attempt = 0; ; attempt++) {
            Lookup l = findSuccessor(hash(key));
            if (l.node().equals(self)) {
                observed("get", key, l, started);
                return new Value(data.get(key), l.hops(), self);
            }
            try {
                String url = l.node().http + "/dht/get?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                Value v = new Value(FogCommunication.get(url, TIMEOUT), l.hops() + 1, l.node());
                observed("get", key, l, started);
                return v;
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 404")) {
                    observed("get", key, l, started);
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
            long key = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject().get("key").getAsLong();
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
        http.createContext("/dht/claims", ex -> {
            JsonObject b = JsonParser.parseString(FogCommunication.body(ex)).getAsJsonObject();
            JsonArray results = new JsonArray();
            for (JsonElement x : b.getAsJsonArray("entries")) {
                JsonObject e = x.getAsJsonObject();
                String key = e.get("key").getAsString();
                Claim c = keepNewest(key, e.getAsJsonObject("value"));
                JsonObject o = new JsonObject();
                o.addProperty("key", key);
                o.add("previous", c.previous());
                o.add("stored", c.stored());
                results.add(o);
            }
            JsonObject r = new JsonObject();
            r.add("results", results);
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
