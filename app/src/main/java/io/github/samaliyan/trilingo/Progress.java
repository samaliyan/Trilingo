package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * What the learner did each day: reviews (and how many were right), words saved, words learned (a word
 * that will not be asked for three weeks or more), practice answers (and how many were right), words
 * spoken. From it: the streak (days in a row), the best streak, and the weeks for the chart.
 * Kept in the notebook's file. Pure Java (tested).
 */
final class Progress {
    static final int REVIEWS = 0, REVIEWS_RIGHT = 1, SAVED = 2, LEARNED = 3, PRACTICE = 4, PRACTICE_RIGHT = 5, SPOKEN = 6;
    private static final int FIELDS = 7;
    static final long DAY = 86_400_000L;

    /** Day number (days since 1970 in the phone's time zone) → counts. */
    private final TreeMap<Long, int[]> days = new TreeMap<>();

    static long day(long now) {
        return Math.floorDiv(now + TimeZone.getDefault().getOffset(now), DAY);
    }

    void add(long now, int field, int n) {
        days.computeIfAbsent(day(now), d -> new int[FIELDS])[field] += n;
    }

    int on(long day, int field) {
        int[] c = days.get(day);
        return c == null ? 0 : c[field];
    }

    boolean active(long day) {
        int[] c = days.get(day);
        if (c == null) return false;
        return c[REVIEWS] + c[SAVED] + c[PRACTICE] + c[SPOKEN] > 0;
    }

    /** Days in a row with some learning, up to today (or yesterday, when today has nothing yet). */
    int streak(long now) {
        long d = day(now);
        if (!active(d)) d--;
        int n = 0;
        while (active(d)) {
            n++;
            d--;
        }
        return n;
    }

    int bestStreak() {
        int best = 0, run = 0;
        long prev = Long.MIN_VALUE;
        for (Map.Entry<Long, int[]> e : days.entrySet()) {
            if (!active(e.getKey())) continue;
            run = e.getKey() == prev + 1 ? run + 1 : 1;
            prev = e.getKey();
            best = Math.max(best, run);
        }
        return best;
    }

    /** The sum of a field over the 7 days ending today, {@code weeksAgo} weeks back (0 = this week). */
    int week(long now, int field, int weeksAgo) {
        long end = day(now) - 7L * weeksAgo;
        int n = 0;
        for (long d = end - 6; d <= end; d++) n += on(d, field);
        return n;
    }

    /** A field for the last {@code n} weeks, oldest first (for the chart). */
    List<Integer> weeks(long now, int field, int n) {
        List<Integer> out = new ArrayList<>();
        for (int w = n - 1; w >= 0; w--) out.add(week(now, field, w));
        return out;
    }

    /** Right answers in percent (reviews and practice) over the last 7 days, or -1 when there were none. */
    int accuracy(long now) {
        int all = week(now, REVIEWS, 0) + week(now, PRACTICE, 0);
        if (all == 0) return -1;
        return Math.round(100f * (week(now, REVIEWS_RIGHT, 0) + week(now, PRACTICE_RIGHT, 0)) / all);
    }

    boolean isEmpty() {
        return days.isEmpty();
    }

    // ------------------------------------------------------------------ the file

    void read(Map<String, Object> log) {
        if (log == null) return;
        for (Map.Entry<String, Object> e : log.entrySet()) {
            try {
                long d = Long.parseLong(e.getKey());
                if (!(e.getValue() instanceof List)) continue;
                List<?> v = (List<?>) e.getValue();
                int[] c = new int[FIELDS];
                for (int i = 0; i < Math.min(FIELDS, v.size()); i++)
                    if (v.get(i) instanceof Number) c[i] = ((Number) v.get(i)).intValue();
                days.put(d, c);
            } catch (NumberFormatException ignored) {
                // a damaged day is skipped
            }
        }
    }

    /** {"20419":[3,2,1,0,5,4,0],…} — the last two years at most. */
    String toJson(long now) {
        StringBuilder b = new StringBuilder("{");
        long from = day(now) - 730;
        boolean first = true;
        for (Map.Entry<Long, int[]> e : days.entrySet()) {
            if (e.getKey() < from) continue;
            if (!first) b.append(',');
            first = false;
            b.append('"').append(e.getKey()).append("\":[");
            int[] c = e.getValue();
            for (int i = 0; i < c.length; i++) b.append(i > 0 ? "," : "").append(c[i]);
            b.append(']');
        }
        return b.append('}').toString();
    }
}
