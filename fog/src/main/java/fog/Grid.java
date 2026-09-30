package fog;

/**
 * Split of the 200x200 grid into Fog node areas.
 * Same numbering as the notebook:
 *   fog = ((y-1) / FOG_SIZE) * (GRID / FOG_SIZE) + ((x-1) / FOG_SIZE) + 1
 */
public final class Grid {

    private Grid() {
    }

    public static int fogsPerAxis() {
        return Config.GRID / Config.FOG_SIZE;
    }

    /** Fog node responsible for cell (x, y). */
    public static int fogOfCell(int x, int y) {
        return ((y - 1) / Config.FOG_SIZE) * fogsPerAxis() + ((x - 1) / Config.FOG_SIZE) + 1;
    }

    /** {xMin, yMin, xMax, yMax} of the Fog node's area (inclusive). */
    public static int[] area(int fog) {
        int i = fog - 1;
        int x0 = (i % fogsPerAxis()) * Config.FOG_SIZE + 1;
        int y0 = (i / fogsPerAxis()) * Config.FOG_SIZE + 1;
        return new int[] {x0, y0, x0 + Config.FOG_SIZE - 1, y0 + Config.FOG_SIZE - 1};
    }

    /** Is the cell within the Fog node's reach (area + margin)? */
    public static boolean reaches(int fog, int x, int y) {
        int[] a = area(fog);
        int m = Config.REACH_MARGIN;
        return x >= a[0] - m && x <= a[2] + m && y >= a[1] - m && y <= a[3] + m;
    }
}
