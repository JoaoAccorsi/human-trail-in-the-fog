package fog;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Synthetic radio signal. YJMob100K has no radio measurements, so the RSRP the device
 * receives from each Fog node is generated with the log-distance path loss model:
 *
 *   RSRP_i = P0 - 10 n log10(d_i / d0) + X_sigma
 *
 * d_i = distance from the user's cell to the center of Fog i's area. The shadowing noise
 * X_sigma is seeded by (pseudonym, seq, fog), so a run is reproducible and every Fog
 * computes the same value for the same ping.
 */
final class Radio {

    private static final double CELL_KM = 0.5;

    private Radio() {
    }

    static double rsrp(int fog, int x, int y, String h, int seq) {
        int[] a = Grid.area(fog);
        double cx = (a[0] + a[2]) / 2.0;
        double cy = (a[1] + a[3]) / 2.0;
        double dKm = Math.max(Math.hypot(x - cx, y - cy) * CELL_KM, Config.RSRP_D0_KM);
        double pathLoss = 10 * Config.PATH_LOSS_EXPONENT * Math.log10(dKm / Config.RSRP_D0_KM);
        double shadowing = new Random(Objects.hash(h, seq, fog)).nextGaussian() * Config.SHADOWING_SIGMA_DB;
        return Config.RSRP_P0_DBM - pathLoss + shadowing;
    }

    /** Fogs measured for a ping: the serving Fog, its 8 neighbors and the Fog of the current cell. */
    static Set<Integer> candidates(int serving, int x, int y) {
        Set<Integer> out = new LinkedHashSet<>();
        out.add(serving);
        int n = Grid.fogsPerAxis();
        int row = (serving - 1) / n;
        int col = (serving - 1) % n;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = row + dr;
                int c = col + dc;
                if (r >= 0 && r < n && c >= 0 && c < n) {
                    out.add(r * n + c + 1);
                }
            }
        }
        out.add(Grid.fogOfCell(x, y));
        return out;
    }
}
