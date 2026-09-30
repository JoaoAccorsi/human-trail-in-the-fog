package fog;

/**
 * One-dimensional Kalman filter (random-walk model) that smooths the RSRP of one Fog node.
 * State = {estimate, error variance}; it is part of the user context and migrates on handover.
 */
final class Kalman {

    private Kalman() {
    }

    /** Updates the state with a new measurement z and returns it (creates it on the first measurement). */
    static double[] update(double[] state, double z) {
        if (state == null) {
            return new double[] {z, Config.KALMAN_R};
        }
        double p = state[1] + Config.KALMAN_Q;          // predict
        double k = p / (p + Config.KALMAN_R);           // Kalman gain
        state[0] = state[0] + k * (z - state[0]);       // correct
        state[1] = (1 - k) * p;
        return state;
    }
}
