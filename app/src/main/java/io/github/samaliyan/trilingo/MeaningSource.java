package io.github.samaliyan.trilingo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the meaning of a looked-up word in its own language, and a picture for it. Decides which sources to
 * ask and in which order; the network is passed in, so this is pure Java and tested with saved answers.
 */
final class MeaningSource {
    interface Fetch {
        /** The body of a GET answer; "" when the page does not exist (404). Throws when the network fails. */
        String get(String url) throws IOException;
    }

    private MeaningSource() { }

    /** The word whose meaning is shown: the typed word, if it is one word or a short phrase. */
    static String word(Lookup l) {
        String q = Text.clean(l.query);
        if (q.isEmpty() || q.split(" ").length > 3 || q.length() > 40) return null;
        return q;
    }

    /** The German word of a lookup (typed, or the main German translation), without an article. */
    static String germanWord(Lookup l) {
        String w = null;
        if (l.source == Lang.DE) w = Text.clean(l.query);
        else for (Lookup.Result r : l.results) if (r.lang == Lang.DE) { w = Text.clean(r.text); break; }
        if (w == null || w.isEmpty()) return null;
        String low = w.toLowerCase(Locale.ROOT);
        for (String a : new String[]{"der ", "die ", "das ", "ein ", "eine "}) if (low.startsWith(a)) w = w.substring(a.length());
        if (w.split(" ").length > 2) return null;
        return w;
    }

    static String englishWord(Lookup l) {
        if (l.source == Lang.EN) return Text.clean(l.query);
        for (Lookup.Result r : l.results) if (r.lang == Lang.EN) return Text.clean(r.text);
        return null;
    }

    /**
     * Meaning of the typed word (in its language) with a picture. {@code german} gives the right case of a German
     * word ("tisch" → "Tisch"), {@code enWords} keeps only real, common words in the English word family.
     */
    static Meaning load(Lookup l, Fetch net, java.util.function.UnaryOperator<String> german, Words enWords) throws IOException {
        String w = word(l);
        Meaning m = new Meaning(l.source, w == null ? Text.clean(l.query) : w);
        if (w == null) return m;
        Meaning own;
        switch (l.source) {
            case EN: {
                own = Meaning.parseDictionary(net.get(Meaning.dictionaryUrl(w)), w);
                try {
                    List<String> fam = Meaning.englishFamily(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("en", w.replace(' ', '_')))), w, 600);
                    own.family.addAll(common(fam, enWords, 10));
                } catch (IOException e) {
                    // the family is extra; the meanings are enough
                }
                break;
            }
            case DE: {
                String page = german == null ? w : german.apply(w);
                own = Meaning.parseGerman(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("de", page))), page);
                break;
            }
            default:
                own = Meaning.parsePersian(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("fa", w))), w);
        }
        m = own;
        picture(m, l, net, german);
        return m;
    }

    /** German prefixes of separable and inseparable verbs, longest first (einschlafen → schlafen). */
    private static final String[] PREFIXES = {"zurück", "zusammen", "weiter", "herein", "heraus", "hinaus", "vorbei", "entgegen",
            "durch", "nach", "fern", "fest", "weg", "hin", "her", "auf", "aus", "ein", "mit", "vor", "zu", "ab", "an", "be", "er",
            "ver", "zer", "ent", "um", "los", "über", "unter"};

    /**
     * The picture, from the first source that has one:
     * 1. the German Wiktionary picture of the German word (typed, or the German translation) — chosen by its
     *    authors to show the meaning;
     * 2. for a German prefix verb without one, the picture of its base verb (einschlafen → schlafen);
     * 3. Wikipedia: for a noun its article (Dog); for a verb the article about the activity — German
     *    "Einschlafen", or English "Running" / "Sleep".
     */
    static void picture(Meaning m, Lookup l, Fetch net, java.util.function.UnaryOperator<String> german) {
        String de = germanWord(l);
        if (de != null && german != null) de = german.apply(de);
        if (m.image.isEmpty() && de != null && l.source != Lang.DE) take(m, germanPage(de, net), "");
        boolean verb = isVerb(m, l) || (de != null && isGermanInfinitive(de));
        if (m.image.isEmpty() && de != null && isGermanInfinitive(de)) {
            for (String p : PREFIXES) {
                String base = de.startsWith(p) ? de.substring(p.length()) : "";
                if (base.length() >= 4 && isGermanInfinitive(base)) {
                    take(m, germanPage(base, net), base);
                    if (!m.image.isEmpty()) break;
                }
            }
        }
        if (!m.image.isEmpty()) return;
        String en = englishWord(l);
        if (en != null && en.toLowerCase(Locale.ROOT).startsWith("to ")) en = en.substring(3);
        List<String[]> tries = new ArrayList<>();                       // {wikipedia language, title}
        if (isNoun(m) || m.senses.isEmpty() && !verb) {
            if (en != null && !en.isEmpty() && en.split(" ").length <= 2) tries.add(new String[]{"en", capital(en)});
        } else if (verb) {
            if (de != null && isGermanInfinitive(de)) tries.add(new String[]{"de", capital(de)});
            if (en != null && !en.isEmpty() && !en.contains(" ")) {
                String base = EnglishVerbs.lemma(en.toLowerCase(Locale.ROOT));
                if (base != null && !base.isEmpty()) {
                    tries.add(new String[]{"en", capital(EnglishVerbs.ing(base))});
                    tries.add(new String[]{"en", capital(base)});
                }
            }
        }
        for (String[] t : tries) {
            try {
                String img = Meaning.wikipediaImage(net.get(Meaning.wikipediaUrl(t[0], t[1])));
                if (!img.isEmpty()) {
                    m.image = img;
                    m.imageCaption = "";
                    m.imageSource = "Wikipedia";
                    return;
                }
            } catch (IOException e) {
                return;                                                 // no network: no picture
            }
        }
    }

    private static Meaning germanPage(String page, Fetch net) {
        try {
            return Meaning.parseGerman(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("de", page))), page);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Uses the other page's picture; for a base verb the caption names it ("schlafen"), so the link is clear. */
    private static void take(Meaning m, Meaning from, String base) {
        if (from == null || from.image.isEmpty()) return;
        m.image = from.image;
        m.imageCaption = base.isEmpty() ? from.imageCaption : base + (from.imageCaption.isEmpty() ? "" : ": " + from.imageCaption);
        m.imageSource = from.imageSource;
    }

    private static boolean isGermanInfinitive(String w) {
        return w.length() >= 4 && Character.isLowerCase(w.charAt(0)) && !w.contains(" ")
                && (w.endsWith("en") || w.endsWith("ern") || w.endsWith("eln"));
    }

    private static boolean isVerb(Meaning m, Lookup l) {
        if (!m.senses.isEmpty()) return "Verb".equals(m.senses.get(0).pos);
        for (Lookup.Result r : l.results)
            if (!r.senses.isEmpty() && r.senses.get(0).pos.toLowerCase(Locale.ROOT).startsWith("verb")) return true;
        return false;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** True when the main meaning is a noun. */
    static boolean isNoun(Meaning m) {
        return !m.senses.isEmpty() && "Noun".equals(m.senses.get(0).pos);
    }

    /** Family words that are in the word list, most frequent first. */
    static List<String> common(List<String> words, Words list, int max) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            for (String w : words) if (out.size() < max) out.add(w);
            return out;
        }
        List<String> known = new ArrayList<>();
        for (String w : words) if (!w.contains(" ") && list.known(w)) known.add(w);
        known.sort((a, b) -> Integer.compare(list.rank(a), list.rank(b)));
        for (String w : known) if (out.size() < max) out.add(w);
        return out;
    }
}
