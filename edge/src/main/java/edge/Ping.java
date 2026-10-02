package edge;

/**
 * Message published on the htf/pings topic (JSON).
 * type = "ping" (one position), "attach" (just connected to a new broker) or "end" (end of the day).
 */
public class Ping {
    public String type = "ping";
    public String h;      // pseudonym h(s, e): the real uid never leaves the Edge
    public int day;
    public int t;         // timeslot 0..47
    public String time;
    public int x;         // cell of the 200x200 grid (500 m)
    public int y;
    public int seq;       // day * 48 + t

    /** Sent right after the phone connects to a new broker (like the "reconfiguration complete"
     *  of a cellular handover): the target Fog takes over without waiting for the next ping. */
    public static Ping attach(String h, int day) {
        Ping p = new Ping();
        p.type = "attach";
        p.h = h;
        p.day = day;
        return p;
    }

    public static Ping end(String h, int day) {
        Ping p = new Ping();
        p.type = "end";
        p.h = h;
        p.day = day;
        return p;
    }
}
