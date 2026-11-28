package io.github.samaliyan.trilingo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Numbers in English, German and Persian: "8" (also "۸" or "٨") ↔ eight / acht / هشت, with the ordinal
 * (eighth / achte / هشتم). Voice search in Persian writes digits, and a number has no translation of its
 * own, so the app turns it into the number word. Pure Java; 0 to 999,999,999.
 */
final class Numbers {
    static final long MAX = 999_999_999L;

    private Numbers() { }

    /** The value of text made only of digits (any script, with , . or spaces between groups), or -1. */
    static long parse(String text) {
        if (text == null) return -1;
        String s = text.trim();
        // "8." is the German way to write "8th": the number is the same
        if (s.length() > 1 && s.endsWith(".") && isDigit(s.charAt(s.length() - 2))) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.length() > 15) return -1;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
            else if (c >= '۰' && c <= '۹') d.append((char) ('0' + c - '۰'));   // Persian digits
            else if (c >= '٠' && c <= '٩') d.append((char) ('0' + c - '٠'));   // Arabic digits
            else if (c == ',' || c == '.' || c == ' ' || c == '٬' || c == ' ') {
                // group separator only: "1,000", "1.000", "1 000" (never a decimal point)
                if (d.length() == 0 || !groupFollows(s, i)) return -1;
            } else return -1;
        }
        if (d.length() == 0 || d.length() > 9) return -1;
        return Long.parseLong(d.toString());
    }

    /** After a separator come exactly three digits, then the end or another separator. */
    private static boolean groupFollows(String s, int i) {
        int n = 0, j = i + 1;
        while (j < s.length() && isDigit(s.charAt(j))) {
            n++;
            j++;
        }
        return n == 3 && (j == s.length() || !isDigit(s.charAt(j)));
    }

    private static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= '۰' && c <= '۹') || (c >= '٠' && c <= '٩');
    }

    /**
     * A decimal number in words ("3,5" / "3.5" → three point five / drei Komma fünf / سه ممیز پنج), or null.
     * Only a whole part and 1–3 digits after the point (a group of three after "." or "," is a thousands
     * separator only when the whole part is 1–3 digits and there are more groups: 1.000 stays one thousand).
     */
    static String decimalWords(String text, Lang l) {
        String s = text == null ? "" : text.trim();
        StringBuilder d = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '۰' && c <= '۹') d.append((char) ('0' + c - '۰'));
            else if (c >= '٠' && c <= '٩') d.append((char) ('0' + c - '٠'));
            else if (c == '٫') d.append('.');
            else d.append(c);
        }
        String t = d.toString();
        if (!t.matches("[0-9]{1,9}[.,][0-9]{1,3}") || parse(s) >= 0) return null;
        int i = Math.max(t.indexOf('.'), t.indexOf(','));
        long whole = Long.parseLong(t.substring(0, i));
        String frac = t.substring(i + 1);
        switch (l) {
            case DE: {
                StringBuilder b = new StringBuilder(german(whole)).append(" Komma");
                for (char c : frac.toCharArray()) b.append(' ').append(german(c - '0'));
                return b.toString();
            }
            case FA:
                return persian(whole) + " ممیز " + persian(Long.parseLong(frac));
            default: {
                StringBuilder b = new StringBuilder(english(whole)).append(" point");
                for (char c : frac.toCharArray()) b.append(' ').append(english(c - '0'));
                return b.toString();
            }
        }
    }

    /** The number written as a word in the language ("8" → eight / acht / هشت). */
    static String words(long n, Lang l) {
        if (n < 0 || n > MAX) return "";
        switch (l) {
            case DE: return german(n);
            case FA: return persian(n);
            default: return english(n);
        }
    }

    /** The ordinal: eighth / achte / هشتم. */
    static String ordinal(long n, Lang l) {
        if (n < 0 || n > MAX) return "";
        switch (l) {
            case DE: {
                long small = n % 100;
                String head = n >= 100 && small > 0 && small < 20 ? german(n - small) : "";
                if (!head.isEmpty() || n < 20) {
                    switch ((int) small) {
                        case 1: return head + "erste";
                        case 3: return head + "dritte";
                        case 7: return head + "siebte";
                        case 8: return head + "achte";
                        default: return head + german(small) + "te";
                    }
                }
                return german(n) + "ste";
            }
            case FA: {
                if (n == 1) return "یکم";
                if (n == 3) return "سوم";
                String w = persian(n);
                return w.endsWith("سه") ? w.substring(0, w.length() - 2) + "سوم" : w + "م";
            }
            default: {
                String w = english(n);
                int sp = Math.max(w.lastIndexOf(' '), w.lastIndexOf('-'));
                String head = w.substring(0, sp + 1), last = w.substring(sp + 1);
                return head + englishOrdinal(last);
            }
        }
    }

    private static final String[] EN_ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] EN_TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    private static String english(long n) {
        if (n < 20) return EN_ONES[(int) n];
        if (n < 100) return EN_TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + EN_ONES[(int) (n % 10)]);
        if (n < 1000) return EN_ONES[(int) (n / 100)] + " hundred" + (n % 100 == 0 ? "" : " " + english(n % 100));
        if (n < 1_000_000) return english(n / 1000) + " thousand" + (n % 1000 == 0 ? "" : " " + english(n % 1000));
        return english(n / 1_000_000) + " million" + (n % 1_000_000 == 0 ? "" : " " + english(n % 1_000_000));
    }

    private static String englishOrdinal(String w) {
        switch (w) {
            case "one": return "first";
            case "two": return "second";
            case "three": return "third";
            case "five": return "fifth";
            case "eight": return "eighth";
            case "nine": return "ninth";
            case "twelve": return "twelfth";
            default: return w.endsWith("y") ? w.substring(0, w.length() - 1) + "ieth" : w + "th";
        }
    }

    private static final String[] DE_ONES = {"null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
            "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"};
    private static final String[] DE_TENS = {"", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig"};

    private static String german(long n) {
        if (n < 20) return DE_ONES[(int) n];
        if (n < 100) {
            int one = (int) (n % 10);
            return one == 0 ? DE_TENS[(int) (n / 10)] : (one == 1 ? "ein" : DE_ONES[one]) + "und" + DE_TENS[(int) (n / 10)];
        }
        if (n < 1000) return germanPrefix(n / 100) + "hundert" + (n % 100 == 0 ? "" : german(n % 100));
        if (n < 1_000_000) return germanPrefix(n / 1000) + "tausend" + (n % 1000 == 0 ? "" : german(n % 1000));
        long m = n / 1_000_000;
        return (m == 1 ? "eine Million" : german(m) + " Millionen") + (n % 1_000_000 == 0 ? "" : " " + german(n % 1_000_000));
    }

    /** "ein" instead of "eins" before hundert / tausend. */
    private static String germanPrefix(long n) {
        return n == 1 ? "ein" : german(n);
    }

    private static final String[] FA_ONES = {"صفر", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده",
            "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده"};
    private static final String[] FA_TENS = {"", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود"};
    private static final String[] FA_HUNDREDS = {"", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد"};

    private static String persian(long n) {
        if (n < 20) return FA_ONES[(int) n];
        if (n < 100) return FA_TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : " و " + FA_ONES[(int) (n % 10)]);
        if (n < 1000) return FA_HUNDREDS[(int) (n / 100)] + (n % 100 == 0 ? "" : " و " + persian(n % 100));
        if (n < 1_000_000) return (n / 1000 == 1 ? "" : persian(n / 1000) + " ") + "هزار" + (n % 1000 == 0 ? "" : " و " + persian(n % 1000));
        return persian(n / 1_000_000) + " میلیون" + (n % 1_000_000 == 0 ? "" : " و " + persian(n % 1_000_000));
    }

    /** Number words → value, for the words of 0 … 1000 in the three languages ("acht" → 8). */
    private static Map<String, Long> known;

    static synchronized long valueOf(String word) {
        if (known == null) {
            known = new HashMap<>();
            for (long i = 0; i <= 1000; i++)
                for (Lang l : Lang.values()) known.putIfAbsent(key(words(i, l)), i);
        }
        Long v = known.get(key(word == null ? "" : word));
        return v == null ? -1 : v;
    }

    /** English number words of any size ("one thousand nine hundred ninety" → 1990), as {@link #words} writes them; else -1. */
    static long ofEnglish(String text) {
        String t = Text.clean(text).toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        long total = 0, cur = 0;
        boolean any = false;
        for (String tok : t.split("[ -]+")) {
            int v = java.util.Arrays.asList(EN_ONES).indexOf(tok);
            int tens = java.util.Arrays.asList(EN_TENS).indexOf(tok);
            if (v >= 0 && !tok.isEmpty()) cur += v;
            else if (tens > 0) cur += tens * 10L;
            else if (tok.equals("hundred")) cur *= 100;
            else if (tok.equals("thousand")) { total += cur * 1000; cur = 0; }
            else if (tok.equals("million")) { total += cur * 1_000_000; cur = 0; }
            else return -1;
            any = true;
        }
        return any ? total + cur : -1;
    }

    /** The value of a number lookup: its words in any language up to 1000, or the English words of any size. */
    static long valueOfLookup(Lookup l) {
        long n = valueOf(l.query);
        if (n >= 0) return n;
        Lookup.Result en = l.result(Lang.EN);
        return en == null ? -1 : ofEnglish(en.text);
    }

    /** The language whose words for {@code n} are {@code word} (vierzig → German), or null. */
    static Lang languageOf(String word, long n) {
        for (Lang l : Lang.values()) if (key(words(n, l)).equals(key(word))) return l;
        return null;
    }

    private static String key(String s) {
        return Text.unifyPersian(Text.clean(s)).toLowerCase(Locale.ROOT).replace('‌', ' ');
    }

    /** "8" in the script of the language: Persian digits for Persian. */
    static String digits(long n, Lang l) {
        // Locale.US: older Android writes no thousands separator for Locale.ROOT
        String s = String.format(Locale.US, "%,d", n);
        if (l != Lang.FA) return l == Lang.DE ? s.replace(',', '.') : s;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('۰' + c - '0') : c == ',' ? '٬' : c);
        return b.toString();
    }
}
