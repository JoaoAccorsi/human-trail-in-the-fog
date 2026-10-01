package tms;

/**
 * Query period in absolute timeslots (day * 48 + slot), both ends inclusive.
 *
 *   day=20                         -> the whole day 20
 *   day=20&from=07:00&to=19:00     -> day 20, from 07:00 to 19:00
 *   day=20&toDay=22                -> days 20 to 22
 *   day=20&from=18:00&toDay=21&to=09:00 -> from day 20 18:00 to day 21 09:00
 *
 * A visit belongs to the period when it overlaps it (it is returned whole).
 */
record Period(int start, int end) {

    static final int SLOTS_PER_DAY = 48;

    /** null when no day is given (no filter). */
    static Period of(Integer day, Integer toDay, String from, String to) {
        if (day == null) {
            return null;
        }
        int lastDay = toDay == null ? day : toDay;
        int s = day * SLOTS_PER_DAY + (from == null ? 0 : slot(from));
        int e = lastDay * SLOTS_PER_DAY + (to == null ? SLOTS_PER_DAY - 1 : slot(to));
        if (e < s) {
            throw new IllegalArgumentException("the period ends before it starts");
        }
        return new Period(s, e);
    }

    /** "HH:MM" -> timeslot 0..47 (30-minute slots). */
    static int slot(String hhmm) {
        String[] p = hhmm.trim().split(":");
        int h = Integer.parseInt(p[0]);
        int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        if (h < 0 || h > 23 || m < 0 || m > 59) {
            throw new IllegalArgumentException("invalid time " + hhmm);
        }
        return h * 2 + m / 30;
    }

    /** Does a visit (day, arrival slot, departure slot) overlap the period? */
    boolean overlaps(int day, int arrivalT, int departureT) {
        return day * SLOTS_PER_DAY + arrivalT <= end && day * SLOTS_PER_DAY + departureT >= start;
    }

    @Override
    public String toString() {
        return label(start) + " - " + label(end);
    }

    private static String label(int abs) {
        int slot = abs % SLOTS_PER_DAY;
        return String.format("day %d %02d:%02d", abs / SLOTS_PER_DAY, slot / 2, slot % 2 * 30);
    }
}
