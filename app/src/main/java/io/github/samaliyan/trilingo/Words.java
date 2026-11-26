package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A language's common words, most frequent first (built-in list, see tools/build_data.py).
 * Used for: suggestions while typing, "is this a real word?", the closest real word for a typo,
 * and German words typed without ä/ö/ü/ß (mude → müde, strasse → Straße). Pure Java.
 */
final class Words {
    final Lang lang;
    /** Words in frequency order; the index is the rank (0 = most frequent). */
    private final String[] words;
    /** Lower-case word → rank. */
    private final Map<String, Integer> rank;
    /** Indexes into {@link #words}, sorted by {@link #fold} key, for prefix search. */
    private final Integer[] byKey;
    private final String[] keys;

    Words(Lang lang, List<String> list) {
        this.lang = lang;
        this.words = list.toArray(new String[0]);
        rank = new HashMap<>(words.length * 2);
        for (int i = 0; i < words.length; i++) rank.putIfAbsent(words[i].toLowerCase(Locale.ROOT), i);
        keys = new String[words.length];
        for (int i = 0; i < words.length; i++) keys[i] = fold(lang, words[i]);
        byKey = new Integer[words.length];
        for (int i = 0; i < words.length; i++) byKey[i] = i;
        Arrays.sort(byKey, (a, b) -> {
            int c = keys[a].compareTo(keys[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
    }

    static Words read(Lang lang, InputStream in) throws IOException {
        List<String> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
        }
        return new Words(lang, list);
    }

    int size() {
        return words.length;
    }

    /**
     * Comparison key: lower case; German ä/ö/ü → a/o/u and ß → ss (so "mude" finds "müde");
     * Persian with Arabic letter forms unified and without half-spaces.
     */
    static String fold(Lang lang, String w) {
        String s = w.toLowerCase(Locale.ROOT);
        if (lang == Lang.DE) s = s.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
        if (lang == Lang.FA) s = Text.unifyPersian(s).replace(String.valueOf((char) 0x200C), "");
        return s;
    }

    /** Rank of the word (any case), or -1 when it is not in the list. */
    int rank(String w) {
        Integer r = rank.get(w.toLowerCase(Locale.ROOT));
        if (r == null && lang == Lang.FA) r = rank.get(Text.unifyPersian(w));
        return r == null ? -1 : r;
    }

    boolean known(String w) {
        return rank(w) >= 0;
    }

    String word(int rank) {
        return words[rank];
    }

    /** The word as the list writes it ("tisch" → "Tisch"); unchanged when it is not in the list. */
    String canonical(String w) {
        int r = rank(w);
        return r >= 0 ? words[r] : w;
    }

    /** Words starting with what was typed (case, umlauts and half-spaces ignored), most frequent first. */
    List<String> complete(String typed, int max) {
        List<String> out = new ArrayList<>();
        String p = fold(lang, Text.clean(typed));
        if (p.isEmpty()) return out;
        int lo = lowerBound(p);
        // keep the max most frequent of the matching range
        int[] best = new int[max];
        int n = 0;
        for (int i = lo; i < byKey.length && keys[byKey[i]].startsWith(p); i++) {
            int r = byKey[i];
            if (n < max) {
                best[n++] = r;
                Arrays.sort(best, 0, n);
            } else if (r < best[max - 1]) {
                best[max - 1] = r;
                Arrays.sort(best, 0, n);
            }
        }
        for (int i = 0; i < n; i++) out.add(words[best[i]]);
        return out;
    }

    /** Most frequent word whose fold key is exactly this one ("mude" → "müde"), or null. */
    String sameKey(String key) {
        int i = lowerBound(key);
        if (i < byKey.length && keys[byKey[i]].equals(key)) return words[byKey[i]];
        return null;
    }

    private int lowerBound(String key) {
        int lo = 0, hi = byKey.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[byKey[mid]].compareTo(key);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid;
            else {
                // first of equal keys: they are ordered by rank, step back to the first
                while (mid > 0 && keys[byKey[mid - 1]].equals(key)) mid--;
                return mid;
            }
        }
        return lo;
    }

    // ------------------------------------------------------------------ language

    /**
     * English or German for Latin text: a word only one of the two lists has decides ("Haus" → German,
     * which the translation service sometimes takes for English). Null when unsure (both have it,
     * e.g. "Gift", or neither), so the translation service decides.
     */
    static Lang guessLatin(Words en, Words de, String text) {
        if (en == null || de == null || text == null || Text.hasPersian(text)) return null;
        int onlyEn = 0, onlyDe = 0, words = 0;
        for (String t : Text.clean(text).split("[^A-Za-zÄÖÜäöüß]+")) {
            if (t.length() < 2) continue;
            words++;
            int re = en.rank(t), rd = de.rank(t);
            if (re >= 0 && rd < 0) onlyEn++;
            else if (rd >= 0 && re < 0) onlyDe++;
            else if (re >= 0) {                       // in both lists: much more common in one of them?
                if ((long) rd * 20 < re) onlyDe++;
                else if ((long) re * 20 < rd) onlyEn++;
            }
        }
        if (words == 0 || words > 12) return null;
        if (onlyDe > 0 && onlyEn == 0) return Lang.DE;
        if (onlyEn > 0 && onlyDe == 0) return Lang.EN;
        if (onlyDe >= 2 * onlyEn + 2) return Lang.DE;
        if (onlyEn >= 2 * onlyDe + 2) return Lang.EN;
        return null;
    }

    // ------------------------------------------------------------------ spelling

    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String GERMAN = LATIN + "äöüß";
    private static final String PERSIAN = "آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیئ";

    /**
     * The most frequent known word one typing mistake away (a letter missing, extra, wrong or two letters
     * swapped), for words of 4+ letters; null if the word is known or nothing is close.
     */
    String closest(String typed) {
        String w = Text.clean(typed).toLowerCase(Locale.ROOT);
        if (lang == Lang.FA) w = Text.unifyPersian(w);
        if (w.length() < 4 || w.contains(" ") || known(w)) return null;
        String alphabet = lang == Lang.FA ? PERSIAN : lang == Lang.DE ? GERMAN : LATIN;
        int bestRank = Integer.MAX_VALUE;
        for (String c : edits(w, alphabet)) {
            int r = rank(c);
            if (r >= 0 && r < bestRank) bestRank = r;
        }
        if (bestRank == Integer.MAX_VALUE) return null;
        String best = words[bestRank];
        // keep a capital the user typed (German nouns are capitalised in the list anyway)
        if (Character.isUpperCase(typed.trim().charAt(0)) && lang != Lang.FA)
            best = Character.toUpperCase(best.charAt(0)) + best.substring(1);
        return best;
    }

    static List<String> edits(String w, String alphabet) {
        List<String> out = new ArrayList<>();
        int n = w.length();
        for (int i = 0; i < n; i++) out.add(w.substring(0, i) + w.substring(i + 1));                       // delete
        for (int i = 0; i + 1 < n; i++) out.add(w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2)); // swap
        for (int i = 0; i < n; i++) for (int k = 0; k < alphabet.length(); k++) {
            char c = alphabet.charAt(k);
            if (c != w.charAt(i)) out.add(w.substring(0, i) + c + w.substring(i + 1));                     // replace
        }
        for (int i = 0; i <= n; i++) for (int k = 0; k < alphabet.length(); k++)
            out.add(w.substring(0, i) + alphabet.charAt(k) + w.substring(i));                              // insert
        return out;
    }
}
