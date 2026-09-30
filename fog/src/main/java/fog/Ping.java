package fog;

/** Message received from the Edge on the htf/pings topic. type = "ping" or "end". */
public class Ping {
    public String type = "ping";
    public String h;      // pseudonym h(s, e)
    public int day;
    public int t;         // timeslot 0..47
    public String time;
    public int x;
    public int y;
    public int seq;
}
