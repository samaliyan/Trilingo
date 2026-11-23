package io.github.samaliyan.trilingo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the verb to conjugate in each language of a lookup: the typed word (as its infinitive) and the
 * verb translations. Empty when the word is not a verb. Pure Java.
 */
final class Verbs {
    private Verbs() { }

    static boolean isVerbPos(String pos) {
        return pos != null && pos.trim().toLowerCase(Locale.ROOT).startsWith("verb");
    }

    private static boolean hasVerbSense(Lookup l) {
        for (Lookup.Result r : l.results) for (Lookup.Sense s : r.senses) if (isVerbPos(s.pos)) return true;
        return false;
    }

    /** Infinitive in this language for a dictionary term or translation, or null if it is not one. */
    static String infinitive(Lang lang, String text) {
        if (text == null) return null;
        String t = Text.clean(text);
        if (t.isEmpty() || t.length() > 40) return null;
        switch (lang) {
            case DE: {
                String w = t.toLowerCase(Locale.GERMAN);
                return GermanVerbs.analyze(w) != null ? w : null;
            }
            case EN: {
                String w = EnglishVerbs.clean(t);
                return w != null && w.split(" ").length <= 3 ? w : null;
            }
            default: {
                String w = Text.unifyPersian(t);
                if (w.split(" ").length > 3) return null;
                return PersianVerbs.analyze(w) != null ? w : null;
            }
        }
    }

    /** Language → infinitive, the typed word's language first. Only verbs that can be conjugated are included. */
    static Map<Lang, String> of(Lookup l) {
        Map<Lang, String> out = new LinkedHashMap<>();
        boolean verbSense = hasVerbSense(l);
        String q = Text.clean(l.query);
        int words = q.isEmpty() ? 0 : q.split(" ").length;

        // 1. the typed word
        if (words <= 3) {
            String src = null;
            switch (l.source) {
                case DE:
                    if (verbSense || GermanVerbs.isKnownForm(q)) {
                        String base = l.baseForm.isEmpty() ? null : infinitive(Lang.DE, l.baseForm);
                        src = base != null ? base : GermanVerbs.lemma(q);
                    }
                    break;
                case EN:
                    if (verbSense) {
                        String base = l.baseForm.isEmpty() ? null : infinitive(Lang.EN, l.baseForm);
                        src = base != null ? base : EnglishVerbs.lemma(q);
                    }
                    break;
                default:
                    src = infinitive(Lang.FA, q);       // Persian: only infinitives (رفتن, کار کردن)
            }
            if (src != null && Conjugation.of(l.source, src) != null) out.put(l.source, src);
        }

        // 2. the translations: the first verb meaning, else the main translation if it is an infinitive
        boolean isVerb = !out.isEmpty() || verbSense;
        if (!isVerb) return out;
        for (Lookup.Result r : l.results) {
            String v = null;
            for (Lookup.Sense s : r.senses) {
                if (!isVerbPos(s.pos)) continue;
                for (String term : s.terms) {
                    v = infinitive(r.lang, term);
                    if (v != null) break;
                }
                if (v != null) break;
            }
            if (v == null) v = infinitive(r.lang, r.text);
            if (v != null && Conjugation.of(r.lang, v) != null) out.put(r.lang, v);
        }
        return out;
    }
}
