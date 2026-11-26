package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What typed (or spoken) text is, before it is translated: a number ("8" → هشت / eight / acht), German without
 * umlauts (mude → müde), a German compound with a typo (marketingassitentin → Marketingassistentin), and which
 * language it is in. Also gives what can be answered without the internet (saved dictionary, numbers, compounds
 * built from their parts) and the dictionary forms of words (ging → gehen, Häuser → Haus, رفتم → رفتن).
 * The main screen, the bubble and the coverage check all use this. Pure Java.
 */
final class Resolver {
    final Words en, de;
    final Nouns nouns;
    final Dict enDict, deDict;
    final Compounds compounds;

    Resolver(Words en, Words de, Nouns nouns, Dict enDict, Dict deDict) {
        this.en = en;
        this.de = de;
        this.nouns = nouns;
        this.enDict = enDict;
        this.deDict = deDict;
        compounds = de == null ? null : new Compounds(de, nouns, deDict);
        if (compounds != null) compounds.setEnglish(enDict);
    }

    /** Typed text made ready for translating. */
    static final class Prepared {
        /** The text to translate. */
        String text;
        /** Its language when known here (forced, a number, a German compound); null = the service decides. */
        Lang source;
        /** For a number: its value, else -1. */
        long number = -1;
        /** A decimal number as typed ("3,5"), else null. */
        String decimal;
        /** The text was changed for sure (umlauts, a typo in a compound): "Showing results for …". */
        boolean corrected;
        /** A possible umlaut form ("Did you mean"), or null. */
        String hint;
        /** A German compound's parts, or null. */
        Compounds.Split split;
    }

    /**
     * @param forced the chosen language (null = automatic)
     * @param spoken the language of voice search, when the text was spoken (Persian voice writes "8" for هشت)
     */
    /** Common abbreviations: written out, they translate and have a meaning ("z.B." → "zum Beispiel"). */
    private static final java.util.Map<String, String[]> ABBREVIATIONS = new java.util.HashMap<>();

    static {
        String[][] list = {
                {"de", "z.b.", "zum Beispiel"}, {"de", "usw.", "und so weiter"}, {"de", "d.h.", "das heißt"},
                {"de", "u.a.", "unter anderem"}, {"de", "bzw.", "beziehungsweise"}, {"de", "ca.", "circa"},
                {"de", "evtl.", "eventuell"}, {"de", "ggf.", "gegebenenfalls"}, {"de", "z.t.", "zum Teil"},
                {"de", "u.u.", "unter Umständen"}, {"de", "vgl.", "vergleiche"}, {"de", "inkl.", "inklusive"},
                {"de", "zzgl.", "zuzüglich"}, {"de", "bzgl.", "bezüglich"}, {"de", "mfg", "mit freundlichen Grüßen"},
                {"de", "o.ä.", "oder Ähnliches"}, {"de", "s.o.", "siehe oben"}, {"de", "s.u.", "siehe unten"},
                {"de", "geb.", "geboren"}, {"de", "str.", "Straße"}, {"de", "nr.", "Nummer"},
                {"en", "e.g.", "for example"}, {"en", "i.e.", "that is"}, {"en", "etc.", "et cetera"},
                {"en", "asap", "as soon as possible"}, {"en", "fyi", "for your information"},
                {"en", "btw", "by the way"}, {"en", "aka", "also known as"}, {"en", "approx.", "approximately"},
                {"en", "vs.", "versus"}, {"en", "a.m.", "in the morning"}, {"en", "p.m.", "in the afternoon"},
        };
        for (String[] x : list) ABBREVIATIONS.put(x[1], new String[]{x[0], x[2]});
    }

    Prepared prepare(String typed, Lang forced, Lang spoken) {
        Prepared p = new Prepared();
        String q = Text.clean(typed);
        p.text = q;
        p.source = forced;
        if (Numbers.decimalWords(q, Lang.EN) != null) {
            Lang l = forced != null ? forced : spoken != null ? spoken : Lang.FA;
            p.decimal = q;
            p.source = l;
            p.text = Numbers.decimalWords(q, l);
            return p;
        }
        String[] ab = ABBREVIATIONS.get(q.toLowerCase(Locale.ROOT).replace(" ", ""));
        if (ab != null && (forced == null || forced.code.equals(ab[0]))) {
            p.text = ab[1];
            p.source = Lang.EN.code.equals(ab[0]) ? Lang.EN : Lang.DE;
            p.corrected = true;
            return p;
        }
        long n = Numbers.parse(q);
        if (n >= 0) {
            Lang l = forced != null ? forced : spoken != null ? spoken : Lang.FA;
            p.number = n;
            p.source = l;
            p.text = Numbers.words(n, l);
            return p;
        }
        if (forced == null || forced == Lang.DE) {
            Umlauts.Fix uf = de == null ? null : Umlauts.fix(q, de, forced == Lang.DE ? null : en);
            if (uf != null && uf.sure) {
                p.text = uf.text;
                p.corrected = true;
            } else if (uf != null) {
                p.hint = uf.text;
            }
        }
        if ((forced == null || forced == Lang.DE) && compounds != null && !p.corrected && !Text.hasPersian(q)
                && !(en != null && en.known(q))) {
            Compounds.Split s = compounds.split(p.text);
            if (s != null) {
                p.split = s;
                // split into German parts and not an English word: German (the service would guess English)
                if (forced == null) p.source = Lang.DE;
                if (s.corrected) {
                    p.text = s.word;
                    p.corrected = true;
                }
            }
        }
        return p;
    }

    /** English or German for Latin text (word lists, then German compounds); null when unsure. */
    Lang guess(String text) {
        Lang g = Words.guessLatin(en, de, text);
        if (g != null || compounds == null || text == null || Text.hasPersian(text)) return g;
        String t = Text.clean(text);
        if (!t.contains(" ") && !(en != null && en.known(t)) && compounds.split(t, true) != null) return Lang.DE;
        return null;
    }

    /**
     * An answer without the internet, or null: a number in words, the built-in dictionary, or a German compound
     * translated from its parts. {@code anyway}: also a word both dictionaries have (no service to decide).
     */
    Lookup offline(Prepared p, boolean anyway, long now) {
        if (p.number >= 0) return number(p.number, p.source, now);
        if (p.decimal != null) {
            List<Lookup.Result> rs = new ArrayList<>();
            for (Lang t : p.source.targets()) {
                List<Lookup.Sense> senses = new ArrayList<>();
                senses.add(new Lookup.Sense("number", java.util.Collections.singletonList(Numbers.decimalWords(p.decimal, t))));
                rs.add(new Lookup.Result(t, Numbers.decimalWords(p.decimal, t), senses));
            }
            return new Lookup(p.text, p.source, rs, NUMBER, now);
        }
        Lookup d = Dict.choose(p.text, p.source, anyway, now, enDict, deDict, en, de);
        if (d != null) return d;
        if (p.split != null && compounds != null) {
            Lookup c = compounds.lookup(p.split, now);
            if (c != null) return c;
        }
        return null;
    }

    /** A number in the two other languages: the word, and the ordinal as a second form. */
    static Lookup number(long n, Lang source, long now) {
        List<Lookup.Result> rs = new ArrayList<>();
        for (Lang t : source.targets()) {
            List<Lookup.Sense> senses = new ArrayList<>();
            List<String> forms = new ArrayList<>();
            forms.add(Numbers.words(n, t));
            senses.add(new Lookup.Sense("number", forms));
            List<String> ord = new ArrayList<>();
            ord.add(Numbers.ordinal(n, t));
            senses.add(new Lookup.Sense("ordinal", ord));
            rs.add(new Lookup.Result(t, Numbers.words(n, t), senses));
        }
        return new Lookup(Numbers.words(n, source), source, rs, NUMBER, now);
    }

    static final String NUMBER = "Number";

    // ------------------------------------------------------------------ dictionary forms

    /** "tisch" → "Tisch": the case the word list (or the noun list) writes. */
    String germanCase(String word) {
        String w = Text.clean(word);
        if (w.isEmpty() || w.contains(" ")) return w;
        if (de != null && de.known(w)) return de.canonical(w);
        if (nouns != null && Character.isLowerCase(w.charAt(0)) && nouns.lookup(w) != null) return Compounds.capital(w);
        return w;
    }

    /**
     * Other forms to look a word up under when it has no entry itself, best first: the singular, the infinitive,
     * the adjective without its ending, the male form of -in, the last part of a compound
     * (ging → gehen, Häuser → Haus, kleinen → klein, Lehrerin → Lehrer, Schreibtischlampe → Lampe;
     * went → go, children → child; رفتم → رفتن, کتاب‌ها → کتاب).
     */
    private static final String[] SEPARABLE = {"zusammen", "zurück", "weiter", "heraus", "hinaus", "herum", "fest",
            "fort", "nach", "auf", "aus", "bei", "ein", "her", "hin", "los", "mit", "vor", "weg", "dazu", "ab", "an", "zu"};

    /** The infinitive of a separable verb's participle or zu-form (aufgestanden, aufzustehen → aufstehen), or null. */
    String separable(String low) {
        for (String p : SEPARABLE) {
            if (!low.startsWith(p) || low.length() < p.length() + 5) continue;
            String rest = low.substring(p.length());
            String inner = null;
            if (rest.startsWith("ge")) inner = GermanVerbs.lemma(rest);
            if ((inner == null || inner.equals(rest)) && rest.startsWith("zu")) inner = rest.substring(2);
            if (inner == null || inner.equals(rest) || !inner.endsWith("n")) continue;
            String verb = p + inner;
            if (de == null || de.known(verb) || deDict != null && deDict.has(verb)) return verb;
        }
        return null;
    }

    List<String> bases(String word, Lang lang) {
        List<String> out = new ArrayList<>();
        String w = Text.clean(word);
        if (w.isEmpty() || w.contains(" ")) return out;
        String low = w.toLowerCase(Locale.ROOT);
        switch (lang) {
            case DE: {
                Nouns.Info n = nouns == null ? null : nouns.exact(Compounds.capital(w));
                boolean lower = Character.isLowerCase(w.charAt(0));
                // typed in lower case, a verb form comes first (gefahren → fahren, not die Gefahr)
                if (lower) {
                    add(out, separable(low), w);
                    add(out, GermanVerbs.lemma(low), w);
                }
                if (n != null && !n.noun.equals(w)) add(out, n.noun, w);
                if (GermanVerbs.isKnownForm(low)) add(out, GermanVerbs.lemma(low), w);
                for (String end : new String[]{"en", "em", "er", "es", "e"})
                    if (low.endsWith(end) && low.length() - end.length() >= 3) {
                        String stem = low.substring(0, low.length() - end.length());
                        if (deDict != null && deDict.has(stem)) add(out, deDict.find(stem).word, w);
                    }
                if (w.endsWith("in") && w.length() > 5) {
                    String m = w.substring(0, w.length() - 2);
                    if (nouns != null && nouns.exact(Compounds.capital(m)) != null) add(out, Compounds.capital(m), w);
                }
                Compounds.Split s = compounds == null ? null : compounds.split(w, true);
                if (s != null) {
                    String last = s.parts.get(s.parts.size() - 1).base;
                    add(out, last, w);
                    if (!s.male.isEmpty()) {
                        String ml = last.substring(0, last.length() - 2);
                        add(out, ml, w);
                    }
                }
                break;
            }
            case EN: {
                add(out, EnglishVerbs.lemma(low), w);
                String l = EnglishVerbs.lemma(low);
                if (l != null && !l.equals(low)) add(out, l + "e", w);
                if (low.endsWith("ies")) add(out, low.substring(0, low.length() - 3) + "y", w);
                if (low.endsWith("es")) add(out, low.substring(0, low.length() - 2), w);
                if (low.endsWith("s")) add(out, low.substring(0, low.length() - 1), w);
                if (low.endsWith("er")) add(out, low.substring(0, low.length() - 2), w);
                if (low.endsWith("est")) add(out, low.substring(0, low.length() - 3), w);
                if (low.endsWith("ly")) add(out, low.substring(0, low.length() - 2), w);
                // keep only real words
                out.removeIf(x -> en != null && !en.known(x));
                break;
            }
            default: {
                String u = Text.unifyPersian(w).replace("‌", "");
                add(out, PersianVerbs.infinitiveOf(w), w);
                for (String end : new String[]{"های", "ها", "ان", "ی", "ای"})
                    if (u.endsWith(end) && u.length() - end.length() >= 2) add(out, u.substring(0, u.length() - end.length()), w);
            }
        }
        return out;
    }

    private static void add(List<String> out, String b, String word) {
        if (b != null && !b.isEmpty() && !b.equalsIgnoreCase(word) && !out.contains(b)) out.add(b);
    }

    /** A real word of the language (word list, or a German compound of known parts): for Review. */
    boolean isWord(Lang lang, String w) {
        String t = Text.clean(w);
        if (lang == Lang.EN) return en != null && en.known(t);
        if (lang == Lang.DE) return (de != null && de.known(t)) || (compounds != null && compounds.split(t, true) != null && !compounds.split(t, true).corrected);
        return false;
    }

    // ------------------------------------------------------------------ the words of a sentence

    /**
     * The words of a sentence or phrase with their translations from the built-in dictionary, each in its
     * dictionary form ("Ich gehe ins Kino" → gehen: go · رفتن; Kino: cinema · سینما). Very common short words
     * (ich, the, و) are left out. {word, translation} pairs; empty when nothing is found.
     */
    List<String[]> glossary(String text, Lang lang, int max) {
        List<String[]> out = new ArrayList<>();
        Dict d = lang == Lang.EN ? enDict : lang == Lang.DE ? deDict : null;
        List<String> seen = new ArrayList<>();
        for (String raw : Text.clean(text).split("[\\s,.;:!?()\"„“”«»]+")) {
            if (out.size() >= max) break;
            String t = raw.replaceAll("^['’-]+|['’-]+$", "");
            if (t.length() < 2) continue;
            if (lang == Lang.FA) {
                if (seen.contains(t)) continue;
                seen.add(t);
                Lookup l = Dict.persian(t, enDict, deDict, 0);
                if (l == null) for (String b : bases(t, Lang.FA)) if ((l = Dict.persian(b, enDict, deDict, 0)) != null) {
                    t = b;
                    break;
                }
                if (l != null) out.add(new String[]{t, l.result(Lang.EN).text + " · " + l.result(Lang.DE).text});
                continue;
            }
            if (d == null) break;
            Words w = lang == Lang.EN ? en : de;
            int rank = w == null ? -1 : w.rank(t);
            if (rank >= 0 && rank < 60) continue;                       // ich, the, and …
            String form = lang == Lang.DE ? germanCase(t) : t.toLowerCase(Locale.ROOT);
            Dict.Entry e = d.find(form);
            if (e == null) for (String b : bases(form, lang)) if ((e = d.find(b)) != null) break;
            if (e == null || seen.contains(e.word)) continue;
            seen.add(e.word);
            out.add(new String[]{e.word, d.summary(e.word).replace("\n", " · ")});
        }
        return out;
    }
}
