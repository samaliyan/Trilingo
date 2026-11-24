package io.github.samaliyan.trilingo;

import java.util.Locale;

/**
 * German words typed without ä, ö, ü or ß: "mude" → "müde", "strasse" → "Straße", "fuer" → "für".
 * A word that is not an English or German word is corrected for sure; a real English word that is much
 * rarer than the German one ("fur" / "für") only gets a "Did you mean". Pure Java.
 */
final class Umlauts {
    private Umlauts() { }

    /** Corrected text, and whether it is sure (every changed word was unknown). */
    static final class Fix {
        final String text;
        final boolean sure;

        Fix(String text, boolean sure) {
            this.text = text;
            this.sure = sure;
        }
    }

    private static boolean hasUmlaut(String s) {
        for (char c : s.toCharArray()) if ("äöüßÄÖÜ".indexOf(c) >= 0) return true;
        return false;
    }

    /** @return the fixed text, or null when nothing needs fixing */
    static Fix fix(String text, Words de, Words en) {
        if (de == null || text == null || Text.hasPersian(text)) return null;
        String[] parts = text.split("(?<=[^A-Za-zÄÖÜäöüß])|(?=[^A-Za-zÄÖÜäöüß])");
        StringBuilder out = new StringBuilder();
        boolean changed = false, allSure = true;
        int words = 0;
        for (String t : parts) {
            if (!t.matches("[A-Za-z]{2,}")) {
                out.append(t);
                continue;
            }
            if (++words > 8) return null;          // a long sentence: leave it to the translation service
            String lower = t.toLowerCase(Locale.ROOT);
            String cand = de.sameKey(Words.fold(Lang.DE, lower));
            if (cand == null || !hasUmlaut(cand)) {
                String alt = lower.replace("ae", "a").replace("oe", "o").replace("ue", "u");
                if (!alt.equals(lower)) cand = de.sameKey(Words.fold(Lang.DE, alt));
                if (cand != null && !hasUmlaut(cand)) cand = null;
                // "fuer" → "für" only when the ae/oe/ue spelling itself is not a word
                if (cand != null && (de.known(lower) || (en != null && en.known(lower)))) cand = null;
            }
            if (cand == null || !hasUmlaut(cand)) {
                out.append(t);
                continue;
            }
            int rDe = de.rank(lower), rEn = en == null ? -1 : en.rank(lower), rCand = de.rank(cand);
            boolean known = rDe >= 0 || rEn >= 0;
            if (known) {
                int r = rDe >= 0 ? rDe : rEn;
                if ((long) rCand * 4 >= r) {               // the typed word is common enough: keep it
                    out.append(t);
                    continue;
                }
                allSure = false;
            }
            String fixed = cand;
            if (Character.isUpperCase(t.charAt(0)) && Character.isLowerCase(cand.charAt(0)))
                fixed = Character.toUpperCase(cand.charAt(0)) + cand.substring(1);
            out.append(fixed);
            changed = true;
        }
        return changed ? new Fix(out.toString(), allSure) : null;
    }
}
