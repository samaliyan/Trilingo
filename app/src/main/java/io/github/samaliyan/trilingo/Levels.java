package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CEFR level (A1 … C2) of English and German words, from open word lists (see tools/build_levels.py).
 * Inflected forms are looked up by their base form (went → go, Tische → Tisch). Pure Java.
 */
final class Levels {
    private final Map<String, String> map;

    Levels(Map<String, String> map) {
        this.map = map;
    }

    static Levels read(InputStream in) throws IOException {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) m.put(line.substring(0, t), line.substring(t + 1).trim());
            }
        }
        return new Levels(m);
    }

    /** Words of one level (lower case), sorted. */
    java.util.List<String> words(String level) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) if (e.getValue().equals(level)) out.add(e.getKey());
        java.util.Collections.sort(out);
        return out;
    }

    int size() {
        return map.size();
    }

    /** Level of a word ("B1"), or null when the lists do not know it. */
    String of(String word, Lang lang, Nouns nouns) {
        String w = Text.clean(word).toLowerCase(Locale.ROOT);
        if (w.isEmpty() || w.contains(" ") && lang == Lang.DE) return null;
        if (w.startsWith("to ") && lang == Lang.EN) w = w.substring(3);
        String l = map.get(w);
        if (lang == Lang.EN && !w.contains(" ")) {           // an inflected form has the level of its base form (went → go)
            String base = EnglishVerbs.lemma(w);
            if (base != null && !base.equals(w) && map.get(base) != null && (l == null || map.get(base).compareTo(l) < 0)) return map.get(base);
        }
        if (l != null) return l;
        if (lang == Lang.DE) {
            String base = GermanVerbs.isKnownForm(w) ? GermanVerbs.lemma(w) : null;
            if (base != null && map.get(base) != null) return map.get(base);
            if (nouns != null) {
                Nouns.Info n = nouns.lookup(Character.toUpperCase(w.charAt(0)) + w.substring(1));
                if (n != null && map.get(n.noun.toLowerCase(Locale.ROOT)) != null) return map.get(n.noun.toLowerCase(Locale.ROOT));
            }
        } else if (lang == Lang.EN) {
            if (w.contains(" ")) return null;                 // phrasal verbs: no level
            String base = EnglishVerbs.lemma(w);
            if (base != null && map.get(base) != null) return map.get(base);
            if (w.endsWith("ies") && map.get(w.substring(0, w.length() - 3) + "y") != null) return map.get(w.substring(0, w.length() - 3) + "y");
            if (w.endsWith("es") && map.get(w.substring(0, w.length() - 2)) != null) return map.get(w.substring(0, w.length() - 2));
            if (w.endsWith("s") && map.get(w.substring(0, w.length() - 1)) != null) return map.get(w.substring(0, w.length() - 1));
        }
        return null;
    }

    /** "Beginner" … "Proficient" for a level. */
    static String name(String level) {
        switch (level == null ? "" : level) {
            case "A1": return "Beginner";
            case "A2": return "Elementary";
            case "B1": return "Intermediate";
            case "B2": return "Upper intermediate";
            case "C1": return "Advanced";
            case "C2": return "Proficient";
            default: return "";
        }
    }
}
