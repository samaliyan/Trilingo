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

    /** Quality filters, best first: recorded by a person (proofread), written by a native speaker, any. */
    static final String[] FILTERS = {"&has_audio=yes", "&is_native=yes", ""};

    static String url(String word, Lang src, String filter) {
        String w = Text.clean(word);
        if (src == Lang.FA) w = Text.unifyPersian(w);
        String q = w.contains(" ") ? "\"" + w + "\"" : "=" + w;      // exact word, or the exact phrase
        Lang[] t = src.targets();
        return "https://api.tatoeba.org/v1/sentences?lang=" + code(src) + "&q=" + Services.enc(q)
                + "&trans%3Alang=" + code(t[0]) + "%2C" + code(t[1])
                + "&showtrans=matching&word_count=3-12&sort=relevance&limit=40&is_unapproved=no&is_orphan=no" + filter;
    }

    /** A sentence with its translations and how good an example it is. */
    static final class Candidate {
        final Example example;
        final boolean[] direct;
        double score;

        Candidate(Example e, boolean[] direct) {
            this.example = e;
            this.direct = direct;
        }
    }

    /** Reads one Tatoeba answer. */
    static List<Candidate> parse(String body, Lang src) throws IOException {
        List<Object> data;
        try {
            data = Json.arr(Json.obj(Json.parse(body)), "data");
        } catch (IllegalArgumentException e) {
            throw new IOException("bad answer", e);
        }
        if (data == null) throw new IOException("bad answer");
        Lang[] targets = src.targets();
        List<Candidate> out = new ArrayList<>();
        for (Object o : data) {
            Map<String, Object> m = Json.obj(o);
            String text = Text.clean(Json.str(m, "text"));
            if (text.isEmpty()) continue;
            Map<Lang, String> tr = new LinkedHashMap<>();
            boolean[] direct = new boolean[2];
            for (int k = 0; k < 2; k++) {
                Lang l = targets[k];
                String best = null;
                boolean d0 = false;
                List<Object> ts = Json.arr(m, "translations");
                if (ts != null) {
                    for (Object to : ts) {
                        Map<String, Object> tm = Json.obj(to);
                        if (fromCode(Json.str(tm, "lang")) != l) continue;
                        String tt = Text.clean(Json.str(tm, "text"));
                        if (tt.isEmpty()) continue;
                        boolean d = Boolean.TRUE.equals(tm == null ? null : tm.get("is_direct"));
                        if (best == null || (d && !d0)) {
                            best = tt;
                            d0 = d;
                        }
                    }
                }
                if (best != null) {
                    tr.put(l, best);
                    direct[k] = d0;
                }
            }
            out.add(new Candidate(new Example(text, tr), direct));
        }
        return out;
    }

    /** Names that Tatoeba uses in very many sentences; such sentences make weaker examples. */
    private static final Set<String> NAMES = new HashSet<>(java.util.Arrays.asList(
            "tom", "mary", "john", "sami", "layla", "ken", "jack", "maria", "ziri", "yanni", "dan", "linda",
            "mennad", "baya", "rima", "skura", "fadil", "dania", "jim", "bob", "alice", "muiriel", "taninna",
            "mike", "jane", "bill", "kate", "lucy", "emily", "marika", "tatoeba"));

    private static Set<String> wordSet(String s) {
        Set<String> w = new HashSet<>();
        for (String x : s.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (!x.isEmpty()) w.add(x);
        return w;
    }

    /**
     * Picks the best {@code max} examples. Good: both translations made by people, the translation uses
     * the expected meaning ({@code expected}: words of the lookup's translations), 4–9 words, a plain
     * statement, no recurring names. Very similar sentences are not shown twice.
     * The candidates' order (quality filter, then relevance) breaks ties.
     */
    static List<Example> select(List<Candidate> all, Lang src, Map<Lang, Set<String>> expected, int max) {
        Lang[] targets = src.targets();
        List<Candidate> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : all) {
            String t = c.example.text;
            if (!seen.add(Text.key(t).replaceAll("[.!?]+$", ""))) continue;
            double s = 0;
            for (int k = 0; k < 2; k++) {
                String tr = c.example.translations.get(targets[k]);
                if (tr == null) continue;
                s += c.direct[k] ? 3 : 1;
                Set<String> exp = expected == null ? null : expected.get(targets[k]);
                if (exp != null) {
                    Set<String> tw = wordSet(tr);
                    for (String e : exp) {
                        if (tw.contains(e) || (e.contains(" ") && tr.toLowerCase(Locale.ROOT).contains(e))) {
                            s += 2;
                            break;
                        }
                    }
                }
            }
            Set<String> ws = wordSet(t);
            int words = t.split(" ").length;
            if (words >= 4 && words <= 9) s += 1;
            if (t.endsWith(".")) s += 0.5;
            if (t.endsWith("!")) s -= 0.5;
            for (String n : NAMES) if (ws.contains(n)) {
                s -= 3;
                break;
            }
            c.score = s;
            int at = 0;
            while (at < scored.size() && scored.get(at).score >= s) at++;
            scored.add(at, c);
        }
        List<Example> out = new ArrayList<>();
        List<Set<String>> chosen = new ArrayList<>();
        for (Candidate c : scored) {
            if (out.size() >= max) break;
            Set<String> ws = wordSet(c.example.text);
            boolean similar = false;
            for (Set<String> o : chosen) {
                Set<String> inter = new HashSet<>(ws);
                inter.retainAll(o);
                Set<String> union = new HashSet<>(ws);
                union.addAll(o);
                if (!union.isEmpty() && inter.size() * 1.0 / union.size() >= 0.5) similar = true;
            }
            if (similar) continue;
            chosen.add(ws);
            out.add(c.example);
        }
        return out;
    }

    /** Words of the lookup's translations, by language, for {@link #select}. */
    static Map<Lang, Set<String>> expected(Lookup l) {
        Map<Lang, Set<String>> m = new java.util.EnumMap<>(Lang.class);
        if (l == null) return m;
        for (Lookup.Result r : l.results) {
            Set<String> s = new HashSet<>();
            String main = r.text.toLowerCase(Locale.ROOT);
            if (main.split(" ").length <= 3) s.add(main.startsWith("to ") ? main.substring(3) : main);
            for (Lookup.Sense sense : r.senses) for (String t : sense.terms) {
                String x = t.toLowerCase(Locale.ROOT);
                s.add(x.startsWith("to ") ? x.substring(3) : x);
            }
            m.put(r.lang, s);
        }
        return m;
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
