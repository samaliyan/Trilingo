package io.github.samaliyan.trilingo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * "Listen and write": checks what was typed against the spoken word or sentence. Punctuation does not
 * count; letter case does in German (nouns start with a capital), ä/ö/ü/ß written as ae/oe/ue/ss is
 * "almost". Also marks which letters of the answer were missed. Pure Java.
 */
final class Dictation {
    enum Grade { RIGHT, ALMOST, WRONG }

    static final class Result {
        final Grade grade;
        /** Why it is only almost right; "" otherwise. */
        final String note;
        /** For each letter of the expected text: true when the typed text missed or changed it. */
        final boolean[] missed;

        Result(Grade grade, String note, boolean[] missed) {
            this.grade = grade;
            this.note = note;
            this.missed = missed;
        }
    }

    static final String NOTE_CAPITAL = "Mind the capital letters";
    static final String NOTE_UMLAUT = "Write ä, ö, ü and ß";
    static final String NOTE_TYPO = "One letter is wrong";

    private Dictation() { }

    static Result check(String expected, String typed, Lang lang) {
        String e = plain(expected), t = plain(typed);
        boolean[] missed = missed(expected, typed);
        if (t.isEmpty()) return new Result(Grade.WRONG, "", missed);
        if (e.equals(t)) return new Result(Grade.RIGHT, "", new boolean[expected.length()]);
        if (e.equalsIgnoreCase(t)) {
            // English: only the first letter's case differs → right; German: capitals carry meaning
            if (lang == Lang.EN && e.substring(1).equals(t.substring(1))) return new Result(Grade.RIGHT, "", new boolean[expected.length()]);
            return new Result(Grade.ALMOST, NOTE_CAPITAL, missed);
        }
        if (lang == Lang.DE && umlautsWritten(e).equalsIgnoreCase(umlautsWritten(t))) return new Result(Grade.ALMOST, NOTE_UMLAUT, missed);
        // one small typo in a longer word or sentence
        if (e.length() >= 6 && distance(e.toLowerCase(Locale.ROOT), t.toLowerCase(Locale.ROOT)) == 1)
            return new Result(Grade.ALMOST, NOTE_TYPO, missed);
        return new Result(Grade.WRONG, "", missed);
    }

    /** Without punctuation and extra spaces, apostrophes unified. */
    static String plain(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC).replace('’', '\'').replace('‘', '\'');
        x = x.replaceAll("[\\p{Punct}&&[^'-]]|[„“”«»…–—]", " ");
        return x.replaceAll("\\s+", " ").trim();
    }

    private static String umlautsWritten(String s) {
        return s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae").replace("Ö", "Oe")
                .replace("Ü", "Ue").replace("ß", "ss");
    }

    /** Edit distance (insert, delete, change one letter). */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] x = prev;
            prev = cur;
            cur = x;
        }
        return prev[b.length()];
    }

    /**
     * Letters of {@code expected} that {@code typed} does not have in the same place (by the longest common
     * subsequence, ignoring case). Punctuation and spaces are never marked.
     */
    static boolean[] missed(String expected, String typed) {
        String a = expected == null ? "" : expected, b = typed == null ? "" : typed;
        String la = a.toLowerCase(Locale.ROOT), lb = b.toLowerCase(Locale.ROOT);
        int n = la.length(), m = lb.length();
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                l[i][j] = la.charAt(i) == lb.charAt(j) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
        boolean[] out = new boolean[n];
        int i = 0, j = 0;
        while (i < n) {
            if (j < m && la.charAt(i) == lb.charAt(j)) {
                i++;
                j++;
            } else if (j < m && l[i][j + 1] >= l[i + 1][j]) {
                j++;
            } else {
                out[i] = Character.isLetterOrDigit(a.charAt(i));
                i++;
            }
        }
        return out;
    }

    /** {@code n} items from the pool, not ones shown recently when there are enough others. */
    static List<String> pick(List<String> pool, int n, Random random, Set<String> recent) {
        List<String> fresh = new ArrayList<>(), old = new ArrayList<>();
        for (String p : pool) (recent != null && recent.contains(p) ? old : fresh).add(p);
        Collections.shuffle(fresh, random);
        Collections.shuffle(old, random);
        List<String> out = new ArrayList<>();
        for (String s : fresh) if (out.size() < n && !out.contains(s)) out.add(s);
        for (String s : old) if (out.size() < n && !out.contains(s)) out.add(s);
        return out;
    }
}
