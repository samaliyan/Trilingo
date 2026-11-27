package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Did you mean": the known words closest to a misspelt one, best first.
 *
 * <p>Up to two or three mistakes for longer words. A mistake costs less when it is a typical one:
 * a neighbouring key (QWERTY, QWERTZ, the Persian layout), a missing or doubled letter (Schmeterling),
 * two letters swapped (Fruend), a letter that sounds the same (ph/f, ä/e, ß/ss; in Persian س/ص/ث,
 * ز/ذ/ض/ظ, ت/ط, ه/ح, ق/غ, ا/ع) or a left-out vowel. Among equally close words the more common wins.
 */
final class Spelling {
    private Spelling() {
    }

    static final class Candidate {
        final String word;
        final double cost;

        Candidate(String word, double cost) {
            this.word = word;
            this.cost = cost;
        }

        @Override public String toString() {
            return word + "(" + String.format(Locale.ROOT, "%.2f", cost) + ")";
        }
    }

    /** The best suggestion for one word, or null (known, too short, or nothing close). */
    static String best(Words w, String typed) {
        List<String> s = suggest(w, typed, 1);
        return s.isEmpty() ? null : s.get(0);
    }

    /** Up to {@code max} known words close to the typed one, best first; empty when the word is known. */
    static List<String> suggest(Words w, String typed, int max) {
        List<String> out = new ArrayList<>();
        for (Candidate c : candidates(w, typed, max)) out.add(c.word);
        return out;
    }

    static List<Candidate> candidates(Words w, String typed, int max) {
        return candidates(w, typed, max, 3);
    }

    /** As above; in a sentence two-letter words are corrected too (how ar you). */
    private static List<Candidate> candidates(Words w, String typed, int max, int minLength) {
        List<Candidate> out = new ArrayList<>();
        if (w == null) return out;
        String t = Text.clean(typed);
        if (t.isEmpty() || t.contains(" ")) return out;
        String low = norm(w.lang, t);
        if (low.length() < minLength || w.known(t) || !hasLetters(low)) return out;
        double limit = limit(low.length());
        String key = sound(w.lang, low);
        List<Candidate> all = new ArrayList<>();
        int n = w.size();
        for (int r = 0; r < n; r++) {
            String word = w.word(r);
            String c = norm(w.lang, word);
            if (Math.abs(c.length() - low.length()) > 3 || c.length() < 2) continue;
            // a cheap first test: the first or the second letter usually is right
            if (c.charAt(0) != low.charAt(0) && (c.length() < 2 || low.length() < 2
                    || c.charAt(1) != low.charAt(1) && c.charAt(0) != low.charAt(1) && c.charAt(1) != low.charAt(0))
                    && !similar(w.lang, c.charAt(0), low.charAt(0))) continue;
            double d = distance(w.lang, low, c, limit);
            if (d > 0.6) {
                double ds = distance(w.lang, key, sound(w.lang, c), limit) + 0.35;   // sounds the same
                d = Math.min(d, ds);
            }
            if (d > limit) continue;
            // a common word is a likelier meaning: up to 0.5 for the rarest
            double cost = d + 0.1 * Math.log10(r + 10);
            all.add(new Candidate(cased(word, t, w.lang), cost));
        }
        Collections.sort(all, (a, b) -> Double.compare(a.cost, b.cost));
        List<String> seen = new ArrayList<>();
        for (Candidate c : all) {
            String k = c.word.toLowerCase(Locale.ROOT);
            if (seen.contains(k) || k.equals(low)) continue;
            seen.add(k);
            out.add(c);
            if (out.size() >= max) break;
        }
        return out;
    }

    /**
     * A sentence with each unknown word replaced by its best suggestion ("how ar you todya" →
     * "how are you today"), or null when nothing changed.
     */
    static String phrase(Words w, String typed) {
        if (w == null) return null;
        String t = Text.clean(typed);
        if (!t.contains(" ")) return best(w, t);
        String[] parts = t.split(" ");
        if (parts.length > 12) return null;
        boolean changed = false;
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            String core = p.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
            List<Candidate> c = core.length() >= 2 && !w.known(core) ? candidates(w, core, 1, 2) : null;
            String fix = c == null || c.isEmpty() ? null : c.get(0).word;
            if (b.length() > 0) b.append(' ');
            if (fix != null) {
                b.append(p.replace(core, fix));
                changed = true;
            } else b.append(p);
        }
        return changed ? b.toString() : null;
    }

    /** How many mistakes a word of this length may have. */
    static double limit(int len) {
        if (len <= 2) return 0.6;
        if (len <= 3) return 0.8;
        if (len <= 4) return 1.05;
        if (len <= 6) return 1.75;
        if (len <= 9) return 2.1;
        return 2.7;
    }

    private static boolean hasLetters(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    /** Lower case; Persian letter forms unified, without half-spaces. */
    static String norm(Lang l, String s) {
        String x = s.toLowerCase(Locale.ROOT);
        if (l == Lang.FA) x = Text.unifyPersian(x).replace(String.valueOf((char) 0x200C), "").replace('آ', 'ا');
        return x;
    }

    /** The word written as it sounds, so that sound-alike spellings meet (fysik/physik, sähr/sehr). */
    static String sound(Lang l, String s) {
        String x = s;
        switch (l) {
            case FA:
                x = x.replaceAll("[صث]", "س").replaceAll("[ذضظ]", "ز").replace('ط', 'ت').replace('ح', 'ه')
                        .replace('غ', 'ق').replace('ع', 'ا').replace("ئ", "ی").replace("ؤ", "و").replace("أ", "ا")
                        // spoken Persian: خونه / خانه, مهربون / مهربان
                        .replace("ون", "ان");
                break;
            case DE:
                // typed without umlauts: schoen, Kuehlschrank
                x = x.replace("ae", "ä").replace("oe", "ö").replace("ue", "ü");
                x = x.replace("ß", "ss").replace("ph", "f").replace("th", "t").replace("dt", "t").replace("ck", "k")
                        .replace("tz", "z").replace("äu", "eu").replace('ä', 'e').replace('v', 'f').replace('y', 'i')
                        .replace("ie", "i").replace("ah", "a").replace("eh", "e").replace("ih", "i").replace("oh", "o")
                        .replace("uh", "u").replace("qu", "kw");
                break;
            default:
                x = x.replace("ce", "se").replace("ci", "si").replace("ph", "f").replace("ck", "k").replace("gh", "g").replace("wr", "r").replace("kn", "n")
                        .replace('y', 'i').replace("ee", "i").replace("ea", "i").replace("ou", "u").replace("oo", "u")
                        .replace('c', 'k').replace('z', 's');
        }
        // doubled letters sound like one
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length(); i++) if (i == 0 || x.charAt(i) != x.charAt(i - 1)) b.append(x.charAt(i));
        return b.toString();
    }

    /** The suggestion with the case the user typed (a capital first letter stays). */
    private static String cased(String word, String typed, Lang l) {
        if (l == Lang.FA || word.isEmpty()) return word;
        if (Character.isUpperCase(typed.charAt(0)) && Character.isLowerCase(word.charAt(0)))
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return word;
    }

    /**
     * Weighted edit distance (Damerau, adjacent swaps), stopping early once every path costs more than
     * {@code limit}.
     */
    static double distance(Lang l, String a, String b, double limit) {
        int n = a.length(), m = b.length();
        double[][] d = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i == 0 ? 0 : d[i - 1][0] + indel(l, a, i - 1);
        for (int j = 1; j <= m; j++) d[0][j] = d[0][j - 1] + indel(l, b, j - 1);
        for (int i = 1; i <= n; i++) {
            double rowMin = Double.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                char x = a.charAt(i - 1), y = b.charAt(j - 1);
                double v = Math.min(d[i - 1][j] + indel(l, a, i - 1), d[i][j - 1] + indel(l, b, j - 1));
                v = Math.min(v, d[i - 1][j - 1] + sub(l, x, y));
                if (i > 1 && j > 1 && x == b.charAt(j - 2) && a.charAt(i - 2) == y && x != y)
                    v = Math.min(v, d[i - 2][j - 2] + 0.5);
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > limit) return limit + 1;
        }
        return d[n][m];
    }

    /** A letter missing or extra: cheap when it doubles its neighbour or is a vowel left out. */
    private static double indel(Lang l, String s, int i) {
        char c = s.charAt(i);
        if (i > 0 && s.charAt(i - 1) == c || i + 1 < s.length() && s.charAt(i + 1) == c) return 0.4;
        if (l == Lang.FA) return "اوی".indexOf(c) >= 0 ? 0.5 : 1;
        if (l == Lang.DE && c == 'h' && i > 0 && "aeiouäöü".indexOf(s.charAt(i - 1)) >= 0) return 0.5;
        // English: a silent h (which, whether) or a silent e at the end (hous → house)
        if (l == Lang.EN && c == 'h' && i > 0 && "cstwgp".indexOf(s.charAt(i - 1)) >= 0) return 0.5;
        if (l == Lang.EN && c == 'e' && i == s.length() - 1) return 0.5;
        return "aeiou".indexOf(c) >= 0 ? 0.8 : 1;
    }

    private static double sub(Lang l, char x, char y) {
        if (x == y) return 0;
        if (similar(l, x, y)) return 0.3;
        if (neighbours(l, x, y)) return 0.6;
        if (l != Lang.FA && "aeiouäöüy".indexOf(x) >= 0 && "aeiouäöüy".indexOf(y) >= 0) return 0.7;
        return 1;
    }

    private static final String[] SAME_FA = {"سصث", "زذضظ", "تط", "هح", "قغ", "اعأآ", "یئ", "وؤ", "جچحخ", "کگ", "بپ", "رز"};
    private static final String[] SAME_DE = {"aä", "oö", "uü", "sß", "fv", "iy", "eä", "kc", "zc"};
    private static final String[] SAME_EN = {"kc", "sz", "iy", "fv"};

    /** Letters people mix up because they sound (or look) the same. */
    static boolean similar(Lang l, char x, char y) {
        for (String g : l == Lang.FA ? SAME_FA : l == Lang.DE ? SAME_DE : SAME_EN)
            if (g.indexOf(x) >= 0 && g.indexOf(y) >= 0) return true;
        return false;
    }

    private static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] QWERTZ = {"qwertzuiopü", "asdfghjklöä", "yxcvbnm"};
    private static final String[] PERSIAN_KEYS = {"ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "ظطزرذدپو"};

    /** Keys next to each other on the keyboard the language is usually typed on. */
    static boolean neighbours(Lang l, char x, char y) {
        String[] rows = l == Lang.FA ? PERSIAN_KEYS : l == Lang.DE ? QWERTZ : QWERTY;
        int[] p = pos(rows, x), q = pos(rows, y);
        if (p == null || q == null) return false;
        int dr = Math.abs(p[0] - q[0]);
        // the rows are shifted by about half a key
        int dc = q[1] - p[1] + (q[0] > p[0] ? 0 : 0);
        return dr == 0 && Math.abs(dc) == 1 || dr == 1 && (q[0] > p[0] ? dc == 0 || dc == -1 : dc == 0 || dc == 1);
    }

    private static int[] pos(String[] rows, char c) {
        for (int r = 0; r < rows.length; r++) {
            int i = rows[r].indexOf(c);
            if (i >= 0) return new int[]{r, i};
        }
        return null;
    }
}
