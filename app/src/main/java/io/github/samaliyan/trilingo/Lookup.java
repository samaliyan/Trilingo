package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One looked-up word or text: its language and its translation into the two other languages.
 * Saved in the history, so a word looked up once is shown instantly (and offline) the next time.
 */
final class Lookup {
    /** A group of dictionary meanings, e.g. pos "noun" → [house, home, building]. */
    static final class Sense {
        final String pos;
        final List<String> terms;

        Sense(String pos, List<String> terms) {
            this.pos = pos == null ? "" : pos;
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
        }
    }

    /** Translation into one language. */
    static final class Result {
        final Lang lang;
        final String text;
        final List<Sense> senses;

        Result(Lang lang, String text, List<Sense> senses) {
            this.lang = lang;
            this.text = text == null ? "" : text;
            this.senses = Collections.unmodifiableList(new ArrayList<>(senses));
        }

        /** The main translation and up to {@code max} further different meanings, for short summaries. */
        String summary(int max) {
            List<String> parts = new ArrayList<>();
            if (!text.isEmpty()) parts.add(text);
            for (Sense s : senses) {
                for (String t : s.terms) {
                    if (parts.size() > max) break;
                    boolean dup = false;
                    for (String p : parts) dup |= p.equalsIgnoreCase(t);
                    if (!dup) parts.add(t);
                }
            }
            return String.join(lang == Lang.FA ? "\u060C " : ", ", parts);
        }
    }

    final String query;
    final Lang source;
    final List<Result> results;
    final String service;
    final long time;
    /** "Did you mean" correction of the query; "" when the spelling looks right. */
    final String suggestion;
    /** Dictionary form of the query from the service (may be ""). */
    final String baseForm;

    Lookup(String query, Lang source, List<Result> results, String service, long time) {
        this(query, source, results, service, time, "");
    }

    Lookup(String query, Lang source, List<Result> results, String service, long time, String suggestion) {
        this(query, source, results, service, time, suggestion, "");
    }

    /**
     * @param baseForm dictionary form of the query given by the service (habe → haben, went → go), or ""
     */
    Lookup(String query, Lang source, List<Result> results, String service, long time, String suggestion, String baseForm) {
        this.query = query;
        this.source = source;
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
        this.service = service == null ? "" : service;
        this.time = time;
        String sg = suggestion == null ? "" : suggestion;
        // A "correction" that only changes letter case or spaces is not shown.
        this.suggestion = Text.key(sg).equals(Text.key(query == null ? "" : query)) ? "" : sg;
        this.baseForm = baseForm == null ? "" : Text.clean(baseForm);
    }

    boolean hasSuggestion() {
        return !suggestion.isEmpty();
    }

    Lookup withTime(long t) {
        return new Lookup(query, source, results, service, t, suggestion, baseForm);
    }

    Result result(Lang l) {
        for (Result r : results) if (r.lang == l) return r;
        return null;
    }

    /** History key: same text (ignoring case and extra spaces) and same source language. */
    String key() {
        return Text.key(query) + "|" + source.code;
    }

    // ------------------------------------------------------------------ JSON

    String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\"q\":").append(Json.quote(query))
                .append(",\"src\":").append(Json.quote(source.code))
                .append(",\"svc\":").append(Json.quote(service))
                .append(",\"t\":").append(time)
                .append(",\"sg\":").append(Json.quote(suggestion))
                .append(",\"bf\":").append(Json.quote(baseForm))
                .append(",\"r\":[");
        for (int k = 0; k < results.size(); k++) {
            Result r = results.get(k);
            if (k > 0) b.append(',');
            b.append("{\"l\":").append(Json.quote(r.lang.code)).append(",\"x\":").append(Json.quote(r.text)).append(",\"s\":[");
            for (int m = 0; m < r.senses.size(); m++) {
                Sense s = r.senses.get(m);
                if (m > 0) b.append(',');
                b.append("{\"p\":").append(Json.quote(s.pos)).append(",\"w\":[");
                for (int n = 0; n < s.terms.size(); n++) {
                    if (n > 0) b.append(',');
                    b.append(Json.quote(s.terms.get(n)));
                }
                b.append("]}");
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    /** Reads one saved lookup; null if it is damaged or incomplete. */
    static Lookup fromJson(Object o) {
        Map<String, Object> m = Json.obj(o);
        String q = Json.str(m, "q");
        Lang src = Lang.of(Json.str(m, "src"));
        List<Object> rs = Json.arr(m, "r");
        if (q == null || q.isEmpty() || src == null || rs == null) return null;
        List<Result> results = new ArrayList<>();
        for (Object ro : rs) {
            Map<String, Object> rm = Json.obj(ro);
            Lang l = Lang.of(Json.str(rm, "l"));
            String x = Json.str(rm, "x");
            if (l == null || x == null || l == src) return null;
            List<Sense> senses = new ArrayList<>();
            List<Object> ss = Json.arr(rm, "s");
            if (ss != null) {
                for (Object so : ss) {
                    Map<String, Object> sm = Json.obj(so);
                    List<String> terms = new ArrayList<>();
                    List<Object> ws = Json.arr(sm, "w");
                    if (ws != null) for (Object w : ws) if (Json.str(w) != null) terms.add((String) w);
                    if (!terms.isEmpty()) senses.add(new Sense(Json.str(sm, "p"), terms));
                }
            }
            results.add(new Result(l, x, senses));
        }
        if (results.size() != 2) return null;
        return new Lookup(q, src, results, Json.str(m, "svc"), (long) Json.num(m, "t", 0), Json.str(m, "sg"), Json.str(m, "bf"));
    }
}
