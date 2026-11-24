package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verb families. English: phrasal verbs of a verb (give → give up, give in …) from a built-in list with
 * short meanings. German has no phrasal verbs, but its prefix verbs work the same way
 * (kommen → ankommen, mitkommen, bekommen …); they are found in the German word list. Pure Java.
 */
final class Phrasal {
    /** A phrasal / prefix verb and its meaning ("" when it still has to be looked up). */
    static final class Entry {
        final String verb, meaning;

        Entry(String verb, String meaning) {
            this.verb = verb;
            this.meaning = meaning;
        }
    }

    private final Map<String, List<Entry>> english = new LinkedHashMap<>();

    Phrasal(List<String> lines) {
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int bar = l.indexOf('|');
            if (bar <= 0) continue;
            String verb = l.substring(0, bar).trim().toLowerCase(Locale.ROOT);
            String base = verb.split(" ")[0];
            english.computeIfAbsent(base, k -> new ArrayList<>()).add(new Entry(verb, l.substring(bar + 1).trim()));
        }
    }

    static Phrasal read(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return new Phrasal(lines);
    }

    /** Phrasal verbs of an English verb ("give" or "give up" → all "give …"). */
    List<Entry> english(String verb) {
        String v = EnglishVerbs.clean(verb);
        if (v == null) return new ArrayList<>();
        List<Entry> l = english.get(v.split(" ")[0]);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /** Meaning of an English phrasal verb ("give up"), or null. */
    String meaning(String phrasal) {
        String v = EnglishVerbs.clean(phrasal);
        if (v == null || !v.contains(" ")) return null;
        for (Entry e : english(v)) if (e.verb.equals(v)) return e.meaning;
        return null;
    }

    private static final String[] PREFIXES = {"ab", "an", "auf", "aus", "bei", "ein", "fest", "fort", "her", "herein",
            "heraus", "hin", "los", "mit", "nach", "vor", "vorbei", "weg", "weiter", "zu", "zurück", "zusammen",
            "be", "emp", "ent", "er", "ge", "miss", "ver", "zer", "über", "unter", "um", "durch", "wieder"};

    /**
     * German verbs made of a prefix and this verb that are in the word list (common ones), most frequent
     * first: kommen → ankommen, bekommen, mitkommen … The verb's own prefix is removed first (ankommen → kommen).
     */
    static List<Entry> german(String infinitive, Words de, int max) {
        List<Entry> out = new ArrayList<>();
        GermanVerbs.Parts p = GermanVerbs.analyze(infinitive);
        if (p == null || de == null) return out;
        String base = p.base;
        // "gekommen" is the participle of kommen, not a prefix verb
        Conjugation c = GermanVerbs.conjugate(base);
        String participle = "";
        if (c != null) for (Conjugation.Info i : c.info) if (i.label.equals("Participle")) participle = i.value;
        List<int[]> found = new ArrayList<>();
        List<String> verbs = new ArrayList<>();
        for (String pre : PREFIXES) {
            String v = pre + base;
            int r = de.rank(v);
            if (r >= 0 && !v.equals(base) && !v.equals(participle)) {
                found.add(new int[]{r, verbs.size()});
                verbs.add(v);
            }
        }
        found.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] f : found) {
            if (out.size() >= max) break;
            out.add(new Entry(verbs.get(f[1]), ""));
        }
        return out;
    }
}
