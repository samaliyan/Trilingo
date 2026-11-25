package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Words from a text": the words of an English or German text that are probably new for a learner of a
 * given level, in their dictionary form (ging → gehen, Häuser → Haus, went → go), harder words first.
 * Words of the learner's level or below, names, numbers and saved words are left out. Pure Java.
 */
final class TextWords {
    static final class Item {
        final String word;
        /** "B2", or null when the word lists do not give a level (a rare word). */
        final String level;
        final int rank;
        int count = 1;

        Item(String word, String level, int rank) {
            this.word = word;
            this.level = level;
            this.rank = rank;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-zÄÖÜäöüß]+(?:[-'’][A-Za-zÄÖÜäöüß]+)*|[.!?:;\\n]");
    /** For a learner of each level: a word this common (rank in the frequency list) is probably known. */
    private static final int[] KNOWN_RANK = {800, 2000, 4000, 7000, 11000, 16000};

    private final Lang lang;
    private final Levels levels;
    private final Words words;
    private final Nouns nouns;
    /** The built-in dictionary: its headwords are dictionary forms ("leider" stays, "schönen" becomes "schön"). */
    private final Dict dict;

    TextWords(Lang lang, Levels levels, Words words, Nouns nouns, Dict dict) {
        this.lang = lang;
        this.levels = levels;
        this.words = words;
        this.nouns = nouns;
        this.dict = dict;
    }

    /** English or German: the language more of the text's words belong to only; null for Persian or no words. */
    static Lang detect(String text, Words en, Words de) {
        if (text == null || en == null || de == null) return null;
        int e = 0, d = 0, persian = 0, latin = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x0600 && c <= 0x06FF) persian++;
            else if (Character.isLetter(c)) latin++;
            if (c == 'ä' || c == 'ö' || c == 'ü' || c == 'ß' || c == 'Ä' || c == 'Ö' || c == 'Ü') d += 2;
        }
        if (persian > latin) return null;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group();
            if (t.length() < 2) continue;
            boolean inE = en.known(t), inD = de.known(t);
            if (inE && !inD) e++;
            else if (inD && !inE) d++;
        }
        if (e == 0 && d == 0) return null;
        return d > e ? Lang.DE : Lang.EN;
    }

    /**
     * The probably new words, most useful first (by level, then common before rare).
     * @param myLevel the learner's level ("A2"): words of this level and below are left out
     * @param saved   true for words already in the notebook
     */
    List<Item> find(String text, String myLevel, Predicate<String> saved) {
        int my = Math.max(0, Arrays.asList(Grammar.LEVELS).indexOf(myLevel));
        Map<String, Item> found = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(text == null ? "" : text);
        boolean sentenceStart = true;
        while (m.find()) {
            String t = m.group();
            if (t.length() == 1 && ".!?:;\n".contains(t)) {
                sentenceStart = true;
                continue;
            }
            boolean first = sentenceStart;
            sentenceStart = false;
            String base = base(t, first);
            if (base == null) continue;
            String key = base.toLowerCase(Locale.ROOT);
            Item have = found.get(key);
            if (have != null) {
                have.count++;
                continue;
            }
            String level = levels == null ? null : levels.get(key);
            int rank = words == null ? -1 : words.rank(base);
            int li = level == null ? -1 : Arrays.asList(Grammar.LEVELS).indexOf(level);
            if (li >= 0 && li <= my) continue;                                       // known at this level
            if (li < 0 && (rank < 0 || rank < KNOWN_RANK[Math.min(my, KNOWN_RANK.length - 1)])) continue;
            if (saved != null && saved.test(base)) continue;
            found.put(key, new Item(base, level, rank < 0 ? Integer.MAX_VALUE : rank));
        }
        List<Item> out = new ArrayList<>(found.values());
        out.sort((a, b) -> {
            int la = a.level == null ? 9 : Arrays.asList(Grammar.LEVELS).indexOf(a.level);
            int lb = b.level == null ? 9 : Arrays.asList(Grammar.LEVELS).indexOf(b.level);
            if (la != lb) return Integer.compare(la, lb);
            return Integer.compare(a.rank, b.rank);
        });
        return out;
    }

    /** Dictionary form of a word of the text, or null to leave it out (name, abbreviation, unknown). */
    String base(String token, boolean sentenceStart) {
        String t = token.replace('’', '\'');
        if (t.length() < 2 || t.contains("'")) return null;
        boolean upper = Character.isUpperCase(t.charAt(0));
        if (t.length() > 1 && t.equals(t.toUpperCase(Locale.ROOT))) return null;         // EU, USA
        String low = t.toLowerCase(Locale.ROOT);
        if (lang == Lang.DE) return german(t, low, upper, sentenceStart);
        // English: a capital in the middle of a sentence is a name
        if (upper && !sentenceStart && !"I".equals(t)) return null;
        if (headword(low)) return low;
        String lemma = EnglishVerbs.lemma(low);
        for (String c : new String[]{lemma, lemma == null ? null : lemma + "e",
                low.endsWith("ies") ? low.substring(0, low.length() - 3) + "y" : null,
                low.endsWith("es") ? low.substring(0, low.length() - 2) : null,
                low.endsWith("s") ? low.substring(0, low.length() - 1) : null}) {
            if (c != null && !c.equals(low) && c.length() >= 2 && (headword(c) || leveled(c))) return c;
        }
        return has(low) ? low : null;
    }

    /** A dictionary form: a headword of the built-in dictionary. */
    private boolean headword(String w) {
        return dict != null && dict.find(w) != null && dict.find(w).word.equalsIgnoreCase(w);
    }

    private boolean leveled(String w) {
        return levels != null && levels.get(w) != null;
    }

    private String german(String t, String low, boolean upper, boolean sentenceStart) {
        // a noun (capital letter): its singular ("Häuser" → "Haus"); at the start of a sentence only when
        // the word is not also a common small word ("Gestern", "Das")
        boolean smallWord = words != null && words.known(low) && Character.isLowerCase(words.canonical(low).charAt(0));
        if (upper && !(sentenceStart && smallWord) && nouns != null) {
            Nouns.Info n = nouns.lookup(t);
            if (n == null && t.length() > 4 && (t.endsWith("n") || t.endsWith("s"))) n = nouns.lookup(t.substring(0, t.length() - 1));
            if (n == null && t.length() > 5 && t.endsWith("es")) n = nouns.lookup(t.substring(0, t.length() - 2));
            // a compound guessed from its last part ("Kowalski" → …Ski) must be a known word itself
            if (n != null && (headword(n.noun) || leveled(n.noun) || (words != null && words.known(n.noun)))) return n.noun;
            if (!sentenceStart) return null;                                         // a name
        }
        if (GermanVerbs.isKnownForm(low)) {                                        // ging → gehen
            String v = GermanVerbs.lemma(low);
            if (v != null && has(v)) return v;
        }
        if (headword(low)) return low;                                             // leider, gestern
        String v = GermanVerbs.lemma(low);                                         // spielte → spielen
        if (v != null && !v.equals(low) && (headword(v) || leveled(v) || (words != null && words.known(v)))) return v;
        for (String end : new String[]{"en", "em", "er", "es", "e"}) {          // adjective endings: schönen → schön
            String stem = low.length() - end.length() >= 3 && low.endsWith(end) ? low.substring(0, low.length() - end.length()) : null;
            if (stem != null && (headword(stem) || (dict == null && leveled(stem)))) return stem;
        }
        return has(low) ? low : null;
    }

    /** In the level list, or a known word of the language. */
    private boolean has(String w) {
        return (levels != null && levels.get(w) != null) || (words != null && words.known(w));
    }
}
