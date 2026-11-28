package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The meanings of a word with several ("put on": wear · switch on · gain weight), to choose the one meant.
 * For an English phrasal verb they come from the built-in list (in English); otherwise one per word kind from
 * the Persian translation (light: نور · سبک · روشن کردن), or for a Persian word from its English one. Choosing one translates that
 * meaning again ({@link #query}), so the German and Persian, the explanation and the picture all follow it.
 * Pure Java (tested).
 */
final class Senses {
    static final int MAX = 6;

    static final class Option {
        /** What the chip shows. */
        final String label;
        /** The language of the label (and of what is translated to find the other languages). */
        final Lang lang;
        /** A meaning of an English phrasal verb (a verb: translated as "to wear"). */
        final boolean verb;

        Option(String label, Lang lang) {
            this(label, lang, false);
        }

        Option(String label, Lang lang, boolean verb) {
            this.label = label;
            this.lang = lang;
            this.verb = verb;
        }
    }

    private Senses() {
    }

    /** The meanings to choose from; empty when the word has only one, or is a sentence. */
    static List<Option> of(Lookup l, String phrasalMeanings) {
        return of(l, phrasalMeanings, null);
    }

    /**
     * As above; {@code kinds}: the word kinds the built-in dictionary knows for the word (noun, verb …). A
     * kind only the translation service lists is mostly a rare use ("give" as a noun: دهش) and is not offered.
     */
    static List<Option> of(Lookup l, String phrasalMeanings, java.util.Set<String> kinds) {
        List<Option> out = new ArrayList<>();
        String q = Text.clean(l.query);
        if (q.isEmpty() || q.split(" ").length > 3 || l.results.isEmpty()) return out;
        if (l.source == Lang.EN && phrasalMeanings != null && !phrasalMeanings.isEmpty()) {
            for (String m : phrasalMeanings.split(";")) add(out, Text.clean(m), Lang.EN, true);
        }
        if (out.size() < 2) {
            out.clear();
            Lang own = l.source == Lang.FA ? Lang.EN : Lang.FA;
            Lookup.Result r = l.result(own);
            if (r != null) {
                // one meaning per word kind (light: نور as a noun, سبک as an adjective, روشن کردن as a verb);
                // the other words of one kind are mostly the same meaning said differently, not meanings
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Lookup.Sense s : r.senses) {
                    if (s.terms.isEmpty() || s.pos == null || s.pos.isEmpty()) continue;
                    String k = s.pos.toLowerCase(Locale.ROOT);
                    if (kinds != null && !kinds.contains(k) || !seen.add(k)) continue;
                    add(out, Text.clean(s.terms.get(0)), own);
                }
                if (out.size() >= 2) {
                    // the translation shown first, as the first chip
                    String main = Text.clean(r.text);
                    for (int i = 0; i < out.size(); i++) {
                        if (out.get(i).label.equalsIgnoreCase(main)) {
                            out.add(0, out.remove(i));
                            break;
                        }
                    }
                }
            }
        }
        return out.size() >= 2 ? out : Collections.emptyList();
    }

    private static void add(List<Option> out, String label, Lang lang) {
        add(out, label, lang, false);
    }

    private static void add(List<Option> out, String label, Lang lang, boolean verb) {
        if (label.isEmpty() || out.size() >= MAX || label.length() > 40) return;
        for (Option o : out) if (o.label.equalsIgnoreCase(label)) return;
        out.add(new Option(label, lang, verb));
    }

    /**
     * What to translate to get the chosen meaning in the other languages: "gain (weight)" → "gain weight";
     * a Persian or English word as it is.
     */
    static String query(Option o) {
        String q = Text.clean(o.label.replace("(", " ").replace(")", " "));
        // a verb meaning with "to": the service then gives the infinitive (پوشیدن), not an order (بپوش)
        return o.verb && !q.startsWith("to ") ? "to " + q : q;
    }

    /** The meaning in English as kept with the word ("wear", "gain weight"), without "to". */
    static String gloss(Option o) {
        return Text.clean(o.label.replace("(", " ").replace(")", " ")).replaceFirst("^to ", "");
    }

    /**
     * The lookup with the chosen meaning: each language takes the translation of that meaning ({@code byLang},
     * from translating {@link #query}); the option's own word stays as chosen. The other words of the first
     * lookup stay after it, so nothing is lost. {@code sense} is the meaning in English.
     */
    static Lookup choose(Lookup l, Option o, java.util.Map<Lang, String> byLang, String sense) {
        List<Lookup.Result> rs = new ArrayList<>();
        for (Lookup.Result r : l.results) {
            String main = r.lang == o.lang ? o.label : byLang.get(r.lang);
            if (main == null || Text.clean(main).isEmpty()) main = r.text;
            // "to wear" was asked: the German "zu tragen" is tragen, the Persian "برای روشن کردن" روشن کردن
            main = Text.clean(main).replaceFirst("^(zu|to|um) ", "").replaceFirst("^برای ", "");
            List<Lookup.Sense> senses = new ArrayList<>();
            List<String> first = new ArrayList<>();
            first.add(main);
            senses.add(new Lookup.Sense("", first));
            for (Lookup.Sense s : r.senses) {
                List<String> rest = new ArrayList<>();
                for (String t : s.terms) if (!t.equalsIgnoreCase(main)) rest.add(t);
                if (!rest.isEmpty()) senses.add(new Lookup.Sense(s.pos, rest));
            }
            rs.add(new Lookup.Result(r.lang, main, senses));
        }
        return new Lookup(l.query, l.source, rs, l.service, l.time, l.suggestion, l.baseForm, sense);
    }

    /** Whether a definition belongs to the chosen meaning: it has one of the meaning's words (wear → "to wear"). */
    static boolean matches(String definition, String sense) {
        if (sense == null || sense.isEmpty()) return false;
        String d = " " + definition.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", " ") + " ";
        for (String w : sense.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", " ").split(" +")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            String stem = w.length() > 5 ? w.substring(0, w.length() - 2) : w;
            if (d.contains(" " + stem)) return true;
        }
        return false;
    }

    private static final List<String> STOP = java.util.Arrays.asList("the", "and", "for", "with", "into", "onto", "from",
            "something", "someone", "one", "own", "your", "you");
}
