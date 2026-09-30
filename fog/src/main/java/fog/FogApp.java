package fog;

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
                ids.size(), ids, Config.FOG_SIZE, Config.FOG_SIZE, Config.TMS_URL);
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

    /** Waits for the Chord ring to close: following successors from the seed, it visits every node and returns. */
    private static void awaitRing(List<FogNode> nodes) throws InterruptedException {
        Map<Integer, FogNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.ref.id, n));
        FogNode first = nodes.get(0);
        for (int s = 0; s < 60; s++) {
            Thread.sleep(1000);
            List<Integer> order = new ArrayList<>();
            FogNode current = first;
            do {
                order.add(current.id);
                current = byId.get(current.chord.successor().id);
            } while (current != null && current != first && order.size() <= nodes.size());
            if (current == first && order.size() == nodes.size() && s >= 4) {   // +seconds for the finger tables
                System.out.printf("[Fog   ] Chord ring ready with %d nodes (ring order: %s)%n", nodes.size(), order);
                System.out.println("[Fog   ] you can run the Edge now.");
                return;
            }
        }
        System.out.println("[Fog   ] warning: the Chord ring has not stabilized yet; the nodes keep trying.");
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
