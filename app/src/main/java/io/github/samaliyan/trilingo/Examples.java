package io.github.samaliyan.trilingo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Example sentences from Tatoeba (tatoeba.org, CC BY): real, short sentences written and translated by people.
 * This class builds the request, reads the answer and picks the simplest good examples. Pure Java.
 */
final class Examples {
    private Examples() { }

    static final int MAX = 3;
    static final String SOURCE = "Tatoeba";

    /** One example: a sentence in the typed word's language and its translations (only the two other languages). */
    static final class Example {
        final String text;
        final Map<Lang, String> translations;

        Example(String text, Map<Lang, String> translations) {
            this.text = text;
            this.translations = Collections.unmodifiableMap(new LinkedHashMap<>(translations));
        }

        Example with(Lang l, String t) {
            Map<Lang, String> m = new LinkedHashMap<>(translations);
            m.put(l, t);
            return new Example(text, m);
        }
    }

    static String code(Lang l) {
        switch (l) {
            case EN: return "eng";
            case DE: return "deu";
            default: return "pes";
        }
    }

    static Lang fromCode(String c) {
        if ("eng".equals(c)) return Lang.EN;
        if ("deu".equals(c)) return Lang.DE;
        if ("pes".equals(c)) return Lang.FA;
        return null;
    }

    /** Examples make sense for a word or a short phrase, not for a sentence. */
    static boolean suitable(String q) {
        String c = Text.clean(q);
        return Text.hasLetter(c) && c.split(" ").length <= 3 && c.length() <= 40;
    }

    static String url(String word, Lang src) {
        String w = Text.clean(word);
        if (src == Lang.FA) w = Text.unifyPersian(w);
        String q = w.contains(" ") ? "\"" + w + "\"" : "=" + w;      // exact word, or the exact phrase
        Lang[] t = src.targets();
        return "https://api.tatoeba.org/unstable/sentences?lang=" + code(src) + "&q=" + Services.enc(q)
                + "&trans%3Alang=" + code(t[0]) + "%2C" + code(t[1])
                + "&showtrans=matching&word_count=3-12&sort=relevance&limit=40";
    }

    /** Reads Tatoeba's answer and returns up to {@code max} examples, the most useful first. */
    static List<Example> parse(String body, Lang src, int max) throws IOException {
        List<Object> data;
        try {
            data = Json.arr(Json.obj(Json.parse(body)), "data");
        } catch (IllegalArgumentException e) {
            throw new IOException("bad answer", e);
        }
        if (data == null) throw new IOException("bad answer");
        Lang[] targets = src.targets();
        List<Example> out = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : data) {
            Map<String, Object> m = Json.obj(o);
            String text = Text.clean(Json.str(m, "text"));
            if (text.isEmpty() || !seen.add(Text.key(text).replaceAll("[.!?]+$", ""))) continue;
            Map<Lang, String> tr = new LinkedHashMap<>();
            double score = 0;
            for (Lang l : targets) {
                String best = null;
                boolean direct = false;
                List<Object> ts = Json.arr(m, "translations");
                if (ts != null) {
                    for (Object to : ts) {
                        Map<String, Object> tm = Json.obj(to);
                        if (fromCode(Json.str(tm, "lang")) != l) continue;
                        String tt = Text.clean(Json.str(tm, "text"));
                        if (tt.isEmpty()) continue;
                        boolean d = Boolean.TRUE.equals(tm == null ? null : tm.get("is_direct"));
                        if (best == null || (d && !direct)) {
                            best = tt;
                            direct = d;
                        }
                    }
                }
                if (best != null) {
                    tr.put(l, best);
                    score += direct ? 3 : 1;
                }
            }
            int words = text.split(" ").length;
            if (words >= 4 && words <= 9) score += 1;
            if (text.endsWith(".")) score += 0.5;
            if (text.endsWith("!")) score -= 0.5;
            // keep the original order (relevance) among equal scores
            int at = 0;
            while (at < scores.size() && scores.get(at) >= score) at++;
            scores.add(at, score);
            out.add(at, new Example(text, tr));
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Where the word appears in a sentence, as [start, end) pairs, for bold text. Case is ignored; for
     * German and English only whole words count.
     */
    static List<int[]> find(String text, String word) {
        List<int[]> out = new ArrayList<>();
        String w = Text.clean(word);
        if (w.isEmpty()) return out;
        boolean persian = Text.hasPersian(w);
        String t = persian ? Text.unifyPersian(text) : text.toLowerCase(Locale.ROOT);
        String needle = persian ? Text.unifyPersian(w) : w.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = t.indexOf(needle, from);
            if (i < 0) break;
            int end = i + needle.length();
            boolean ok = persian || ((i == 0 || !Character.isLetter(t.charAt(i - 1))) && (end >= t.length() || !Character.isLetter(t.charAt(end))));
            if (ok) out.add(new int[]{i, end});
            from = end;
        }
        return out;
    }
}
