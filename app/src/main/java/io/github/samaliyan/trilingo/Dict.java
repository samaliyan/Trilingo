package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in dictionary of common English and German words with their translations (from Wiktionary,
 * see tools/build_dict.py). Gives an answer at once and without internet. Persian words are found
 * through the Persian translations of both dictionaries. Pure Java.
 */
final class Dict {
    static final String SERVICE = "Offline dictionary";
    private static final int MAX_POS = 3, MAX_TERMS = 4;

    static final class Entry {
        final String word;
        final int rank;
        final Map<Lang, List<Lookup.Sense>> targets = new EnumMap<>(Lang.class);

        Entry(String word, int rank) {
            this.word = word;
            this.rank = rank;
        }
    }

    final Lang lang;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<Entry>> byKey = new HashMap<>();

    Dict(Lang lang, List<String> lines) {
        this.lang = lang;
        for (String line : lines) {
            Entry e = parse(line, entries.size());
            if (e == null) continue;
            entries.add(e);
            byKey.computeIfAbsent(e.word.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
        }
    }

    static Dict read(Lang lang, InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty()) lines.add(l);
        }
        return new Dict(lang, lines);
    }

    /** "house\tde=noun:Haus,Gebäude;verb:unterbringen\tfa=noun:خانه" → entry; null when damaged. */
    static Entry parse(String line, int rank) {
        String[] cols = line.split("\t");
        if (cols.length < 3 || cols[0].trim().isEmpty()) return null;
        Entry e = new Entry(cols[0].trim(), rank);
        for (int i = 1; i < cols.length; i++) {
            int eq = cols[i].indexOf('=');
            Lang l = eq > 0 ? Lang.of(cols[i].substring(0, eq)) : null;
            if (l == null) continue;
            List<Lookup.Sense> senses = new ArrayList<>();
            for (String part : cols[i].substring(eq + 1).split(";")) {
                int c = part.indexOf(':');
                if (c <= 0) continue;
                List<String> terms = new ArrayList<>();
                for (String t : part.substring(c + 1).split(",")) if (!t.trim().isEmpty()) terms.add(t.trim());
                if (!terms.isEmpty()) senses.add(new Lookup.Sense(part.substring(0, c), terms));
            }
            if (!senses.isEmpty()) e.targets.put(l, senses);
        }
        return e.targets.size() == 2 ? e : null;
    }

    int size() {
        return entries.size();
    }

    /** The entry for a word: the same spelling first ("Essen" the noun, "essen" the verb), else any case. */
    Entry find(String word) {
        String w = Text.clean(word);
        if (lang == Lang.EN && w.toLowerCase(Locale.ROOT).startsWith("to ")) w = w.substring(3);
        List<Entry> es = byKey.get(w.toLowerCase(Locale.ROOT));
        if (es == null || es.isEmpty()) return null;
        for (Entry e : es) if (e.word.equals(w)) return e;
        // typed in lower case: a lower-case entry (verb, adjective) before a noun
        if (!w.isEmpty() && Character.isLowerCase(w.charAt(0)))
            for (Entry e : es) if (Character.isLowerCase(e.word.charAt(0))) return e;
        return es.get(0);
    }

    boolean has(String word) {
        return find(word) != null;
    }

    /** Rank of the word (0 = most common), or -1. */
    int rank(String word) {
        Entry e = find(word);
        return e == null ? -1 : e.rank;
    }

    /** The word's lookup (as the translation service gives it), or null when the dictionary does not have it. */
    Lookup lookup(String query, long now) {
        Entry e = find(query);
        if (e == null) return null;
        List<Lookup.Result> rs = new ArrayList<>();
        for (Lang t : lang.targets()) {
            List<Lookup.Sense> s = e.targets.get(t);
            if (s == null || s.isEmpty()) return null;
            rs.add(new Lookup.Result(t, s.get(0).terms.get(0), s));
        }
        return new Lookup(Text.clean(query), lang, rs, SERVICE, now);
    }

    /** "Haus, Gebäude · خانه" — a short summary for lists (the back of a flashcard). */
    String summary(String word) {
        Lookup l = lookup(word, 0);
        return l == null ? "" : Notebook.summary(l);
    }

    /**
     * The lookup from the dictionaries for typed text, or null. {@code source} null = automatic: Persian script
     * is Persian; a Latin word only one dictionary (or word list) has is taken from it. A word both have
     * ("Gift", "Rat") is left to the translation service unless {@code anyway} (then the more common one wins).
     */
    static Lookup choose(String query, Lang source, boolean anyway, long now, Dict en, Dict de, Words enWords, Words deWords) {
        String q = Text.clean(query);
        if (q.isEmpty() || en == null || de == null || q.split(" ").length > 3) return null;
        if (source == Lang.FA || (source == null && Text.hasPersian(q))) return persian(q, en, de, now);
        if (source == Lang.EN) return en.lookup(q, now);
        if (source == Lang.DE) return de.lookup(q, now);
        Lang guess = Words.guessLatin(enWords, deWords, q);
        if (guess == Lang.EN) return en.lookup(q, now);
        if (guess == Lang.DE) return de.lookup(q, now);
        int re = en.rank(q), rd = de.rank(q);
        if (re < 0 && rd < 0) return null;
        if (re < 0) return de.lookup(q, now);
        if (rd < 0) return en.lookup(q, now);
        if (!anyway) return null;
        return (rd < re ? de : en).lookup(q, now);
    }

    // ------------------------------------------------------------------ Persian → English / German

    /** Persian comparison key: Arabic letter forms unified, no half-spaces or vowel marks. */
    static String persianKey(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : Text.unifyPersian(Text.clean(s)).toCharArray()) {
            if (c == 0x200C) b.append(' ');
            else if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x0640) continue;
            else b.append(c);
        }
        return b.toString().replaceAll(" +", " ").trim();
    }

    /** Persian term → entries whose translation it is, the main translation and common words first. */
    private Map<String, List<Entry>> persian;

    private synchronized Map<String, List<Entry>> persianIndex() {
        if (persian != null) return persian;
        Map<String, List<long[]>> scored = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            List<Lookup.Sense> fa = e.targets.get(Lang.FA);
            if (fa == null) continue;
            for (int s = 0; s < fa.size(); s++) {
                List<String> terms = fa.get(s).terms;
                for (int t = 0; t < terms.size(); t++) {
                    // the first word of the first meaning counts most; a later, rarer meaning much less
                    long score = e.rank + (s == 0 && t == 0 ? 0 : t == 0 ? 4000 : 8000 + 2000L * t);
                    scored.computeIfAbsent(persianKey(terms.get(t)), k -> new ArrayList<>()).add(new long[]{score, i});
                }
            }
        }
        Map<String, List<Entry>> out = new HashMap<>();
        for (Map.Entry<String, List<long[]>> m : scored.entrySet()) {
            List<long[]> v = m.getValue();
            v.sort((a, b) -> Long.compare(a[0], b[0]));
            List<Entry> es = new ArrayList<>();
            for (long[] x : v) {
                Entry e = entries.get((int) x[1]);
                if (!es.contains(e)) es.add(e);
            }
            out.put(m.getKey(), es);
        }
        persian = out;
        return out;
    }

    /** Words of this dictionary that translate the Persian word, best first. */
    List<Entry> fromPersian(String fa) {
        List<Entry> es = persianIndex().get(persianKey(fa));
        return es == null ? new ArrayList<>() : es;
    }

    /** A Persian word's lookup from the English and the German dictionary; null when either has nothing. */
    static Lookup persian(String query, Dict en, Dict de, long now) {
        if (en == null || de == null || !Text.hasPersian(query)) return null;
        List<Entry> e = en.fromPersian(query), d = de.fromPersian(query);
        if (e.isEmpty() && d.isEmpty()) return null;
        // one side missing: the translation of the other side's best word (same word type)
        List<Lookup.Sense> enS = e.isEmpty() ? viaOther(d.get(0), Lang.EN) : group(e);
        List<Lookup.Sense> deS = d.isEmpty() ? viaOther(e.get(0), Lang.DE) : group(d);
        if (enS.isEmpty() || deS.isEmpty()) return null;
        List<Lookup.Result> rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, enS.get(0).terms.get(0), enS));
        rs.add(new Lookup.Result(Lang.DE, deS.get(0).terms.get(0), deS));
        return new Lookup(Text.clean(query), Lang.FA, rs, SERVICE, now);
    }

    /** Words grouped by their word type ("noun: house, home"), in the order found. */
    private static List<Lookup.Sense> group(List<Entry> es) {
        Map<String, List<String>> byPos = new LinkedHashMap<>();
        for (Entry x : es) {
            List<Lookup.Sense> fa = x.targets.get(Lang.FA);
            String pos = fa == null || fa.isEmpty() ? "" : fa.get(0).pos;
            List<String> t = byPos.computeIfAbsent(pos, k -> new ArrayList<>());
            if (t.size() < MAX_TERMS && byPos.size() <= MAX_POS && !t.contains(x.word)) t.add(x.word);
        }
        List<Lookup.Sense> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> m : byPos.entrySet()) if (!m.getValue().isEmpty()) out.add(new Lookup.Sense(m.getKey(), m.getValue()));
        return out;
    }

    private static List<Lookup.Sense> viaOther(Entry e, Lang want) {
        List<Lookup.Sense> s = e.targets.get(want);
        return s == null ? new ArrayList<>() : s;
    }
}
