package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * German compound words: Marketingassistentin = Marketing + Assistentin, Bundesregierung = Bund(es) + Regierung.
 * Finds the parts (also when one part has a typo: "marketingassitentin" → Marketing + Assistentin), the
 * female form (-in), and a translation built from the parts' translations. Pure Java.
 */
final class Compounds {
    static final String SERVICE = "Word parts";
    /** Linking letters between parts: Arbeit-s-platz, Bund-es-regierung, Blume-n-topf, Kind-er-garten. */
    private static final String[] LINKS = {"es", "s", "en", "n", "er", "e"};
    /** Endings that look like words but make one word, not a compound (Freund-schaft, Gesund-heit). */
    private static final java.util.Set<String> SUFFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
            "schaft", "heit", "keit", "ung", "nis", "tum", "ling", "chen", "lein", "ion", "tät", "ismus", "ist", "ler", "erin",
            "isch", "lich", "bar", "sam", "haft", "los", "ig", "end", "ern", "eln", "ieren"));

    /** One part: as it stands in the word ("Bundes") and its dictionary form ("Bund"). */
    static final class Part {
        final String shown, base;

        Part(String shown, String base) {
            this.shown = shown;
            this.base = base;
        }
    }

    static final class Split {
        final List<Part> parts;
        /** The whole word, written correctly, with its capital ("Marketingassistentin"). */
        final String word;
        /** True when a typo in a part was corrected. */
        final boolean corrected;
        /** For a female form (-in): the male form ("Marketingassistent"), else "". */
        final String male;

        Split(List<Part> parts, String word, boolean corrected, String male) {
            this.parts = parts;
            this.word = word;
            this.corrected = corrected;
            this.male = male;
        }
    }

    private final Words de;
    private final Nouns nouns;
    private final Dict dict;

    Compounds(Words de, Nouns nouns, Dict dict) {
        this.de = de;
        this.nouns = nouns;
        this.dict = dict;
    }

    /**
     * The parts of a German compound the app does not know as a whole, or null (not a compound, unknown
     * parts, or a known word — those have their own translation).
     */
    Split split(String typed) {
        return split(typed, false);
    }

    /** {@code known}: also split a word the app knows as a whole (for its meaning and picture: Lieblingsessen). */
    Split split(String typed, boolean known) {
        String w = Text.clean(typed);
        if (w.length() < 7 || w.contains(" ") || !w.matches("[A-Za-zÄÖÜäöüß]+") || de == null) return null;
        String low = w.toLowerCase(Locale.GERMAN);
        if (!known && isKnownWhole(low)) return null;
        List<Part> best = parts(low, 3);
        boolean corrected = false;
        if (best == null && !isKnownWhole(low)) {
            best = fuzzy(low);
            corrected = best != null;
        }
        if (best == null || best.size() < 2) return null;
        StringBuilder word = new StringBuilder();
        for (Part p : best) word.append(p.shown.toLowerCase(Locale.GERMAN));
        Part last = best.get(best.size() - 1);
        // typed with a capital: a noun compound (Lieblingsessen: …Essen, not essen)
        if (Character.isUpperCase(w.charAt(0)) && Character.isLowerCase(last.base.charAt(0)) && nouns != null
                && nouns.exact(capital(last.base)) != null) {
            last = new Part(capital(last.shown), capital(last.base));
            best.set(best.size() - 1, last);
        }
        // a noun compound is written with a capital (Marketingassistentin); others not (hellblau)
        boolean noun = Character.isUpperCase(last.base.charAt(0));
        String whole = noun ? capital(word.toString()) : word.toString();
        return new Split(best, whole, corrected, noun ? male(whole, last.base) : "");
    }

    /** The word itself is known (Kühlschrank, Freundschaft): it needs no splitting. */
    boolean isKnownWhole(String low) {
        int r = de.rank(low);
        return (r >= 0 && r < 40000) || (dict != null && dict.has(low)) || (nouns != null && nouns.exact(capital(low)) != null);
    }

    /**
     * The best split of a lower-case word into up to {@code max} known parts. Of all splits the one whose
     * parts are dictionary words wins; then the longer last part (the part that decides the meaning).
     */
    private List<Part> parts(String low, int max) {
        List<Part> best = null;
        int bestScore = -1;
        for (int i = 3; i <= low.length() - 3; i++) {
            String tail = low.substring(i);
            if (!isNounOrWord(tail) || SUFFIXES.contains(tail)) continue;
            List<Part> front = front(low.substring(0, i), max - 1);
            if (front == null) continue;
            front.add(new Part(cased(tail), cased(tail)));
            int score = 0;
            for (Part p : front) score += strength(p.base);
            // the average strength: more parts must not win just by adding up (Assistent, not Assi + Stent)
            score = score * 100 / front.size() - front.size() * 50 + tail.length();
            if (score > bestScore) {
                bestScore = score;
                best = front;
            }
        }
        return best;
    }

    /** 3: in the built-in dictionary; 2: a known noun; 1: just a known word form. */
    private int strength(String part) {
        String low = part.toLowerCase(Locale.GERMAN);
        if (dict != null && dict.has(low)) return 3;
        if (nouns != null && nouns.exact(capital(low)) != null) return 2;
        return 1;
    }

    /** The front of a compound: one known word (maybe with a linking s/es/n…), or itself a compound. */
    private List<Part> front(String head, int max) {
        if (max < 1 || head.length() < 3) return null;
        // the word itself (Haus) or without a linking letter (Bundes → Bund, Lieblings → Liebling): the stronger
        Part best = isWord(head) ? new Part(cased(head), cased(head)) : null;
        int bestStrength = best == null ? 0 : strength(head);
        for (String link : LINKS) {
            if (head.endsWith(link) && head.length() - link.length() >= 3) {
                String base = head.substring(0, head.length() - link.length());
                if (isWord(base) && strength(base) > bestStrength) {
                    best = new Part(cased(head), cased(base));
                    bestStrength = strength(base);
                }
            }
        }
        if (best != null) return list(best);
        if (max >= 2) return parts(head, max);
        return null;
    }

    /** One typing mistake in one part: the other part known exactly, the misspelled one one letter away. */
    private List<Part> fuzzy(String low) {
        // one typo in a whole known word (Schmeterling → Schmetterling) is a spelling mistake, not a compound
        if (de.closest(low) != null) return null;
        for (int i = 3; i <= low.length() - 4; i++) {
            String head = low.substring(0, i), tail = low.substring(i);
            List<Part> front = front(head, 2);
            if (front != null && tail.length() >= 4) {
                String fixed = de.closest(tail);
                if (fixed != null && isNounOrWord(fixed.toLowerCase(Locale.GERMAN))) {
                    front.add(new Part(cased(fixed), cased(fixed)));
                    return front;
                }
            }
            if (isNounOrWord(tail) && head.length() >= 4) {
                String fixed = de.closest(head);
                if (fixed != null && isWord(fixed.toLowerCase(Locale.GERMAN))) {
                    List<Part> out = list(new Part(cased(fixed), cased(fixed)));
                    out.add(new Part(cased(tail), cased(tail)));
                    return out;
                }
            }
        }
        return null;
    }

    /** "Marketingassistentin" → "Marketingassistent" when the last part is a female form of a known noun. */
    private String male(String word, String last) {
        if (nouns == null || !last.endsWith("in") || last.length() < 6) return "";
        String m = last.substring(0, last.length() - 2);
        for (String c : new String[]{m, m.replace("ä", "a").replace("ö", "o").replace("ü", "u")}) {
            Nouns.Info i = nouns.lookup(capital(c));
            if (i != null && i.genders.contains("m")) return word.substring(0, word.length() - last.length()) + c.toLowerCase(Locale.GERMAN);
        }
        return "";
    }

    /** A known word of at least 3 letters (word list or built-in dictionary). */
    private boolean isWord(String low) {
        if (low.length() < 3) return false;
        int r = de.rank(low);
        return (r >= 0 && r < 40000) || (dict != null && dict.has(low));
    }

    private boolean isNounOrWord(String low) {
        return isWord(low) || (nouns != null && low.length() >= 3 && nouns.exact(capital(low)) != null);
    }

    /** Written as the word list has it (nouns with a capital, "blau" small). */
    private String cased(String low) {
        Dict.Entry e = dict == null ? null : dict.find(low);
        if (e != null) return e.word;
        if (nouns != null && nouns.exact(capital(low)) != null) return capital(low);
        return de.known(low) ? de.canonical(low) : low;
    }

    private static List<Part> list(Part p) {
        List<Part> l = new ArrayList<>();
        l.add(p);
        return l;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ translation from the parts

    private Dict english;

    /** The English dictionary, for parts that are English words (Marketing, Handy is not). */
    void setEnglish(Dict en) {
        english = en;
    }

    /**
     * {English, Persian} of one part: from the German dictionary; a female form (-in) through the male one
     * (Assistentin → Assistent); an English loanword (Marketing) from the English dictionary. Null if none.
     */
    String[] translate(String part) {
        Lookup l = dict.lookup(part, 0);
        if (l == null && part.endsWith("in") && part.length() > 5) l = dict.lookup(part.substring(0, part.length() - 2), 0);
        if (l != null) return new String[]{l.result(Lang.EN).text, l.result(Lang.FA).text};
        Lookup e = english == null ? null : english.lookup(part.toLowerCase(Locale.ROOT), 0);
        if (e != null) return new String[]{part.toLowerCase(Locale.ROOT), e.result(Lang.FA).text};
        return null;
    }

    /**
     * A translation built from the parts ("marketing assistant", "دستیار بازاریابی"): English keeps the German
     * order, Persian puts the main (last) part first. Null when a part has no translation.
     */
    Lookup lookup(Split s, long now) {
        if (dict == null) return null;
        List<String> en = new ArrayList<>(), fa = new ArrayList<>();
        for (Part p : s.parts) {
            String[] t = translate(p.base);
            if (t == null) return null;
            en.add(t[0]);
            fa.add(t[1]);
        }
        java.util.Collections.reverse(fa);
        String e = String.join(" ", en).toLowerCase(Locale.ROOT), f = String.join(" ", fa);
        List<Lookup.Result> rs = new ArrayList<>();
        List<Lookup.Sense> fs = new ArrayList<>(), es = new ArrayList<>();
        fs.add(new Lookup.Sense("noun", java.util.Collections.singletonList(f)));
        es.add(new Lookup.Sense("noun", java.util.Collections.singletonList(e)));
        for (Lang t : Lang.DE.targets()) rs.add(new Lookup.Result(t, t == Lang.FA ? f : e, t == Lang.FA ? fs : es));
        return new Lookup(s.word, Lang.DE, rs, SERVICE, now);
    }
}
