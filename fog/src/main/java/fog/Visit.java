package fog;

import java.util.ArrayList;
import java.util.List;

/** The user's stay in a Fog node's area. Sent to the TMS as a GS1 EPCIS document (see Epcis). */
public class Visit {
    public String h;
    public int day;
    public int fog;
    public int fogSize = Config.FOG_SIZE;
    public int arrivalT;
    public int departureT;
    public String arrival;
    public String departure;
    public int pings;
    public List<int[]> cells = new ArrayList<>();   // {x, y} in order
    public String reason;                          // "handover" | "end_of_day"
    public Integer nextFog;                        // set on handover
}
