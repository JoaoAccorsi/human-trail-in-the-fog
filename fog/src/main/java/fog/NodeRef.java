package fog;

import java.util.Objects;

/** Reference to a Fog node on the network: position on the Chord ring and addresses. */
public class NodeRef {
    public long id;         // position on the Chord ring (0 .. 2^CHORD_BITS - 1, read as unsigned)
    public int fogId;       // Fog node number (1..16)
    public String http;     // node API: http://host:900N
    public String mqtt;     // node broker: tcp://host:190N
    public long seenAt;     // when the node last published its address (soft state, see Config.ADDRESS_TTL_MS)

    public static NodeRef ofFog(int fogId) {
        NodeRef r = new NodeRef();
        r.fogId = fogId;
        r.id = ChordNode.hash("fog-" + fogId);
        r.http = "http://" + Config.HOST + ":" + (Config.HTTP_BASE_PORT + fogId);
        r.mqtt = "tcp://" + Config.HOST + ":" + (Config.MQTT_BASE_PORT + fogId);
        return r;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NodeRef n && n.id == id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Fog " + fogId;
    }
}
