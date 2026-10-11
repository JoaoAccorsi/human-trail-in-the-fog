package fog;

import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Handover decision of the serving Fog (Algorithm 1 of the paper).
 *
 * rsrp policy: for every candidate Fog, RSRP (synthetic) -> Kalman filter. If the best neighbor
 * B satisfies RSRP_B > RSRP_serving + HOM, a counter accumulates the elapsed time; when it
 * reaches TTT the handover to B is triggered. Any ping that breaks the condition (or picks
 * another neighbor) resets the counter. This removes the ping-pong effect near area borders.
 *
 * area policy (baseline): handover as soon as the user's cell belongs to another Fog.
 *
 * Fog nodes that are down are never targets: the user stays with the serving Fog (which covers
 * the area of a failed neighbor, see FogNode.servingFogOf) or moves to another live neighbor.
 */
final class HandoverDecision {

    /** Result: target Fog (null = stay) and a one-line summary for the log. */
    record Result(Integer target, String summary) {
    }

    private HandoverDecision() {
    }

    static Result evaluate(int serving, Context ctx, Ping p, IntPredicate up) {
        if ("area".equals(Config.HANDOVER_POLICY)) {
            int cellFog = Grid.fogOfCell(p.x, p.y);
            if (cellFog != serving && !up.test(cellFog)) {
                return new Result(null, "area policy | Fog " + cellFog + " is down: covered here");
            }
            return new Result(cellFog == serving ? null : cellFog, "area policy");
        }
        int elapsed = ctx.lastSeq < 0 ? Config.MINUTES_PER_SLOT
                : Math.max(1, p.seq - ctx.lastSeq) * Config.MINUTES_PER_SLOT;
        Integer best = null;
        double bestEst = Double.NEGATIVE_INFINITY;
        double servingEst = Double.NaN;
        for (int fog : Radio.candidates(serving, p.x, p.y)) {
            if (fog != serving && !up.test(fog)) {
                continue;                                   // a Fog that is down is not a candidate
            }
            double z = Radio.rsrp(fog, p.x, p.y, p.h, p.seq);
            double[] s = Kalman.update(ctx.filters.get(fog), z);
            ctx.filters.put(fog, s);
            if (fog == serving) {
                servingEst = s[0];
            } else if (s[0] > bestEst) {
                bestEst = s[0];
                best = fog;
            }
        }
        boolean condition = best != null && bestEst > servingEst + Config.HOM_DB;
        if (condition && best.equals(ctx.candidate)) {
            ctx.conditionMinutes += elapsed;
        } else if (condition) {
            ctx.candidate = best;
            ctx.conditionMinutes = elapsed;
        } else {
            ctx.candidate = null;
            ctx.conditionMinutes = 0;
        }
        String summary = String.format(Locale.ROOT, "RSRP Fog %d %.1f dBm, best neighbor Fog %d %.1f dBm%s",
                serving, servingEst, best, bestEst,
                condition ? String.format(" | > HOM for %d/%d min", ctx.conditionMinutes, Config.TTT_MINUTES) : "");
        if (condition && ctx.conditionMinutes >= Config.TTT_MINUTES) {
            ctx.candidate = null;
            ctx.conditionMinutes = 0;
            return new Result(best, summary);
        }
        return new Result(null, summary);
    }
}
