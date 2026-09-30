package fog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** User context migrated from one Fog node to another on handover (direct HTTP, POST /handover/prepare). */
public class Context {
    public String h;
    public int day;
    /** Fog node handing the user over (receives the COMMIT). */
    public NodeRef origin;
    public int handovers;
    /** Highest seq already processed: drops repeated or out-of-order pings. */
    public int lastSeq = -1;
    /** Last known positions {t, x, y}. */
    public List<int[]> history = new ArrayList<>();
    /** Kalman filter state per Fog id: {estimated RSRP (dBm), error variance}. Migrates with the user. */
    public Map<Integer, double[]> filters = new HashMap<>();
    /** Fog that currently satisfies the handover condition, and for how long (TTT counter, minutes). */
    public Integer candidate;
    public int conditionMinutes;
    /** Ping that triggered the handover: the target starts the trail with it. */
    public Ping triggerPing;
}
