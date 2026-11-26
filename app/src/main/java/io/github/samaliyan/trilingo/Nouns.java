package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * German nouns with their article (der / die / das) and plural, from German Wiktionary
 * (see tools/build_data.py). Plural forms are found too (Häuser → das Haus), and compound nouns take
 * the article of their last part (Haustür → die Tür → die Haustür). Pure Java.
 */
final class Nouns {
    /** A noun's article(s) and plural. Some nouns have several genders (der See / die See). */
    static final class Info {
        final String noun;
        final List<String> genders;     // "m", "f", "n"
        final List<String> plurals;     // same order; "" when there is none

        Info(String noun, List<String> genders, List<String> plurals) {
            this.noun = noun;
            this.genders = genders;
            this.plurals = plurals;
        }

        /** "der", "die", "das" or e.g. "der / die" */
        String articles() {
            List<String> a = new ArrayList<>();
            for (String g : genders) {
                String x = article(g);
                if (!a.contains(x)) a.add(x);
            }
            return String.join(" / ", a);
        }

        /** First plural, or "" (uncountable nouns). */
        String plural() {
            for (String p : plurals) if (!p.isEmpty()) return p;
            return "";
        }
    }

    static String article(String gender) {
        switch (gender) {
            case "m": return "der";
            case "f": return "die";
            default: return "das";
        }
    }

    private final String[] lemmas;      // sorted
    private final String[] data;        // "m:Plural;f:Plural" per lemma
    private final String[] plurals;     // sorted plural forms
    private final int[] pluralOf;       // index of the lemma for each plural

    Nouns(List<String> lines) {
        List<String[]> rows = new ArrayList<>();
        for (String l : lines) {
            int tab = l.indexOf('\t');
            if (tab > 0) rows.add(new String[]{l.substring(0, tab), l.substring(tab + 1)});
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        lemmas = new String[rows.size()];
        data = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            lemmas[i] = rows.get(i)[0];
            data[i] = rows.get(i)[1];
        }
        List<long[]> pl = new ArrayList<>();
        List<String> plWords = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            for (String part : data[i].split(";")) {
                int c = part.indexOf(':');
                if (c < 0) continue;
                String p = part.substring(c + 1);
                if (!p.isEmpty() && !p.equals(lemmas[i])) {
                    plWords.add(p);
                    pl.add(new long[]{plWords.size() - 1, i});
                }
            }
        }
        Integer[] order = new Integer[plWords.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> plWords.get(a).compareTo(plWords.get(b)));
        plurals = new String[order.length];
        pluralOf = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            plurals[i] = plWords.get(order[i]);
            pluralOf[i] = (int) pl.get(order[i])[1];
        }
    }

    static Nouns read(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) lines.add(line);
        }
        return new Nouns(lines);
    }

    int size() {
        return lemmas.length;
    }

    private Info info(int i, String shownAs) {
        List<String> g = new ArrayList<>(), p = new ArrayList<>();
        for (String part : data[i].split(";")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            g.add(part.substring(0, c));
            p.add(part.substring(c + 1));
        }
        return g.isEmpty() ? null : new Info(shownAs, g, p);
    }

    private Info exact(String noun) {
        int i = Arrays.binarySearch(lemmas, noun);
        if (i >= 0) return info(i, lemmas[i]);
        int j = Arrays.binarySearch(plurals, noun);
        if (j >= 0) return info(pluralOf[j], lemmas[pluralOf[j]]);
        return null;
    }

    /**
     * Article and plural for a German noun (any capitalisation; singular or plural form; compounds).
     * Null when it is not a known noun.
     */
    Info lookup(String word) {
        String w = Text.clean(word);
        if (w.length() < 2 || w.contains(" ") || !w.matches("[A-Za-zÄÖÜäöüß-]+")) return null;
        String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
        Info i = exact(cap);
        if (i != null) return i;
        // compound: the longest known last part decides (Haustür → Tür); both parts at least 3 letters
        for (int k = 3; k <= cap.length() - 3; k++) {
            String tail = Character.toUpperCase(cap.charAt(k)) + cap.substring(k + 1);
            String head = cap.substring(0, k);
            int li = Arrays.binarySearch(lemmas, tail);
            if (li >= 0) {
                Info t = info(li, lemmas[li]);
                if (t == null) return null;
                List<String> pl = new ArrayList<>();
                for (String p : t.plurals) pl.add(p.isEmpty() ? "" : head + p.toLowerCase(Locale.GERMAN));
                return new Info(cap, t.genders, pl);
            }
            int pi = Arrays.binarySearch(plurals, tail);
            if (pi >= 0) {
                Info t = info(pluralOf[pi], lemmas[pluralOf[pi]]);
                if (t == null) return null;
                List<String> pl = new ArrayList<>();
                for (String p : t.plurals) pl.add(p.isEmpty() ? "" : head + p.toLowerCase(Locale.GERMAN));
                return new Info(head + lemmas[pluralOf[pi]].toLowerCase(Locale.GERMAN), t.genders, pl);
            }
        }
        return null;
    }
}
