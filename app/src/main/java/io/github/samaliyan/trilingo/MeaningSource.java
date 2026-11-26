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

    /** Meaning of the typed word (see {@link #load(Lookup, Fetch, Resolver)}), without the extra sources. */
    static Meaning load(Lookup l, Fetch net, java.util.function.UnaryOperator<String> german, Words enWords) throws IOException {
        return load(l, net, german, enWords, null);
    }

    /**
     * Meaning and picture for any lookup — there is always a meaning:
     * 1. the word's own entry (English: dictionaryapi.dev; German, Persian: Wiktionary in that language);
     * 2. else the entry of its dictionary form (ging → gehen, Assistentin → Assistent, a compound's last part);
     * 3. else the Wikipedia article in the word's language (names, places, new words);
     * 4. else the meaning of its English translation;
     * 5. a sentence or longer phrase: its words with their translations;
     * 6. at the very last, its translations, said as such.
     */
    static Meaning load(Lookup l, Fetch net, Resolver r) throws IOException {
        return load(l, net, r == null ? null : r::germanCase, r == null ? null : r.en, r);
    }

    private static Meaning load(Lookup l, Fetch net, java.util.function.UnaryOperator<String> german, Words enWords, Resolver r)
            throws IOException {
        String w = word(l);
        Meaning m;
        if (w == null) {
            m = new Meaning(l.source, Text.clean(l.query));
        } else {
            m = own(l.source, w, net, german, enWords);
            if (m.isEmpty() && r != null && l.source != Lang.FA) {
                // a German noun typed in lower case, an English word with a capital
                String alt = l.source == Lang.DE ? r.germanCase(w) : w.toLowerCase(Locale.ROOT);
                if (!alt.equals(w)) m = keepPicture(m, own(l.source, alt, net, german, enWords));
            }
            if (m.isEmpty() && r != null) {
                for (String b : r.bases(w, l.source)) {
                    Meaning x = own(l.source, b, net, german, enWords);
                    if (!x.isEmpty()) {
                        x.note = Meaning.NOTE_BASE;
                        x.noteWord = b;
                        m = keepPicture(m, x);
                        break;
                    }
                }
            }
            if (m.isEmpty() && Resolver.NUMBER.equals(l.service)) number(m, l);
            // Wikipedia explains names and things (Berlin, EU, Kühlschrank), not phrases (how are you)
            if (m.isEmpty() && !Resolver.NUMBER.equals(l.service) && (!w.contains(" ") || Character.isUpperCase(w.charAt(0))))
                wikipedia(m, l.source, w, net);
        }
        if (m.isEmpty() && Resolver.NUMBER.equals(l.service)) number(m, l);
        // a sentence (or a word with no entry of its own): the meanings of its words, in its own language
        if (m.isEmpty() && r != null) words(m, l, net, german, enWords, r);
        // never the meaning of a translation: the meaning section is only in the word's own language
        if (w != null) picture(m, l, net, german, r);
        return m;
    }

    /** "Hund: Haustier, das …" for up to 5 words of the text, each from its own language's dictionary. */
    private static void words(Meaning m, Lookup l, Fetch net, java.util.function.UnaryOperator<String> german,
                              Words enWords, Resolver r) {
        int found = 0;
        List<String> words = new ArrayList<>();
        for (String[] g : r.glossary(l.query, l.source, 8)) words.add(g[0]);
        // then the words as they are, the common ones too (how are you): used when nothing above has a meaning
        int fromGlossary = words.size();
        for (String t : Text.clean(l.query).split("[\\s,.;:!?()\"„“”«»]+"))
            if (t.length() >= 2 && words.size() < fromGlossary + 4 && !words.contains(t)) words.add(t);
        for (int i = 0; i < words.size(); i++) {
            if (i >= fromGlossary && found > 0) break;
            String word = words.get(i);
            if (found >= 5) break;
            String[] g = {l.source == Lang.EN ? word.toLowerCase(Locale.ROOT) : word};
            try {
                Meaning x = own(l.source, g[0], net, german, enWords);
                if (x.senses.isEmpty()) continue;
                m.senses.add(new Meaning.Sense("", g[0] + ": " + x.senses.get(0).text, ""));
                found++;
            } catch (IOException | RuntimeException e) {
                // the next word
            }
        }
        if (!m.isEmpty()) m.note = Meaning.NOTE_WORDS;
    }

    /** A number without a dictionary entry (twenty-one): its value and the other languages' words. */
    private static void number(Meaning m, Lookup l) {
        long n = Numbers.valueOfLookup(l);
        if (n < 0) return;
        m.senses.add(new Meaning.Sense("number", Numbers.digits(n, l.source)
                + (l.source == Lang.FA ? " (" + n + ")" : ""), ""));
        m.senses.add(new Meaning.Sense("ordinal", Numbers.ordinal(n, l.source), ""));
        m.note = Meaning.NOTE_NUMBER;
        m.noteWord = String.valueOf(n);
    }

    /** The entry of a word in its own language's dictionary (may be empty). */
    private static Meaning own(Lang lang, String w, Fetch net, java.util.function.UnaryOperator<String> german, Words enWords)
            throws IOException {
        switch (lang) {
            case EN: {
                Meaning own = Meaning.parseDictionary(net.get(Meaning.dictionaryUrl(w)), w);
                try {
                    List<String> fam = Meaning.englishFamily(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("en", w.replace(' ', '_')))), w, 600);
                    own.family.addAll(common(fam, enWords, 10));
                } catch (IOException e) {
                    // the family is extra; the meanings are enough
                }
                return own;
            }
            case DE: {
                String page = german == null ? w : german.apply(w);
                return Meaning.parseGerman(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("de", page))), page);
            }
            default:
                return Meaning.parsePersian(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("fa", w))), w);
        }
    }

    /** The other entry, with the first entry's picture and sound when it had them. */
    private static Meaning keepPicture(Meaning first, Meaning other) {
        if (other.image.isEmpty() && !first.image.isEmpty()) {
            other.image = first.image;
            other.imageCaption = first.imageCaption;
            other.imageSource = first.imageSource;
        }
        if (other.audio.isEmpty()) other.audio.addAll(first.audio);
        if (other.ipa.isEmpty()) other.ipa = first.ipa;
        return other;
    }

    /** The first sentences of the Wikipedia article in the word's language (and its picture). */
    private static void wikipedia(Meaning m, Lang lang, String w, Fetch net) {
        String title = lang == Lang.FA ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
        try {
            String body = net.get(Meaning.wikipediaUrl(lang.code, title));
            String text = Meaning.wikipediaExtract(body, 2);
            if (text.isEmpty()) return;
            m.senses.add(new Meaning.Sense("", text, ""));
            m.note = Meaning.NOTE_WIKIPEDIA;
            if (m.image.isEmpty()) {
                String img = Meaning.wikipediaImage(body);
                if (!img.isEmpty()) {
                    m.image = img;
                    m.imageSource = "Wikipedia";
                }
            }
        } catch (IOException | RuntimeException e) {
            // no article
        }
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
        picture(m, l, net, german, null);
    }

    static void picture(Meaning m, Lookup l, Fetch net, java.util.function.UnaryOperator<String> german, Resolver r) {
        if (!m.image.isEmpty()) return;
        // a number: the app draws it (Wikipedia's "25" or "1000" is a year, a calendar)
        if (Resolver.NUMBER.equals(l.service)) return;
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
        // a German compound: its own Wikipedia article, then the picture of its last part (…lampe → Lampe)
        Compounds.Split split = r == null || de == null || r.compounds == null ? null : r.compounds.split(de, true);
        if (split != null) tries.add(0, new String[]{"de", Compounds.capital(de)});
        for (String[] t : tries) {
            try {
                if (wikipediaPicture(m, t[0], t[1], net)) return;
            } catch (RuntimeException e) {
                return;
            }
        }
        if (split != null) {
            String last = split.parts.get(split.parts.size() - 1).base;
            take(m, germanPage(last, net), last);
            if (m.image.isEmpty() && !split.male.isEmpty()) {
                String male = last.substring(0, last.length() - 2);
                take(m, germanPage(male, net), male);
            }
            if (m.image.isEmpty() && en != null && en.contains(" ")) {                   // "marketing assistant" → Assistant
                String head = en.substring(en.lastIndexOf(' ') + 1);
                wikipediaPicture(m, "en", capital(head), net);
            }
            // then the last part (it says what the thing is): its Wikipedia article, its English article.
            // Not the other parts: "Marketing" shows a person, not a marketing assistant.
            for (int i = split.parts.size() - 1; i == split.parts.size() - 1 && m.image.isEmpty(); i--) {
                String part = split.parts.get(i).base;
                List<String> forms = new ArrayList<>();
                forms.add(part);
                if (i == split.parts.size() - 1 && !split.male.isEmpty()) forms.add(part.substring(0, part.length() - 2));
                for (String f : forms) {
                    if (i < split.parts.size() - 1) take(m, germanPage(f, net), f);
                    if (m.image.isEmpty()) wikipediaPicture(m, "de", Compounds.capital(f), net);
                    String[] tr = r.compounds.translate(f);
                    String e = tr == null ? "" : tr[0].replaceFirst("^(to|a|an|the) ", "");
                    if (m.image.isEmpty() && !e.isEmpty() && e.split(" ").length <= 2) wikipediaPicture(m, "en", capital(e), net);
                    if (!m.image.isEmpty()) return;
                }
            }
        }
    }

    /** Takes the picture of a Wikipedia article (not a list of meanings); false when it has none. */
    private static boolean wikipediaPicture(Meaning m, String host, String title, Fetch net) {
        try {
            String img = Meaning.wikipediaImage(net.get(Meaning.wikipediaUrl(host, title)));
            if (img.isEmpty()) return false;
            m.image = img;
            m.imageCaption = "";
            m.imageSource = "Wikipedia";
            return true;
        } catch (IOException e) {
            return false;
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
