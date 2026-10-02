package fog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.Gson;

/**
 * FOG - starts independent Fog nodes (each with its own MQTT broker, HTTP API and state).
 *
 * Locally, by default, it starts the 16 nodes in this same process, but they share
 * NOTHING: they only talk over the network (direct HTTP + Chord ring).
 * In containers, each container starts a single node:
 *
 *   cd fog && mvn -q compile exec:exec                      (all nodes: 1..16)
 *   HTF_FOG_IDS=7 mvn -q compile exec:exec                  (Fog 7 only)
 *   HTF_FOG_IDS=1,2,5-8 mvn -q compile exec:exec            (list/ranges)
 */
public class FogApp {

    static final Gson JSON = new Gson();

    public static void main(String[] args) throws Exception {
        List<Integer> ids = idsToStart();
        List<FogNode> nodes = new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> nodes.forEach(FogNode::stop)));

        System.out.printf("[Fog   ] starting %d Fog node(s): %s | area of each node: %dx%d cells | TMS %s%n",
                ids.size(), ids, Config.FOG_AREA_SIDE, Config.FOG_AREA_SIDE, Config.TMS_URL);
        // The seed node creates the ring; the others join through it.
        for (int id : ids) {
            FogNode n = new FogNode(id);
            try {
                n.start(id == Config.SEED_FOG);
            } catch (Exception e) {
                System.out.printf("[Fog %2d] could not start: %s%n", id, e);
                System.out.println("         If the port is in use: stop the other Fog run (Ctrl+C) and try again.");
                System.exit(1);
            }
            nodes.add(n);
        }
        if (ids.size() > 1) {
            awaitRing(nodes);
        }
        Thread.currentThread().join(); // runs until Ctrl+C
    }

    /**
     * Waits until the Chord ring is stable before telling the user to start the Edge:
     *  1. following successors from the seed visits every node and returns to it;
     *  2. every node's predecessor is the node before it on the ring;
     *  3. every finger table matches finger[i] = successor(id + 2^i);
     *  4. the address record "fog:{id}" of every node can be read from the ring.
     */
    private static void awaitRing(List<FogNode> nodes) throws InterruptedException {
        Map<Long, FogNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.ref.id, n));
        List<Long> sortedIds = new ArrayList<>(byId.keySet());
        sortedIds.sort(Long::compareUnsigned);
        FogNode first = nodes.get(0);
        String missing = "";
        for (int s = 1; s <= 120; s++) {
            Thread.sleep(1000);
            missing = ringProblem(nodes, byId, sortedIds, first);
            if (missing == null) {
                System.out.printf("[Fog   ] Chord ring ready with %d nodes after %d s: successors, predecessors, "
                        + "finger tables and fog:{id} records consistent%n", nodes.size(), s);
                System.out.println("[Fog   ] you can run the Edge now.");
                return;
            }
            if (s % 10 == 0) {
                System.out.printf("[Fog   ] waiting for the Chord ring to stabilize (%s)...%n", missing);
            }
        }
        System.out.printf("[Fog   ] warning: the Chord ring has not stabilized yet (%s); the nodes keep trying.%n", missing);
    }

    /** null if the ring is stable; otherwise a short description of what is not ready yet. */
    private static String ringProblem(List<FogNode> nodes, Map<Long, FogNode> byId,
                                      List<Long> sortedIds, FogNode first) {
        List<Long> order = new ArrayList<>();
        FogNode current = first;
        do {
            order.add(current.ref.id);
            current = byId.get(current.chord.successor().id);
        } while (current != null && current != first && order.size() <= nodes.size());
        if (current != first || order.size() != nodes.size()) {
            return "ring not closed through all successors";
        }
        for (FogNode n : nodes) {
            NodeRef p = n.chord.predecessor();
            long expected = sortedIds.get(Math.floorMod(sortedIds.indexOf(n.ref.id) - 1, sortedIds.size()));
            if (p == null || p.id != expected) {
                return "predecessor of Fog " + n.id + " not set";
            }
            List<NodeRef> fingers = n.chord.fingers();
            for (int i = 0; i < fingers.size(); i++) {
                long target = ChordNode.fingerStart(n.ref.id, i);
                NodeRef f = fingers.get(i);
                if (f == null || f.id != successorOf(target, sortedIds)) {
                    return "finger table of Fog " + n.id + " not converged";
                }
            }
        }
        for (FogNode n : nodes) {
            try {
                if (first.chord.get("fog:" + n.id).value() == null) {
                    return "address of Fog " + n.id + " not on the ring yet";
                }
            } catch (IOException e) {
                return "lookup of fog:" + n.id + " failed";
            }
        }
        return null;
    }

    /** First node id equal to or after the key on the ring. */
    private static long successorOf(long key, List<Long> sortedIds) {
        for (long id : sortedIds) {
            if (Long.compareUnsigned(id, key) >= 0) {
                return id;
            }
        }
        return sortedIds.get(0);
    }

    /** HTF_FOG_IDS: "7", "1,2,3", "5-8" or empty (= all). The seed node starts first. */
    private static List<Integer> idsToStart() {
        String spec = System.getenv("HTF_FOG_IDS");
        TreeSet<Integer> ids = new TreeSet<>();
        if (spec == null || spec.isBlank()) {
            for (int i = 1; i <= Config.numberOfFogs(); i++) {
                ids.add(i);
            }
        } else {
            for (String part : spec.split(",")) {
                String[] ab = part.trim().split("-");
                int a = Integer.parseInt(ab[0].trim());
                int b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                for (int i = a; i <= b; i++) {
                    ids.add(i);
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        if (ids.remove(Config.SEED_FOG)) {
            order.add(Config.SEED_FOG);
        }
        order.addAll(ids);
        return order;
    }
}
