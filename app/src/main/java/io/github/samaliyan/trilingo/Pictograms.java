package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pictures that show what a word means — above all actions (put on, eat, switch on) and qualities (beautiful),
 * which have no photo of their own. They come from ARASAAC (arasaac.org), a free collection of clear drawings
 * made for learning and communication; each has the words it shows and their kind (verb, noun, adjective).
 *
 * A drawing is used only when one of its own words is exactly the word asked for, of the same kind — "put on"
 * is not shown with "put on shoes" alone when a drawing of "dress · wear" exists; nothing is better than a wrong
 * picture. Drawings by Sergio Palao for ARASAAC, Government of Aragón, licence CC BY-NC-SA.
 */
final class Pictograms {
    /** Kinds, as ARASAAC numbers them. */
    static final int NOUN = 2, VERB = 3, ADJECTIVE = 4;
    static final String SOURCE = "ARASAAC";

    /** Topics a learner's word should not open with (a word like "take off" also has such drawings). */
    private static final Set<String> AVOID = new HashSet<>(Arrays.asList("sexuality", "sex", "intimate hygiene",
            "underwear", "disruptive behavior", "violence", "drug", "drugs", "weapon"));

    private Pictograms() { }

    static String searchUrl(String english) {
        try {
            return "https://api.arasaac.org/api/pictograms/en/search/"
                    + java.net.URLEncoder.encode(english, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String imageUrl(int id) {
        return "https://static.arasaac.org/pictograms/" + id + "/" + id + "_500.png";
    }

    /**
     * The best drawing in a search answer for {@code term} of {@code kind}, or -1. {@code hint} (may be empty)
     * is what the meaning is about — "clothes" for "take off (clothes)": a drawing about it comes first.
     */
    static int best(String json, String term, int kind, String hint) {
        return best(json, term, kind, hint, null);
    }

    /**
     * As above; when {@code sense} is given (the word is ambiguous: "take off" chosen as "remove (clothes)"), the
     * drawing must also be of that meaning — one of its words is the meaning's word, or it is about the hint.
     */
    static int best(String json, String term, int kind, String hint, String sense) {
        List<Object> all;
        try {
            all = Json.arr(Json.parse(json));
        } catch (RuntimeException e) {
            return -1;
        }
        if (all == null) return -1;
        String t = norm(term), h = norm(hint);
        int bestId = -1, bestScore = -1;
        for (Object o : all) {
            Map<String, Object> p = Json.obj(o);
            if (p == null || Boolean.TRUE.equals(p.get("sex")) || Boolean.TRUE.equals(p.get("violence"))) continue;
            int id = (int) Json.num(p, "_id", -1);
            if (id < 0) continue;
            List<String> topics = new ArrayList<>(strings(Json.arr(p, "categories")));
            topics.addAll(strings(Json.arr(p, "tags")));
            boolean avoid = false;
            for (String c : topics) if (AVOID.contains(norm(c))) avoid = true;
            if (avoid) continue;
            int exact = -1;                       // the place of the asked word among the drawing's words
            List<String> words = new ArrayList<>();
            List<Object> kws = Json.arr(p, "keywords");
            if (kws == null) continue;
            for (Object k : kws) {
                Map<String, Object> kw = Json.obj(k);
                String w = norm(Json.str(kw, "keyword"));
                words.add(w);
                if (exact < 0 && w.equals(t) && (int) Json.num(kw, "type", -1) == kind) exact = words.size() - 1;
            }
            if (exact < 0) continue;
            if (sense != null) {
                String head = norm(sense.replaceAll("\\(.*?\\)", " ")).replaceFirst("^to ", "");
                boolean of = words.contains(head);
                if (!h.isEmpty()) for (String c : topics) if (norm(c).contains(h)) of = true;
                if (!of) continue;
            }
            int score = 10;
            if (exact == 0) score += 3;                               // the drawing is mainly of this word
            if (!h.isEmpty()) {
                for (String c : topics) if (norm(c).contains(h)) { score += 6; break; }
                for (String w : words) if (!w.equals(t) && w.contains(h)) { score += 6; break; }
            }
            for (String c : topics) {
                String n = norm(c);
                if (n.equals("usual verbs") || n.startsWith("core vocabulary")) { score += 2; break; }
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestId;
    }

    /**
     * The English words to look for, best first: a chosen meaning ("take off" as "remove (clothes)" →
     * "remove clothes", "remove"), then the English word itself. At most three words each.
     */
    static List<String> terms(String sense, String english) {
        List<String> out = new ArrayList<>();
        if (sense != null && !sense.trim().isEmpty()) {
            String joined = norm(sense.replace("(", " ").replace(")", " "));
            String head = norm(sense.replaceAll("\\(.*?\\)", " "));
            add(out, joined);
            add(out, head);
        }
        add(out, english == null ? "" : norm(english));
        return out;
    }

    /** What a meaning is about: the words in brackets of "remove (clothes)"; "" when none. */
    static String hint(String sense) {
        if (sense == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(sense);
        return m.find() ? norm(m.group(1)) : "";
    }

    private static void add(List<String> out, String t) {
        t = t.replaceFirst("^(to|the|a|an) ", "").trim();
        if (t.isEmpty() || t.split(" ").length > 3 || out.contains(t)) return;
        out.add(t);
    }

    private static List<String> strings(List<Object> l) {
        List<String> out = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof String) out.add((String) o);
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
