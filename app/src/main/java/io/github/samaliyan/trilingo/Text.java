package io.github.samaliyan.trilingo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Text helpers: cleaning the input and guessing its language. Pure Java. */
final class Text {
    private Text() { }

    /** Longest text sent for translation (the free services refuse much longer texts). */
    static final int MAX_LEN = 450;

    /** Trimmed, inner spaces collapsed (line breaks kept as single spaces), cut to {@link #MAX_LEN}. */
    static String clean(String s) {
        if (s == null) return "";
        // Java's \s does not match the Persian half-space (ZWNJ), so it is kept.
        String t = s.replaceAll("[\\s\\u00A0]+", " ").trim();
        if (t.length() > MAX_LEN) {
            t = t.substring(0, MAX_LEN);
            // do not cut a surrogate pair in half
            if (Character.isHighSurrogate(t.charAt(t.length() - 1))) t = t.substring(0, t.length() - 1);
            t = t.trim();
        }
        return t;
    }

    /** Comparison key: cleaned, lower case, Arabic letter forms unified with Persian ones. */
    static String key(String s) {
        return unifyPersian(clean(s)).toLowerCase(Locale.ROOT);
    }

    /** Arabic ي/ك (often typed by Arabic keyboards) → Persian ی/ک. */
    static String unifyPersian(String s) {
        return s.replace((char) 0x064A, (char) 0x06CC).replace((char) 0x0649, (char) 0x06CC).replace((char) 0x0643, (char) 0x06A9);
    }

    static boolean hasPersian(String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return true;
        }
        return false;
    }

    /** True if the text has at least one letter (otherwise there is nothing to translate). */
    static boolean hasLetter(String s) {
        for (int k = 0; k < s.length(); k++) if (Character.isLetter(s.charAt(k))) return true;
        return false;
    }

    private static final Set<String> GERMAN_WORDS = new HashSet<>(Arrays.asList(
            "der", "die", "das", "den", "dem", "des", "und", "ist", "nicht", "ich", "du", "wir", "ihr", "sie",
            "ein", "eine", "einen", "einem", "einer", "mit", "für", "auf", "zu", "bei", "von", "nach", "aus",
            "auch", "noch", "schon", "sehr", "aber", "oder", "wenn", "dass", "kein", "keine", "mein", "dein",
            "sind", "bin", "bist", "hat", "haben", "habe", "wird", "werden", "kann", "können", "muss", "müssen",
            "wie", "was", "wo", "wer", "warum", "heute", "morgen", "gut", "danke", "bitte", "ja", "nein",
            "guten", "tag", "ohne", "über", "unter", "zwischen", "gegen", "durch", "immer", "jetzt", "hier"));

    private static final Set<String> ENGLISH_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "is", "are", "of", "to", "you", "it", "that", "this", "with", "for", "on", "be",
            "have", "has", "not", "what", "where", "who", "why", "how", "my", "your", "we", "they", "he",
            "she", "will", "would", "can", "could", "should", "from", "at", "by", "or", "if", "there"));

    /**
     * Guess between English and German for Latin text, used only when the service cannot tell
     * (or for the backup service, which needs the language). Ties go to English.
     */
    static Lang guessLatin(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        int de = 0, en = 0;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == 'ä' || c == 'ö' || c == 'ü' || c == 'ß') de += 2;
        }
        for (String w : t.split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            if (GERMAN_WORDS.contains(w)) de++;
            if (ENGLISH_WORDS.contains(w)) en++;
            if (w.length() > 5 && (w.endsWith("ung") || w.endsWith("keit") || w.endsWith("heit") || w.endsWith("schaft")
                    || w.endsWith("lich") || w.endsWith("chen"))) de++;
            if (w.contains("sch") || (w.startsWith("ge") && w.endsWith("t") && w.length() > 5)) de++;
            if (w.endsWith("ing") || w.endsWith("tion") || w.endsWith("ly") || w.contains("th")) en++;
        }
        return de > en ? Lang.DE : Lang.EN;
    }

    /** Decodes the few HTML entities some services put in their answers. */
    static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int k = 0;
        while (k < s.length()) {
            char c = s.charAt(k);
            int semi = c == '&' ? s.indexOf(';', k) : -1;
            if (semi > k && semi - k <= 8) {
                String e = s.substring(k + 1, semi);
                String r = null;
                switch (e) {
                    case "amp": r = "&"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "nbsp": r = " "; break;
                    default:
                        try {
                            if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                        } catch (IllegalArgumentException ignored) {
                            r = null;
                        }
                }
                if (r != null) {
                    b.append(r);
                    k = semi + 1;
                    continue;
                }
            }
            b.append(c);
            k++;
        }
        return b.toString();
    }
}
